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
import com.n8rtz.winetracker.data.importer.CsvImportHelper
import com.n8rtz.winetracker.data.importer.LegacyCsvImportType
import com.n8rtz.winetracker.data.local.MIGRATION_5_6
import com.n8rtz.winetracker.data.local.MIGRATION_6_7
import com.n8rtz.winetracker.scanner.ScannedWine
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.data.local.ConsumptionEventWithDefinition
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.ui.screens.CellarFilterState
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
    data class Success(val message: String, val restoredBackup: Boolean = false) : RestoreImportState()
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
    private val _cellarResetKey = MutableStateFlow(0)
    val cellarResetKey: StateFlow<Int> = _cellarResetKey.asStateFlow()

    fun resetCellarScreen() {
        _filterState.value = CellarFilterState()
        _cellarResetKey.value += 1
    }

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
    val consumptionHistory: Flow<List<ConsumptionEventWithDefinition>> = repository.consumptionHistory
    val allDefinitions: Flow<List<WineDefinition>> = repository.allDefinitions

    fun tastingNotesForWine(definitionId: Long, vintage: String): Flow<List<TastingNote>> =
        repository.getTastingNotesForWine(definitionId, vintage)
    
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

    fun resetDatabase(onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAllTables()
            runCatching { com.n8rtz.winetracker.image.WineImageFileStore(getApplication()).clearStoredImages() }
            withContext(Dispatchers.Main) {
                resetCellarScreen()
                onComplete()
            }
        }
    }
    fun deleteWine(bottleId: Long) { viewModelScope.launch { repository.deleteWine(bottleId) } }
    
    fun importJsonFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            importState.value = RestoreImportState.Loading
            try {
                val importer = DataImporter(getApplication(), repository.database)
                importer.standardImport(uri)
                resetCellarScreen()
                importState.value = RestoreImportState.Success(
                    message = "Backup restored. Existing app data was replaced.",
                    restoredBackup = true
                )
            } catch (e: Exception) {
                Log.e("WineViewModel", "JSON import failed", e)
                importState.value = RestoreImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun importLegacyCsv(uri: Uri, type: LegacyCsvImportType) {
        viewModelScope.launch(Dispatchers.IO) {
            importState.value = RestoreImportState.Loading
            try {
                val result = when (type) {
                    LegacyCsvImportType.TASTING_NOTES ->
                        CsvImportHelper.importTastingNotes(getApplication(), repository.database, uri)
                    LegacyCsvImportType.CONSUMPTION_HISTORY ->
                        CsvImportHelper.importConsumptionHistory(getApplication(), repository.database, uri)
                }
                importState.value = RestoreImportState.Success(
                    "Imported ${result.imported}; ${result.alreadyPresent} already present; " +
                        "${result.skippedInvalid} incomplete row(s) skipped."
                )
            } catch (e: Exception) {
                Log.e("WineViewModel", "Legacy CSV import failed", e)
                importState.value = RestoreImportState.Error(e.message ?: "CSV import failed")
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
    fun addWine(
        maker: String,
        name: String,
        vintage: String,
        type: String,
        country: String,
        region: String?,
        subregion: String?,
        quantity: Int,
        price: Double?,
        drinkingWindowStart: Int? = null,
        drinkingWindowEnd: Int? = null
    ) {
        viewModelScope.launch {
            repository.addWine(
                maker, name, vintage, type, country, region, subregion, quantity, price,
                drinkingWindowStart, drinkingWindowEnd
            )
        }
    }
    fun updateWineDefinition(definition: WineDefinition) { viewModelScope.launch { repository.updateWineDefinition(definition) } }
    fun updateWine(definition: WineDefinition, bottle: WineBottle) { viewModelScope.launch { repository.updateWine(definition, bottle) } }
    
    // Removed direct inventoryDao accessor to enforce Repository pattern

    fun consumeBottle(
        bottleId: Long,
        rating: Double?,
        notes: String?,
        rebuy: Boolean,
        consumptionDate: Long,
        onComplete: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val eventId = repository.consumeBottle(bottleId, rating, notes, rebuy, consumptionDate)
            if (eventId > 0L) onComplete(eventId)
        }
    }

    fun undoConsumeBottle(eventId: Long) {
        viewModelScope.launch(Dispatchers.IO) { repository.undoConsumeBottle(eventId) }
    }

    /** History: put the bottle back in the cellar and drop the log entry; the tasting note stays. */
    fun restoreConsumptionToCellar(eventId: Long) {
        Log.d("WineTracker", "History: restore event $eventId to cellar")
        viewModelScope.launch(Dispatchers.IO) { repository.undoConsumeBottle(eventId, keepNote = true) }
    }

    /** History: delete the log entry only; the cellar and any tasting note are untouched. */
    fun deleteConsumptionLog(eventId: Long) {
        Log.d("WineTracker", "History: delete event $eventId")
        viewModelScope.launch(Dispatchers.IO) { repository.deleteConsumptionEvent(eventId) }
    }
}


