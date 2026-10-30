package com.yourapp.wine.phonecorpus

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.textlab.PaddleCandidates
import com.yourapp.wine.textlab.TextLabCsv
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import com.yourapp.wine.capture.WineScannerServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val WINE_APP_LABEL_COUNT = 124

/**
 * The retired comparators. Each photo runs through these once, the outcome is frozen on this
 * device, and later passes score the frozen record instead of re-running them. Only Lab v7
 * consumes OCR on a later pass, so a captured corpus never reruns a Prod version.
 */
private val BASELINE_MODES = listOf(
    PhoneCorpusRunner.Mode.PRODUCTION_V1,
    PhoneCorpusRunner.Mode.PRODUCTION_V2,
    PhoneCorpusRunner.Mode.PRODUCTION_V4,
    PhoneCorpusRunner.Mode.PRODUCTION_V5,
    PhoneCorpusRunner.Mode.PRODUCTION_V6,
)

internal data class WineAppLabelsState(
    val busy: Boolean = false,
    val suiteRunning: Boolean = false,
    val suiteProgress: String = "",
    val savedSuites: List<String> = emptyList(),
    val mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB,
    val message: String = "Loading the 124 retained wine labels…",
    val completed: Int = 0,
    val total: Int = WINE_APP_LABEL_COUNT,
    val board: PhoneCorpusScoreboard.Board? = null,
    val exportParts: List<File> = emptyList(),
    /** Photos the current Lab run has checkpointed. */
    val labCompleted: Int = 0,
    val labRunning: Boolean = false,
    /** When checked next to Lab v5, reuses verified cached OCR outputs instead of re-running both OCR models. */
    val useCachedOcr: Boolean = true,
    /** Mix-and-match post-OCR modules added after Best V5; all false reproduces Best V5 exactly. */
    // Lab v7 starts from the Prod v6.0 known-improvement checkpoint; experiments
    // toggle modules away from (and back to) that measured configuration.
    val labModules: LabPostOcrModules = LabPostOcrModules.prodV6Checkpoint,
    /** Photos with verified ML Kit + Paddle Medium OCR outputs cached on this device. */
    val ocrCached: Int = 0,
    /** Frozen Prod capture per version; absent until its first capture pass. */
    val captures: Map<PhoneCorpusRunner.Mode, WineAppLabelsBaselines.Capture> = emptyMap(),
    /** Photos still to run through the not-yet-frozen Prod versions. */
    val captureRemaining: Int = WINE_APP_LABEL_COUNT,
    /** Active benchmark execution duration accumulated before the current running pass (ms). */
    val elapsedRunMs: Long = 0L,
    /** Monotonic SystemClock.elapsedRealtime() when the active benchmark pass started; null when idle. */
    val runSegmentStartedRealtimeMs: Long? = null,
    /** Mean execution time per completed image in the current Lab run (ms); null until 1+ image finishes. */
    val avgImageMs: Long? = null,
    /** Per-image pace (ms) used for the live countdown (current pass pace once 1+ image finishes, else avgImageMs). */
    val etaPerImageMs: Long? = null,
    /** Monotonic SystemClock.elapsedRealtime() when the currently-executing image started; null when idle. */
    val currentImageStartedRealtimeMs: Long? = null,
    /** Remaining images to process in the active pass (or remaining uncheckpointed/uncaptured when idle). */
    val remainingImages: Int = WINE_APP_LABEL_COUNT,
    /** Per-field correct/incorrect counts for the current build's Lab v5 run. */
    val currentBuildFieldScores: Map<String, WineAppLabelsBuildLog.FieldScore> = emptyMap(),
    /** Highest per-field correct/incorrect counts achieved across all logged Lab v5 builds. */
    val bestBuildFieldScores: Map<String, WineAppLabelsBuildLog.FieldScore> = emptyMap(),
    /** Per-field correct/incorrect counts for frozen Prod v4.0 (scoped to Lab v5's attempted subset). */
    val prodV4FieldScores: Map<String, WineAppLabelsBuildLog.FieldScore> = emptyMap(),
    val prodV5FieldScores: Map<String, WineAppLabelsBuildLog.FieldScore> = emptyMap(),
    val prodV6FieldScores: Map<String, WineAppLabelsBuildLog.FieldScore> = emptyMap(),
    /** Per-photo rows for matched-cohort comparisons, including the full frozen Prod capture. */
    val comparisonFieldRows: Map<PhoneCorpusRunner.Mode, Map<String, List<Map<String, String>>>> = emptyMap(),
    val recentLabRuns: List<WineAppLabelsBuildLog.Entry> = emptyList(),
)

private data class RunTiming(
    val elapsedRunMs: Long = 0L,
    val timedImagesCount: Int = 0,
    val timedImagesTotalMs: Long = 0L,
)

/**
 * Process-scoped holder so an active 124-label benchmark run survives navigating away from
 * [WineAppLabelsActivity] (Back to the cellar or switching apps while WineTracker stays open).
 */
private class WineAppLabelsSession {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mutable = MutableStateFlow(WineAppLabelsState())
    val lock = Mutex()
    @Volatile var loaded = false
    @Volatile var stopping = false
    @Volatile var exporting = false
    @Volatile var activityForeground = false
    @Volatile var labFolder: File? = null
    @Volatile var baselines: WineAppLabelsBaselines? = null
    @Volatile var ocrCache: WineAppLabelsOcrCache? = null
    @Volatile var buildLog: WineAppLabelsBuildLog? = null
    @Volatile var expectedHashes: Map<String, String>? = null

    companion object {
        @Volatile private var instance: WineAppLabelsSession? = null
        fun get(): WineAppLabelsSession = instance ?: synchronized(this) {
            instance ?: WineAppLabelsSession().also { instance = it }
        }
    }
}

/** Runs the 124 retained workbook labels. Targets stay on the scoring side of the boundary. */
internal class WineAppLabelsViewModel(app: Application) : AndroidViewModel(app) {
    private val app get() = getApplication<Application>()
    private val session = WineAppLabelsSession.get()
    private val bgScope get() = session.scope
    private val mutable get() = session.mutable
    val state = session.mutable.asStateFlow()
    private val root get() = File(app.noBackupFilesDir, "wine-app-labels-v1").apply { mkdirs() }
    private val installId get() = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
    private val lock get() = session.lock
    private var stopping: Boolean
        get() = session.stopping
        set(value) { session.stopping = value }
    /** CSV preparation/saving also sets [WineAppLabelsState.busy]; this flag keeps it out of run lifecycle logs. */
    private var exporting: Boolean
        get() = session.exporting
        set(value) { session.exporting = value }
    private var activityForeground: Boolean
        get() = session.activityForeground
        set(value) { session.activityForeground = value }
    /** Lab run folder. The Prod versions no longer have a run folder: they are frozen records. */
    private var labFolder: File?
        get() = session.labFolder
        set(value) { session.labFolder = value }
    private var baselines: WineAppLabelsBaselines?
        get() = session.baselines
        set(value) { session.baselines = value }
    private var ocrCache: WineAppLabelsOcrCache?
        get() = session.ocrCache
        set(value) { session.ocrCache = value }
    private var buildLog: WineAppLabelsBuildLog?
        get() = session.buildLog
        set(value) { session.buildLog = value }

    init { load(PhoneCorpusRunner.Mode.LAB) }

    private fun assetJson(name: String) = JSONObject(app.assets.open("wine-app-labels-v1/$name").bufferedReader().use { it.readText() })

    private fun includedIds(): Set<String> {
        val selection = assetJson("evaluation-set.json")
        val list = selection.getJSONArray("included_images")
        val expected = selection.optInt("included_count")
        return (0 until list.length()).map { list.getJSONObject(it).getString("image") }.toSet().also {
            check(it.size == WINE_APP_LABEL_COUNT && expected == WINE_APP_LABEL_COUNT) { "The retained workbook selection is invalid" }
        }
    }

    private fun inputHashes(): Map<String, String> {
        session.expectedHashes?.let { return it }
        val included = includedIds()
        val images = assetJson("inputs.json").getJSONArray("images")
        return (0 until images.length()).map { images.getJSONObject(it) }
            .filter { it.getString("image") in included }
            .associate { it.getString("image") to it.getString("sha256") }
            .also { session.expectedHashes = it }
    }

    private fun prefsFile() = File(root, "ui-prefs.json")

    private fun readPrefsJson(): JSONObject? = runCatching {
        val file = prefsFile()
        if (file.isFile) JSONObject(file.readText()) else null
    }.getOrNull()

    private fun readUseCachedOcrPref(): Boolean = runCatching {
        val file = prefsFile()
        if (file.isFile) JSONObject(file.readText()).optBoolean("use_cached_ocr_v2", true) else true
    }.getOrDefault(true)

    private fun readLabModulesPref(): LabPostOcrModules = runCatching {
        val arr = readPrefsJson()?.optJSONArray("lab_post_ocr_modules") ?: return@runCatching LabPostOcrModules.none
        val keys = (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf(String::isNotBlank) }.toSet()
        LabPostOcrModules.fromKeys(keys).withoutRetiredModules()
    }.getOrDefault(LabPostOcrModules.none)

    private fun writeUiPrefs(useCachedOcr: Boolean, modules: LabPostOcrModules) {
        runCatching {
            val moduleArray = JSONArray(modules.activeKeys())
            prefsFile().writeText(
                JSONObject()
                    .put("use_cached_ocr", useCachedOcr)
                    .put("use_cached_ocr_v2", useCachedOcr)
                    .put("lab_post_ocr_modules", moduleArray)
                    .toString(),
            )
        }
    }

    fun setUseCachedOcr(enabled: Boolean) {
        if (mutable.value.busy) return
        writeUiPrefs(enabled, mutable.value.labModules)
        mutable.update { it.copy(useCachedOcr = enabled) }
    }

