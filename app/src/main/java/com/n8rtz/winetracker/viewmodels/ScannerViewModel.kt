package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.image.WineImageBatchProcessor
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.scanner.OneShotScanGate
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.scanner.ScannedWine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicBoolean

class ScannerViewModel(application: Application, val repository: WineRepository) : AndroidViewModel(application) {
    private val db = repository.database
    
    // Retain lazy initialization so image imports do not create an unused recognizer.
    private val scannerManagerDelegate = lazy {
        Log.d("WineTracker", "ScannerViewModel: Initializing ScannerManager (lazy)")
        ScannerManager()
    }
    private val scannerManager by scannerManagerDelegate
    private val scanGate = OneShotScanGate()
    private val scanStateLock = Any()
    private var nextScanRequestId = 0L

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

    private val _labelScanState = MutableStateFlow<LabelScanState>(LabelScanState.Idle)
    val labelScanState: StateFlow<LabelScanState> = _labelScanState.asStateFlow()

    fun requestLabelScan(): Boolean = synchronized(scanStateLock) {
        val current = _labelScanState.value
        if (current !is LabelScanState.Idle && current !is LabelScanState.Error) {
            return@synchronized false
        }

        val requestId = ++nextScanRequestId
        if (!scanGate.request(requestId)) return@synchronized false
        _labelScanState.value = LabelScanState.WaitingForFrame(requestId)

        viewModelScope.launch {
            kotlinx.coroutines.delay(SCAN_FRAME_TIMEOUT_MS)
            synchronized(scanStateLock) {
                val state = _labelScanState.value
                if (state is LabelScanState.WaitingForFrame &&
                    state.requestId == requestId &&
                    scanGate.cancel(requestId)
                ) {
                    _labelScanState.value = LabelScanState.Error(
                        requestId,
                        "Camera did not provide a frame. Try scanning again."
                    )
                }
            }
        }
        true
    }

    /** Called for every ImageAnalysis frame; only a requested frame is handed to ML Kit. */
    fun onAnalysisFrame(imageProxy: ImageProxy) {
        val requestId = synchronized(scanStateLock) {
            val claimedId = scanGate.claimNextFrame()
            if (claimedId == null) {
                imageProxy.close()
                return
            }

            val state = _labelScanState.value
            if (state !is LabelScanState.WaitingForFrame || state.requestId != claimedId) {
                scanGate.finish(claimedId)
                if (state is LabelScanState.FinishingCancelledScan && state.requestId == claimedId) {
                    _labelScanState.value = LabelScanState.Idle
                }
                imageProxy.close()
                return
            }

            _labelScanState.value = LabelScanState.Processing(claimedId)
            claimedId
        }

        val ownershipTransferred = AtomicBoolean(false)
        val job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val manager = scannerManager
                ownershipTransferred.set(true)
                val result = manager.scanImageProxy(imageProxy)
                finishScan(requestId, result, null)
            } catch (cancelled: CancellationException) {
                finishScan(requestId, null, null)
                throw cancelled
            } catch (error: Exception) {
                Log.w("ScannerViewModel", "On-device label OCR failed", error)
                finishScan(requestId, null, error)
            }
        }
        job.invokeOnCompletion {
            // If the ViewModel was cleared before the coroutine started, ScannerManager never owned it.
            if (!ownershipTransferred.get()) imageProxy.close()
        }
    }

    private fun finishScan(requestId: Long, result: ScannedWine?, error: Exception?) {
        synchronized(scanStateLock) {
            val mayPublish = scanGate.finish(requestId)
            when (val current = _labelScanState.value) {
                is LabelScanState.Processing -> if (current.requestId == requestId && mayPublish) {
                    _labelScanState.value = when {
                        error != null -> LabelScanState.Error(
                            requestId,
                            "Could not read this label. Try better light, hold it steady, or enter details manually."
                        )
                        result?.hasSuggestions == true -> LabelScanState.Review(requestId, result)
                        else -> LabelScanState.Error(
                            requestId,
                            "No readable wine details were found. Try again or enter them manually."
                        )
                    }
                }
                is LabelScanState.FinishingCancelledScan -> if (current.requestId == requestId) {
                    _labelScanState.value = LabelScanState.Idle
                }
                else -> Unit
            }
        }
    }

    fun dismissLabelScan(requestId: Long) {
        synchronized(scanStateLock) {
            when (val current = _labelScanState.value) {
                is LabelScanState.WaitingForFrame -> if (current.requestId == requestId && scanGate.cancel(requestId)) {
                    _labelScanState.value = LabelScanState.Idle
                }
                is LabelScanState.Processing -> if (current.requestId == requestId && scanGate.cancel(requestId)) {
                    _labelScanState.value = LabelScanState.FinishingCancelledScan(requestId)
                }
                is LabelScanState.Review -> if (current.requestId == requestId) {
                    _labelScanState.value = LabelScanState.Idle
                }
                is LabelScanState.Error -> if (current.requestId == requestId) {
                    _labelScanState.value = LabelScanState.Idle
                }
                is LabelScanState.FinishingCancelledScan, LabelScanState.Idle -> Unit
            }
        }
    }

    fun cancelActiveLabelScan() {
        val requestId = when (val current = _labelScanState.value) {
            is LabelScanState.WaitingForFrame -> current.requestId
            is LabelScanState.Processing -> current.requestId
            is LabelScanState.Review -> current.requestId
            is LabelScanState.Error -> current.requestId
            is LabelScanState.FinishingCancelledScan, LabelScanState.Idle -> null
        }
        requestId?.let(::dismissLabelScan)
    }

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

    override fun onCleared() {
        if (scannerManagerDelegate.isInitialized()) scannerManager.close()
        super.onCleared()
    }

    private companion object {
        const val SCAN_FRAME_TIMEOUT_MS = 8_000L
    }
}
