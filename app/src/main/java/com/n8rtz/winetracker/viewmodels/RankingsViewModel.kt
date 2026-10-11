package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.models.*
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.ui.screens.RankingsFilterState
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RankingsViewModel(application: Application, val repository: WineRepository) : AndroidViewModel(application) {

    private val tastingHistory = repository.tastingHistory

    val rankingsLocationData: StateFlow<Map<String, Map<String, List<String>>>> = tastingHistory
        .map { list ->
            val start = System.currentTimeMillis()
            val map = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
            for (item in list) {
                val def = item.definition
                val country = def.country.normalizeLocation()
                val region = def.region?.normalizeLocation()
                val subregion = def.subregion?.normalizeLocation()

                val countryMap = map.getOrPut(country) { mutableMapOf() }
                if (!region.isNullOrBlank()) {
                    val regionList = countryMap.getOrPut(region) { mutableListOf() }
                    if (!subregion.isNullOrBlank()) {
                        if (regionList.none { it.equals(subregion, ignoreCase = true) }) {
                            regionList.add(subregion)
                        }
                    }
                }
            }
            val result = map.mapValues { (_, countryMap) ->
                countryMap.mapValues { (_, regionList) ->
                    regionList.sorted()
                }
            }
            Log.d("WineTracker", "Rankings Location Mapping took ${System.currentTimeMillis() - start}ms")
            result
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    private val _rankingsFilterState = MutableStateFlow(RankingsFilterState())
    val rankingsFilterState: StateFlow<RankingsFilterState> = _rankingsFilterState.asStateFlow()

    fun updateRankingsFilterState(newState: RankingsFilterState) {
        _rankingsFilterState.value = newState
    }

    private val _rankingsSearchQuery = MutableStateFlow("")
    val rankingsSearchQuery = _rankingsSearchQuery.asStateFlow()

    fun updateRankingsSearchQuery(query: String) {
        _rankingsSearchQuery.value = query
    }

    val rankingsPrices: StateFlow<Map<Pair<Long, String>, Double?>> = tastingHistory
        .map { history ->
            val start = System.currentTimeMillis()
            val definitionIds = history.map { it.note.definitionId }.distinct()
            if (definitionIds.isEmpty()) return@map emptyMap()
            
            val allBottles = repository.getAllPricesForDefinitions(definitionIds)
            val priceMap = mutableMapOf<Pair<Long, String>, Double?>()
            
            history.forEach { item ->
                val defId = item.note.definitionId
                val vintage = item.note.vintage
                val key = Pair(defId, vintage)
                if (priceMap.containsKey(key)) return@forEach

                val specificBottles = allBottles.filter { it.definitionId == defId && it.vintage == vintage }
                var price = specificBottles.firstOrNull { it.status == BottleStatus.IN_CELLAR && (it.price ?: 0.0) > 0.0 }?.price
                if (price == null || price <= 0.0) {
                    price = specificBottles.firstOrNull { (it.price ?: 0.0) > 0.0 }?.price
                }
                
                if (price == null || price <= 0.0) {
                    price = allBottles.filter { it.definitionId == defId && (it.price ?: 0.0) > 0.0 }
                        .sortedByDescending { it.status == BottleStatus.IN_CELLAR }
                        .firstOrNull()?.price
                }
                priceMap[key] = price
            }
            Log.d("WineTracker", "Rankings Price Lookup took ${System.currentTimeMillis() - start}ms for ${history.size} items")
            priceMap
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val filteredRankings: StateFlow<List<RankedWineDisplay>> = combine(
        tastingHistory,
        rankingsFilterState,
        rankingsSearchQuery,
        rankingsPrices
    ) { history, filter, query, prices ->
        val start = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        
        val result = history
            .filter { it.note.rating > 0 }
            .filter { item ->
                val matchesSearch = query.isBlank() ||
                        item.definition.maker.contains(query, ignoreCase = true) ||
                        item.definition.name.contains(query, ignoreCase = true) ||
                        item.note.vintage.contains(query)

                val matchesType = filter.types.isEmpty() ||
                        item.definition.type in filter.types

                val matchesRating = filter.ratingRange?.let { range ->
                    item.note.rating.toFloat() in range
                } ?: true

                val price = prices[Pair(item.note.definitionId, item.note.vintage)]
                val matchesPrice = filter.priceRange?.let { range ->
                    price != null && price.toFloat() in range
                } ?: true

                val matchesCountry = filter.country?.let { country ->
                    item.definition.country.trim().equals(country, ignoreCase = true)
                } ?: true

                val matchesRegion = filter.region?.let { region ->
                    item.definition.region?.trim()?.equals(region, ignoreCase = true) == true
                } ?: true

                val matchesSubregion = filter.subregion?.let { subregion ->
                    item.definition.subregion?.trim()?.equals(subregion, ignoreCase = true) == true
                } ?: true

                matchesSearch && matchesType && matchesRating && matchesPrice &&
                        matchesCountry && matchesRegion && matchesSubregion
            }
            .sortedWith { a, b ->
                val priceA = prices[Pair(a.note.definitionId, a.note.vintage)] ?: 0.0
                val priceB = prices[Pair(b.note.definitionId, b.note.vintage)] ?: 0.0
                when (filter.sortOrder) {
                    "Rating (High)" -> b.note.rating.compareTo(a.note.rating)
                    "Rating (Low)" -> a.note.rating.compareTo(b.note.rating)
                    "Price (High)" -> priceB.compareTo(priceA)
                    "Price (Low)" -> priceA.compareTo(priceB)
                    else -> b.note.rating.compareTo(a.note.rating)
                }
            }
            .map { item ->
                val price = prices[Pair(item.note.definitionId, item.note.vintage)]
                val priceStr = if (price != null && price > 0.0) "$${"%.2f".format(price)}" else ""
                val dateStr = dateFormat.format(Date(item.note.dateTasted))
                RankedWineDisplay(item, priceStr, dateStr)
            }
        Log.d("WineTracker", "Rankings Filter/Sort took ${System.currentTimeMillis() - start}ms for ${result.size} results")
        result
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private fun String.normalizeLocation(): String {
        return this.trim()
            .lowercase()
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }
}
