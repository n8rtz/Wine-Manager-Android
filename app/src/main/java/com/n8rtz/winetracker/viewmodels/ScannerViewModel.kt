package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.os.SystemClock
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.capture.LocalLabelPhoto
import com.yourapp.wine.capture.SelectedLabelPhotoImporter
import com.yourapp.wine.capture.LabelPhotoTooLargeException
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.diagnostics.ScanStage
import com.yourapp.wine.diagnostics.ScanStageTiming
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.Job
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.scanner.ScannerManager
import com.n8rtz.winetracker.scanner.ScannedWine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    
    // UI States
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

        launchPhotoScan(requestId, acceptedShutterMs, ScanInputSource.CAMERA, photoFile) {
            LocalLabelPhoto(photoFile, photoFile.length())
        }
    }

    /** Picker content URI in; copy original bytes privately and scan locally, without camera/storage permission. */
    fun onLabelPhotoSelected(uri: Uri): Boolean = synchronized(scanStateLock) {
        val current = _labelScanState.value
        if (current !is LabelScanState.Idle && current !is LabelScanState.Error) return@synchronized false
        val requestId = ++nextScanRequestId
        val started = SystemClock.elapsedRealtime()
        _labelScanState.value = LabelScanState.ImportingPhoto(requestId, started)
        launchPhotoScan(requestId, started, ScanInputSource.PHOTO_LIBRARY) {
            SelectedLabelPhotoImporter(getApplication()).copy(uri)
        }
        true
    }

    private fun launchPhotoScan(requestId: Long, started: Long, source: ScanInputSource,
                                initialFile: File? = null, obtain: suspend () -> LocalLabelPhoto) {
        val job = viewModelScope.launch(Dispatchers.Default) {
            var ownedFile: File? = initialFile
            var stage = if (source == ScanInputSource.PHOTO_LIBRARY) ScanStage.IMPORT else ScanStage.INITIALIZE
            var importMs = 0L
            try {
                val deadline = ScanDeadline(started + ScanDeadline.BUDGET_MS)
                val photo = deadline.run { obtain().also { ownedFile = it.file } }
                if (photo == null) {
                    finishScan(requestId, ScanReport.red("Time budget reached while opening the photo — choose again or enter manually",
                        SystemClock.elapsedRealtime() - started, true, source, stage))
                    return@launch
                }
                val preparationMs = SystemClock.elapsedRealtime() - started
                if (source == ScanInputSource.PHOTO_LIBRARY) importMs = preparationMs
                val admitted = synchronized(scanStateLock) {
                    val current = _labelScanState.value
                    if ((current is LabelScanState.ImportingPhoto && current.requestId == requestId) ||
                        (current is LabelScanState.Processing && current.requestId == requestId)) {
                        _labelScanState.value = LabelScanState.Processing(requestId, photo.file.absolutePath, started, source)
                        true
                    } else false
                }
                if (!admitted) return@launch
                stage = ScanStage.INITIALIZE
                val initializationStarted = SystemClock.elapsedRealtime()
                val scanner = deadline.run { WineScannerServices.get(getApplication()) }
                val initializationMs = SystemClock.elapsedRealtime() - initializationStarted
                val result = scanner?.scan(photo.file, started, source) ?: ScanReport.red(
                    "Time budget reached while starting the scanner — retake or enter manually",
                    SystemClock.elapsedRealtime() - started, true, source, stage)
                finishScan(requestId, result.copy(diagnostics = result.diagnostics.copy(
                    source = source, inputBytes = photo.bytes, importMs = importMs,
                    stages = listOfNotNull(
                        if (source == ScanInputSource.PHOTO_LIBRARY) ScanStageTiming(ScanStage.IMPORT, importMs)
                        else ScanStageTiming(ScanStage.CAPTURE, preparationMs),
                        ScanStageTiming(ScanStage.INITIALIZE, initializationMs)) + result.diagnostics.stages)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w("ScannerViewModel", "Local label input/scan failed", error)
                val elapsed = SystemClock.elapsedRealtime() - started
                val timedOut = elapsed >= ScanDeadline.BUDGET_MS
                val hint = when {
                    timedOut -> "Time budget reached while reading the photo — choose again or enter manually"
                    error is LabelPhotoTooLargeException -> "Selected photo exceeds 32 MiB — choose a smaller image"
                    stage == ScanStage.IMPORT -> "Could not open the selected photo — choose another image or enter manually"
                    else -> "Could not start reading this photo — retake or enter details manually"
                }
                finishScan(requestId, ScanReport.red(hint, elapsed, deadlineCancelled = timedOut,
                    source = source, stage = stage, failureType = error.javaClass.name))
            } catch (error: LinkageError) {
                Log.e("ScannerViewModel", "Scanner/native initialization unavailable", error)
                finishScan(requestId, ScanReport.red("Native scanner unavailable — check scan diagnostics",
                    SystemClock.elapsedRealtime() - started, source = source, stage = stage, failureType = error.javaClass.name))
            } catch (error: OutOfMemoryError) {
                finishScan(requestId, ScanReport.red("Memory pressure — choose a smaller photo or enter manually",
                    SystemClock.elapsedRealtime() - started, source = source, stage = stage, failureType = error.javaClass.name))
            } finally {
                ownedFile?.let { if (it.exists() && !it.delete()) Log.w("ScannerViewModel", "Could not delete private label input") }
                finishScan(requestId, null) // Releases FinishingCancelledScan only after IO/native cleanup.
            }
        }
        labelScanJob = job
        // A camera JPEG already exists before dispatch; even cancellation before launch must unlink it.
        job.invokeOnCompletion { initialFile?.delete(); finishScan(requestId, null) }
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
                    SystemClock.elapsedRealtime(), failureType = error.javaClass.name)
            }
        }
    }

    private fun finishScan(requestId: Long, result: ScanReport?) {
        synchronized(scanStateLock) {
            val current = _labelScanState.value
            val ownsInput = (current is LabelScanState.ImportingPhoto && current.requestId == requestId) ||
                (current is LabelScanState.Processing && current.requestId == requestId)
            when {
                ownsInput && result != null -> _labelScanState.value = LabelScanState.Review(requestId, result)
                ownsInput -> _labelScanState.value = LabelScanState.Error(requestId, "Scan cancelled — retake or choose another photo")
                current is LabelScanState.FinishingCancelledScan && current.requestId == requestId ->
                    _labelScanState.value = LabelScanState.Idle
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
                is LabelScanState.ImportingPhoto -> if (current.requestId == requestId) {
                    _labelScanState.value = LabelScanState.FinishingCancelledScan(requestId)
                    labelScanJob?.cancel()
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
            is LabelScanState.ImportingPhoto -> current.requestId
            is LabelScanState.Processing -> current.requestId
            is LabelScanState.Review -> current.requestId
            is LabelScanState.Error -> current.requestId
            is LabelScanState.FinishingCancelledScan, LabelScanState.Idle -> null
        }
        requestId?.let(::dismissLabelScan)
    }

    fun clearScannedResult() { _scannedResult.value = null }
    fun onImageCaptured(bitmap: Bitmap) { viewModelScope.launch { val result = scannerManager.scanImage(bitmap); _scannedResult.value = result } }

    override fun onCleared() {
        if (scannerManagerDelegate.isInitialized()) scannerManager.close()
        super.onCleared()
    }

    private companion object {
        const val PHOTO_CAPTURE_TIMEOUT_MS = com.yourapp.wine.ocr.ScanDeadline.BUDGET_MS
    }
}
