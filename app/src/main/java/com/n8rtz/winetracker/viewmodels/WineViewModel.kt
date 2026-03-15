package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.importer.DataImporter
import com.n8rtz.winetracker.data.importer.JsonExporter
import com.n8rtz.winetracker.data.local.MIGRATION_5_6
import com.n8rtz.winetracker.data.local.MIGRATION_6_7
import com.n8rtz.winetracker.scanner.ScannedWine
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.ui.screens.CellarFilterState
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

    private val _filterState = MutableStateFlow(CellarFilterState())
    val filterState: StateFlow<CellarFilterState> = _filterState.asStateFlow()

    fun updateFilterState(newState: CellarFilterState) {
        _filterState.value = newState
    }

    // UI States
    var importState = mutableStateOf<RestoreImportState>(RestoreImportState.Idle)
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


    val historyBottles: Flow<List<WineBottleWithDefinition>> = repository.archivedInventory


    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    fun resetImportState() {
        importState.value = RestoreImportState.Idle
    }

    // Existing methods...
    fun resetDatabase() { viewModelScope.launch(Dispatchers.IO) { repository.clearAllTables() } }
    fun deleteWine(bottleId: Long) { viewModelScope.launch { repository.deleteWine(bottleId) } }
    
    fun importJsonFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            importState.value = RestoreImportState.Loading
            try {
                val importer = DataImporter(getApplication(), repository.database)
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
                val file = JsonExporter.exportDataToJson(getApplication(), repository.database)
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
    
    // Removed direct inventoryDao accessor to enforce Repository pattern

    fun consumeBottle(bottleId: Long, rating: Double? = null, notes: String? = null, rebuy: Boolean = false) { viewModelScope.launch { repository.consumeBottle(bottleId, rating, notes, rebuy) } }
    fun undoConsumeBottle(bottleId: Long, removeLastNote: Boolean = true) { viewModelScope.launch { repository.undoConsumeBottle(bottleId, removeLastNote) } }
}


