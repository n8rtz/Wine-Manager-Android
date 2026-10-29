package com.yourapp.wine.phonecorpus

import android.app.Application
import com.n8rtz.winetracker.BuildConfig
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Frozen Prod v1.0 / v2.0 / v4.0 / v5.0 outcomes for the 124-label benchmark.
 *
 * The Prod versions are retired comparators: each photo runs through them once, the outcome is
 * written here under the current corpus signature, and every later pass reads that record instead
 * of running them again. Only the Lab version is re-run, so a run compares the current model with
 * a fixed baseline rather than with a fresh copy of itself.
 *
 * Records are keyed by corpus signature, so a revised reference set never mixes with an older
 * capture. Nothing here is namespaced by build SHA or install time, so the baselines survive app
 * updates — that is the point of freezing them. Uninstall / clear app data removes them, like any
 * other private app data.
 *
 * Each record keeps the version's own status, fields, engine flags and pipeline funnel (so the
 * frozen diagnostics table still works) and drops OCR tokens, traces and the raw diagnostics blob.
 */
internal class WineAppLabelsBaselines(app: Application, corpusSignature: String) {
    /** Capture provenance for one frozen version; shown on screen and written into the CSV exports. */
    data class Capture(val mode: PhoneCorpusRunner.Mode, val count: Int, val build: String, val capturedMs: Long)

    private val app = app
    private val signature = anchor(corpusSignature)
    private val baselinesRoot = File(app.noBackupFilesDir, "wine-app-labels-v1/baselines-v1")
    private val root = File(app.noBackupFilesDir, "wine-app-labels-v1/baselines-v1/$signature")

    private fun folder(mode: PhoneCorpusRunner.Mode) = File(root, mode.name)

    /** The owner relabeled the captured Prod v3 baseline as Prod v4; read the old store path too. */
    private fun legacyFolders(mode: PhoneCorpusRunner.Mode): List<File> = when (mode) {
        PhoneCorpusRunner.Mode.PRODUCTION_V4 -> listOf(File(root, "PRODUCTION_V3"))
        else -> emptyList()
    }

    /** Legacy first, current last so a migrated/current record wins if both exist. */
    private fun readFolders(mode: PhoneCorpusRunner.Mode) = legacyFolders(mode) + folder(mode)

