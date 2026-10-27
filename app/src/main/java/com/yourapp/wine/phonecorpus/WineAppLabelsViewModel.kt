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
import com.yourapp.wine.capture.WineScannerServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val WINE_APP_LABEL_COUNT = 125

/**
 * The retired comparators. Each photo runs through these once, the outcome is frozen on this
 * device, and later passes score the frozen record instead of re-running them. Only Lab v4
 * consumes OCR on a later pass, so a captured corpus never reruns a Prod version.
 */
private val BASELINE_MODES = listOf(
    PhoneCorpusRunner.Mode.PRODUCTION_V1,
    PhoneCorpusRunner.Mode.PRODUCTION_V2,
    PhoneCorpusRunner.Mode.PRODUCTION_V3,
)

internal data class WineAppLabelsState(
    val busy: Boolean = false,
    val mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.LAB,
    val message: String = "Loading the 125 retained wine labels…",
    val completed: Int = 0,
    val total: Int = WINE_APP_LABEL_COUNT,
    val board: PhoneCorpusScoreboard.Board? = null,
    val exportParts: List<File> = emptyList(),
    /** Photos the current Lab run has checkpointed. */
    val labCompleted: Int = 0,
    val labRunning: Boolean = false,
    /** When checked next to Lab v4, reuses verified cached OCR outputs instead of re-running both OCR models. */
    val useCachedOcr: Boolean = false,
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
)

private data class RunTiming(
    val elapsedRunMs: Long = 0L,
    val timedImagesCount: Int = 0,
    val timedImagesTotalMs: Long = 0L,
)

