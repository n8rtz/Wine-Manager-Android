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
    private var folder: File? = null

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

    private fun refresh(dir: File, mode: PhoneCorpusRunner.Mode) {
        val scoreboard = PhoneCorpusScoreboard(app, "wine-app-labels-v1", includeLegacyComparisons = false)
        val current = predictions(dir)
        val currentBoard = scoreboard.build(current, mode)
        val comparisonRows = PhoneCorpusRunner.Mode.entries.map { comparedMode ->
            val comparisonDir = if (comparedMode == mode) dir else latestFolder(comparedMode)
            val comparisonPredictions = comparisonDir?.let(::predictions).orEmpty()
            scoreboard.build(comparisonPredictions, comparedMode).rows.single()
        }
        val displayHeaders = currentBoard.headers.map { if (it == "Complete") "Fully correct" else it }
        val comparisonText = buildString {
            appendLine("WINE APP LABELS · ${current.size}/$WINE_APP_LABEL_COUNT attempted in ${mode.title}")
            appendLine("Only the selected version runs fresh OCR. The other rows show saved per-version results for this exact corpus.")
            appendLine("Attempted is the saved-photo tally. Fully correct counts photos where all six scorable fields match; it is an accuracy score, not run progress.")
            appendLine(displayHeaders.joinToString(" | "))
            comparisonRows.forEach { appendLine(it.joinToString(" | ")) }
        }
        val board = currentBoard.copy(headers = displayHeaders, rows = comparisonRows, text = comparisonText)
        mutable.update { it.copy(completed = current.size, total = WINE_APP_LABEL_COUNT, board = board) }
    }

    fun load(mode: PhoneCorpusRunner.Mode, fresh: Boolean = false) {
        if (mutable.value.busy) return
        folder = null
        mutable.update { it.copy(busy = true, mode = mode, completed = 0, total = WINE_APP_LABEL_COUNT, board = null, message = "Loading saved 125-label results…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = selectFolder(mode, fresh)
                folder = dir
                refresh(dir, mode)
                mutable.update { it.copy(message = "Ready. Only the 125 retained workbook labels are eligible for this test.") }
            } catch (e: Exception) {
                folder = null
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

    fun start(retryFailures: Boolean = false) {
        if (mutable.value.busy) return
        val dir = folder ?: return
        val mode = mutable.value.mode
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
                val existing = predictions(dir).associateBy { it.getString("image") }
                File(dir, "current").mkdirs()
                File(dir, "attempts").mkdirs()
                for ((index, input) in manifest.withIndex()) {
                    if (stopping) break
                    val image = input.getString("image")
                    val previous = existing[image]
                    val failed = previous?.let { result ->
                        result.optString("status") != "complete" || result.optBoolean("engine_incomplete") ||
                            result.optJSONArray("pipelines")?.let { rows ->
                                (0 until rows.length()).any { rows.getJSONObject(it).optString("status") != "complete" }
                            } == true
                    } ?: false
                    if (if (retryFailures) !failed else previous != null) continue
                    mutable.update { it.copy(message = "Photo ${index + 1}/${manifest.size} · review #${input.getInt("review_number")} · ${input.getString("source_filename")}\n$mode · keep the phone plugged in; switching apps pauses after this photo and resumes on return.") }
                    val result = PhoneCorpusRunner.runImage(
                        app, image, input.getString("sha256"), mode, File(dir, "work"), "wine-app-labels-v1"
                    )
                    persistResult(dir, image, result, previous?.optInt("attempt") ?: 0)
                    refresh(dir, mode)
                    if (result.optBoolean("abort_run")) { stopping = true; break }
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
