package com.yourapp.wine.phonecorpus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.textlab.PaddleCandidates
import com.yourapp.wine.capture.WineScannerServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val WINE_APP_LABEL_COUNT = 125

internal data class WineAppLabelsState(
    val busy: Boolean = false,
    val mode: PhoneCorpusRunner.Mode = PhoneCorpusRunner.Mode.PRODUCTION_V1,
    val message: String = "Loading the 125 retained wine labels…",
    val completed: Int = 0,
    val total: Int = WINE_APP_LABEL_COUNT,
    val board: PhoneCorpusScoreboard.Board? = null,
)

/** Runs the 125 retained workbook labels. Targets stay on the scoring side of the boundary. */
internal class WineAppLabelsViewModel(app: Application) : AndroidViewModel(app) {
    private val app get() = getApplication<Application>()
    private val mutable = MutableStateFlow(WineAppLabelsState())
    val state = mutable.asStateFlow()
    private val root get() = File(app.noBackupFilesDir, "wine-app-labels-v1").apply { mkdirs() }
    private val installId get() = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
    private val lock = Mutex()
    @Volatile private var stopping = false
    @Volatile private var activityForeground = false
    @Volatile private var resumeAfterBackgroundPause = false
    /** One current run folder per version; every photo is checkpointed into all of them together. */
    private var folders: Map<PhoneCorpusRunner.Mode, File> = emptyMap()

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

    private fun folderPrefix(mode: PhoneCorpusRunner.Mode) = "${BuildConfig.BUILD_COMMIT}-$installId-${mode.name}-"