/**
 * Process-scoped holder so an active 125-label benchmark run survives navigating away from
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

/** Runs the 125 retained workbook labels. Targets stay on the scoring side of the boundary. */
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

    private fun readUseCachedOcrPref(): Boolean = runCatching {
        val file = prefsFile()
        if (file.isFile) JSONObject(file.readText()).optBoolean("use_cached_ocr", false) else false
    }.getOrDefault(false)

    private fun writeUseCachedOcrPref(enabled: Boolean) {
        runCatching {
            prefsFile().writeText(JSONObject().put("use_cached_ocr", enabled).toString())
        }
    }

    fun setUseCachedOcr(enabled: Boolean) {
        if (mutable.value.busy) return
        writeUseCachedOcrPref(enabled)
        mutable.update { it.copy(useCachedOcr = enabled) }
    }

    private fun folderPrefix(mode: PhoneCorpusRunner.Mode) = "${BuildConfig.BUILD_COMMIT}-$installId-${mode.name}-"

    private fun corpusSignature(manifest: JSONObject): String = listOf(
        "inputs_sha256", "evaluation_set_sha256", "scoring_sha256",
    ).joinToString(":") { manifest.getString(it) }

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

    /** Reuse this version's saved per-photo results across app updates, but never across corpus revisions. */
    private fun latestFolder(mode: PhoneCorpusRunner.Mode): File? {
        val expectedCorpus = corpusSignature(assetJson("manifest.json"))
        return root.listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            runCatching {
                val metadata = JSONObject(File(dir, "run.json").readText())
                val savedCorpus = metadata.optJSONObject("corpus_manifest") ?: return@runCatching null
                if (metadata.optString("mode") != mode.name ||
                    metadata.optString("pipeline") != mode.pipelineId ||
                    corpusSignature(savedCorpus) != expectedCorpus
                ) return@runCatching null
                dir to metadata.optLong("created_ms", dir.lastModified())
            }.getOrNull()
        }.maxByOrNull { it.second }?.first
    }

    private fun persistResult(dir: File, image: String, result: JSONObject, previousAttempt: Int = 0) {
        result.put("attempt", previousAttempt + 1)
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
        val metadata = JSONObject()
            .put("build", BuildConfig.BUILD_COMMIT)
            .put("branch", BuildConfig.BUILD_BRANCH)
            .put("version_code", BuildConfig.VERSION_CODE.toString())
            .put("build_key", WineAppLabelsBuildLog.currentBuildKey())
            .put("working_tree", BuildConfig.BUILD_WORKING_TREE)
            .put("mode", mode.name)
            .put("pipeline", mode.pipelineId)
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
     * One record per photo for each version, scoped to the photos Lab v4 has attempted in this
     * pass so all four rows compare side by side over the exact same subset before all 125 finish
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
        val recordedRunIds = log.readEntries().mapTo(hashSetOf()) { it.runId }
        val scorer = scoreboard()
        for (dir in matchingRunFolders(expectedCorpus).asReversed()) {
            if (dir.name in recordedRunIds) continue
            val metadata = runCatching { JSONObject(File(dir, "run.json").readText()) }.getOrNull() ?: continue
            if (metadata.optString("mode") != PhoneCorpusRunner.Mode.LAB.name) continue
            val preds = runCatching { predictions(dir) }.getOrNull().orEmpty()
            if (preds.isEmpty()) continue
            val summaryRow = runCatching { scorer.build(preds, PhoneCorpusRunner.Mode.LAB).rows.single() }.getOrNull() ?: continue
            val info = WineAppLabelsBuildLog.buildInfoFromSavedRun(metadata, preds)
            val ts = File(dir, "current").listFiles().orEmpty().maxOfOrNull { it.lastModified() }
                ?: metadata.optLong("created_ms", dir.lastModified())
            log.recordSummary(dir.name, info, PhoneCorpusRunner.Mode.LAB, summaryRow, ts)
        }
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
        val activeLabDir = labFolder
        if (activeLabDir != null && labCount > 0) {
            val runMeta = runCatching { JSONObject(File(activeLabDir, "run.json").readText()) }.getOrNull()
            val info = WineAppLabelsBuildLog.buildInfoFromSavedRun(runMeta, labPreds)
            val isCurrentBuild = info.buildKey == WineAppLabelsBuildLog.currentBuildKey()
            val ts = if (isCurrentBuild) {
                System.currentTimeMillis()
            } else {
                File(activeLabDir, "current").listFiles().orEmpty().maxOfOrNull { it.lastModified() }
                    ?: runMeta?.optLong("created_ms", activeLabDir.lastModified())
                    ?: System.currentTimeMillis()
            }
            buildLog?.recordSummary(activeLabDir.name, info, PhoneCorpusRunner.Mode.LAB, modeRows.first(), ts)
        }
        val ocrReady = ocrCache?.count(inputHashes()) ?: 0
        val captures = BASELINE_MODES.mapNotNull { version -> baselines?.capture(version) }.associateBy { it.mode }
        val captured = captures.values.map { it.count }.minOrNull() ?: 0
        val frozenReady = captures.size == BASELINE_MODES.size
        val captureRemaining = if (frozenReady) 0 else WINE_APP_LABEL_COUNT - captured
        val displayHeaders = currentBoard.headers.map { if (it == "Complete") "Fully correct" else it }
        val lastBuildEntry = buildLog?.previousBuildEntry()
        val lastBuildRow = lastBuildEntry?.toTableRow()
            ?: WineAppLabelsBuildLog.emptyLastBuildRow(displayHeaders.size)
        val comparisonRows = listOf(modeRows.first(), lastBuildRow) + modeRows.drop(1)
        val comparisonText = buildString {
            appendLine("WINE APP LABELS · Lab v4 $labCount/$WINE_APP_LABEL_COUNT checkpointed · Prod baselines $captured/$WINE_APP_LABEL_COUNT photos frozen · OCR cache $ocrReady/$WINE_APP_LABEL_COUNT ready")
            if (lastBuildEntry != null) {
                appendLine("Lab v4 Last build: ${lastBuildEntry.buildNumber} · branch ${lastBuildEntry.branch} · ${lastBuildEntry.date} ${lastBuildEntry.time}")
            }
            appendLine("Prod v1.0, Prod v2.0 and Prod v3.0 are retired comparators. Each photo runs through them once; the outcome is frozen on this device and replayed for the $labCount/$WINE_APP_LABEL_COUNT photos Lab v4 has attempted so far, so all four rows compare side by side without re-running Prod. Lab v4 runs fresh OCR by default or reuses verified cached OCR outputs when selected. The diagnostics matrix on screen is the selected (Lab) version; the frozen Prod funnel rows are kept with their records and written to the scored and raw CSV exports.")
            appendLine("Prod v2.0, Prod v3.0 and Lab v4 share one fresh OCR pass and differ in post-processing — Prod v2.0: guarded evidence only; Prod v3.0: the spatial pipeline with LWIN-only producer data; Lab v4: the same spatial pipeline with the expanded producer index (LWIN plus X-Wines/TTB/OpenStreetMap candidates) — while Prod v1.0 runs the Add Wine scanner separately.")
            appendLine("Attempted is the saved-photo tally. Fully correct counts photos where all six scorable fields match; it is an accuracy score, not run progress.")
            appendLine(displayHeaders.joinToString(" | "))
            comparisonRows.forEach { appendLine(it.joinToString(" | ")) }
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
                )
            }
        }
    }

    /** One scored row set per version in one file; frozen Prod rows carry their capture provenance. */
    private fun writeScoredCsv(target: File) {
        val scorer = scoreboard()
        val rows = mutableListOf<Map<String, String>>()
        for ((mode, records) in predictionsByMode()) {
            val provenance = if (mode == PhoneCorpusRunner.Mode.LAB) {
                mapOf("build" to BuildConfig.BUILD_COMMIT, "mode" to mode.name, "run_id" to (labFolder?.name ?: ""))
            } else {
                mapOf("build" to BuildConfig.BUILD_COMMIT, "mode" to mode.name, "run_id" to "frozen-baseline",
                    "result_origin" to "frozen_baseline", "captured_build" to baselines?.capture(mode)?.build.orEmpty())
            }
            scorer.build(records, mode).csv.forEach { rows += provenance + it }
        }
        target.bufferedWriter(Charsets.UTF_8).use { writer -> TextLabCsv.write(writer, rows.asSequence()) }
    }

    /**
     * Raw diagnostics for the Lab pass plus the frozen Prod aggregates. Frozen records deliberately
     * carry no OCR tokens or traces, so this file cannot claim raw evidence it never kept.
     */
    private fun writeRawCsv(target: File) {
        val scorer = scoreboard()
        val included = includedIds()
        val dir = labFolder
        // Reiterable, one photo at a time: the CSV writer scans keys, then writes rows.
        val records = sequence {
            if (dir != null) {
                val files = File(dir, "attempts").listFiles().orEmpty()
                    .filter { it.extension == "json" }.sortedBy { it.name }
                for (file in files) {
                    val p = JSONObject(file.readText())
                    if (p.optString("image") !in included) continue
                    val meta = mapOf("build" to p.optString("build"), "mode" to PhoneCorpusRunner.Mode.LAB.name,
                        "pipeline" to PhoneCorpusRunner.Mode.LAB.pipelineId, "run_id" to dir.name,
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
                            yield(meta + mapOf("row_type" to key) +
                                row.keys().asSequence().associateWith { row.optString(it) })
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
        target.bufferedWriter(Charsets.UTF_8).use { writer -> TextLabCsv.write(writer, records) }
    }

    /** Prepares the chosen CSV in the cache, split under the 25 MB upload cap, so each part can be saved. */
    fun prepareExport(raw: Boolean) {
        if (mutable.value.busy) return
        if (labFolder == null) {
            mutable.update { it.copy(message = "Load the 125-label test before preparing an export.") }
            return
        }
        val previousParts = mutable.value.exportParts
        exporting = true
        mutable.update { it.copy(busy = true, exportParts = emptyList(), message = "Preparing upload-sized CSV files…") }
        bgScope.launch {
            val exportDir = File(app.cacheDir, "wine-app-labels-exports/${UUID.randomUUID()}")
            try {
                check(exportDir.mkdirs())
                val target = File(exportDir,
                    "wine-app-labels-${WINE_APP_LABEL_COUNT}-${if (raw) "raw-" else ""}all-versions-${BuildConfig.BUILD_COMMIT}.csv")
                if (raw) writeRawCsv(target) else writeScoredCsv(target)
                val parts = CsvUploadParts.split(target, exportDir)
                if (parts.size > 1) target.delete()
                previousParts.mapNotNull { it.parentFile }.distinct()
                    .filter { it.parentFile == exportDir.parentFile }.forEach { it.deleteRecursively() }
                mutable.update {
                    it.copy(exportParts = parts, message = "${parts.size} CSV file(s) ready, each at most 25 MB. Tap each Save button below. Complete rows and headers are preserved.")
                }
            } catch (cancel: CancellationException) {
                exportDir.deleteRecursively();throw cancel
            } catch (e: Exception) {
                exportDir.deleteRecursively()
                mutable.update { it.copy(message = "CSV preparation failed: ${e.message}. Original run data is unchanged.") }
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
        labFolder = null
        mutable.update {
            it.copy(
                busy = true, mode = mode, useCachedOcr = preferredCachedOcr, completed = 0,
                total = WINE_APP_LABEL_COUNT, board = null, exportParts = emptyList(), labCompleted = 0,
                elapsedRunMs = 0L, runSegmentStartedRealtimeMs = null, avgImageMs = null,
                etaPerImageMs = null, currentImageStartedRealtimeMs = null,
                remainingImages = WINE_APP_LABEL_COUNT,
                message = "Loading saved 125-label results…",
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
                val legacy = PhoneCorpusRunner.Mode.entries.mapNotNull { mode -> latestFolder(mode)?.let { mode to it } }.toMap()
                val promoted = store.promote(BASELINE_MODES, legacy)
                val ocrStore = WineAppLabelsOcrCache(app, signature)
                val promotedOcr = ocrStore.promote(matchingRunFolders(signature), inputHashes())
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
                mutable.update { it.copy(message = "Cannot load the 125-label test: ${e.message}") }
            } finally {
                mutable.update { it.copy(busy = false) }
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

    private fun isFailed(result: JSONObject): Boolean =
        result.optString("status") != "complete" || result.optBoolean("engine_incomplete") ||
            result.optJSONArray("pipelines")?.let { rows ->
                (0 until rows.length()).any { rows.getJSONObject(it).optString("status") != "complete" }
            } == true

    /**
     * Runs the pass. Lab v4 is re-run for every photo; each Prod version runs only for photos that
     * have no frozen record yet (first pass) or whose stored record is a failure ([retryFailures]).
     * Once a photo is frozen, later passes score the stored outcome and never re-run Prod.
     */
    fun start(retryFailures: Boolean = false) {
        if (mutable.value.busy) return
        val lab = labFolder ?: return
        val store = baselines ?: return
        val ocrStore = ocrCache
        val useCachedOcr = mutable.value.useCachedOcr
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
                message = "Checking the 125 retained workbook photos and OCR engines…",
            )
        }
        stopping = false
        WineAppLabelsRunService.start(app, "Preparing 125-label benchmark…")
        bgScope.launch {
            var locked = false
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
                check(packaged.length() == WINE_APP_LABEL_COUNT) { "The packaged workbook photo count is not 125" }
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
                        frozen -> "Pass finished. Prod v1.0/v2.0/v3.0 are frozen on this device; only Lab v4 runs from now on. Failed photos can be retried."
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
