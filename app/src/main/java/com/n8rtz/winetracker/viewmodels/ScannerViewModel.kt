package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.yourapp.wine.capture.WineScannerServices
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.scanner.ScannedWine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

class ScannerViewModel(application: Application, val repository: WineRepository) : AndroidViewModel(application) {
    private val db = repository.database
    
    // Retain lazy initialization so image imports do not create an unused recognizer.
    private val scannerManagerDelegate = lazy {
        Log.d("WineTracker", "ScannerViewModel: Initializing ScannerManager (lazy)")
        // Compatibility bitmap callers are offline too; no barcode registry is injected.
        ScannerManager(application)
    }
    private val scannerManager by scannerManagerDelegate
    init {
        runCatching { com.n8rtz.winetracker.scanner.ProductionLabelScanSession.connect(application) }
            .onFailure { Log.w("ScannerViewModel", "Failed to connect scan session on init", it) }
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
    
    // UI States
    private val _scannedResult = MutableStateFlow<ScannedWine?>(null)
    val scannedResult: StateFlow<ScannedWine?> = _scannedResult.asStateFlow()

    val labelScanState = com.n8rtz.winetracker.scanner.ProductionLabelScanSession.state

    fun requestLabelScan(): Boolean = com.n8rtz.winetracker.scanner.ProductionLabelScanSession.requestCapture(getApplication())
    fun onLabelPhotoCaptured(requestId: Long, photoFile: File) =
        com.n8rtz.winetracker.scanner.ProductionLabelScanSession.captured(getApplication(), requestId, photoFile)
    fun onLabelPhotoSelected(uri: Uri): Boolean =
        com.n8rtz.winetracker.scanner.ProductionLabelScanSession.selected(getApplication(), uri)
    fun onLabelCaptureFailed(requestId: Long, error: Throwable) =
        com.n8rtz.winetracker.scanner.ProductionLabelScanSession.captureFailed(getApplication(), requestId, error)
    fun dismissLabelScan(requestId: Long) =
        com.n8rtz.winetracker.scanner.ProductionLabelScanSession.dismiss(getApplication(), requestId)
    fun cancelPendingLabelCapture() =
        com.n8rtz.winetracker.scanner.ProductionLabelScanSession.cancelPendingCapture(getApplication())
    fun cancelActiveLabelScan() =
        com.n8rtz.winetracker.scanner.ProductionLabelScanSession.cancelActive(getApplication())

    fun warmLabelScanner() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { WineScannerServices.get(getApplication()) }
                .onFailure { Log.w("ScannerViewModel", "Scanner prewarm unavailable", it) }
        }
    }

    fun clearScannedResult() { _scannedResult.value = null }
    fun onImageCaptured(bitmap: Bitmap) { viewModelScope.launch { val result = scannerManager.scanImage(bitmap); _scannedResult.value = result } }

    override fun onCleared() {
        if (scannerManagerDelegate.isInitialized()) scannerManager.close()
        super.onCleared()
    }

}
