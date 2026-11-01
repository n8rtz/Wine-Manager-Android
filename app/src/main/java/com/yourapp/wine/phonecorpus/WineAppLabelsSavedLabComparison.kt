package com.yourapp.wine.phonecorpus

import org.json.JSONObject
import java.io.File

/** Read a single prior Lab v7 run for comparison only; never alter its checkpoints or metadata. */
internal object WineAppLabelsSavedLabComparison {
    data class SavedRun(
        val predictions: List<JSONObject>,
        val build: String,
        val modules: String,
        val rescored: Boolean,
    )

    fun latest(root: File, expectedHashes: Map<String, String>, currentCorpus: String): SavedRun? {
        val candidates = root.listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            val metadata = runCatching { JSONObject(File(dir, "run.json").readText()) }.getOrNull()
                ?: return@mapNotNull null
            // Do not relabel a v4/v5/v6 result as Lab v7, or merge unrelated runs/configurations.
            if (metadata.optString("mode") != PhoneCorpusRunner.Mode.LAB.name ||
                metadata.optString("pipeline") != PhoneCorpusRunner.Mode.LAB.pipelineId) return@mapNotNull null
            val manifest = metadata.optJSONObject("corpus_manifest") ?: return@mapNotNull null
            val signature = listOf("inputs_sha256", "evaluation_set_sha256", "scoring_sha256")
                .map { manifest.optString(it) }
            if (signature.any { it.isBlank() }) return@mapNotNull null
            Triple(dir, metadata, signature.joinToString(":"))
        }.sortedByDescending { (_, metadata, _) -> metadata.optLong("created_ms") }

        for ((dir, metadata, signature) in candidates) {
            val predictions = File(dir, "current").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "json" }.sortedBy { it.name }
                .mapNotNull { file ->
                    val row = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
                    val image = row.optString("image")
                    val expected = expectedHashes[image] ?: return@mapNotNull null
                    if (row.optString("image_sha256") != expected) return@mapNotNull null
                    val pipelines = row.optJSONArray("pipelines") ?: return@mapNotNull null
                    if ((0 until pipelines.length()).none {
                        pipelines.optJSONObject(it)?.optString("pipeline") == PhoneCorpusRunner.Mode.LAB.pipelineId
                    }) return@mapNotNull null
                    row
                }.distinctBy { it.getString("image") }
            if (predictions.isNotEmpty()) return SavedRun(
                predictions = predictions,
                build = metadata.optString("build").ifBlank { "unknown" },
                modules = metadata.optString("lab_modules_label").ifBlank {
                    metadata.optString("lab_modules_key").ifBlank { "unknown" }
                },
                rescored = signature != currentCorpus,
            )
        }
        return null
    }
}
