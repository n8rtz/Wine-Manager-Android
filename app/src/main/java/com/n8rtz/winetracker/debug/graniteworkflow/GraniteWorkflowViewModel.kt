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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Immutable UI state for the isolated Dual OCR + Granite 4.2 4B Workflow screen.
 */
data class GraniteWorkflowUiState(
    val modelsStatus: WorkflowModelsStatus = WorkflowModelsStatus(),
    val ocrMode: OcrAblationMode = OcrAblationMode.DUAL,
    val thinkModeEnabled: Boolean = GraniteLabelExtractor.DEFAULT_THINK_MODE_ENABLED,
    val claheEnabled: Boolean = false,
    val dbRagEnabled: Boolean = true,
    val showMlKitBoxes: Boolean = true,
    val showPpOcrBoxes: Boolean = true,
    val imageSourceLabel: String = "No image selected",
    val previewBitmap: Bitmap? = null,
    val isRunning: Boolean = false,
    val isImportingModel: Boolean = false,
    val activeStageMessage: String = "",
    val statusBannerMessage: String? = null,
    val errorBannerMessage: String? = null,
    val lastResult: GraniteWorkflowRunResult? = null,
    val useGlinerDatasetMode: Boolean = false,
    val activeDatasetImage: String? = null,
    val datasetRows: List<GraniteDatasetRow> = emptyList(),
    val datasetAggregate: GraniteDatasetAggregate? = null,
    val isDatasetBatchRunning: Boolean = false,
    val datasetDoneCount: Int = 0,
    val datasetTotalCount: Int = 0,
    val datasetCurrentLabel: String = "",
    val datasetBatchElapsedMs: Long = 0L
) {
    val missingModelsPrompt: String?
        get() = modelsStatus.missingModelsPrompt(ocrMode)

    val datasetRan: Boolean
        get() = datasetRows.any { it.runResult != null || it.status == "error" }
}

/**
 * Standalone ViewModel for `Dual OCR + Granite 4.2 4B Workflow`.
 *
 * Reuses the app's existing ML Kit and PP-OCRv6 files (`v6-medium-det.onnx`, `v6-medium-rec.onnx`,
 * `ppocrv6_dict.utf8`) and the packaged 124-label GLiNER / Lab v7 wine dataset
 * (`assets/wine-app-labels-v1`) without downloading or copying second copies, while keeping
 * ViewModel state and Granite LLM extraction completely isolated from production repositories.
 */
class GraniteWorkflowViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(GraniteWorkflowUiState())
    val uiState: StateFlow<GraniteWorkflowUiState> = _uiState.asStateFlow()

    private val dualOcrEngine = DualOcrEngine()
    private val ragRetriever = GraniteDatabaseRagRetriever()
    private val graniteExtractor = GraniteLabelExtractor()

    private var currentSourceUri: Uri? = null
    private var currentSourceFile: File? = null
    private var currentMemoryBitmap: Bitmap? = null
    private var currentDatasetImageKey: String? = null
    private var activeRunJob: Job? = null

    init {
        refreshModelStatus()
        loadGlinerDatasetCatalog()
    }

    fun refreshModelStatus() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                GraniteModelStorage.inspectModels(app)
            }.onSuccess { status ->
                _uiState.update { state ->
                    state.copy(modelsStatus = status)
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(errorBannerMessage = "Model status check failed: ${err.message ?: err.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Loads the packaged 124-image `wine-app-labels-v1` dataset catalog shared with `GLiNER lane lab`.
     */
    fun loadGlinerDatasetCatalog() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                GraniteLabCorpus.load(app)
            }.onSuccess { samples ->
                _uiState.update { state ->
                    val existingByImage = state.datasetRows.associateBy { it.sample.image }
                    val rows = samples.mapIndexed { index, sample ->
                        existingByImage[sample.image]?.copy(index = index) ?: GraniteDatasetRow(index, sample)
                    }
                    state.copy(
                        datasetRows = rows,
                        datasetTotalCount = if (state.isDatasetBatchRunning) state.datasetTotalCount else samples.size
                    )
                }
            }.onFailure { err ->
                Log.w("GraniteWorkflowVM", "Could not read packaged wine-app-labels-v1 dataset", err)
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

    fun setDbRagEnabled(enabled: Boolean) {
        _uiState.update { it.copy(dbRagEnabled = enabled, errorBannerMessage = null) }
    }

    fun setShowMlKitBoxes(show: Boolean) {
        _uiState.update { it.copy(showMlKitBoxes = show) }
    }

    fun setShowPpOcrBoxes(show: Boolean) {
        _uiState.update { it.copy(showPpOcrBoxes = show) }
    }

    fun resetModelCache() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            GraniteModelStorage.clearGraniteModelCache(app)
            val status = GraniteModelStorage.inspectModels(app)
            _uiState.update {
                it.copy(
                    modelsStatus = status,
                    statusBannerMessage = "Cleared imported Granite GGUF cache (shared PP-OCRv6 & ML Kit preserved).",
                    errorBannerMessage = null
                )
            }
        }
    }

    fun stopWorkflow() {
        activeRunJob?.cancel()
        activeRunJob = null
        _uiState.update { state ->
            val updatedAggregate = if (state.datasetRows.any { it.runResult != null || it.status == "error" }) {
                GraniteLabCorpus.aggregate(state.datasetRows, stopped = true)
            } else {
                state.datasetAggregate
            }
            state.copy(
                isRunning = false,
                isDatasetBatchRunning = false,
                activeStageMessage = "",
                datasetCurrentLabel = "",
                datasetAggregate = updatedAggregate,
                statusBannerMessage = if (updatedAggregate != null) {
                    "Stopped. ${updatedAggregate.text()}"
                } else {
                    "Stopped."
                }
            )
        }
    }

    fun clearSelectedImage() {
        activeRunJob?.cancel()
        activeRunJob = null
        currentSourceUri = null
        currentSourceFile = null
        currentMemoryBitmap = null
        currentDatasetImageKey = null
        _uiState.update {
            it.copy(
                imageSourceLabel = "No image selected",
                previewBitmap = null,
                lastResult = null,
                activeDatasetImage = null,
                useGlinerDatasetMode = false,
                isRunning = false,
                isDatasetBatchRunning = false,
                activeStageMessage = "",
                statusBannerMessage = "Removed selected image.",
                errorBannerMessage = null
            )
        }
    }

    /**
     * Switches the active source to the packaged 124-image GLiNER / Lab v7 wine label dataset
     * (`assets/wine-app-labels-v1`) and loads the first (or currently selected) label.
     */
    fun onUseGlinerDataset() {
        val rows = _uiState.value.datasetRows
        val targetRow = rows.firstOrNull { it.sample.image == _uiState.value.activeDatasetImage }
            ?: rows.firstOrNull()
        if (targetRow == null) {
            _uiState.update {
                it.copy(
                    useGlinerDatasetMode = true,
                    statusBannerMessage = "Loading GLiNER / Lab v7 dataset (124 wine labels)..."
                )
            }
            loadGlinerDatasetCatalog()
            return
        }
        onSelectDatasetSample(targetRow.sample.image, autoRun = false)
    }

    /**
     * Selects one wine label from the packaged 124-image GLiNER / Lab v7 dataset (`wine-app-labels-v1`),
     * loads its bitmap into the preview, and restores its 4-section inspector result if already run
     * (or runs it immediately when [autoRun] is true).
     */
    fun onSelectDatasetSample(imageKey: String, autoRun: Boolean = false) {
        val app = getApplication<Application>()
        val row = _uiState.value.datasetRows.firstOrNull { it.sample.image == imageKey } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                GraniteLabCorpus.decodeBitmap(app, row.sample)
            }.onSuccess { bmp ->
                currentMemoryBitmap = bmp
                currentSourceUri = null
                currentSourceFile = null
                currentDatasetImageKey = row.sample.image
                _uiState.update { state ->
                    state.copy(
                        useGlinerDatasetMode = true,
                        activeDatasetImage = row.sample.image,
                        imageSourceLabel = "GLiNER dataset ${row.displayName}",
                        previewBitmap = row.runResult?.preprocessedBitmap ?: bmp,
                        lastResult = row.runResult ?: state.lastResult?.takeIf { state.activeDatasetImage == row.sample.image },
                        statusBannerMessage = if (row.runResult != null) {
                            "Loaded ${row.displayName} (${row.message})"
                        } else {
                            "Selected ${row.displayName} from GLiNER 124-label dataset."
                        },
                        errorBannerMessage = null
                    )
                }
                if (autoRun) {
                    runWorkflow(allowPreviewWithoutAllModels = true)
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        errorBannerMessage = "Could not load dataset image ${row.displayName}: ${err.message}"
                    )
                }
            }
        }
    }

    /**
     * Runs Dual OCR + Granite 4.2 on a single label from the 124-label GLiNER dataset, scores it
     * against `scoring.json`, and displays its 4-section inspector output.
     */
    fun runDatasetSample(imageKey: String) {
        onSelectDatasetSample(imageKey, autoRun = true)
    }

    /**
     * Runs the Dual OCR + Granite 4.2 pipeline sequentially across all 124 packaged wine labels
     * in `assets/wine-app-labels-v1` (the exact same dataset as `GLiNER lane lab`), updating
     * `Scored results`, `Incomplete labels`, and `All labels (124)` as each photo finishes.
     */
    fun runAllGlinerDatasetSamples() {
        if (_uiState.value.isRunning) return
        val app = getApplication<Application>()
        activeRunJob?.cancel()
        activeRunJob = viewModelScope.launch {
            val batchStarted = SystemClock.elapsedRealtime()
            val ramTracker = PeakRamTracker()
            ramTracker.sample()

            val currentStatus = withContext(Dispatchers.IO) {
                GraniteModelStorage.inspectModels(app)
            }
            val currentMode = _uiState.value.ocrMode
            val thinkEnabled = _uiState.value.thinkModeEnabled
            val claheEnabled = _uiState.value.claheEnabled
            val dbRagEnabled = _uiState.value.dbRagEnabled

            val rows = _uiState.value.datasetRows.ifEmpty {
                withContext(Dispatchers.IO) {
                    GraniteLabCorpus.load(app).mapIndexed { idx, sample -> GraniteDatasetRow(idx, sample) }
                }
            }
            if (rows.isEmpty()) {
                _uiState.update {
                    it.copy(errorBannerMessage = "No packaged GLiNER dataset images found in assets/wine-app-labels-v1.")
                }
                return@launch
            }

            _uiState.update { state ->
                state.copy(
                    modelsStatus = currentStatus,
                    useGlinerDatasetMode = true,
                    isRunning = true,
                    isDatasetBatchRunning = true,
                    datasetDoneCount = 0,
                    datasetTotalCount = rows.size,
                    datasetCurrentLabel = "",
                    datasetBatchElapsedMs = 0L,
                    datasetAggregate = null,
                    datasetRows = rows.map { it.copy(status = "Waiting", runResult = null, fieldResults = emptyList()) },
                    statusBannerMessage = "Starting Dual OCR + Granite 4.2 on GLiNER dataset (${rows.size} labels)...",
                    errorBannerMessage = null
                )
            }

            try {
                for ((index, row) in rows.withIndex()) {
                    coroutineContext.ensureActive()
                    val sample = row.sample
                    _uiState.update { state ->
                        state.copy(
                            datasetCurrentLabel = row.displayName,
                            activeStageMessage = "Dataset ${index + 1}/${rows.size} · ${row.displayName}",
                            datasetBatchElapsedMs = (SystemClock.elapsedRealtime() - batchStarted).coerceAtLeast(1L),
                            datasetRows = state.datasetRows.map { existing ->
                                if (existing.sample.image == sample.image) existing.copy(status = "Running") else existing
                            }
                        )
                    }

                    val evaluatedRow = try {
                        val runResult = executeSinglePipelineOnSample(
                            app = app,
                            sample = sample,
                            ocrMode = currentMode,
                            thinkEnabled = thinkEnabled,
                            claheEnabled = claheEnabled,
                            dbRagEnabled = dbRagEnabled,
                            ramTracker = ramTracker
                        )
                        GraniteLabCorpus.evaluateRow(index, sample, runResult)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        Log.w("GraniteWorkflowVM", "Dataset label ${row.displayName} failed", e)
                        GraniteLabCorpus.failedRow(index, sample, "${e.javaClass.simpleName}: ${e.message}")
                    }

                    _uiState.update { state ->
                        val updatedRows = state.datasetRows.map { existing ->
                            if (existing.sample.image == sample.image) evaluatedRow else existing
                        }
                        val aggregate = GraniteLabCorpus.aggregate(updatedRows, stopped = false)
                        val elapsed = (SystemClock.elapsedRealtime() - batchStarted).coerceAtLeast(1L)
                        val fallbackBanner = evaluatedRow.runResult?.executionBackendNote
                            ?.takeIf { it.contains("JNI fallback:") }
                        state.copy(
                            datasetDoneCount = index + 1,
                            datasetBatchElapsedMs = elapsed,
                            datasetRows = updatedRows,
                            datasetAggregate = aggregate,
                            activeDatasetImage = sample.image,
                            imageSourceLabel = "GLiNER dataset ${row.displayName}",
                            previewBitmap = evaluatedRow.runResult?.preprocessedBitmap ?: state.previewBitmap,
                            lastResult = evaluatedRow.runResult ?: state.lastResult,
                            errorBannerMessage = fallbackBanner ?: state.errorBannerMessage
                        )
                    }
                }

                val finalElapsed = (SystemClock.elapsedRealtime() - batchStarted).coerceAtLeast(1L)
                _uiState.update { state ->
                    val aggregate = GraniteLabCorpus.aggregate(state.datasetRows, stopped = false)
                    state.copy(
                        isRunning = false,
                        isDatasetBatchRunning = false,
                        activeStageMessage = "",
                        datasetCurrentLabel = "",
                        datasetBatchElapsedMs = finalElapsed,
                        datasetAggregate = aggregate,
                        statusBannerMessage = aggregate.text()
                    )
                }
            } catch (cancel: CancellationException) {
                val elapsed = (SystemClock.elapsedRealtime() - batchStarted).coerceAtLeast(1L)
                _uiState.update { state ->
                    val updatedRows = state.datasetRows.map { existing ->
                        if (existing.status == "Running") existing.copy(status = "Stopped") else existing
                    }
                    val aggregate = GraniteLabCorpus.aggregate(updatedRows, stopped = true)
                    state.copy(
                        isRunning = false,
                        isDatasetBatchRunning = false,
                        activeStageMessage = "",
                        datasetCurrentLabel = "",
                        datasetBatchElapsedMs = elapsed,
                        datasetRows = updatedRows,
                        datasetAggregate = aggregate,
                        statusBannerMessage = "Stopped after ${state.datasetDoneCount} photos. ${aggregate.text()}"
                    )
                }
                throw cancel
            }
        }
    }

    /**
     * Imports `granite-4.2-3b-Q4_K_M.gguf` selected via `ActivityResultContracts.OpenDocument`.
     */
    fun importGraniteModelFile(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isImportingModel = true,
                    statusBannerMessage = "Importing Granite 4.2 3B GGUF model...",
                    errorBannerMessage = null
                )
            }
            val result = GraniteModelStorage.importGraniteGgufUri(app, uri)
            val updatedStatus = GraniteModelStorage.inspectModels(app)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { file ->
                        state.copy(
                            modelsStatus = updatedStatus,
                            isImportingModel = false,
                            statusBannerMessage = "Imported ${file.name} (${updatedStatus.graniteGguf.sizeFormatted})",
                            errorBannerMessage = null
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            modelsStatus = updatedStatus,
                            isImportingModel = false,
                            statusBannerMessage = null,
                            errorBannerMessage = err.message ?: err.javaClass.simpleName
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
        currentDatasetImageKey = null
        _uiState.update {
            it.copy(
                useGlinerDatasetMode = false,
                activeDatasetImage = null,
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
        currentDatasetImageKey = null
        _uiState.update {
            it.copy(
                useGlinerDatasetMode = false,
                activeDatasetImage = null,
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
        currentDatasetImageKey = null
        _uiState.update {
            it.copy(
                useGlinerDatasetMode = false,
                activeDatasetImage = null,
                imageSourceLabel = "Synthetic Test Label (Château Margaux 2018)",
                previewBitmap = sample,
                statusBannerMessage = "Loaded sample wine label. Running workflow...",
                errorBannerMessage = null
            )
        }
        runWorkflow(allowPreviewWithoutAllModels = true)
    }

    /**
     * Runs the 3-stage pipeline on the currently selected image:
     * 1. `GraniteWorkflowImagePreprocessor` (<=1280px longest edge, >=768px preserved, optional CLAHE)
     * 2. `DualOcrEngine` (`async` / `awaitAll` on `Dispatchers.Default` reusing existing ML Kit + PP-OCRv6)
     * 3. `GraniteLabelExtractor` (`granite-4.2-3b-Q4_K_M.gguf` / strict 12-field JSON schema + `<think>` toggle)
     */
    fun runWorkflow(allowPreviewWithoutAllModels: Boolean = true) {
        val uri = currentSourceUri
        val file = currentSourceFile
        val memBitmap = currentMemoryBitmap
        val datasetKey = currentDatasetImageKey
        if (uri == null && file == null && memBitmap == null && datasetKey == null) {
            _uiState.update {
                it.copy(
                    errorBannerMessage = "Pick an image from Gallery, Snap Photo, or Use GLiNER dataset (124) first."
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
            val dbRagEnabled = _uiState.value.dbRagEnabled

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
                    isDatasetBatchRunning = false,
                    activeStageMessage = "Stage 1/4: Preprocessing image (<=1280px, CLAHE=${if (claheEnabled) "ON" else "OFF"})...",
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
                        datasetKey != null -> {
                            val dsRow = _uiState.value.datasetRows.first { it.sample.image == datasetKey }
                            val decoded = GraniteLabCorpus.decodeBitmap(app, dsRow.sample)
                            GraniteWorkflowImagePreprocessor.preprocessBitmap(decoded, claheEnabled)
                        }
                        else -> error("No image input available")
                    }
                }
                ramTracker.sample()

                _uiState.update {
                    it.copy(
                        previewBitmap = preprocessed.bitmap,
                        activeStageMessage = "Stage 2/4: Running Parallel Dual-OCR (${currentMode.displayLabel})..."
                    )
                }

                // Stage 2: Parallel Dual-OCR Engine (reusing existing ML Kit + PP-OCRv6 files)
                val dualOcrResult = dualOcrEngine.recognize(
                    context = app,
                    bitmap = preprocessed.bitmap,
                    ablationMode = currentMode
                )
                ramTracker.sample()

                _uiState.update {
                    it.copy(
                        activeStageMessage = "Stage 3/4: Retrieving candidates from Producer, Wine & Appellation DBs (RAG=${if (dbRagEnabled) "ON" else "OFF"})..."
                    )
                }

                // Stage 3: Offline Database Candidate Retrieval (RAG — same databases as GLiNER Lab)
                val ragContext = ragRetriever.retrieve(
                    context = app,
                    mlKitBlocks = dualOcrResult.mlKitBlocks,
                    ppOcrV6Blocks = dualOcrResult.ppOcrV6Blocks,
                    ablationMode = currentMode,
                    enabled = dbRagEnabled
                )
                ramTracker.sample()

                _uiState.update {
                    it.copy(
                        activeStageMessage = "Stage 4/4: Reconciling with Granite 4.2 3B (<think>=${if (thinkEnabled) "ON" else "OFF"}, DB RAG=${if (dbRagEnabled) "ON" else "OFF"})..."
                    )
                }

                // Stage 4: On-Device LLM Reconciliation with Database RAG Context
                val extraction = graniteExtractor.extract(
                    context = app,
                    mlKitBlocks = dualOcrResult.mlKitBlocks,
                    ppOcrV6Blocks = dualOcrResult.ppOcrV6Blocks,
                    ablationMode = currentMode,
                    thinkModeEnabled = thinkEnabled,
                    ragContext = ragContext
                )
                ramTracker.sample()

                val totalMs = (SystemClock.elapsedRealtime() - totalStart).coerceAtLeast(1L)
                val timingAndMemory = WorkflowTimingAndMemory(
                    preprocessMs = preprocessed.elapsedMs,
                    mlKitMs = dualOcrResult.mlKitElapsedMs,
                    ppOcrV6Ms = dualOcrResult.ppOcrV6ElapsedMs,
                    dbRagMs = ragContext.elapsedMs,
                    graniteMs = extraction.elapsedMs,
                    totalMs = totalMs,
                    peakJvmHeapMb = ramTracker.peakJvmMb,
                    peakNativeHeapMb = ramTracker.peakNativeMb,
                    peakCombinedRamMb = ramTracker.peakCombinedMb
                )

                val refreshedStatus = withContext(Dispatchers.IO) {
                    GraniteModelStorage.inspectModels(app)
                }

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
                    ppOcrStatusNote = dualOcrResult.ppOcrStatusMessage,
                    ragContext = ragContext
                )

                _uiState.update { state ->
                    val updatedDatasetRows = if (datasetKey != null) {
                        state.datasetRows.map { existing ->
                            if (existing.sample.image == datasetKey) {
                                GraniteLabCorpus.evaluateRow(existing.index, existing.sample, runResult)
                            } else {
                                existing
                            }
                        }
                    } else {
                        state.datasetRows
                    }
                    val updatedAggregate = if (updatedDatasetRows.any { it.runResult != null || it.status == "error" }) {
                        GraniteLabCorpus.aggregate(updatedDatasetRows, stopped = false)
                    } else {
                        state.datasetAggregate
                    }
                    val doneCount = updatedDatasetRows.count { it.runResult != null || it.status == "error" }
                    val fallbackBanner = extraction.nativeFallbackReason?.let {
                        "Granite GGUF JNI fallback: $it"
                    }
                    state.copy(
                        modelsStatus = refreshedStatus,
                        isRunning = false,
                        activeStageMessage = "",
                        lastResult = runResult,
                        datasetRows = updatedDatasetRows,
                        datasetAggregate = updatedAggregate,
                        datasetDoneCount = if (datasetKey != null) doneCount else state.datasetDoneCount,
                        statusBannerMessage = missingPrompt ?: "Workflow completed in ${totalMs}ms",
                        errorBannerMessage = fallbackBanner
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

    private suspend fun executeSinglePipelineOnSample(
        app: Application,
        sample: GraniteDatasetSample,
        ocrMode: OcrAblationMode,
        thinkEnabled: Boolean,
        claheEnabled: Boolean,
        dbRagEnabled: Boolean,
        ramTracker: PeakRamTracker
    ): GraniteWorkflowRunResult {
        val sampleStart = SystemClock.elapsedRealtime()
        val preprocessed = withContext(Dispatchers.Default) {
            val decoded = GraniteLabCorpus.decodeBitmap(app, sample)
            GraniteWorkflowImagePreprocessor.preprocessBitmap(decoded, claheEnabled)
        }
        ramTracker.sample()

        val dualOcrResult = dualOcrEngine.recognize(
            context = app,
            bitmap = preprocessed.bitmap,
            ablationMode = ocrMode
        )
        ramTracker.sample()

        val ragContext = ragRetriever.retrieve(
            context = app,
            mlKitBlocks = dualOcrResult.mlKitBlocks,
            ppOcrV6Blocks = dualOcrResult.ppOcrV6Blocks,
            ablationMode = ocrMode,
            enabled = dbRagEnabled
        )
        ramTracker.sample()

        val extraction = graniteExtractor.extract(
            context = app,
            mlKitBlocks = dualOcrResult.mlKitBlocks,
            ppOcrV6Blocks = dualOcrResult.ppOcrV6Blocks,
            ablationMode = ocrMode,
            thinkModeEnabled = thinkEnabled,
            ragContext = ragContext
        )
        ramTracker.sample()

        val totalMs = (SystemClock.elapsedRealtime() - sampleStart).coerceAtLeast(1L)
        val timingAndMemory = WorkflowTimingAndMemory(
            preprocessMs = preprocessed.elapsedMs,
            mlKitMs = dualOcrResult.mlKitElapsedMs,
            ppOcrV6Ms = dualOcrResult.ppOcrV6ElapsedMs,
            dbRagMs = ragContext.elapsedMs,
            graniteMs = extraction.elapsedMs,
            totalMs = totalMs,
            peakJvmHeapMb = ramTracker.peakJvmMb,
            peakNativeHeapMb = ramTracker.peakNativeMb,
            peakCombinedRamMb = ramTracker.peakCombinedMb
        )

        return GraniteWorkflowRunResult(
            preprocessedBitmap = preprocessed.bitmap,
            originalWidth = preprocessed.originalWidth,
            originalHeight = preprocessed.originalHeight,
            preprocessedWidth = preprocessed.width,
            preprocessedHeight = preprocessed.height,
            claheApplied = preprocessed.claheApplied,
            ocrMode = ocrMode,
            thinkModeEnabled = thinkEnabled,
            mlKitBlocks = dualOcrResult.mlKitBlocks,
            ppOcrV6Blocks = dualOcrResult.ppOcrV6Blocks,
            timingAndMemory = timingAndMemory,
            structuredJson = extraction.structuredJson,
            prettyJsonText = extraction.prettyJson,
            rawThinkingTrace = extraction.rawThinkTrace,
            rawLlmResponse = extraction.rawLlmOutput,
            executionBackendNote = extraction.backendUsed,
            ppOcrStatusNote = dualOcrResult.ppOcrStatusMessage,
            ragContext = ragContext
        )
    }

    override fun onCleared() {
        super.onCleared()
        activeRunJob?.cancel()
        dualOcrEngine.close()
        ragRetriever.close()
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
