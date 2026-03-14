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
import com.n8rtz.winetracker.data.importer.CsvImportHelper
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

class WineViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        WineDatabase::class.java, "wine-database"
    )
    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
    .build()

    private val repository =
        WineRepository(db.wineDao(), db.inventoryDao(), db.tastingNoteDao(), db.ratingDao())
    private val scannerManager = ScannerManager()

    // Image Pipeline
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://world.openfoodfacts.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val api = retrofit.create(OpenFoodFactsApi::class.java)
    private val batchProcessor = WineImageBatchProcessor(api, db.wineDao(), getApplication())

    // State for the UI to observe
    var imageImportProgress by mutableStateOf<Float?>(null)
        private set
    var imageImportStatus by mutableStateOf("")
        private set

    val locationData = WineRepository.Companion.locationData

    val activeInventory: Flow<List<WineBottleWithDefinition>> = repository.activeInventory
    val archivedInventory: Flow<List<WineBottleWithDefinition>> = repository.archivedInventory
    val getAllWinesWithBottles: Flow<List<WineBottleWithDefinition>> = repository.activeInventory

    val tastingHistory: Flow<List<TastingNoteWithDefinition>> = repository.tastingHistory
    val allDefinitions: Flow<List<WineDefinition>> = repository.allDefinitions

    private val _scannedResult = MutableStateFlow<ScannedWine?>(null)
    val scannedResult: StateFlow<ScannedWine?> = _scannedResult.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    private val _importStatus = MutableStateFlow("")
    val importStatus: StateFlow<String> = _importStatus.asStateFlow()

    fun startImageImport() {
        viewModelScope.launch {
            // 1. Get only wines that don't have images yet
            val winesToProcess = db.wineDao().getAllWithoutImages()
            if (winesToProcess.isEmpty()) return@launch

            // 2. Start the waterfall pipeline
            batchProcessor.processImport(winesToProcess) { done, total ->
                // Update the UI progress bar
                imageImportProgress = done.toFloat() / total
                imageImportStatus = "Finding images... $done/$total"
            }

            // 3. Clear progress when finished
            imageImportStatus = "Done!"
            delay(2000) // Let the user see "Done" for a moment
            imageImportProgress = null
            imageImportStatus = ""
        }
    }

    fun clearScannedResult() {
        _scannedResult.value = null
    }

    fun onImageCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            val result = scannerManager.scanImage(bitmap)
            _scannedResult.value = result
        }
    }

    fun resetDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            try {
                db.clearAllTables()
                Log.d("WineTracker", "Database reset complete.")
            } catch (e: Exception) {
                Log.e("WineTracker", "Database reset failed", e)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun runFreshImport() {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            try {
                val importer = DataImporter(getApplication(), db)
                importer.performThreeWayImport()
                Log.d("WineTracker", "Fresh import complete.")
            } catch (e: Exception) {
                Log.e("WineTracker", "Fresh import failed", e)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun triggerLogImport() {
        runFreshImport()
    }

    fun triggerCsvImportFromAssets() {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                CsvImportHelper.importWines(getApplication(), db)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun deleteWine(bottleId: Long) {
        viewModelScope.launch {
            repository.deleteWine(bottleId)
        }
    }

    fun importCsvFile(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val importer = DataImporter(getApplication(), db)
                importer.standardImport(uri)
                Log.d("WineTracker", "Successfully imported from CSV")
            } catch (e: Exception) {
                Log.e("WineViewModel", "Import failed", e)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun prepareExport(launcher: ActivityResultLauncher<String>) {
        launcher.launch("WineTracker_Backup.csv")
    }

    fun performExport(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                withContext(Dispatchers.IO) {
                    val file = CsvExporter.exportDataToCsv(getApplication(), db)
                    val contentResolver = getApplication<Application>().contentResolver
                    contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
                Log.d("WineTracker", "Export success")
            } catch (e: Exception) {
                Log.e("WineTracker", "Export failed", e)
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun addWine(maker: String, name: String, vintage: String, type: String, country: String, region: String?, subregion: String?, quantity: Int, price: Double) { viewModelScope.launch { repository.addWine(maker, name, vintage, type, country, region, subregion, quantity, price) } }
    fun updateWineDefinition(definition: WineDefinition) { viewModelScope.launch { repository.updateWineDefinition(definition) } }
    fun consumeBottle(bottleId: Long, rating: Double? = null, notes: String? = null, rebuy: Boolean = false) { viewModelScope.launch { repository.consumeBottle(bottleId, rating, notes, rebuy) } }
    fun undoConsumeBottle(bottleId: Long, removeLastNote: Boolean = true) { viewModelScope.launch { repository.undoConsumeBottle(bottleId, removeLastNote) } }
}