    private fun corpusSignature(manifest: JSONObject): String = listOf(
        "inputs_sha256", "evaluation_set_sha256", "scoring_sha256",
    ).joinToString(":") { manifest.getString(it) }

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
        val metadata = JSONObject()
            .put("build", BuildConfig.BUILD_COMMIT)
            .put("working_tree", BuildConfig.BUILD_WORKING_TREE)
            .put("mode", mode.name)
            .put("pipeline", mode.pipelineId)
            .put("schema_version", 1)
            .put("corpus_manifest", assetJson("manifest.json"))
            .put("created_ms", System.currentTimeMillis())
            .put("runtime", "phone; fresh OCR; owner-reviewed Wine App Labels")
        File(dir, "run.json").writeText(metadata.toString(2))
        return dir
    }

    private fun predictions(dir: File) = File(dir, "current").listFiles().orEmpty()
        .filter { it.extension == "json" }
        .sortedBy { it.name }
        .map { JSONObject(it.readText()) }
        .filter { it.optString("image") in includedIds() }

    private fun refresh() {
        val dirs = folders
        if (dirs.isEmpty()) return
        val mode = mutable.value.mode
        val scoreboard = PhoneCorpusScoreboard(app, "wine-app-labels-v1", includeLegacyComparisons = false)
        // Each row is scored from that version's own current-run checkpoints only, so Attempted
        // is exactly the photos this run has checkpointed for that version.
        val byMode = PhoneCorpusRunner.Mode.entries.associateWith { predictions(dirs.getValue(it)) }
        val currentBoard = scoreboard.build(byMode.getValue(mode), mode)
        val comparisonRows = PhoneCorpusRunner.Mode.entries.map { comparedMode ->
            val comparisonPredictions = byMode.getValue(comparedMode)
            if (comparedMode == mode) currentBoard.rows.single()
            else scoreboard.build(comparisonPredictions, comparedMode).rows.single()
        }
        val fullyCheckpointed = byMode.values
            .map { preds -> preds.map { it.getString("image") }.toSet() }
            .reduceOrNull { common, images -> common intersect images }?.size ?: 0
        val displayHeaders = currentBoard.headers.map { if (it == "Complete") "Fully correct" else it }
        val comparisonText = buildString {
            appendLine("WINE APP LABELS · $fullyCheckpointed/$WINE_APP_LABEL_COUNT photos checkpointed in all four versions")
            appendLine("Every photo runs through all four versions before the test moves on. Prod v2.0, Prod v3.0 and Lab v4 share one fresh OCR pass and differ in post-processing — Prod v2.0: guarded evidence only; Prod v3.0: the spatial pipeline with LWIN-only producer data; Lab v4: the same spatial pipeline with the expanded producer index (LWIN plus X-Wines/TTB/OpenStreetMap candidates) — while Prod v1.0 runs the Add Wine scanner separately. Attempted therefore stays equal across versions.")
            appendLine("Attempted is the saved-photo tally. Fully correct counts photos where all six scorable fields match; it is an accuracy score, not run progress.")
            appendLine(displayHeaders.joinToString(" | "))
            comparisonRows.forEach { appendLine(it.joinToString(" | ")) }
        }
        val board = currentBoard.copy(headers = displayHeaders, rows = comparisonRows, text = comparisonText)
        mutable.update { it.copy(completed = fullyCheckpointed, total = WINE_APP_LABEL_COUNT, board = board) }
    }

    fun load(mode: PhoneCorpusRunner.Mode, fresh: Boolean = false) {
        if (mutable.value.busy) return
        folders = emptyMap()
        mutable.update { it.copy(busy = true, mode = mode, completed = 0, total = WINE_APP_LABEL_COUNT, board = null, message = "Loading saved 125-label results…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // The selected version only chooses which diagnostics are displayed; a run always
                // covers all four versions, so all four current folders load (or start fresh) together.
                folders = PhoneCorpusRunner.Mode.entries.associateWith { selectFolder(it, fresh) }
                refresh()
                mutable.update { it.copy(message = "Ready. Only the 125 retained workbook labels are eligible for this test.") }
            } catch (e: Exception) {
                folders = emptyMap()
                mutable.update { it.copy(message = "Cannot load the 125-label test: ${e.message}") }
            } finally {
                mutable.update { it.copy(busy = false) }
                resumeIfRequested()
            }
        }
    }

    fun stop() {
        stopping = true
        mutable.update { if (it.busy) it.copy(message = "Stopping after the current photo; please wait for OCR to finish.") else it }
    }

    fun onBackgroundPause(resumeWhenForeground: Boolean) {
        activityForeground = false
        if (mutable.value.busy) {
            resumeAfterBackgroundPause = resumeWhenForeground
            stop()
        }
    }

    fun onForeground() {
        activityForeground = true
        resumeIfRequested()
    }

    private fun resumeIfRequested() {
        if (resumeAfterBackgroundPause && activityForeground && !mutable.value.busy) {
            resumeAfterBackgroundPause = false
            start()
        }
    }

    private fun isFailed(result: JSONObject): Boolean =
        result.optString("status") != "complete" || result.optBoolean("engine_incomplete") ||
            result.optJSONArray("pipelines")?.let { rows ->
                (0 until rows.length()).any { rows.getJSONObject(it).optString("status") != "complete" }
            } == true

    fun start(retryFailures: Boolean = false) {
        if (mutable.value.busy) return
        val dirs = folders
        if (dirs.isEmpty()) return
        mutable.update { it.copy(busy = true, message = "Checking the 125 retained workbook photos and OCR engines…") }
        stopping = false
        viewModelScope.launch(Dispatchers.IO) {
            var locked = false
            try {
                lock.lock()
                locked = true
                val spec = PaddleCandidates.all.single { it.id == "candidate_3" }
                val availableAssets = app.assets.list("text-lab-paddle-v1").orEmpty().toSet()
                check(listOf(spec.detector.asset, spec.recognizer.asset, spec.dictionary.asset).all { it in availableAssets }) {
                    "Medium model assets are missing; the test will not silently run ML Kit only."
                }
                WineScannerServices.get(app).withBenchmarkEngines { engines, _ ->
                    check(setOf("mlkit", "paddle-v6-medium").all { id -> engines.any { it.id == id && it.available } }) {
                        "Both ML Kit and Medium must be available."
                    }
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
                val existing = PhoneCorpusRunner.Mode.entries.associateWith { version ->
                    predictions(dirs.getValue(version)).associateBy { it.getString("image") }
                }
                dirs.values.forEach { File(it, "current").mkdirs(); File(it, "attempts").mkdirs() }
                for ((index, input) in manifest.withIndex()) {
                    if (stopping) break
                    val image = input.getString("image")
                    // A photo is done only when every version has a checkpoint for it. Versions
                    // already checkpointed (for example by an older single-version run) are kept;
                    // only the missing or failed versions run, and they run now, for this photo.
                    val needed = PhoneCorpusRunner.Mode.entries.filter { version ->
                        val previous = existing.getValue(version)[image]
                        if (retryFailures) previous != null && isFailed(previous) else previous == null
                    }
                    if (needed.isEmpty()) continue
                    mutable.update { it.copy(message = "Photo ${index + 1}/${manifest.size} · review #${input.getInt("review_number")} · ${input.getString("source_filename")}\nRunning ${needed.joinToString(", ") { version -> version.title }} · keep the phone plugged in; switching apps pauses after this photo and resumes on return.") }
                    val results = PhoneCorpusRunner.runImageAllModes(
                        app, image, input.getString("sha256"), needed.toSet(), File(root, "work"),
                        needed.filter { it != PhoneCorpusRunner.Mode.PRODUCTION_V1 }.map { dirs.getValue(it) },
                        "wine-app-labels-v1",
                    )
                    for ((version, result) in results) {
                        persistResult(dirs.getValue(version), image, result, existing.getValue(version)[image]?.optInt("attempt") ?: 0)
                    }
                    refresh()
                    if (results.values.any { it.optBoolean("abort_run") }) { stopping = true; break }
                }
                mutable.update { it.copy(message = if (stopping) "Stopped safely. Resume continues with unattempted photos." else "Pass finished. Results are saved on this device; failed photos can be retried.") }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(message = "Test stopped: ${e.message}. Completed checkpoints are retained.") }
            } catch (e: OutOfMemoryError) {
                mutable.update { it.copy(message = "Memory pressure stopped the test. Completed checkpoints are retained.") }
            } finally {
                if (locked) lock.unlock()
                mutable.update { it.copy(busy = false) }
                resumeIfRequested()
            }
        }
    }
}
