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
    private val preferences get() = app.getSharedPreferences("wine-app-labels-results", Application.MODE_PRIVATE)
    private val installId get() = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
    private val lock = Mutex()
    @Volatile private var stopping = false
    private var folder: File? = null

    init { load(PhoneCorpusRunner.Mode.PRODUCTION_V1) }

    private fun assetJson(name: String) = JSONObject(app.assets.open("wine-app-labels-v1/$name").bufferedReader().use { it.readText() })

    private fun includedIds(): Set<String> {
        val selection = assetJson("evaluation-set.json")
        val list = selection.getJSONArray("included_images")
        val expected = selection.optInt("included_count")
        return (0 until list.length()).map { list.getJSONObject(it).getString("image") }.toSet().also {
            check(it.size == WINE_APP_LABEL_COUNT && expected == WINE_APP_LABEL_COUNT) { "The retained workbook selection is invalid" }
        }
    }

    private fun lastMode(): PhoneCorpusRunner.Mode? {
        val dirName = preferences.getString("last_run", null) ?: return null
        val dir = File(root, dirName)
        if (dir.parentFile != root || !File(dir, "run.json").isFile) return null
        val value = runCatching { JSONObject(File(dir, "run.json").readText()).optString("mode") }.getOrNull()
        return PhoneCorpusRunner.Mode.entries.firstOrNull { it.name == value }
    }

    private fun folderPrefix(mode: PhoneCorpusRunner.Mode) = "${BuildConfig.BUILD_COMMIT}-$installId-${mode.name}-"

    private fun latestFolder(mode: PhoneCorpusRunner.Mode): File? = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith(folderPrefix(mode)) }
        .maxByOrNull { it.lastModified() }

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
        val comparisonText = buildString {
            appendLine("WINE APP LABELS · ${current.size}/$WINE_APP_LABEL_COUNT attempted in ${mode.title}")
            appendLine("Comparison results accumulate independently as each version runs on the same $WINE_APP_LABEL_COUNT retained workbook labels.")
            appendLine(currentBoard.headers.joinToString(" | "))
            comparisonRows.forEach { appendLine(it.joinToString(" | ")) }
        }
        val board = currentBoard.copy(rows = comparisonRows, text = comparisonText)
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
                preferences.edit().putString("last_run", dir.name).apply()
                refresh(dir, mode)
                mutable.update { it.copy(message = "Ready. Only the 125 retained workbook labels are eligible for this test.") }
            } catch (e: Exception) {
                folder = null
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
                    mutable.update { it.copy(message = "Photo ${index + 1}/${manifest.size} · review #${input.getInt("review_number")} · ${input.getString("source_filename")}\n$mode · keep app open and phone plugged in.") }
                    val result = PhoneCorpusRunner.runImage(
                        app, image, input.getString("sha256"), mode, File(dir, "work"), "wine-app-labels-v1"
                    )
                    result.put("attempt", (previous?.optInt("attempt") ?: 0) + 1)
                    File(File(dir, "attempts"), "$image-${System.currentTimeMillis()}.json").writeText(result.toString())
                    result.remove("trace")
                    result.remove("tokens")
                    result.remove("diagnostics")
                    File(File(dir, "current"), "$image.json").writeText(result.toString())
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
            }
        }
    }
}