    fun setLabModule(moduleId: LabPostOcrModules.ModuleId, enabled: Boolean) {
        if (moduleId !in LabPostOcrModules.selectableModuleIds) return
        switchLabModules(mutable.value.labModules.withModule(moduleId, enabled))
    }

    fun setAllLabModules(enabled: Boolean) {
        switchLabModules(if (enabled) LabPostOcrModules.all else LabPostOcrModules.none)
    }

    /** Restores the module set the last best measured Lab v5 run shipped with. */
    fun setBestBuildLabModules() {
        switchLabModules(LabPostOcrModules.bestBuild)
    }

    private fun switchLabModules(requested: LabPostOcrModules) {
        val next = requested.withoutRetiredModules().withIntegrated()
        if (mutable.value.busy || mutable.value.suiteRunning) return
        if (mutable.value.labModules == next && labFolder != null) return
        writeUiPrefs(mutable.value.useCachedOcr, next)
        Log.d("WineTracker", "WineAppLabels post-OCR modules set to ${next.signatureKey()}")
        mutable.update { it.copy(busy = true, labModules = next, exportParts = emptyList()) }
        bgScope.launch {
            try {
                if (!session.loaded || baselines == null || ocrCache == null || buildLog == null) {
                    mutable.update { it.copy(busy = false) }
                    load(PhoneCorpusRunner.Mode.LAB, fresh = false)
                    return@launch
                }
                labFolder = selectFolder(PhoneCorpusRunner.Mode.LAB, fresh = false)
                refresh()
                mutable.update { it.copy(message = "Post-OCR config: ${next.summaryLabel()}") }
            } catch (e: Exception) {
                mutable.update { it.copy(message = "Cannot switch post-OCR modules: ${e.message}") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun folderPrefix(mode: PhoneCorpusRunner.Mode) = "${BuildConfig.BUILD_COMMIT}-$installId-${mode.name}-"

    private fun corpusSignature(manifest: JSONObject): String = listOf(
        "inputs_sha256", "evaluation_set_sha256", "scoring_sha256",
    ).joinToString(":") { manifest.getString(it) }

    /** Short stable id of the loaded label corpus, so the build log never mixes reference sets. */
    private fun corpusLogKey(signature: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(12)

    private var cachedCorpusLogKey: String? = null

    private fun loadedCorpusLogKey(): String {
        cachedCorpusLogKey?.let { return it }
        val key = runCatching { corpusLogKey(corpusSignature(assetJson("manifest.json"))) }.getOrDefault("")
        if (key.isNotBlank()) cachedCorpusLogKey = key
        return key
    }

    private fun matchingRunFolders(expectedCorpus: String): List<File> =
        root.listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            runCatching {
                val metadata = JSONObject(File(dir, "run.json").readText())
                val savedCorpus = metadata.optJSONObject("corpus_manifest") ?: return@runCatching null
                if (corpusSignature(savedCorpus) != expectedCorpus) return@runCatching null
                dir to metadata.optLong("created_ms", dir.lastModified())
            }.getOrNull()
        }.sortedByDescending { it.second }.map { it.first }

    private fun allRunFolders(): List<File> =
        root.listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            val createdMs = runCatching {
                JSONObject(File(dir, "run.json").readText()).optLong("created_ms", dir.lastModified())
            }.getOrDefault(dir.lastModified())
            dir to createdMs
        }.sortedByDescending { it.second }.map { it.first }

    /** Reuse this version's saved per-photo results across app updates, but never across corpus revisions. */
    private fun latestFolder(mode: PhoneCorpusRunner.Mode): File? {
        val expectedCorpus = corpusSignature(assetJson("manifest.json"))
        return root.listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            runCatching {
                val metadata = JSONObject(File(dir, "run.json").readText())
                val savedCorpus = metadata.optJSONObject("corpus_manifest") ?: return@runCatching null
                val savedMode = metadata.optString("mode")
                val savedPipeline = metadata.optString("pipeline")
                val isCurrentRun = savedMode == mode.name && savedPipeline == mode.pipelineId
                // Preserve frozen Prod v3 results after the enum/folder was promoted to Prod v4.
                // Lab v4 runs intentionally do not satisfy this: Lab v5 must start on its new pipeline.
                val isLegacyProdV3Run = mode == PhoneCorpusRunner.Mode.PRODUCTION_V4 &&
                    savedMode == "PRODUCTION_V3" &&
                    PhoneCorpusRunner.Mode.fromPipelineId(savedPipeline) == mode
                if ((!isCurrentRun && !isLegacyProdV3Run) || corpusSignature(savedCorpus) != expectedCorpus) {
                    return@runCatching null
                }
                if (mode == PhoneCorpusRunner.Mode.LAB &&
                    metadata.optString("lab_modules_key") != mutable.value.labModules.signatureKey()
                ) {
                    return@runCatching null
                }
                dir to metadata.optLong("created_ms", dir.lastModified())
            }.getOrNull()
        }.maxByOrNull { it.second }?.first
    }

    private fun persistResult(dir: File, image: String, result: JSONObject, previousAttempt: Int = 0) {
        result.put("attempt", previousAttempt + 1)
        if(mutable.value.suiteRunning) {
            val trace=result.optJSONArray("trace") ?: JSONArray()
            val decisions=JSONArray()
            for(i in 0 until trace.length()) {
                val row=trace.getJSONObject(i)
                if(row.optString("candidate")!="spatial_lab") continue
                if(row.optString("stage").endsWith("evidence_observation")) {
                    row.put("shared_ocr_text_hash",diagnosticHash(com.yourapp.wine.merge.TextNorm.scrub(row.optString("raw_text"))))
                    row.remove("raw_text")
                }
                decisions.put(row)
            }
            result.put("trace",decisions)
            result.remove("tokens")
        }
        File(dir, "attempts").apply { mkdirs() }
            .resolve("$image-${System.currentTimeMillis()}.json").writeText(result.toString())
        result.remove("trace")
        result.remove("tokens")
        result.remove("diagnostics")
        File(dir, "current").apply { mkdirs() }.resolve("$image.json").writeText(result.toString())
    }

    private fun selectFolder(mode: PhoneCorpusRunner.Mode, fresh: Boolean): File {
        val prefix = folderPrefix(mode)
        if (!fresh) latestFolder(mode)?.let { return it }
        val dir = File(root, prefix + UUID.randomUUID()).apply { mkdirs() }
        val cachedOcrEnabled = mode == PhoneCorpusRunner.Mode.LAB && mutable.value.useCachedOcr
        val activeModules = mutable.value.labModules
        val metadata = JSONObject()
            .put("build", BuildConfig.BUILD_COMMIT)
            .put("branch", BuildConfig.BUILD_BRANCH)
            .put("version_code", BuildConfig.VERSION_CODE.toString())
            .put("build_key", WineAppLabelsBuildLog.currentBuildKey())
            .put("working_tree", BuildConfig.BUILD_WORKING_TREE)
            .put("mode", mode.name)
            .put("pipeline", mode.pipelineId)
            .put("lab_modules_key", if (mode == PhoneCorpusRunner.Mode.LAB) activeModules.signatureKey() else "")
            .put("lab_modules_label", if (mode == PhoneCorpusRunner.Mode.LAB) activeModules.summaryLabel() else "")
            .put("schema_version", 1)
            .put("corpus_manifest", assetJson("manifest.json"))
            .put("created_ms", System.currentTimeMillis())
            .put("ocr_cache_enabled", cachedOcrEnabled)
            .put(
                "runtime",
                if (cachedOcrEnabled) "phone; cached OCR outputs; owner-reviewed Wine App Labels"
                else "phone; fresh OCR; owner-reviewed Wine App Labels",
            )
            .put("elapsed_run_ms", 0L)
            .put("timed_images_count", 0)
            .put("timed_images_total_ms", 0L)
        File(dir, "run.json").writeText(metadata.toString(2))
        return dir
    }

    private fun readRunTiming(dir: File?): RunTiming = runCatching {
        if (dir == null) return RunTiming()
        val file = File(dir, "run.json")
        if (!file.isFile) return RunTiming()
        val metadata = JSONObject(file.readText())
        RunTiming(
            elapsedRunMs = metadata.optLong("elapsed_run_ms", 0L).coerceAtLeast(0L),
            timedImagesCount = metadata.optInt("timed_images_count", 0).coerceAtLeast(0),
            timedImagesTotalMs = metadata.optLong("timed_images_total_ms", 0L).coerceAtLeast(0L),
        )
    }.getOrDefault(RunTiming())

    private fun writeRunTiming(dir: File, timing: RunTiming) {
        runCatching {
            val file = File(dir, "run.json")
            if (!file.isFile) return
            val metadata = JSONObject(file.readText())
                .put("elapsed_run_ms", timing.elapsedRunMs.coerceAtLeast(0L))
                .put("timed_images_count", timing.timedImagesCount.coerceAtLeast(0))
                .put("timed_images_total_ms", timing.timedImagesTotalMs.coerceAtLeast(0L))
            val tmp = File(dir, "run.json.tmp")
            tmp.writeText(metadata.toString(2))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun predictions(dir: File) = File(dir, "current").listFiles().orEmpty()
        .filter { it.extension == "json" }
        .sortedBy { it.name }
        .map { JSONObject(it.readText()) }
        .filter { it.optString("image") in includedIds() }

    /** Lab checkpoints in run order. */
    private fun labPredictions(): List<JSONObject> = labFolder?.let(::predictions).orEmpty()

    /**
     * One record per photo for each version, scoped to the photos Lab v5 has attempted in this
     * pass so all four rows compare side by side over the exact same subset before all 124 finish
     * (while still showing the full frozen baseline when Lab has 0 checkpoints).
     * On-disk Prod baselines remain frozen at full dataset size in [WineAppLabelsBaselines].
     */
    private fun predictionsByMode(): Map<PhoneCorpusRunner.Mode, List<JSONObject>> {
        val lab = labPredictions()
        val attempted: Set<String> = lab.mapNotNullTo(linkedSetOf()) {
            it.optString("image").takeIf(String::isNotBlank)
        }
        return PhoneCorpusRunner.Mode.entries.associateWith { mode ->
            when (mode) {
                PhoneCorpusRunner.Mode.LAB -> lab
                else -> {
                    val frozen = baselines?.all(mode).orEmpty()
                    if (attempted.isEmpty()) frozen.values.toList() else attempted.mapNotNull(frozen::get)
                }
            }
        }
    }

    private fun scoreboard() = PhoneCorpusScoreboard(app, "wine-app-labels-v1")

    private fun backfillBuildLog(log: WineAppLabelsBuildLog, expectedCorpus: String) {
        val existingByRunId = log.readEntries().associateBy { it.runId }
        val scorer = scoreboard()
        val corpusKey = corpusLogKey(expectedCorpus)
        val unstamped = mutableSetOf<String>()
        for (dir in matchingRunFolders(expectedCorpus).asReversed()) {
            val prior = existingByRunId[dir.name]
            if (prior != null && prior.hasFieldScores) {
                // Already scored from this corpus's folder, so it only needs the cohort tag.
                if (prior.corpusKey.isBlank()) unstamped += dir.name
                continue
            }
            val metadata = runCatching { JSONObject(File(dir, "run.json").readText()) }.getOrNull() ?: continue
            if (metadata.optString("mode") != PhoneCorpusRunner.Mode.LAB.name) continue
            val preds = runCatching { predictions(dir) }.getOrNull().orEmpty()
            if (preds.isEmpty()) continue
            val built = runCatching { scorer.build(preds, PhoneCorpusRunner.Mode.LAB) }.getOrNull() ?: continue
            val summaryRow = built.rows.singleOrNull() ?: continue
            val fieldScores = WineAppLabelsBuildLog.summarizeFieldRows(built.csv)
            val info = WineAppLabelsBuildLog.buildInfoFromSavedRun(metadata, preds)
            val ts = prior?.timestampMs
                ?: File(dir, "current").listFiles().orEmpty().maxOfOrNull { it.lastModified() }
                ?: metadata.optLong("created_ms", dir.lastModified())
            log.recordSummary(dir.name, info, PhoneCorpusRunner.Mode.LAB, summaryRow, ts, fieldScores,
                corpusKey = corpusKey)
        }
        log.stampCorpusKeys(unstamped, corpusKey, PhoneCorpusRunner.Mode.LAB)
        log.syncBestBuildSummary(PhoneCorpusRunner.Mode.LAB)
    }

    private fun refresh() {
        val mode = mutable.value.mode
        val scoreboard = PhoneCorpusScoreboard(app, "wine-app-labels-v1")
        // Lab is scored from this pass's checkpoints; frozen Prod rows follow Lab's attempted
        // photo subset so side-by-side totals stay apples-to-apples while a pass is running.
        val byMode = predictionsByMode()
        val currentBoard = scoreboard.build(byMode.getValue(mode), mode)
        val modeRows = PhoneCorpusRunner.Mode.entries.map { comparedMode ->
            val comparisonPredictions = byMode.getValue(comparedMode)
            if (comparedMode == mode) currentBoard.rows.single()
            else scoreboard.build(comparisonPredictions, comparedMode).rows.single()
        }
        val labPreds = byMode.getValue(PhoneCorpusRunner.Mode.LAB)
        val labCount = labPreds.size
        val labCsv = if (mode == PhoneCorpusRunner.Mode.LAB) {
            currentBoard.csv
        } else {
            scoreboard.build(labPreds, PhoneCorpusRunner.Mode.LAB).csv
        }
        val currentFieldScores = WineAppLabelsBuildLog.summarizeFieldRows(labCsv)
        val prodV4Preds = byMode.getValue(PhoneCorpusRunner.Mode.PRODUCTION_V4)
        val prodV4FieldScores = if (prodV4Preds.isEmpty()) {
            emptyMap()
        } else {
            val prodV4Csv = if (mode == PhoneCorpusRunner.Mode.PRODUCTION_V4) {
                currentBoard.csv
            } else {
                scoreboard.build(prodV4Preds, PhoneCorpusRunner.Mode.PRODUCTION_V4).csv
            }
            WineAppLabelsBuildLog.summarizeFieldRows(prodV4Csv)
        }
        val prodV5Preds = byMode.getValue(PhoneCorpusRunner.Mode.PRODUCTION_V5)
        val prodV5FieldScores = if (prodV5Preds.isEmpty()) {
            emptyMap()
        } else {
            val prodV5Csv = if (mode == PhoneCorpusRunner.Mode.PRODUCTION_V5) {
                currentBoard.csv
            } else {
                scoreboard.build(prodV5Preds, PhoneCorpusRunner.Mode.PRODUCTION_V5).csv
            }
            WineAppLabelsBuildLog.summarizeFieldRows(prodV5Csv)
        }
        val prodV6Preds = byMode.getValue(PhoneCorpusRunner.Mode.PRODUCTION_V6)
        val prodV6Csv = if (prodV6Preds.isEmpty()) emptyList() else if (mode == PhoneCorpusRunner.Mode.PRODUCTION_V6) {
            currentBoard.csv
        } else {
            scoreboard.build(prodV6Preds, PhoneCorpusRunner.Mode.PRODUCTION_V6).csv
        }
        val prodV6FieldScores = WineAppLabelsBuildLog.summarizeFieldRows(prodV6Csv)
        // The Lab page scopes its Prod totals to Lab's attempted photos. GLiNER needs the full
        // frozen capture so it can select its own completed-photo cohort, including photos not
        // yet attempted in a partial Lab pass.
        val included = includedIds()
        val allProdV6 = baselines?.all(PhoneCorpusRunner.Mode.PRODUCTION_V6).orEmpty()
            .filterKeys { it in included }.values.toList()
        val comparisonProdCsv = if (allProdV6.map { it.optString("image") }.toSet() ==
            prodV6Preds.map { it.optString("image") }.toSet()) prodV6Csv else
            scoreboard.build(allProdV6, PhoneCorpusRunner.Mode.PRODUCTION_V6).csv
        fun fieldRows(csv:List<Map<String,String>>):Map<String,List<Map<String,String>>> =
            csv.filter { it["row_type"] == "field" && !it["image"].isNullOrBlank() }.groupBy { it.getValue("image") }
        val comparisonFieldRows = mapOf(
            PhoneCorpusRunner.Mode.LAB to fieldRows(labCsv),
            PhoneCorpusRunner.Mode.PRODUCTION_V6 to fieldRows(comparisonProdCsv),
        )
        val activeLabDir = labFolder
        if (activeLabDir != null && labCount > 0) {
            val runMeta = runCatching { JSONObject(File(activeLabDir, "run.json").readText()) }.getOrNull()
            val info = WineAppLabelsBuildLog.buildInfoFromSavedRun(runMeta, labPreds)
            val isCurrentBuild = info.buildKey.startsWith(WineAppLabelsBuildLog.currentBuildKey())
            val ts = if (isCurrentBuild) {
                System.currentTimeMillis()
            } else {
                File(activeLabDir, "current").listFiles().orEmpty().maxOfOrNull { it.lastModified() }
                    ?: runMeta?.optLong("created_ms", activeLabDir.lastModified())
                    ?: System.currentTimeMillis()
            }
            buildLog?.recordSummary(activeLabDir.name, info, PhoneCorpusRunner.Mode.LAB, modeRows.first(), ts, currentFieldScores,
                corpusKey = loadedCorpusLogKey())
        }
        val ocrReady = ocrCache?.count(inputHashes()) ?: 0
        val captures = BASELINE_MODES.mapNotNull { version -> baselines?.capture(version) }.associateBy { it.mode }
        val captured = captures.values.map { it.count }.minOrNull() ?: 0
        val frozenReady = captures.size == BASELINE_MODES.size
        val captureRemaining = if (frozenReady) 0 else WINE_APP_LABEL_COUNT - captured
        val displayHeaders = currentBoard.headers.map { if (it == "Complete") "Fully correct" else it }
        val bestBuild = buildLog?.bestBuildSummary()
        val bestBuildRow = bestBuild?.toTableRow()
            ?: WineAppLabelsBuildLog.emptyBestBuildRow(displayHeaders.size)
        val bestBuildFieldScores = bestBuild?.fieldScores.orEmpty()
        val comparisonRows = listOf(modeRows.first(), bestBuildRow) + modeRows.drop(1)
        val recentLabRuns = buildLog?.readEntries().orEmpty()
            .filter { it.modelVersion == PhoneCorpusRunner.Mode.LAB.title &&
                it.corpusKey == loadedCorpusLogKey() && it.fieldScores.isNotEmpty() }
            .sortedByDescending { it.timestampMs }
            .distinctBy { it.runId }
            .take(10)
        val activeModules = mutable.value.labModules
        val comparisonText = buildString {
            appendLine("WINE APP LABELS · Lab v7 $labCount/$WINE_APP_LABEL_COUNT checkpointed · Prod baselines $captured/$WINE_APP_LABEL_COUNT photos frozen · OCR cache $ocrReady/$WINE_APP_LABEL_COUNT ready")
            appendLine("Lab v7 post-OCR modules: ${activeModules.summaryLabel()} (${activeModules.signatureKey()})")
            if (bestBuild != null) {
                appendLine(bestBuild.provenanceLine())
            }
            appendLine("Prod v1.0, Prod v2.0, Prod v4.0, Prod v5.0 and Prod v6.0 are retired comparators. Each photo runs through them once; the outcome is frozen on this device and replayed for the $labCount/$WINE_APP_LABEL_COUNT photos Lab v7 has attempted so far, so all six rows compare side by side without re-running Prod. Lab v7 runs fresh OCR by default or reuses verified cached OCR outputs when selected. The diagnostics matrix on screen is the selected (Lab) version; the frozen Prod funnel rows are kept with their records and written to the scored CSV and raw diagnostic JSON exports.")
            appendLine("Prod v2.0, Prod v4.0, Prod v5.0, Prod v6.0 and Lab v7 share one fresh OCR pass and differ in post-processing — Prod v2.0: guarded evidence only; Prod v4.0: the spatial pipeline with LWIN-only producer data; Prod v5.0: the same spatial pipeline with the expanded producer index and the frozen Prod v5.0 checkpoint module set; Prod v6.0: the same pipeline with the frozen Prod v6.0 checkpoint module set (the known-improvement release); Lab v7: the same pipeline and index with the experimental module toggles, starting from the Prod v6.0 checkpoint — while Prod v1.0 runs the Add Wine scanner separately.")
            appendLine("Attempted is the saved-photo tally. Fully correct counts photos where all six scorable fields match; it is an accuracy score, not run progress.")
            appendLine(displayHeaders.joinToString(" | "))
            comparisonRows.forEach { appendLine(it.joinToString(" | ")) }
            appendLine("Build | Producer | Wine name | Country | Region | Subregion | Vintage")
            fun formatFieldRow(title: String, scores: Map<String, WineAppLabelsBuildLog.FieldScore>): String =
                (listOf(title) + PhoneCorpusMath.fields.map { scores[it]?.label() ?: "—" }).joinToString(" | ")
            appendLine(formatFieldRow("Current build", currentFieldScores))
            appendLine(formatFieldRow("Best build", bestBuildFieldScores))
            appendLine(formatFieldRow(PhoneCorpusRunner.Mode.PRODUCTION_V4.title, prodV4FieldScores))
            appendLine(formatFieldRow(PhoneCorpusRunner.Mode.PRODUCTION_V5.title, prodV5FieldScores))
            appendLine(formatFieldRow(PhoneCorpusRunner.Mode.PRODUCTION_V6.title, prodV6FieldScores))
            recentLabRuns.forEach { run ->
                appendLine(formatFieldRow("${run.date} ${run.time} · ${run.buildNumber} · ${run.labModules}", run.fieldScores))
            }
        }
        val board = currentBoard.copy(headers = displayHeaders, rows = comparisonRows, text = comparisonText)
        val timing = readRunTiming(activeLabDir)
        val idleAvg = WineAppLabelsTimers.averageImageMs(timing.timedImagesTotalMs, timing.timedImagesCount)
        val idleRemaining = maxOf(WINE_APP_LABEL_COUNT - labCount, captureRemaining).coerceAtLeast(0)
        mutable.update {
            if (it.labRunning) {
                it.copy(
                    completed = captured, total = WINE_APP_LABEL_COUNT, board = board,
                    labCompleted = labCount, ocrCached = ocrReady,
                    captures = captures, captureRemaining = captureRemaining,
                    currentBuildFieldScores = currentFieldScores,
                    bestBuildFieldScores = bestBuildFieldScores,
                    prodV4FieldScores = prodV4FieldScores, prodV5FieldScores = prodV5FieldScores, prodV6FieldScores = prodV6FieldScores,
                    comparisonFieldRows = comparisonFieldRows,
                    recentLabRuns = recentLabRuns,
                    savedSuites = savedSuiteIds(),
                )
            } else {
                it.copy(
                    completed = captured, total = WINE_APP_LABEL_COUNT, board = board,
                    labCompleted = labCount, ocrCached = ocrReady,
                    captures = captures, captureRemaining = captureRemaining,
                    elapsedRunMs = timing.elapsedRunMs,
                    runSegmentStartedRealtimeMs = null,
                    avgImageMs = idleAvg,
                    etaPerImageMs = idleAvg,
                    currentImageStartedRealtimeMs = null,
                    remainingImages = idleRemaining,
                    currentBuildFieldScores = currentFieldScores,
                    bestBuildFieldScores = bestBuildFieldScores,
                    prodV4FieldScores = prodV4FieldScores, prodV5FieldScores = prodV5FieldScores, prodV6FieldScores = prodV6FieldScores,
                    comparisonFieldRows = comparisonFieldRows,
                    recentLabRuns = recentLabRuns,
                    savedSuites = savedSuiteIds(),
                )
            }
        }
    }

    /** One scored row set per version in one file; frozen Prod rows carry their capture provenance. */
    private fun writeScoredCsv(target: File) {
        val scorer = scoreboard()
        val rows = mutableListOf<Map<String, String>>()
        val labModulesKey = mutable.value.labModules.signatureKey()
        for ((mode, records) in predictionsByMode()) {
            val provenance = if (mode == PhoneCorpusRunner.Mode.LAB) {
                mapOf("build" to BuildConfig.BUILD_COMMIT, "mode" to mode.name, "run_id" to (labFolder?.name ?: ""),
                    "lab_modules" to labModulesKey)
            } else {
                mapOf("build" to BuildConfig.BUILD_COMMIT, "mode" to mode.name, "run_id" to "frozen-baseline",
                    "result_origin" to "frozen_baseline", "captured_build" to baselines?.capture(mode)?.build.orEmpty(),
                    "lab_modules" to "")
            }
            scorer.build(records, mode).csv.forEach { rows += provenance + it }
        }
        target.bufferedWriter(Charsets.UTF_8).use { writer -> TextLabCsv.write(writer, rows.asSequence()) }
    }

    /**
     * Raw diagnostics for the Lab pass plus the frozen Prod aggregates. Frozen records deliberately
     * carry no OCR tokens or traces, so this file cannot claim raw evidence it never kept.
     */
    private fun rawExportRows(): Sequence<Map<String, String>> {
        val scorer = scoreboard()
        val included = includedIds()
        val dir = labFolder
        // One photo at a time; the normalized JSON writer interns shared metadata and payloads.
        return sequence {
            if (dir != null) {
                val files = File(dir, "attempts").listFiles().orEmpty()
                    .filter { it.extension == "json" }.sortedBy { it.name }
                for (file in files) {
                    val p = JSONObject(file.readText())
                    if (p.optString("image") !in included) continue
                    val meta = mapOf("build" to p.optString("build"), "mode" to PhoneCorpusRunner.Mode.LAB.name,
                        "pipeline" to PhoneCorpusRunner.Mode.LAB.pipelineId, "run_id" to dir.name,
                        "lab_modules" to p.optString("lab_modules").ifBlank { mutable.value.labModules.signatureKey() },
                        "image" to p.optString("image"), "attempt" to p.optString("attempt"),
                        "ocr_source" to p.optString("ocr_source").ifBlank { "fresh_ocr" })
                    yield(meta + mapOf("row_type" to "image_summary", "status" to p.optString("status"),
                        "elapsed_ms" to p.optString("total_elapsed_ms"), "error" to p.optString("error"),
                        "engine_incomplete" to p.optBoolean("engine_incomplete").toString(),
                        "diagnostics" to p.optString("diagnostics"),
                        "pipeline_funnel_error" to p.optString("pipeline_funnel_error")))
                    for (key in listOf("tokens", "engine_timings", "trace", "pipelines", "pipeline_funnel")) {
                        val rows = p.optJSONArray(key) ?: continue
                        for (index in 0 until rows.length()) {
                            val row = rows.getJSONObject(index)
                            yield(meta + mapOf("row_type" to key, "record_index" to index.toString(),
                                "diagnostic_json" to row.toString()))
                        }
                    }
                }
            }
            for ((mode, frozen) in predictionsByMode()) {
                val provenance = if (mode == PhoneCorpusRunner.Mode.LAB) {
                    mapOf("build" to BuildConfig.BUILD_COMMIT, "mode" to mode.name, "run_id" to (dir?.name ?: ""))
                } else {
                    mapOf("build" to BuildConfig.BUILD_COMMIT, "mode" to mode.name, "run_id" to "frozen-baseline",
                        "result_origin" to "frozen_baseline", "captured_build" to baselines?.capture(mode)?.build.orEmpty())
                }
                // Same current/latest-attempt aggregate as the screen and scored CSV; old attempts
                // above remain raw evidence only.
                for (row in scorer.build(frozen, mode).csv.filter { it["row_type"] == "pipeline_step" })
                    yield(provenance + row)
            }
        }
    }

    /** Prepares the chosen CSV in the cache, split under the 25 MB upload cap, so each part can be saved. */
    private fun diagnosticHash(text:String):String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun savedSuiteIds(): List<String> = File(root,"suites").listFiles().orEmpty()
        .filter { it.isDirectory && File(it,"manifest.json").isFile }
        .sortedByDescending { it.name }.map { it.name }

    private fun writeSuiteManifest(dir:File,manifest:JSONObject) {
        val tmp=File(dir,"manifest.tmp")
        tmp.writeText(manifest.toString(2))
        java.nio.file.Files.move(tmp.toPath(),File(dir,"manifest.json").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Shared OCR is captured once and retained outside the export cache for later re-export. */
    private fun snapshotSuiteOcr(dir:File) {
        val dest=File(dir,"ocr").apply { mkdirs() }
        for((image,sha) in inputHashes()) {
            val entry=checkNotNull(ocrCache?.entry(image,sha)) { "Missing OCR snapshot: $image" }
            val rows=JSONArray(entry.rows.map { JSONObject(it) })
            val payload=rows.toString()
            val ref=diagnosticHash(payload)
            File(dest,"$image.json").writeText(JSONObject().put("image",image).put("image_sha256",sha)
                .put("ocr_ref",ref).put("captured_build",entry.capturedBuild).put("rows",rows).toString())
        }
    }

    fun prepareSavedSuiteExport(suiteId:String) {
        if(mutable.value.busy || mutable.value.suiteRunning) return
        val suite=File(root,"suites").listFiles().orEmpty().firstOrNull { it.name==suiteId && it.isDirectory } ?: return
        mutable.update { it.copy(busy=true,exportParts=emptyList()) }
        exporting=true
        bgScope.launch {
            try {
                val manifest=JSONObject(File(suite,"manifest.json").readText())
                val ids=manifest.getJSONArray("run_ids")
                val archived=manifest.optJSONArray("run_summaries") ?: JSONArray()
                val archivedEntries=(0 until archived.length()).mapNotNull { i ->
                    val row=archived.getJSONObject(i)
                    WineAppLabelsBuildLog.fromCsvMap(row.keys().asSequence().associateWith { row.optString(it) })
                }
                val entries=(buildLog?.readEntries().orEmpty()+archivedEntries).associateBy { it.runId }
                val runs=(0 until ids.length()).map { checkNotNull(entries[ids.getString(it)]) { "Saved run summary unavailable" } }
                check(runs.isNotEmpty()) { "Suite has no saved runs" }
                val dir=File(app.cacheDir,"wine-app-labels-exports/${UUID.randomUUID()}").apply { mkdirs() }
                val target=File(dir,"wine-app-labels-suite-$suiteId.json")
                val parts=LabSuiteArchive.write(target,recentBuildRows(runs,suite))
                mutable.update { it.copy(exportParts=parts,message="Compact JSON package ready. Save the manifest and every numbered JSON part; scores CSV is optional.") }
            } catch(cancel:CancellationException) { throw cancel }
            catch(e:Exception) { mutable.update { it.copy(message="Suite export failed: ${e.message}") } }
            finally { exporting=false;mutable.update { it.copy(busy=false) } }
        }
    }

    /** Compact by default; saved suites additionally link retained decisions to shared OCR. */
    private fun writeRecentBuildsCsv(target: File, runs: List<WineAppLabelsBuildLog.Entry>, suite:File? = null) {
        target.bufferedWriter(Charsets.UTF_8).use { TextLabCsv.write(it,recentBuildRows(runs,suite)) }
    }

    private fun recentBuildRows(runs: List<WineAppLabelsBuildLog.Entry>, suite:File? = null): Sequence<Map<String,String>> {
        check(runs.isNotEmpty()) { "No recent Lab runs to export" }
        val reference=if(suite!=null) JSONObject(File(suite,"scoring.json").readText()) else assetJson("scoring.json")
        val images = reference.getJSONArray("images")
        val scorer = if(suite!=null) PhoneCorpusScoreboard(app,"wine-app-labels-v1",reference,
            JSONObject(File(suite,"evaluation-set.json").readText())) else scoreboard()
        val rows = sequence {
            val ocrRefs=mutableMapOf<String,String>()
            val ocrObservationRows=mutableMapOf<String,Map<String,List<Int>>>()
            if(suite!=null) {
                yield(mapOf("row_type" to "suite_manifest","suite_id" to suite.name,
                    "diagnostic_json" to File(suite,"manifest.json").readText()))
                for(file in File(suite,"ocr").listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.name }) {
                    val snapshot=JSONObject(file.readText())
                    val image=snapshot.getString("image")
                    val ref=snapshot.getString("ocr_ref")
                    ocrRefs[image]=ref
                    val shared=snapshot.getJSONArray("rows")
                    ocrObservationRows[image]=(0 until shared.length()).filter { shared.getJSONObject(it).optString("raw_text").isNotBlank() }
                        .groupBy { diagnosticHash(com.yourapp.wine.merge.TextNorm.scrub(shared.getJSONObject(it).optString("raw_text"))) }
                    for(i in 0 until shared.length()) yield(mapOf("row_type" to "shared_ocr", "image" to image,
                        "ocr_ref" to ref, "ocr_row_id" to i.toString(), "image_sha256" to snapshot.getString("image_sha256"),
                        "diagnostic_json" to shared.getJSONObject(i).toString()))
                }
            }
            for ((index, run) in runs.withIndex()) {
                val metadata = mapOf(
                    "recent_rank" to (index + 1).toString(), "run_id" to run.runId,
                    "date" to run.date, "time" to run.time, "build_number" to run.buildNumber,
                    "build_commit" to run.buildCommit, "lab_modules" to run.labModules,
                    "corpus_key" to run.corpusKey,
                )
                yield(metadata + mapOf("row_type" to "run_summary",
                    "attempted" to run.attempted.toString(), "eligible" to run.eligible.toString(),
                    "complete" to run.complete.toString(), "failed" to run.failed.toString(),
                    "total_correct" to run.totalCorrect.toString()))
                for (field in PhoneCorpusMath.fields) {
                    val score = run.fieldScores[field]
                    yield(metadata + mapOf("row_type" to "parameter_summary", "field" to field,
                        "correct" to (score?.correct?.toString() ?: ""),
                        "incorrect" to (score?.incorrect?.toString() ?: "")))
                }
                // Locate by an existing directory name, not an unchecked path from the log.
                val dir = root.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name == run.runId }
                val saved = if(suite==null) dir?.let(::predictions).orEmpty() else
                    dir?.let { File(it,"current").listFiles().orEmpty().filter { f -> f.extension=="json" }
                        .sortedBy { f -> f.name }.map { f -> JSONObject(f.readText()) } }.orEmpty()
                val fieldRows = if (saved.isEmpty()) emptyList() else
                    scorer.build(saved, PhoneCorpusRunner.Mode.LAB).csv.filter { it["row_type"] == "field" }
                val byField = fieldRows.associateBy { it["image"] to it["field"] }
                for (i in 0 until images.length()) {
                    val image = images.getJSONObject(i)
                    val imageId = image.getString("image")
                    if(suite!=null) {
                        // Existing attempts retain full per-configuration traces; current/ deliberately omits them.
                        val attempts=dir?.let { File(it,"attempts").listFiles().orEmpty().filter { f ->
                            f.name.startsWith("$imageId-") && f.extension=="json"
                        }.maxByOrNull { f -> f.name.substringAfter("$imageId-").substringBeforeLast('.').toLongOrNull() ?: 0L } }
                        val attempt=attempts?.let { JSONObject(it.readText()) }
                        val trace=attempt?.optJSONArray("trace")
                        var retained=0
                        if(trace!=null) for(t in 0 until trace.length()) {
                            val row=trace.getJSONObject(t)
                            if(row.optString("candidate")!="spatial_lab") continue
                            // Reuse existing phrase/hypothesis/solver/gazetteer/final-field explanations.
                            // Original observation strings are linked to shared OCR, not repeated per config.
                            if(row.optString("stage").endsWith("evidence_observation")) {
                                row.put("shared_ocr_row_ids",JSONArray(ocrObservationRows[imageId]
                                    ?.get(row.optString("shared_ocr_text_hash").ifBlank {
                                        diagnosticHash(com.yourapp.wine.merge.TextNorm.scrub(row.optString("raw_text")))
                                    }).orEmpty()))
                                row.remove("raw_text")
                            }
                            yield(metadata + mapOf("row_type" to "run_decision", "image" to imageId,
                                "ocr_ref" to ocrRefs[imageId].orEmpty(), "stage" to row.optString("stage"),
                                "diagnostic_json" to row.toString()))
                            retained++
                            if(row.optString("stage").endsWith("evidence_summary")) for(field in PhoneCorpusMath.fields) {
                                yield(metadata+mapOf("row_type" to "field_decision", "image" to imageId,
                                    "ocr_ref" to ocrRefs[imageId].orEmpty(), "field" to field,
                                    "recognized" to row.optString(field), "selection_reason" to row.optString("${field}_reason"),
                                    "trace_rows_dropped" to row.optString("trace_rows_dropped"),
                                    "graph_truncated" to row.optString("graph_truncated")))
                            }
                        }
                        yield(metadata+mapOf("row_type" to "diagnostic_status", "image" to imageId,
                            "ocr_ref" to ocrRefs[imageId].orEmpty(), "status" to if(retained>0) "available" else "unavailable",
                            "diagnostic_rows" to retained.toString()))
                    }
                    for (field in PhoneCorpusMath.fields) {
                        val scored = byField[imageId to field]
                        val reference = image.getJSONObject("fields").getJSONObject(field)
                        yield(metadata + mapOf(
                            "row_type" to "wine_field", "image" to imageId, "ocr_ref" to ocrRefs[imageId].orEmpty(),
                            "review_number" to image.getInt("review_number").toString(),
                            "source_filename" to image.optString("source_filename"), "field" to field,
                            "recognized" to scored?.get("recognized").orEmpty(),
                            "expected" to reference.optString("target"),
                            "reference_status" to reference.optString("status"),
                            "outcome" to (scored?.get("outcome") ?: if (dir == null || saved.isEmpty()) "results_unavailable" else "not_attempted"),
                            "execution_status" to scored?.get("execution_status").orEmpty(),
                        ))
                    }
                }
            }
        }
        return rows
    }

    fun prepareRecentBuildsExport() = prepareExport(raw = false, recentBuilds = true)

    fun prepareExport(raw: Boolean, recentBuilds: Boolean = false) {
        if (mutable.value.busy) return
        if (labFolder == null) {
            mutable.update { it.copy(message = "Load the 124-label test before preparing an export.") }
            return
        }
        val previousParts = mutable.value.exportParts
        val recentRuns = mutable.value.recentLabRuns.toList()
        exporting = true
        mutable.update { it.copy(busy = true, exportParts = emptyList(), message =
            if (raw && !recentBuilds) "Preparing compact diagnostic JSON…" else "Preparing upload-sized CSV files…") }
        bgScope.launch {
            val exportDir = File(app.cacheDir, "wine-app-labels-exports/${UUID.randomUUID()}")
            try {
                check(exportDir.mkdirs())
                val jsonDiagnostics = raw && !recentBuilds
                val extension = if (jsonDiagnostics) "json" else "csv"
                val target = File(exportDir,
                    "wine-app-labels-${WINE_APP_LABEL_COUNT}-${if (recentBuilds) "recent-builds" else if (raw) "raw-all-versions" else "all-versions"}-${BuildConfig.BUILD_COMMIT}.$extension")
                val parts = if (jsonDiagnostics) {
                    LabSuiteArchive.write(target, rawExportRows())
                } else {
                    if (recentBuilds) writeRecentBuildsCsv(target, recentRuns) else writeScoredCsv(target)
                    CsvUploadParts.split(target, exportDir).also { if (it.size > 1) target.delete() }
                }
                previousParts.mapNotNull { it.parentFile }.distinct()
                    .filter { it.parentFile == exportDir.parentFile }.forEach { it.deleteRecursively() }
                mutable.update {
                    it.copy(exportParts = parts, message = if (jsonDiagnostics)
                        "Raw diagnostics JSON ready. Save the manifest and every numbered JSON part (at most 20 MB each). Scores CSV is optional."
                    else "${parts.size} CSV file(s) ready, each at most 25 MB. Tap each Save button below. Complete rows and headers are preserved.")
                }
            } catch (cancel: CancellationException) {
                exportDir.deleteRecursively();throw cancel
            } catch (e: Exception) {
                exportDir.deleteRecursively()
                mutable.update { it.copy(message = "Export preparation failed: ${e.message}. Original run data is unchanged.") }
            } finally {
                exporting = false
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    /** Saves one prepared part through the system document picker; the prepared file stays in the cache. */
    fun saveExportPart(uri: Uri, index: Int) {
        if (mutable.value.busy) return
        val file = mutable.value.exportParts.getOrNull(index) ?: run {
            mutable.update { it.copy(message = "Export session expired. Prepare the CSV again, then save its parts.") };return
        }
        exporting = true
        mutable.update { it.copy(busy = true, message = "Saving ${file.name}…") }
        bgScope.launch {
            try {
                app.contentResolver.openOutputStream(uri, "w").use { out -> requireNotNull(out);file.inputStream().use { it.copyTo(out) } }
                mutable.update { it.copy(message = "Saved ${file.name}. Save each listed part before uploading.") }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(message = "Export failed: ${e.message}") }
            } finally {
                exporting = false
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    /** Allows an already-saved export to be split for upload without rerunning OCR. */
    fun splitExistingCsv(uri: Uri) {
        if (mutable.value.busy) return
        exporting = true
        mutable.update { it.copy(busy = true, exportParts = emptyList(), message = "Splitting selected CSV for upload…") }
        bgScope.launch {
            val exportDir = File(app.cacheDir, "wine-app-labels-exports/${UUID.randomUUID()}")
            try {
                check(exportDir.mkdirs())
                val source = File(exportDir, "wine-app-labels-existing.csv")
                requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> source.outputStream().use { input.copyTo(it) } }
                val parts = CsvUploadParts.split(source, exportDir)
                if (parts.size > 1) source.delete()
                mutable.update {
                    it.copy(exportParts = parts, message = "${parts.size} upload-sized CSV file(s) ready. Save every part below. Original file unchanged; no OCR rerun.")
                }
            } catch (cancel: CancellationException) {
                exportDir.deleteRecursively();throw cancel
            } catch (e: Exception) {
                exportDir.deleteRecursively()
                mutable.update { it.copy(message = "Cannot split CSV: ${e.message}. Original file unchanged.") }
            } finally {
                exporting = false
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Loads the Lab run and the frozen Prod baselines. [fresh] starts a new Lab run (old runs are
     * kept on disk); it never discards frozen Prod records, because those are the baseline.
     */
    fun load(mode: PhoneCorpusRunner.Mode, fresh: Boolean = false) {
        if (mutable.value.busy) return
        if (session.loaded && !fresh && labFolder != null && baselines != null && ocrCache != null && buildLog != null) {
            mutable.update { it.copy(mode = mode) }
            bgScope.launch { runCatching { refresh() } }
            return
        }
        val preferredCachedOcr = if (session.loaded) mutable.value.useCachedOcr else readUseCachedOcrPref()
        val preferredModules = if (session.loaded) mutable.value.labModules else readLabModulesPref()
        labFolder = null
        mutable.update {
            it.copy(
                busy = true, mode = mode, useCachedOcr = preferredCachedOcr, labModules = preferredModules, completed = 0,
                total = WINE_APP_LABEL_COUNT, board = null, exportParts = emptyList(), labCompleted = 0,
                elapsedRunMs = 0L, runSegmentStartedRealtimeMs = null, avgImageMs = null,
                etaPerImageMs = null, currentImageStartedRealtimeMs = null,
                remainingImages = WINE_APP_LABEL_COUNT,
                currentBuildFieldScores = emptyMap(), bestBuildFieldScores = emptyMap(), recentLabRuns = emptyList(),
                prodV4FieldScores = emptyMap(), prodV5FieldScores = emptyMap(), prodV6FieldScores = emptyMap(),
                comparisonFieldRows = emptyMap(),
                message = "Loading saved 124-label results…",
            )
        }
        bgScope.launch {
            try {
                val signature = corpusSignature(assetJson("manifest.json"))
                val store = WineAppLabelsBaselines(app, signature)
                // One-time upgrade from the retired all-four-versions screen: the Prod checkpoints
                // those builds produced ARE the frozen capture, so promote them instead of asking
                // for a rerun. Only completed attempts are promoted and existing records win.
                // Lookups only: this must not create run folders for versions that no longer run.
                val hashes = inputHashes()
                val legacy = PhoneCorpusRunner.Mode.entries.mapNotNull { mode -> latestFolder(mode)?.let { mode to it } }.toMap()
                val migratedBaselines = store.migrateRetainedBaselines(BASELINE_MODES, hashes)
                val promoted = store.promote(BASELINE_MODES, legacy) + migratedBaselines
                val ocrStore = WineAppLabelsOcrCache(app, signature)
                val promotedOcr = ocrStore.promote(matchingRunFolders(signature) + allRunFolders(), hashes)
                val logStore = WineAppLabelsBuildLog(File(root, WineAppLabelsBuildLog.FILE_NAME))
                backfillBuildLog(logStore, signature)
                baselines = store
                ocrCache = ocrStore
                buildLog = logStore
                labFolder = selectFolder(PhoneCorpusRunner.Mode.LAB, fresh)
                session.loaded = true
                refresh()
                // A plain "ready" line said nothing the counts above do not already show.
                val message = when {
                    promoted > 0 && promotedOcr > 0 ->
                        "Froze $promoted Prod baseline outcome(s) and cached OCR for $promotedOcr photo(s) from earlier checkpoints."
                    promoted > 0 ->
                        "Froze $promoted Prod baseline outcome(s) from this device's earlier checkpoints."
                    promotedOcr > 0 ->
                        "Cached OCR outputs for $promotedOcr photo(s) from this device's earlier checkpoints."
                    else -> ""
                }
                mutable.update { it.copy(message = message) }
            } catch (e: Exception) {
                labFolder = null
                baselines = null
                ocrCache = null
                buildLog = null
                mutable.update { it.copy(message = "Cannot load the 124-label test: ${e.message}") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    /** Fresh, sequential ablations over one verified OCR cache; no automatic promotion of winners. */
    fun startAutomatedSuite(includePairs: Boolean = false, foundations: Boolean = false, focused: Boolean = false) {
        if (mutable.value.busy || mutable.value.suiteRunning) return
        if (ocrCache?.count(inputHashes()) != WINE_APP_LABEL_COUNT ||
            BASELINE_MODES.any { baselines?.capture(it)?.count != WINE_APP_LABEL_COUNT }) {
            mutable.update { it.copy(message = "Automation needs all 124 cached OCR records and frozen Prod baselines. Complete a normal pass first.") }
            return
        }
        val originalModules = mutable.value.labModules
        val originalCached = mutable.value.useCachedOcr
        val control = LabPostOcrModules.bestBuild.copy(blankSubregionFuzzy = true)
        val ids = LabPostOcrModules.selectableModuleIds
        val plan = mutableListOf(control)
        if (focused) {
            val isolated = control.copy(candidateIsolation = true)
            plan += isolated
            plan += isolated.copy(vintageSpans = true)
            plan += control.copy(roleAwareJoins = true)
            plan += control.copy(broaderDiscovery = true)
            plan += control.copy(
                candidateIsolation = true,
                exactProducerProtection = true,
                administrativeFiltering = true,
                roleAwareJoins = true,
                broaderDiscovery = true,
            )
        } else if (foundations) {
            var staged = control
            for (id in listOf(LabPostOcrModules.ModuleId.CANDIDATE_ISOLATION,
                LabPostOcrModules.ModuleId.EXACT_PRODUCER_PROTECTION,
                LabPostOcrModules.ModuleId.ADMINISTRATIVE_FILTERING,
                LabPostOcrModules.ModuleId.ROLE_AWARE_JOINS,
                LabPostOcrModules.ModuleId.BROADER_DISCOVERY)) {
                staged = staged.withModule(id, true)
                plan += staged
            }
        } else {
        ids.forEach { plan += control.withModule(it, !control.isEnabled(it)) }
        if (includePairs) for (i in ids.indices) for (j in i + 1 until ids.size) {
            plan += control.withModule(ids[i], !control.isEnabled(ids[i]))
                .withModule(ids[j], !control.isEnabled(ids[j]))
        }
        }
        stopping = false
        mutable.update { it.copy(suiteRunning = true, suiteProgress = "Preparing ${plan.size} configurations") }
        bgScope.launch {
            val completedRuns = mutableListOf<WineAppLabelsBuildLog.Entry>()
            val suite=File(root,"suites/${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { mkdirs() }
            val manifest=JSONObject().put("schema",1).put("suite_id",suite.name).put("build",BuildConfig.BUILD_COMMIT)
                .put("corpus_key",loadedCorpusLogKey()).put("status","running").put("run_ids",JSONArray())
                .put("suite_kind",when {
                    focused -> "focused_diagnostics"
                    foundations -> "ordered_foundations"
                    includePairs -> "single_and_pair_ablations"
                    else -> "single_ablations"
                })
                .put("planned_modules",JSONArray(plan.map { it.signatureKey() }))
            try {
                writeSuiteManifest(suite,manifest)
                File(suite,"scoring.json").writeText(assetJson("scoring.json").toString())
                File(suite,"evaluation-set.json").writeText(assetJson("evaluation-set.json").toString())
                snapshotSuiteOcr(suite)
                for ((index, modules) in plan.withIndex()) {
                    if (stopping) break
                    // Recheck before each run. Never silently fall back to fresh OCR in this suite.
                    check(ocrCache?.count(inputHashes()) == WINE_APP_LABEL_COUNT) { "OCR cache became incomplete" }
                    for((image,sha) in inputHashes()) {
                        val snapshot=JSONObject(File(suite,"ocr/$image.json").readText())
                        val cached=checkNotNull(ocrCache?.entry(image,sha))
                        check(JSONArray(cached.rows.map { JSONObject(it) }).toString()==snapshot.getJSONArray("rows").toString()) {
                            "OCR changed during suite; stopped rather than mislinking decisions"
                        }
                    }
                    mutable.update { it.copy(labModules = modules, useCachedOcr = true,
                        suiteProgress = "Config ${index + 1}/${plan.size}: ${modules.signatureKey()}") }
                    labFolder = selectFolder(PhoneCorpusRunner.Mode.LAB, fresh = true)
                    val runId = checkNotNull(labFolder).name
                    manifest.getJSONArray("run_ids").put(runId)
                    writeSuiteManifest(suite,manifest)
                    start(automated = true)?.join() ?: error("Could not start automated configuration")
                    if (stopping) break
                    check(predictions(checkNotNull(labFolder)).size == WINE_APP_LABEL_COUNT) {
                        "Configuration did not finish all photos: ${mutable.value.message}"
                    }
                    completedRuns += checkNotNull(buildLog?.readEntries()?.firstOrNull { it.runId == runId })
                    manifest.put("run_summaries",JSONArray(completedRuns.map { JSONObject(it.toCsvMap()) }))
                    writeSuiteManifest(suite,manifest)
                }
                if (completedRuns.isNotEmpty()) {
                    val dir = File(app.cacheDir, "wine-app-labels-exports/${UUID.randomUUID()}")
                    check(dir.mkdirs())
                    val target = File(dir, "wine-app-labels-automated-suite.json")
                    // Unlike the recent-ten table, the suite export retains every completed configuration.
                    manifest.put("status",if(stopping) "stopped" else "complete")
                    writeSuiteManifest(suite,manifest)
                    val parts = LabSuiteArchive.write(target,recentBuildRows(completedRuns,suite))
                    mutable.update { it.copy(exportParts = parts) }
                }
                mutable.update { it.copy(suiteProgress = "${if (stopping) "Stopped" else "Finished"}: ${completedRuns.size}/${plan.size} configurations. Save all numbered JSON parts and the manifest below.") }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(suiteProgress = "Suite stopped: ${e.message}. Completed runs remain saved.") }
            } finally {
                if(manifest.optString("status")=="running") manifest.put("status",if(stopping) "stopped" else "interrupted")
                val archivedIds=manifest.getJSONArray("run_ids")
                val archivedIdSet=(0 until archivedIds.length()).map { archivedIds.getString(it) }.toSet()
                manifest.put("run_summaries",JSONArray(buildLog?.readEntries().orEmpty()
                    .filter { it.runId in archivedIdSet }.map { JSONObject(it.toCsvMap()) }))
                runCatching { writeSuiteManifest(suite,manifest) }
                mutable.update { it.copy(suiteRunning = false, labModules = originalModules, useCachedOcr = originalCached, savedSuites=savedSuiteIds()) }
                writeUiPrefs(originalCached, originalModules)
                labFolder = selectFolder(PhoneCorpusRunner.Mode.LAB, fresh = false)
                runCatching { refresh() }
            }
        }
    }

    fun stop() {
        stopping = true
        mutable.update { if (it.busy) it.copy(message = "Stopping after the current photo; please wait for OCR to finish.") else it }
    }

    fun onBackgroundPause(resumeWhenForeground: Boolean) {
        activityForeground = false
        // Navigating away (Back inside the app or switching apps while kept open in the background)
        // keeps the benchmark running via WineAppLabelsSession + WineAppLabelsRunService.
        if (mutable.value.busy && !exporting) {
            Log.d("WineTracker", "WineAppLabelsActivity left foreground (resumeWhenForeground=$resumeWhenForeground); continuing benchmark in background")
        }
    }

    fun onForeground() {
        activityForeground = true
    }

    /**
     * Loads captured raw OCR lines per engine for [image] on demand when an incomplete-label card's
     * Raw OCR section is expanded. Reads from the latest saved attempt trace or verified OCR cache
     * on Dispatchers.IO so normal score refreshes never carry 124 raw OCR payloads in memory.
     */
    suspend fun loadRawOcrText(
        image: String,
        mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB,
    ): String = withContext(Dispatchers.IO) {
        fun formatEngineBlocks(mlkit: List<String>, paddle: List<String>): String = buildString {
            append("ML Kit (${mlkit.size}):\n")
            if (mlkit.isEmpty()) append("  (none)\n") else mlkit.forEach { append("  ").append(it).append('\n') }
            append("\nPaddle Medium (${paddle.size}):\n")
            if (paddle.isEmpty()) append("  (none)") else paddle.forEachIndexed { idx, line ->
                append("  ").append(line)
                if (idx < paddle.lastIndex) append('\n')
            }
        }

        val folder = if (mode == PhoneCorpusRunner.Mode.LAB) labFolder else latestFolder(mode) ?: labFolder
        val latestAttempt = folder?.let { File(it, "attempts") }
            ?.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("$image-") && it.name.endsWith(".json") }
            .maxByOrNull { it.lastModified() }
        if (latestAttempt != null) {
            val parsed = runCatching { JSONObject(latestAttempt.readText()) }.getOrNull()
            val trace = parsed?.optJSONArray("trace")
            if (trace != null && trace.length() > 0) {
                val mlkit = mutableListOf<String>()
                val paddle = mutableListOf<String>()
                for (i in 0 until trace.length()) {
                    val row = trace.optJSONObject(i) ?: continue
                    if (row.optString("stage") != "recognition") continue
                    val raw = row.optString("raw_text").trim()
                    if (raw.isEmpty()) continue
                    when (row.optString("candidate")) {
                        "mlkit_reference" -> mlkit.add(raw)
                        "candidate_3" -> paddle.add(raw)
                    }
                }
                return@withContext formatEngineBlocks(mlkit, paddle)
            }
            val tokens = parsed?.optJSONArray("tokens")
            if (tokens != null && tokens.length() > 0) {
                val mlkit = mutableListOf<String>()
                val paddle = mutableListOf<String>()
                for (i in 0 until tokens.length()) {
                    val tok = tokens.optJSONObject(i) ?: continue
                    val raw = tok.optString("raw").trim()
                    if (raw.isEmpty()) continue
                    when (tok.optString("engine")) {
                        "mlkit" -> mlkit.add(raw)
                        "paddle-v6-medium" -> paddle.add(raw)
                    }
                }
                return@withContext formatEngineBlocks(mlkit, paddle)
            }
        }

        val sha = runCatching { inputHashes()[image] }.getOrNull()
        val cached = if (sha != null) runCatching { ocrCache?.entry(image, sha) }.getOrNull() else null
        if (cached != null) {
            val mlkit = cached.rows
                .filter { it["candidate"] == "mlkit_reference" && it["stage"] == "recognition" }
                .mapNotNull { it["raw_text"]?.trim()?.takeIf(String::isNotEmpty) }
            val paddle = cached.rows
                .filter { it["candidate"] == "candidate_3" && it["stage"] == "recognition" }
                .mapNotNull { it["raw_text"]?.trim()?.takeIf(String::isNotEmpty) }
            return@withContext formatEngineBlocks(mlkit, paddle)
        }

        "No raw OCR recorded for this label."
    }

    private fun isFailed(result: JSONObject): Boolean =
        result.optString("status") != "complete" || result.optBoolean("engine_incomplete") ||
            result.optJSONArray("pipelines")?.let { rows ->
                (0 until rows.length()).any { rows.getJSONObject(it).optString("status") != "complete" }
            } == true

    /**
     * Runs the pass. Lab v5 is re-run for every photo; each Prod version runs only for photos that
     * have no frozen record yet (first pass) or whose stored record is a failure ([retryFailures]).
     * Once a photo is frozen, later passes score the stored outcome and never re-run Prod.
     */
    fun start(retryFailures: Boolean = false, automated: Boolean = false): kotlinx.coroutines.Job? {
        if (mutable.value.busy || (mutable.value.suiteRunning && !automated)) return null
        val lab = labFolder ?: return null
        val store = baselines ?: return null
        val ocrStore = ocrCache
        val useCachedOcr = mutable.value.useCachedOcr
        val activeModules = mutable.value.labModules
        val initialTiming = readRunTiming(lab)
        val initialAvg = WineAppLabelsTimers.averageImageMs(
            initialTiming.timedImagesTotalMs,
            initialTiming.timedImagesCount,
        )
        val passStartRealtime = SystemClock.elapsedRealtime()
        mutable.update {
            it.copy(
                busy = true,
                labRunning = true,
                elapsedRunMs = initialTiming.elapsedRunMs,
                runSegmentStartedRealtimeMs = passStartRealtime,
                avgImageMs = initialAvg,
                etaPerImageMs = initialAvg,
                currentImageStartedRealtimeMs = null,
                message = "Checking the 124 retained workbook photos and OCR engines…",
            )
        }
        stopping = false
        WineAppLabelsRunService.start(app, "Preparing 124-label benchmark…")
        return bgScope.launch {
            var locked = false
            var warmSession: PhoneCorpusRunner.WarmSession? = null
            var runImagesCount = initialTiming.timedImagesCount
            var runImagesTotalMs = initialTiming.timedImagesTotalMs
            var passImagesCount = 0
            var passImagesTotalMs = 0L
            try {
                lock.lock()
                locked = true
                val spec = PaddleCandidates.all.single { it.id == "candidate_3" }
                val availableAssets = app.assets.list("text-lab-paddle-v1").orEmpty().toSet()
                check(listOf(spec.detector.asset, spec.recognizer.asset, spec.dictionary.asset).all { it in availableAssets }) {
                    "Medium model assets are missing; the test will not silently run ML Kit only."
                }
                val packaged = assetJson("inputs.json").getJSONArray("images")
                check(packaged.length() == WINE_APP_LABEL_COUNT) { "The packaged workbook photo count is not 124" }
                val included = includedIds()
                val manifest = (0 until packaged.length()).map { packaged.getJSONObject(it) }
                    .filter { it.getString("image") in included }
                    .sortedBy { it.getInt("review_number") }
                check(manifest.size == WINE_APP_LABEL_COUNT && manifest.map { it.getString("image") }.toSet() == included) {
                    "The packaged inputs do not match the selected workbook set"
                }
                val existingLabByImage = predictions(lab).associateBy { it.optString("image") }
                var remainingInPass = manifest.count { item ->
                    val img = item.getString("image")
                    val prev = existingLabByImage[img]
                    val needsLab = if (retryFailures) prev == null || isFailed(prev) else prev == null
                    val needsProd = BASELINE_MODES.any { version ->
                        val existing = store.record(version, img)
                        if (retryFailures) existing != null && isFailed(existing) else existing == null
                    }
                    needsLab || needsProd
                }
                mutable.update { it.copy(remainingImages = remainingInPass) }
                val requiresFreshEngines = manifest.any { item ->
                    val img = item.getString("image")
                    val prev = existingLabByImage[img]
                    val needsLab = if (retryFailures) prev == null || isFailed(prev) else prev == null
                    val needsProd = BASELINE_MODES.any { version ->
                        val existing = store.record(version, img)
                        if (retryFailures) existing != null && isFailed(existing) else existing == null
                    }
                    if (!needsLab && !needsProd) false
                    else needsProd || !useCachedOcr || ocrStore?.entry(img, item.getString("sha256")) == null
                }
                if (requiresFreshEngines) {
                    WineScannerServices.get(app).withBenchmarkEngines { engines, _ ->
                        check(setOf("mlkit", "paddle-v6-medium").all { id -> engines.any { it.id == id && it.available } }) {
                            "Both ML Kit and Medium must be available."
                        }
                    }
                }
                warmSession = PhoneCorpusRunner.WarmSession(app)
                File(lab, "current").mkdirs(); File(lab, "attempts").mkdirs()
                for ((index, input) in manifest.withIndex()) {
                    if (stopping) break
                    val image = input.getString("image")
                    val labPrevious = predictions(lab).firstOrNull { it.getString("image") == image }
                    val runLab = if (retryFailures) labPrevious == null || isFailed(labPrevious) else labPrevious == null
                    // Only photos without a frozen record (or with a frozen failure being retried)
                    // run through a Prod version; everything else is read back from the store.
                    val neededBaselines = BASELINE_MODES.filter { version ->
                        val frozen = store.record(version, image)
                        if (retryFailures) frozen != null && isFailed(frozen) else frozen == null
                    }
                    if (!runLab && neededBaselines.isEmpty()) continue
                    val needed = neededBaselines.toSet() + if (runLab) setOf(PhoneCorpusRunner.Mode.LAB) else emptySet()
                    val imageSha = input.getString("sha256")
                    val cachedEntry = if (useCachedOcr && needed == setOf(PhoneCorpusRunner.Mode.LAB)) {
                        ocrStore?.entry(image, imageSha)
                    } else null
                    val ocrSourceLabel = if (cachedEntry != null) "cached OCR" else "fresh OCR"
                    val running = buildList {
                        if (runLab) add(PhoneCorpusRunner.Mode.LAB.title)
                        if (neededBaselines.isNotEmpty()) add("capturing ${neededBaselines.joinToString(", ") { it.title }}")
                    }
                    val imageStartRealtime = SystemClock.elapsedRealtime()
                    WineAppLabelsRunService.update(
                        app,
                        "Photo ${index + 1}/${manifest.size} · ${running.joinToString(" + ")} ($ocrSourceLabel)",
                    )
                    mutable.update {
                        it.copy(
                            remainingImages = remainingInPass,
                            currentImageStartedRealtimeMs = imageStartRealtime,
                            message = "Photo ${index + 1}/${manifest.size} · review #${input.getInt("review_number")} · ${input.getString("source_filename")}\nRunning ${running.joinToString(" + ")} ($ocrSourceLabel) · continues in background if navigated away.",
                        )
                    }
                    val results = PhoneCorpusRunner.runImageAllModes(
                        app, image, input.getString("sha256"), needed, File(root, "work"),
                        listOf(lab), "wine-app-labels-v1",
                        cachedOcr = cachedEntry,
                        onFreshOcr = { meta, rows -> ocrStore?.save(image, imageSha, meta, rows) },
                        warmSession = warmSession,
                        labModules = activeModules,
                    )
                    for ((version, result) in results) {
                        if (version == PhoneCorpusRunner.Mode.LAB) {
                            persistResult(lab, image, result, labPrevious?.optInt("attempt") ?: 0)
                        } else {
                            // Freeze the Prod outcome the first time it is produced. An explicit
                            // retry of a frozen failure replaces that record.
                            val previous = store.record(version, image)
                            // Keep an increasing attempt number so a later retry is distinguishable
                            // from the record it replaced.
                            result.put("attempt", (previous?.optInt("attempt") ?: 0) + 1)
                            store.save(version, image, result)
                        }
                    }
                    refresh()
                    val nowAfterImage = SystemClock.elapsedRealtime()
                    val imageDurationMs = (nowAfterImage - imageStartRealtime).coerceAtLeast(1L)
                    runImagesCount += 1
                    runImagesTotalMs += imageDurationMs
                    passImagesCount += 1
                    passImagesTotalMs += imageDurationMs
                    remainingInPass = (remainingInPass - 1).coerceAtLeast(0)
                    val updatedAvg = WineAppLabelsTimers.averageImageMs(runImagesTotalMs, runImagesCount)
                    val updatedEtaPace = WineAppLabelsTimers.averageImageMs(passImagesTotalMs, passImagesCount) ?: updatedAvg
                    val totalElapsedSoFar = initialTiming.elapsedRunMs + (nowAfterImage - passStartRealtime).coerceAtLeast(0L)
                    writeRunTiming(lab, RunTiming(totalElapsedSoFar, runImagesCount, runImagesTotalMs))
                    mutable.update {
                        it.copy(
                            avgImageMs = updatedAvg,
                            etaPerImageMs = updatedEtaPace,
                            currentImageStartedRealtimeMs = null,
                            remainingImages = remainingInPass,
                        )
                    }
                    if (results.values.any { it.optBoolean("abort_run") }) { stopping = true; break }
                }
                val frozen = BASELINE_MODES.all { store.capture(it)?.count == WINE_APP_LABEL_COUNT }
                mutable.update {
                    it.copy(message = when {
                        stopping -> "Stopped safely. Resume continues with unattempted photos."
                        frozen -> ""
                        else -> "Pass finished. Prod baselines still have photos to capture; run again to freeze the rest. Failed photos can be retried."
                    })
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(message = "Test stopped: ${e.message}. Completed checkpoints are retained.") }
            } catch (e: OutOfMemoryError) {
                mutable.update { it.copy(message = "Memory pressure stopped the test. Completed checkpoints are retained.") }
            } finally {
                runCatching { warmSession?.close() }
                val finalElapsed = initialTiming.elapsedRunMs + (SystemClock.elapsedRealtime() - passStartRealtime).coerceAtLeast(0L)
                runCatching { writeRunTiming(lab, RunTiming(finalElapsed, runImagesCount, runImagesTotalMs)) }
                val finalAvg = WineAppLabelsTimers.averageImageMs(runImagesTotalMs, runImagesCount)
                if (locked) lock.unlock()
                WineAppLabelsRunService.stop(app)
                mutable.update { state ->
                    val idleRemaining = maxOf(state.total - state.labCompleted, state.captureRemaining).coerceAtLeast(0)
                    state.copy(
                        busy = false,
                        labRunning = false,
                        elapsedRunMs = finalElapsed,
                        runSegmentStartedRealtimeMs = null,
                        avgImageMs = finalAvg,
                        etaPerImageMs = finalAvg,
                        currentImageStartedRealtimeMs = null,
                        remainingImages = idleRemaining,
                    )
                }
            }
        }
    }
}