    /** Frozen outcomes for this version, keyed by photo. Unreadable records are treated as missing. */
    fun all(mode: PhoneCorpusRunner.Mode): Map<String, JSONObject> {
        val files = readFolders(mode).flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.extension == "json" && !it.name.startsWith("_") }
        }
        val records = files.mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            .filter { it.optBoolean("frozen") && it.optString("image").isNotBlank() }
        return records.associateBy { it.getString("image") }
    }

    fun record(mode: PhoneCorpusRunner.Mode, image: String): JSONObject? =
        readFolders(mode).asReversed().asSequence()
            .map { File(it, "$image.json") }
            .filter { it.isFile }
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            .firstOrNull { it.optBoolean("frozen") }

    /** Freezes one photo's outcome. Called only for a capture this device just produced. */
    fun save(mode: PhoneCorpusRunner.Mode, image: String, result: JSONObject) {
        val dir = folder(mode).apply { mkdirs() }
        val record = JSONObject(result.toString())
        // Raw evidence stays in the exporting build's own run folder; a frozen record keeps only
        // what the scoreboard, diagnostics table and CSV rows read.
        record.remove("trace")
        record.remove("tokens")
        record.remove("diagnostics")
        // A promoted record may carry a pipeline ID that has since been renamed (for example
        // production-v3.0-spatial-merged). Freezing it *as* this version means the stored rows must
        // speak the current ID, or the scorer would not find this version's row at all.
        record.optJSONArray("pipelines")?.let { pipelines ->
            for (index in 0 until pipelines.length()) {
                val row = pipelines.getJSONObject(index)
                if (PhoneCorpusRunner.Mode.fromPipelineId(row.optString("pipeline")) == mode) row.put("pipeline", mode.pipelineId)
            }
        }
        record.put("image", image)
        record.put("mode", mode.name)
        record.put("pipeline", mode.pipelineId)
        record.put("stored_pipeline", result.optString("pipeline"))
        record.put("frozen", true)
        record.put("corpus_signature", signature)
        record.put("captured_build", BuildConfig.BUILD_COMMIT)
        record.put("captured_working_tree", BuildConfig.BUILD_WORKING_TREE)
        record.put("captured_ms", System.currentTimeMillis())
        atomic(File(dir, "$image.json"), record.toString())
        val previous = index(mode)
        val captured = previous?.optLong("captured_ms")?.takeIf { it > 0 } ?: record.optLong("captured_ms")
        val capturedBuild = previous?.optString("build")?.takeIf { it.isNotBlank() } ?: BuildConfig.BUILD_COMMIT
        val count = all(mode).size
        atomic(File(dir, "_capture.json"), JSONObject()
            .put("mode", mode.name).put("pipeline", mode.pipelineId).put("corpus_signature", signature)
            .put("count", count).put("build", capturedBuild).put("captured_ms", captured)
            .put("updated_ms", System.currentTimeMillis()).toString())
    }

    private fun index(mode: PhoneCorpusRunner.Mode): JSONObject? =
        readFolders(mode).asReversed().asSequence()
            .map { File(it, "_capture.json") }
            .filter { it.isFile }
            .mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            .firstOrNull()

    /** Capture summary for the screen; count is always read back from the records themselves. */
    fun capture(mode: PhoneCorpusRunner.Mode): Capture? {
        val records = all(mode)
        if (records.isEmpty()) return null
        val index = index(mode)
        return Capture(
            mode = mode,
            count = records.size,
            build = index?.optString("build")?.takeIf { it.isNotBlank() }
                ?: records.values.mapNotNull { it.optString("captured_build").takeIf(String::isNotBlank) }.minOrNull()
                ?: "unknown",
            capturedMs = index?.optLong("captured_ms")?.takeIf { it > 0 }
                ?: records.values.minOfOrNull { it.optLong("captured_ms") } ?: 0L,
        )
    }

    /**
     * Seeds the store from a retired build's own checkpoints the first time this screen upgrades:
     * those results were produced by the frozen pipeline, so they are the capture, not a rerun.
     * Existing frozen records always win; nothing is overwritten.
     */
    fun promote(modes: List<PhoneCorpusRunner.Mode>, folders: Map<PhoneCorpusRunner.Mode, File>): Int {
        var promoted = 0
        for (mode in modes) {
            val dir = folders[mode] ?: continue
            val checkpoints = File(dir, "current").listFiles().orEmpty().filter { it.extension == "json" }
            for (file in checkpoints) {
                val previous = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
                val image = previous.optString("image").ifBlank { file.nameWithoutExtension }
                if (image.isBlank() || record(mode, image) != null) continue
                // Only a completed attempt is frozen; failures stay unfrozen so they are retried.
                if (previous.optString("status") != "complete") continue
                save(mode, image, previous)
                promoted++
            }
        }
        return promoted
    }

    /**
     * Carries over already-frozen per-photo Prod baseline outcomes from sibling
     * `baselines-v1/<sig>` directories when the photo remains in the active evaluation set with
     * matching [expectedHashes], so reference-sheet/scoring updates do not discard unaffected
     * frozen baselines.
     */
    fun migrateRetainedBaselines(
        modes: List<PhoneCorpusRunner.Mode>,
        expectedHashes: Map<String, String>,
    ): Int {
        val siblings = baselinesRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.absolutePath != root.absolutePath }
            .sortedBy { it.lastModified() }
        if (siblings.isEmpty()) return 0
        var migrated = 0
        for (mode in modes) {
            val targetDir = folder(mode)
            val sourceDirsForMode = siblings.flatMap { sib ->
                when (mode) {
                    PhoneCorpusRunner.Mode.PRODUCTION_V4 -> listOf(File(sib, "PRODUCTION_V3"), File(sib, mode.name))
                    else -> listOf(File(sib, mode.name))
                }
            }.filter { it.isDirectory }
            if (sourceDirsForMode.isEmpty()) continue
            var inheritedBuild: String? = null
            var inheritedMs: Long? = null
            for (srcDir in sourceDirsForMode) {
                val srcIndex = File(srcDir, "_capture.json").takeIf { it.isFile }
                    ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                if (inheritedBuild.isNullOrBlank()) {
                    inheritedBuild = srcIndex?.optString("build")?.takeIf { it.isNotBlank() }
                }
                if (inheritedMs == null || inheritedMs == 0L) {
                    inheritedMs = srcIndex?.optLong("captured_ms")?.takeIf { it > 0L }
                }
                for (file in srcDir.listFiles().orEmpty().filter { it.extension == "json" && !it.name.startsWith("_") }) {
                    val prev = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
                    if (!prev.optBoolean("frozen") || prev.optString("status") != "complete") continue
                    val image = prev.optString("image").ifBlank { file.nameWithoutExtension }
                    val expectedSha = expectedHashes[image] ?: continue
                    if (record(mode, image) != null) continue
                    val recordedSha = prev.optString("image_sha256")
                    if (recordedSha.isNotBlank() && recordedSha != expectedSha) continue
                    targetDir.mkdirs()
                    val copy = JSONObject(prev.toString())
                    copy.optJSONArray("pipelines")?.let { pipelines ->
                        for (idx in 0 until pipelines.length()) {
                            val row = pipelines.getJSONObject(idx)
                            if (PhoneCorpusRunner.Mode.fromPipelineId(row.optString("pipeline")) == mode) {
                                row.put("pipeline", mode.pipelineId)
                            }
                        }
                    }
                    copy.put("mode", mode.name)
                    copy.put("pipeline", mode.pipelineId)
                    copy.put("corpus_signature", signature)
                    atomic(File(targetDir, "$image.json"), copy.toString())
                    migrated++
                }
            }
            if (targetDir.isDirectory) {
                val records = all(mode)
                if (records.isNotEmpty() && !File(targetDir, "_capture.json").isFile) {
                    val build = inheritedBuild
                        ?: records.values.mapNotNull { it.optString("captured_build").takeIf(String::isNotBlank) }.firstOrNull()
                        ?: "unknown"
                    val capMs = inheritedMs
                        ?: records.values.mapNotNull { it.optLong("captured_ms").takeIf { ms -> ms > 0L } }.minOrNull()
                        ?: System.currentTimeMillis()
                    atomic(
                        File(targetDir, "_capture.json"),
                        JSONObject()
                            .put("mode", mode.name)
                            .put("pipeline", mode.pipelineId)
                            .put("corpus_signature", signature)
                            .put("count", records.size)
                            .put("build", build)
                            .put("captured_ms", capMs)
                            .put("updated_ms", System.currentTimeMillis())
                            .toString(),
                    )
                }
            }
        }
        return migrated
    }

    private fun atomic(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun anchor(corpusSignature: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(corpusSignature.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) }.take(16)
    }
}
