package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.repository.WineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.scanner.ScannedWine
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import com.n8rtz.winetracker.image.WineImageBatchProcessor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ScannerViewModel(application: Application, val repository: WineRepository) : AndroidViewModel(application) {
    private val db = repository.database
    
    // Lazy initialize components to avoid blocking the main thread during ViewModel creation
    private val scannerManager by lazy { 
        Log.d("WineTracker", "ScannerViewModel: Initializing ScannerManager (lazy)")
        ScannerManager() 
    }

    // Image Pipeline
    private val retrofit by lazy {
        Log.d("WineTracker", "ScannerViewModel: Initializing Retrofit (lazy)")
        Retrofit.Builder()
            .baseUrl("https://world.openfoodfacts.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    private val api by lazy { 
        Log.d("WineTracker", "ScannerViewModel: Initializing API (lazy)")
        retrofit.create(OpenFoodFactsApi::class.java) 
    }
    
    private val batchProcessor by lazy { 
        Log.d("WineTracker", "ScannerViewModel: Initializing BatchProcessor (lazy)")
        WineImageBatchProcessor(api, db.wineDao(), getApplication()) 
    }

    // UI States
    var imageImportProgress by mutableStateOf<Float?>(null)
        private set
    var imageImportStatus by mutableStateOf("")
        private set

    private val _scannedResult = MutableStateFlow<ScannedWine?>(null)
    val scannedResult: StateFlow<ScannedWine?> = _scannedResult.asStateFlow()

    fun startImageImport() {
        viewModelScope.launch {
            val winesToProcess = repository.getAllWithoutImages()
            if (winesToProcess.isEmpty()) return@launch

            batchProcessor.processImport(winesToProcess) { done, total ->
                imageImportProgress = done.toFloat() / total
                imageImportStatus = "Finding images... $done/$total"
            }
            imageImportStatus = "Done!"
            kotlinx.coroutines.delay(2000)
            imageImportProgress = null
            imageImportStatus = ""
        }
    }

    fun clearScannedResult() { _scannedResult.value = null }
    fun onImageCaptured(bitmap: Bitmap) { viewModelScope.launch { val result = scannerManager.scanImage(bitmap); _scannedResult.value = result } }
}
