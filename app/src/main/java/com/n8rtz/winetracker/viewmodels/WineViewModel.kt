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
import com.n8rtz.winetracker.data.local.MIGRATION_5_6
import com.n8rtz.winetracker.data.local.MIGRATION_6_7
import com.n8rtz.winetracker.scanner.ScannedWine
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

sealed class RestoreImportState {
    object Idle : RestoreImportState()
    object Loading : RestoreImportState()
    object Success : RestoreImportState()
    data class Error(val message: String) : RestoreImportState()
}

class WineViewModel(application: Application, val repository: WineRepository) : AndroidViewModel(application) {
    private val db = repository.database
    private val scannerManager = ScannerManager()

    // Image Pipeline
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://world.openfoodfacts.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val api = retrofit.create(OpenFoodFactsApi::class.java)
    private val batchProcessor = WineImageBatchProcessor(api, db.wineDao(), getApplication())

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
    
    fun importCsvFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            importState.value = RestoreImportState.Loading
            try {
                val importer = DataImporter(getApplication(), db)
                importer.standardImport(uri)
                importState.value = RestoreImportState.Success
            } catch (e: Exception) {
                Log.e("WineViewModel", "CSV import failed", e)
                importState.value = RestoreImportState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun prepareExport(launcher: ActivityResultLauncher<String>) { launcher.launch("WineTracker_Backup.csv") }
    fun performExport(uri: Uri) { viewModelScope.launch { withContext(Dispatchers.IO) { val file = CsvExporter.exportDataToCsv(getApplication(), db); getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input -> input.copyTo(output) } } } } }
    fun addWine(maker: String, name: String, vintage: String, type: String, country: String, region: String?, subregion: String?, quantity: Int, price: Double) { viewModelScope.launch { repository.addWine(maker, name, vintage, type, country, region, subregion, quantity, price) } }
    fun updateWineDefinition(definition: WineDefinition) { viewModelScope.launch { repository.updateWineDefinition(definition) } }
    fun consumeBottle(bottleId: Long, rating: Double? = null, notes: String? = null, rebuy: Boolean = false) { viewModelScope.launch { repository.consumeBottle(bottleId, rating, notes, rebuy) } }
    fun undoConsumeBottle(bottleId: Long, removeLastNote: Boolean = true) { viewModelScope.launch { repository.undoConsumeBottle(bottleId, removeLastNote) } }
}
