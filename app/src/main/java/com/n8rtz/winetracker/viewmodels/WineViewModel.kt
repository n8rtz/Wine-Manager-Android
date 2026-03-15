package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.n8rtz.winetracker.data.importer.CsvExporter
import com.n8rtz.winetracker.data.importer.DataImporter
import com.n8rtz.winetracker.data.importer.JsonExporter
import com.n8rtz.winetracker.data.local.MIGRATION_5_6
import com.n8rtz.winetracker.data.local.MIGRATION_6_7
import com.n8rtz.winetracker.scanner.ScannedWine
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.image.WineImageBatchProcessor
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import com.n8rtz.winetracker.data.local.BottleStatus
import com.n8rtz.winetracker.data.local.PriceLookupResult
import com.n8rtz.winetracker.ui.screens.RankingsFilterState

sealed class RestoreImportState {
    object Idle : RestoreImportState()
    object Loading : RestoreImportState()
    object Success : RestoreImportState()
    data class Error(val message: String) : RestoreImportState()
}

data class RankedWineDisplay(
    val item: TastingNoteWithDefinition,
    val priceStr: String,
    val dateStr: String
)

class WineViewModel(application: Application, val repository: WineRepository) : AndroidViewModel(application) {
    private val db = repository.database
    
    // Lazy initialize components to avoid blocking the main thread during ViewModel creation
    private val scannerManager by lazy { 
        Log.d("WineTracker", "WineViewModel: Initializing ScannerManager (lazy)")
        ScannerManager() 
    }

    // Image Pipeline
    private val retrofit by lazy {
        Log.d("WineTracker", "WineViewModel: Initializing Retrofit (lazy)")
        Retrofit.Builder()
            .baseUrl("https://world.openfoodfacts.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    private val api by lazy { 
        Log.d("WineTracker", "WineViewModel: Initializing API (lazy)")
        retrofit.create(OpenFoodFactsApi::class.java) 
    }
    
    private val batchProcessor by lazy { 
        Log.d("WineTracker", "WineViewModel: Initializing BatchProcessor (lazy)")
        WineImageBatchProcessor(api, db.wineDao(), getApplication()) 
    }

    // UI States
    var importState = mutableStateOf<RestoreImportState>(RestoreImportState.Idle)
        private set

    var imageImportProgress by mutableStateOf<Float?>(null)
        private set
    var imageImportStatus by mutableStateOf("")
        private set

    val locationData = WineRepository.Companion.locationData
    
    val activeInventory: Flow<List<WineBottleWithDefinition>> = repository.activeInventory
    val archivedInventory: Flow<List<WineBottleWithDefinition>> = repository.archivedInventory
    val tastingHistory: Flow<List<TastingNoteWithDefinition>> = repository.tastingHistory
    val allDefinitions: Flow<List<WineDefinition>> = repository.allDefinitions
    
    private fun String.normalizeLocation(): String {
        return this.trim()
            .lowercase()
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }

    val cellarLocationData: StateFlow<Map<String, Map<String, List<String>>>> = activeInventory
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
            Log.d("WineTracker", "Cellar Location Mapping: ${map.keys.size} countries normalized")
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

    // Cellar filtering state
    private val _filterState = MutableStateFlow(com.n8rtz.winetracker.ui.screens.CellarFilterState())
    val filterState: StateFlow<com.n8rtz.winetracker.ui.screens.CellarFilterState> = _filterState.asStateFlow()

    fun updateFilterState(newState: com.n8rtz.winetracker.ui.screens.CellarFilterState) {
        _filterState.value = newState
    }

    // Rankings filtering state
    private val _rankingsFilterState = MutableStateFlow(com.n8rtz.winetracker.ui.screens.RankingsFilterState())
    val rankingsFilterState: StateFlow<com.n8rtz.winetracker.ui.screens.RankingsFilterState> = _rankingsFilterState.asStateFlow()

    fun updateRankingsFilterState(newState: com.n8rtz.winetracker.ui.screens.RankingsFilterState) {
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
            
            val allBottles = inventoryDao.getAllPricesForDefinitions(definitionIds)
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

    // HistoryBottles flow for history screen: fetches archived bottles sorted descending by date
    val historyBottles: Flow<List<WineBottleWithDefinition>> = repository.archivedInventory

    private val _scannedResult = MutableStateFlow<ScannedWine?>(null)
    val scannedResult: StateFlow<ScannedWine?> = _scannedResult.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    fun resetImportState() {
        importState.value = RestoreImportState.Idle
    }

    // Existing methods...
    fun startImageImport() {
        viewModelScope.launch {
            val winesToProcess = db.wineDao().getAllWithoutImages()
            if (winesToProcess.isEmpty()) return@launch

            batchProcessor.processImport(winesToProcess) { done, total ->
                imageImportProgress = done.toFloat() / total
                imageImportStatus = "Finding images... $done/$total"
            }
            imageImportStatus = "Done!"
            delay(2000)
            imageImportProgress = null
            imageImportStatus = ""
        }
    }

    fun clearScannedResult() { _scannedResult.value = null }
    fun onImageCaptured(bitmap: Bitmap) { viewModelScope.launch { val result = scannerManager.scanImage(bitmap); _scannedResult.value = result } }
    fun resetDatabase() { viewModelScope.launch(Dispatchers.IO) { db.clearAllTables() } }
    fun deleteWine(bottleId: Long) { viewModelScope.launch { repository.deleteWine(bottleId) } }
    
    fun importJsonFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            importState.value = RestoreImportState.Loading
            try {
                val importer = DataImporter(getApplication(), db)
                importer.standardImport(uri)
                importState.value = RestoreImportState.Success
            } catch (e: Exception) {
                Log.e("WineViewModel", "JSON import failed", e)
                importState.value = RestoreImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun prepareExport(launcher: ActivityResultLauncher<String>) { launcher.launch("WineTracker_Backup.json") }
    fun performExport(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val file = JsonExporter.exportDataToJson(getApplication(), db)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
    fun addWine(maker: String, name: String, vintage: String, type: String, country: String, region: String?, subregion: String?, quantity: Int, price: Double?) { viewModelScope.launch { repository.addWine(maker, name, vintage, type, country, region, subregion, quantity, price) } }
    fun updateWineDefinition(definition: WineDefinition) { viewModelScope.launch { repository.updateWineDefinition(definition) } }
    fun updateWine(definition: WineDefinition, bottle: WineBottle) { viewModelScope.launch { repository.updateWine(definition, bottle) } }
    
    // Expose inventoryDao for price lookups
    val inventoryDao: com.n8rtz.winetracker.data.local.InventoryDao get() = db.inventoryDao()

    fun consumeBottle(bottleId: Long, rating: Double? = null, notes: String? = null, rebuy: Boolean = false) { viewModelScope.launch { repository.consumeBottle(bottleId, rating, notes, rebuy) } }
    fun undoConsumeBottle(bottleId: Long, removeLastNote: Boolean = true) { viewModelScope.launch { repository.undoConsumeBottle(bottleId, removeLastNote) } }
}
