package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.n8rtz.winetracker.data.repository.WineRepository

import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.local.BottleStatus
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.ui.screens.TastingNotesFilterState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import android.util.Log
import java.util.Locale

// Exposes tasting-note, wine-vintage, and reviewer-setting state while delegating note
// persistence to the shared repository provided by MainActivity.
data class WineTastingNotesRequest(val definitionId: Long, val vintage: String, val requestId: Long)

class TastingNoteViewModel(application: Application, private val repository: WineRepository) : AndroidViewModel(application) {

    private val _wineNotesRequest = MutableStateFlow<WineTastingNotesRequest?>(null)
    val wineNotesRequest: StateFlow<WineTastingNotesRequest?> = _wineNotesRequest.asStateFlow()
    private var wineNotesRequestId = 0L

    fun openWineTastingNotes(definitionId: Long, vintage: String) {
        _wineNotesRequest.value = WineTastingNotesRequest(definitionId, displayVintage(vintage), ++wineNotesRequestId)
    }

    fun consumeWineTastingNotesRequest(requestId: Long) {
        if (_wineNotesRequest.value?.requestId == requestId) _wineNotesRequest.value = null
    }

    val tastingNotes = repository.tastingHistory
    val allDefinitions = repository.allDefinitions

    val cellarVintages: StateFlow<Map<Long, List<String>>> = repository.activeInventory
        .map { inventory ->
            inventory.groupBy { it.bottle.definitionId }.mapValues { (_, bottles) ->
                bottles.map { displayVintage(it.bottle.vintage) }
                    .distinct()
                    .sortedWith(
                        compareByDescending<String> { it.toIntOrNull() ?: Int.MIN_VALUE }
                            .thenBy { it }
                    )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val cellarPrices: StateFlow<Map<Pair<Long, String>, Double?>> = repository.activeInventory
        .map { inventory ->
            inventory.groupBy { it.bottle.definitionId to displayVintage(it.bottle.vintage) }
                .mapValues { (key, bottles) ->
                    bottles.firstOrNull { it.bottle.vintage == key.second }?.bottle?.price
                        ?: bottles.firstOrNull()?.bottle?.price
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    private val reviewerPreferences = application.getSharedPreferences(
        REVIEWER_PREFERENCES_FILE,
        Context.MODE_PRIVATE
    )
    private val _reviewerNames = MutableStateFlow(loadReviewerNames())
    val reviewerNames: StateFlow<List<String>> = _reviewerNames.asStateFlow()

    fun updateReviewerNames(names: List<String>) {
        val cleanedNames = normalizeReviewerNames(names)
        reviewerPreferences.edit()
            .putStringSet(REVIEWER_NAMES_KEY, cleanedNames.toSet())
            .apply()
        _reviewerNames.value = cleanedNames
    }

    private fun loadReviewerNames(): List<String> {
        val savedNames = reviewerPreferences.getStringSet(REVIEWER_NAMES_KEY, null)
        return normalizeReviewerNames(savedNames?.toList() ?: DEFAULT_REVIEWER_NAMES)
    }

    private fun normalizeReviewerNames(names: Iterable<String>): List<String> = names
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedBy { it.lowercase(Locale.ROOT) }

    suspend fun getTastingNoteById(noteId: Long): TastingNote? = repository.getTastingNoteById(noteId)

    fun saveTastingNote(note: TastingNote, onSaved: (Long) -> Unit) =
        saveTastingNote(note, linkedEventId = null, onSaved = onSaved)

    fun saveTastingNote(note: TastingNote, linkedEventId: Long?, onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val savedId = repository.saveTastingNote(note, linkedEventId)
                if (savedId > 0L) onSaved(savedId)
            } catch (error: Exception) {
                Log.e("TastingNoteViewModel", "Unable to save tasting note", error)
            }
        }
    }

    fun saveManualTastingNote(
        note: TastingNote,
        maker: String,
        name: String,
        country: String,
        region: String?,
        subregion: String?,
        onSaved: (Long) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val savedId = repository.saveManualTastingNote(note, maker, name, country, region, subregion)
                if (savedId > 0L) onSaved(savedId)
            } catch (error: Exception) {
                Log.e("TastingNoteViewModel", "Unable to save manual tasting note", error)
            }
        }
    }

    fun deleteTastingNote(noteId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteTastingNote(noteId)
                onDeleted()
            } catch (error: Exception) {
                Log.e("TastingNoteViewModel", "Unable to delete tasting note", error)
            }
        }
    }

    private val _filterState = MutableStateFlow(TastingNotesFilterState())
    val filterState: StateFlow<TastingNotesFilterState> = _filterState.asStateFlow()

    fun updateFilterState(newState: TastingNotesFilterState) {
        _filterState.value = newState
    }

    private fun String.normalizeLocation(): String {
        return this.trim()
            .lowercase()
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }

    val tastingNotesLocationData: StateFlow<Map<String, Map<String, List<String>>>> = tastingNotes
        .map { list ->
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
            map.mapValues { (_, countryMap) ->
                countryMap.mapValues { (_, regionList) ->
                    regionList.sorted()
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    val rankingsPrices: StateFlow<Map<Pair<Long, String>, Double?>> = tastingNotes
        .map { history ->
            val definitionIds = history.map { it.note.definitionId }.distinct()
            if (definitionIds.isEmpty()) return@map emptyMap()
            
            val allBottles = repository.getAllPricesForDefinitions(definitionIds)
            val priceMap = mutableMapOf<Pair<Long, String>, Double?>()
            
            history.forEach { item ->
                val defId = item.note.definitionId
                val vintage = displayVintage(item.note.vintage)
                val key = Pair(defId, vintage)
                if (priceMap.containsKey(key)) return@forEach

                val specificBottles = allBottles.filter {
                    it.definitionId == defId && displayVintage(it.vintage) == vintage
                }.sortedByDescending { it.vintage == vintage }
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
            priceMap
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    private companion object {
        const val REVIEWER_PREFERENCES_FILE = "wine_manager_settings"
        const val REVIEWER_NAMES_KEY = "wine_reviewer_names"
        val DEFAULT_REVIEWER_NAMES = listOf(
            "Costco Wine Blog",
            "Decanter",
            "James Suckling",
            "Konstantin Baum",
            "Peter Koff",
            "Wine Advocate",
            "Wine Enthusiast",
            "Wine Spectator"
        )
    }
}
