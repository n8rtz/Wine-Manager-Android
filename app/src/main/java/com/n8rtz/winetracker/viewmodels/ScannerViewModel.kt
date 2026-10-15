package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import android.os.SystemClock
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.Job
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.image.WineImageBatchProcessor
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.scanner.ScannedWine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val scanStateLock = Any()
    private var nextScanRequestId = 0L
    private var labelScanJob: Job? = null

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

    /** Starts a user-requested still photo capture; no OCR runs on the live preview. */
    fun requestLabelScan(): Boolean = synchronized(scanStateLock) {
        val current = _labelScanState.value
        if (current !is LabelScanState.Idle && current !is LabelScanState.Error) {
            return@synchronized false
        }

        val requestId = ++nextScanRequestId
        _labelScanState.value = LabelScanState.CapturingPhoto(requestId, SystemClock.elapsedRealtime())
        viewModelScope.launch {
            delay(PHOTO_CAPTURE_TIMEOUT_MS)
            synchronized(scanStateLock) {
                val state = _labelScanState.value
                if (state is LabelScanState.CapturingPhoto && state.requestId == requestId) {
                    _labelScanState.value = state.rejected("The photo took too long — retake or enter details manually",
                        SystemClock.elapsedRealtime(), deadlineCancelled = true)
                }
            }
        }
        true
    }

    /** Called by CameraX only after the full-resolution still photo has been saved. */
    fun onLabelPhotoCaptured(requestId: Long, photoFile: File) {
        val acceptedShutterMs = synchronized(scanStateLock) {
            val state = _labelScanState.value
            if (state is LabelScanState.CapturingPhoto && state.requestId == requestId) {
                _labelScanState.value = LabelScanState.Processing(requestId, photoFile.absolutePath, state.shutterMs)
                state.shutterMs
            } else {
                null
            }
        }
        if (acceptedShutterMs == null) {
            photoFile.delete()
            return
        }

        val shutterMs = acceptedShutterMs
        labelScanJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val deadline = ScanDeadline(shutterMs + ScanDeadline.BUDGET_MS)
                val scanner = deadline.run { WineScannerServices.get(getApplication()) }
                val result = scanner?.scan(photoFile, shutterMs) ?: ScanReport.red(
                    "Time budget reached while starting the scanner — retake or enter manually",
                    SystemClock.elapsedRealtime() - shutterMs, deadlineCancelled = true)
                finishScan(requestId, result, null)
            } catch (cancelled: CancellationException) {
                finishScan(requestId, null, null)
                throw cancelled
            } catch (error: Exception) {
                Log.w("ScannerViewModel", "Offline label scan failed", error)
                finishScan(requestId, null, error)
            } catch (error: LinkageError) {
                Log.e("ScannerViewModel", "OpenCV/native build unavailable", error)
                finishScan(requestId, null, IllegalStateException("Native scanner unavailable", error))
            } catch (error: OutOfMemoryError) {
                Log.e("ScannerViewModel", "Scanner initialization memory pressure", error)
                finishScan(requestId, ScanReport.red("Memory pressure — enter manually or retake"), null)
            } finally {
                if (photoFile.exists() && !photoFile.delete()) Log.w("ScannerViewModel", "Could not delete temporary label JPEG")
            }
        }
    }

    /** No coordinates: warm the app-scoped KB/recognizer on Default while the camera opens. */
    fun warmLabelScanner() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { WineScannerServices.get(getApplication()) }
                .onFailure { Log.w("ScannerViewModel", "Scanner prewarm unavailable", it) }
        }
    }

    /** No image coordinates: a failed still capture enters six-field RED review instead of retaining stale auto-fill. */
    fun onLabelCaptureFailed(requestId: Long, error: Throwable) {
        Log.w("ScannerViewModel", "Could not capture wine label photo", error)
        synchronized(scanStateLock) {
            val state = _labelScanState.value
            if (state is LabelScanState.CapturingPhoto && state.requestId == requestId) {
                _labelScanState.value = state.rejected("Could not take the photo — retake or enter details manually",
                    SystemClock.elapsedRealtime())
            }
        }
    }

    private fun finishScan(requestId: Long, result: ScanReport?, error: Exception?) {
        synchronized(scanStateLock) {
            when (val current = _labelScanState.value) {
                is LabelScanState.Processing -> if (current.requestId == requestId) {
                    _labelScanState.value = when {
                        error != null -> LabelScanState.Review(requestId, ScanReport.red(
                            "Could not read this photo — retake or enter details manually"))
                        result != null -> LabelScanState.Review(requestId, result)
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
                is LabelScanState.CapturingPhoto -> if (current.requestId == requestId) {
                    _labelScanState.value = LabelScanState.Idle
                }
                is LabelScanState.Processing -> if (current.requestId == requestId) {
                    _labelScanState.value = LabelScanState.FinishingCancelledScan(requestId)
                    labelScanJob?.cancel()
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
            is LabelScanState.CapturingPhoto -> current.requestId
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
        const val PHOTO_CAPTURE_TIMEOUT_MS = 8_000L
    }
}
