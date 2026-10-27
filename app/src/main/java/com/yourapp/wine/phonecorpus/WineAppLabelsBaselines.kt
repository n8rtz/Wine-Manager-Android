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
 * Frozen Prod v1.0 / v2.0 / v3.0 outcomes for the 125-label benchmark.
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
    private val root = File(app.noBackupFilesDir, "wine-app-labels-v1/baselines-v1/$signature")

    private fun folder(mode: PhoneCorpusRunner.Mode) = File(root, mode.name)

    /** Frozen outcomes for this version, keyed by photo. Unreadable records are treated as missing. */
    fun all(mode: PhoneCorpusRunner.Mode): Map<String, JSONObject> {
        val files = folder(mode).listFiles().orEmpty().filter { it.extension == "json" && !it.name.startsWith("_") }
        val records = files.mapNotNull { file -> runCatching { JSONObject(file.readText()) }.getOrNull() }
            .filter { it.optBoolean("frozen") && it.optString("image").isNotBlank() }
        return records.associateBy { it.getString("image") }
    }

    fun record(mode: PhoneCorpusRunner.Mode, image: String): JSONObject? {
        val file = File(folder(mode), "$image.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.optBoolean("frozen") }
    }

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

    private fun index(mode: PhoneCorpusRunner.Mode): JSONObject? {
        val file = File(folder(mode), "_capture.json")
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

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
