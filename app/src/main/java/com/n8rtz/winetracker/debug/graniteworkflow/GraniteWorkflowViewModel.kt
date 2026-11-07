package com.n8rtz.winetracker.debug.graniteworkflow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Immutable UI state for the isolated Dual OCR + Granite 4.2 4B Workflow screen.
 */
data class GraniteWorkflowUiState(
    val modelsStatus: WorkflowModelsStatus = WorkflowModelsStatus(),
    val ocrMode: OcrAblationMode = OcrAblationMode.DUAL,
    val thinkModeEnabled: Boolean = GraniteLabelExtractor.DEFAULT_THINK_MODE_ENABLED,
    val claheEnabled: Boolean = false,
    val showMlKitBoxes: Boolean = true,
    val showPpOcrBoxes: Boolean = true,
    val imageSourceLabel: String = "No image selected",
    val previewBitmap: Bitmap? = null,
    val isRunning: Boolean = false,
    val isImportingModel: Boolean = false,
    val activeStageMessage: String = "",
    val statusBannerMessage: String? = null,
    val errorBannerMessage: String? = null,
    val lastResult: GraniteWorkflowRunResult? = null
) {
    val missingModelsPrompt: String?
        get() = modelsStatus.missingModelsPrompt(ocrMode)
}

/**
 * Completely standalone ViewModel for `Dual OCR + Granite 4.2 4B Workflow`.
 *
 * Shares zero mutable state, repositories, or services with production app classes.
 */
class GraniteWorkflowViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(GraniteWorkflowUiState())
    val uiState: StateFlow<GraniteWorkflowUiState> = _uiState.asStateFlow()

    private val dualOcrEngine = DualOcrEngine()
    private val graniteExtractor = GraniteLabelExtractor()

    private var currentSourceUri: Uri? = null
    private var currentSourceFile: File? = null
    private var currentMemoryBitmap: Bitmap? = null
    private var activeRunJob: Job? = null

    init {
        refreshModelStatus()
    }

    fun refreshModelStatus() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val status = GraniteModelStorage.inspectModels(app)
            _uiState.update { state ->
                state.copy(modelsStatus = status)
            }
        }
    }

    fun setOcrMode(mode: OcrAblationMode) {
        _uiState.update { it.copy(ocrMode = mode, errorBannerMessage = null) }
    }

    fun setThinkModeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(thinkModeEnabled = enabled, errorBannerMessage = null) }
    }

    fun setClaheEnabled(enabled: Boolean) {
        _uiState.update { it.copy(claheEnabled = enabled, errorBannerMessage = null) }
    }

    fun setShowMlKitBoxes(show: Boolean) {
        _uiState.update { it.copy(showMlKitBoxes = show) }
    }

    fun setShowPpOcrBoxes(show: Boolean) {
        _uiState.update { it.copy(showPpOcrBoxes = show) }
    }

    fun clearBanners() {
        _uiState.update { it.copy(statusBannerMessage = null, errorBannerMessage = null) }
    }

    /**
     * Imports one or more model files (`.gguf`, `.onnx`, `.utf8`) selected via `ActivityResultContracts.OpenDocument`.
     */
    fun importModelFiles(uris: List<Uri>, target: GraniteModelImportTarget) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isImportingModel = true,
                    statusBannerMessage = "Importing ${uris.size} model file(s)...",
                    errorBannerMessage = null
                )
            }
            val importedNames = mutableListOf<String>()
            val errors = mutableListOf<String>()
            for (uri in uris) {
                GraniteModelStorage.importModelUri(app, uri, target)
                    .onSuccess { file -> importedNames.add(file.name) }
                    .onFailure { err -> errors.add(err.message ?: err.javaClass.simpleName) }
            }
            val updatedStatus = GraniteModelStorage.inspectModels(app)
            _uiState.update { state ->
                state.copy(
                    modelsStatus = updatedStatus,
                    isImportingModel = false,
                    statusBannerMessage = if (importedNames.isNotEmpty()) {
                        "Imported: ${importedNames.joinToString(", ")}"
                    } else null,
                    errorBannerMessage = if (errors.isNotEmpty()) {
                        errors.joinToString("; ")
                    } else null
                )
            }
        }
    }

    /**
     * Copies bundled PP-OCRv6 ONNX models (`v6-medium-det.onnx` & `v6-medium-rec.onnx` + `ppocrv6_dict.utf8`)
     * into `context.getExternalFilesDir("models/ppocrv6")` with one tap.
     */
    fun seedBundledPpOcrModels() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isImportingModel = true,
                    statusBannerMessage = "Copying bundled PP-OCRv6 ONNX files to models/ppocrv6...",
                    errorBannerMessage = null
                )
            }
            val result = GraniteModelStorage.seedPpOcrFromBundledAssets(app)
            val updatedStatus = GraniteModelStorage.inspectModels(app)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { count ->
                        state.copy(
                            modelsStatus = updatedStatus,
                            isImportingModel = false,
                            statusBannerMessage = "Copied $count PP-OCRv6 files into ${updatedStatus.ppOcrDirectoryPath}",
                            errorBannerMessage = null
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            modelsStatus = updatedStatus,
                            isImportingModel = false,
                            statusBannerMessage = null,
                            errorBannerMessage = "Could not copy bundled PP-OCRv6 assets: ${err.message}"
                        )
                    }
                )
            }
        }
    }

    fun onGalleryImageSelected(uri: Uri) {
        currentSourceUri = uri
        currentSourceFile = null
        currentMemoryBitmap = null
        _uiState.update {
            it.copy(
                imageSourceLabel = "Gallery URI: ${uri.lastPathSegment ?: uri.toString()}",
                statusBannerMessage = "Image selected. Running Dual OCR + Granite 4.2 workflow...",
                errorBannerMessage = null
            )
        }
        runWorkflow(allowPreviewWithoutAllModels = true)
    }

    fun onCameraPhotoCaptured(file: File) {
        currentSourceFile = file
        currentSourceUri = null
        currentMemoryBitmap = null
        _uiState.update {
            it.copy(
                imageSourceLabel = "Camera capture: ${file.name}",
                statusBannerMessage = "Photo captured. Running Dual OCR + Granite 4.2 workflow...",
                errorBannerMessage = null
            )
        }
        runWorkflow(allowPreviewWithoutAllModels = true)
    }

    fun onLoadSyntheticSampleLabel() {
        val sample = createSyntheticWineLabelBitmap()
        currentMemoryBitmap = sample
        currentSourceUri = null
        currentSourceFile = null
        _uiState.update {
            it.copy(
                imageSourceLabel = "Synthetic Test Label (Château Margaux 2018)",
                previewBitmap = sample,
                statusBannerMessage = "Loaded sample wine label. Running workflow...",
                errorBannerMessage = null
            )
        }
        runWorkflow(allowPreviewWithoutAllModels = true)
    }

    /**
     * Runs the 3-stage pipeline:
     * 1. `GraniteWorkflowImagePreprocessor` (<=1280px longest edge, >=768px preserved, optional CLAHE)
     * 2. `DualOcrEngine` (`async` / `awaitAll` on `Dispatchers.Default` for ML Kit v2 + PP-OCRv6 ONNX)
     * 3. `GraniteLabelExtractor` (`granite-4.2-3b-Q4_K_M.gguf` / strict 12-field JSON schema + `<think>` toggle)
     */
    fun runWorkflow(allowPreviewWithoutAllModels: Boolean = true) {
        val uri = currentSourceUri
        val file = currentSourceFile
        val memBitmap = currentMemoryBitmap
        if (uri == null && file == null && memBitmap == null) {
            _uiState.update {
                it.copy(
                    errorBannerMessage = "Pick an image from Gallery or Snap Photo first."
                )
            }
            return
        }

        activeRunJob?.cancel()
        val app = getApplication<Application>()
        activeRunJob = viewModelScope.launch {
            val totalStart = SystemClock.elapsedRealtime()
            val ramTracker = PeakRamTracker()
            ramTracker.sample()

            val currentStatus = withContext(Dispatchers.IO) {
                GraniteModelStorage.inspectModels(app)
            }
            val currentMode = _uiState.value.ocrMode
            val thinkEnabled = _uiState.value.thinkModeEnabled
            val claheEnabled = _uiState.value.claheEnabled

            val missingPrompt = currentStatus.missingModelsPrompt(currentMode)
            if (missingPrompt != null && !allowPreviewWithoutAllModels) {
                _uiState.update {
                    it.copy(
                        modelsStatus = currentStatus,
                        statusBannerMessage = missingPrompt
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    modelsStatus = currentStatus,
                    isRunning = true,
                    activeStageMessage = "Stage 1/3: Preprocessing image (<=1280px, CLAHE=${if (claheEnabled) "ON" else "OFF"})...",
                    errorBannerMessage = null
                )
            }

            try {
                // Stage 1: Image Preprocessor
                val preprocessed = withContext(Dispatchers.Default) {
                    when {
                        uri != null -> GraniteWorkflowImagePreprocessor.preprocessUri(app, uri, claheEnabled)
                        file != null -> GraniteWorkflowImagePreprocessor.preprocessFile(file, claheEnabled)
                        memBitmap != null -> GraniteWorkflowImagePreprocessor.preprocessBitmap(memBitmap, claheEnabled)
                        else -> error("No image input available")
                    }
                }
                ramTracker.sample()

                _uiState.update {
                    it.copy(
                        previewBitmap = preprocessed.bitmap,
                        activeStageMessage = "Stage 2/3: Running Parallel Dual-OCR (${currentMode.displayLabel})..."
                    )
                }

                // Stage 2: Parallel Dual-OCR Engine
                val dualOcrResult = dualOcrEngine.recognize(
                    context = app,
                    bitmap = preprocessed.bitmap,
                    ablationMode = currentMode
                )
                ramTracker.sample()

                _uiState.update {
                    it.copy(
                        activeStageMessage = "Stage 3/3: Reconciling with Granite 4.2 3B (<think>=${if (thinkEnabled) "ON" else "OFF"})..."
                    )
                }

                // Stage 3: On-Device LLM Reconciliation
                val extraction = graniteExtractor.extract(
                    context = app,
                    mlKitBlocks = dualOcrResult.mlKitBlocks,
                    ppOcrV6Blocks = dualOcrResult.ppOcrV6Blocks,
                    ablationMode = currentMode,
                    thinkModeEnabled = thinkEnabled
                )
                ramTracker.sample()

                val totalMs = (SystemClock.elapsedRealtime() - totalStart).coerceAtLeast(1L)
                val timingAndMemory = WorkflowTimingAndMemory(
                    preprocessMs = preprocessed.elapsedMs,
                    mlKitMs = dualOcrResult.mlKitElapsedMs,
                    ppOcrV6Ms = dualOcrResult.ppOcrV6ElapsedMs,
                    graniteMs = extraction.elapsedMs,
                    totalMs = totalMs,
                    peakJvmHeapMb = ramTracker.peakJvmMb,
                    peakNativeHeapMb = ramTracker.peakNativeMb,
                    peakCombinedRamMb = ramTracker.peakCombinedMb
                )

                val runResult = GraniteWorkflowRunResult(
                    preprocessedBitmap = preprocessed.bitmap,
                    originalWidth = preprocessed.originalWidth,
                    originalHeight = preprocessed.originalHeight,
                    preprocessedWidth = preprocessed.width,
                    preprocessedHeight = preprocessed.height,
                    claheApplied = preprocessed.claheApplied,
                    ocrMode = currentMode,
                    thinkModeEnabled = thinkEnabled,
                    mlKitBlocks = dualOcrResult.mlKitBlocks,
                    ppOcrV6Blocks = dualOcrResult.ppOcrV6Blocks,
                    timingAndMemory = timingAndMemory,
                    structuredJson = extraction.structuredJson,
                    prettyJsonText = extraction.prettyJson,
                    rawThinkingTrace = extraction.rawThinkTrace,
                    rawLlmResponse = extraction.rawLlmOutput,
                    executionBackendNote = extraction.backendUsed,
                    ppOcrStatusNote = dualOcrResult.ppOcrStatusMessage
                )

                _uiState.update {
                    it.copy(
                        isRunning = false,
                        activeStageMessage = "",
                        lastResult = runResult,
                        statusBannerMessage = missingPrompt ?: "Workflow completed in ${totalMs}ms"
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.e("GraniteWorkflowVM", "Workflow execution error", e)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        activeStageMessage = "",
                        errorBannerMessage = "Workflow error: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeRunJob?.cancel()
        dualOcrEngine.close()
        graniteExtractor.close()
    }

    private class PeakRamTracker {
        var peakJvmMb: Double = 0.0
            private set
        var peakNativeMb: Double = 0.0
            private set
        var peakCombinedMb: Double = 0.0
            private set

        fun sample() {
            val runtime = Runtime.getRuntime()
            val jvmUsedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
            val nativeUsedBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L).coerceAtLeast(0L)
            val jvmMb = jvmUsedBytes / (1024.0 * 1024.0)
            val nativeMb = nativeUsedBytes / (1024.0 * 1024.0)
            peakJvmMb = max(peakJvmMb, jvmMb)
            peakNativeMb = max(peakNativeMb, nativeMb)
            peakCombinedMb = max(peakCombinedMb, jvmMb + nativeMb)
        }
    }

    companion object {
        /**
         * Creates a clean 800x1100 synthetic wine bottle label Bitmap for quick on-device testing.
         */
        fun createSyntheticWineLabelBitmap(): Bitmap {
            val width = 800
            val height = 1100
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(248, 244, 234))

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = Color.rgb(114, 47, 55)
            }
            canvas.drawRect(40f, 40f, width - 40f, height - 40f, borderPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 24, 24)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }

            textPaint.textSize = 46f
            canvas.drawText("CHÂTEAU MARGAUX", width / 2f, 220f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textPaint.textSize = 34f
            canvas.drawText("PREMIER GRAND CRU CLASSÉ", width / 2f, 320f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textPaint.textSize = 36f
            canvas.drawText("Pavillon Rouge", width / 2f, 430f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textPaint.textSize = 52f
            canvas.drawText("2018", width / 2f, 560f, textPaint)

            textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textPaint.textSize = 38f
            canvas.drawText("MARGAUX", width / 2f, 670f, textPaint)

            textPaint.textSize = 26f
            canvas.drawText("APPELLATION MARGAUX CONTRÔLÉE", width / 2f, 735f, textPaint)
            canvas.drawText("Cabernet Sauvignon · Merlot", width / 2f, 800f, textPaint)

            textPaint.textSize = 24f
            canvas.drawText("13.5% ALC./VOL.    PRODUCE OF FRANCE    750 ML", width / 2f, 960f, textPaint)

            return bmp
        }
    }
}
