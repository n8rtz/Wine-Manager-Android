package com.yourapp.wine.phonecorpus

import android.app.Application
import android.util.Log
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.textlab.PaddleCandidates
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-photo cache of verified OCR model outputs (ML Kit + Paddle v6 Medium) for the 125-label
 * benchmark so Lab v4 extraction rules can be iterated without re-running both OCR models on every
 * pass when the user checks "Cached OCR" next to Lab v4.
 *
 * Keyed by corpus signature + OCR model/threshold signature (never by build SHA or install time),
 * so cached OCR outputs survive app updates while invalidating automatically if any corpus image
 * or packaged OCR model changes. Prod baselines never read from this cache.
 */
internal class WineAppLabelsOcrCache(
    app: Application,
    corpusSignature: String,
) {
    data class Entry(
        val image: String,
        val imageSha256: String,
        val metadata: Map<String, String>,
        val rows: List<Map<String, String>>,
        val capturedBuild: String,
        val capturedMs: Long,
    )

    private val signature = anchor("$corpusSignature|ocr-cache-v1|${modelSignature()}")
    private val dir = File(app.noBackupFilesDir, "wine-app-labels-v1/ocr-cache-v1/$signature")
    private val verifiedShas = ConcurrentHashMap<String, String>()

    fun entry(image: String, expectedSha256: String): Entry? {
        val file = File(dir, "$image.json")
        if (!file.isFile) {
            verifiedShas.remove(image)
            return null
        }
        return runCatching {
            val obj = JSONObject(file.readText())
            if (!obj.optBoolean("ocr_cached")) return null
            if (obj.optString("cache_signature") != signature) return null
            if (obj.optString("image") != image) return null
            if (obj.optString("image_sha256") != expectedSha256) return null
            val metaObj = obj.optJSONObject("metadata") ?: JSONObject()
            val metadata = linkedMapOf<String, String>()
            for (key in metaObj.keys()) {
                metadata[key] = metaObj.optString(key)
            }
            val rowsArr = obj.optJSONArray("rows") ?: return null
            val rawRows = (0 until rowsArr.length()).map { idx ->
                val rowObj = rowsArr.getJSONObject(idx)
                val map = linkedMapOf<String, String>()
                for (key in rowObj.keys()) {
                    val value = rowObj.optString(key)
                    map[key] = if (key == "raw_text") TextNorm.scrub(value) else value
                }
                map as Map<String, String>
            }
            val validRows = extractValidOcrRows(rawRows) ?: return null
            verifiedShas[image] = expectedSha256
            Entry(
                image = image,
                imageSha256 = expectedSha256,
                metadata = metadata,
                rows = validRows,
                capturedBuild = obj.optString("captured_build").ifBlank { "unknown" },
                capturedMs = obj.optLong("captured_ms"),
            )
        }.getOrNull()
    }

    fun count(expectedHashes: Map<String, String>): Int =
        expectedHashes.count { (image, sha) -> hasValidFile(image, sha) }

    private fun hasValidFile(image: String, expectedSha256: String): Boolean {
        if (verifiedShas[image] == expectedSha256 && File(dir, "$image.json").isFile) return true
        return entry(image, expectedSha256) != null
    }

    /**
     * Saves pre-extraction OCR metadata and stage rows for [image] if and only if both ML Kit and
     * Paddle Medium completed cleanly and produced structurally valid readings.
     */
    fun save(
        image: String,
        imageSha256: String,
        metadata: Map<String, String>,
        rows: List<Map<String, String>>,
        capturedBuild: String = BuildConfig.BUILD_COMMIT,
    ): Boolean {
        val validRows = extractValidOcrRows(rows) ?: return false
        dir.mkdirs()
        val filteredMeta = linkedMapOf<String, String>()
        for (key in PRESERVED_METADATA_KEYS) {
            val value = metadata[key]
            if (!value.isNullOrBlank()) filteredMeta[key] = value
        }
        if (filteredMeta["input_sha256"].isNullOrBlank()) {
            filteredMeta["input_sha256"] = imageSha256
        }
        val record = JSONObject()
            .put("ocr_cached", true)
            .put("cache_signature", signature)
            .put("image", image)
            .put("image_sha256", imageSha256)
            .put("captured_build", capturedBuild)
            .put("captured_ms", System.currentTimeMillis())
            .put("metadata", JSONObject(filteredMeta))
            .put("rows", JSONArray(validRows.map { JSONObject(it) }))
        atomic(File(dir, "$image.json"), record.toString())
        verifiedShas[image] = imageSha256
        return true
    }

    /**
     * Seeds missing OCR cache entries from completed attempts already stored on this device under
     * the same verified corpus signature, so upgrading immediately makes prior 125-label OCR runs
     * reusable without requiring another cold OCR pass first.
     */
    fun promote(runFolders: List<File>, expectedHashes: Map<String, String>): Int {
        var promoted = 0
        val missing = expectedHashes.filterKeys { image ->
            val sha = expectedHashes.getValue(image)
            !hasValidFile(image, sha)
        }.toMutableMap()
        if (missing.isEmpty()) return 0

        for (folder in runFolders) {
            if (missing.isEmpty()) break
            val attempts = File(folder, "attempts").listFiles().orEmpty()
                .filter { it.extension == "json" }
                .sortedByDescending { it.lastModified() }
            for (file in attempts) {
                if (missing.isEmpty()) break
                val candidateImage = file.name.substringBefore("-")
                if (candidateImage.endsWith(".jpg") && candidateImage !in missing) continue
                val attempt = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
                val image = attempt.optString("image").ifBlank { candidateImage }
                val expectedSha = missing[image] ?: continue
                if (attempt.optString("status") != "complete" || attempt.optBoolean("engine_incomplete")) continue
                val recordedSha = attempt.optString("image_sha256")
                if (recordedSha.isNotBlank() && recordedSha != expectedSha) continue
                val traceArr = attempt.optJSONArray("trace") ?: continue
                val traceRows = (0 until traceArr.length()).map { idx ->
                    val rowObj = traceArr.getJSONObject(idx)
                    val map = linkedMapOf<String, String>()
                    for (key in rowObj.keys()) {
                        map[key] = rowObj.optString(key)
                    }
                    map as Map<String, String>
                }
                val savedOk = save(
                    image = image,
                    imageSha256 = expectedSha,
                    metadata = mapOf("input_sha256" to expectedSha),
                    rows = traceRows,
                    capturedBuild = attempt.optString("build").ifBlank { BuildConfig.BUILD_COMMIT },
                )
                if (savedOk) {
                    missing.remove(image)
                    promoted++
                }
            }
        }
        if (promoted > 0) {
            Log.d("WineTracker", "Promoted $promoted cached OCR label(s) from prior run attempts")
        }
        return promoted
    }

    companion object {
        private val OCR_CANDIDATES = setOf("shared", "mlkit_reference", "candidate_3")
        private val POST_OCR_STAGES = setOf("knowledge_setup", "field_summary")
        private val MLKIT_DONE = setOf("complete", "no_text")
        private val PADDLE_DONE = setOf("complete", "no_text", "no_regions")
        private val PRESERVED_METADATA_KEYS = listOf(
            "services_setup_ms",
            "scan_lock_wait_ms",
            "input_sha256",
            "crop_sha256",
            "original_width",
            "original_height",
            "working_width",
            "working_height",
            "crop_x",
            "crop_y",
            "crop_width",
            "crop_height",
            "input_width",
            "input_height",
            "enlarge",
            "base_to_original",
            "original_to_base",
            "preparation_ms",
            "auto_zoom_crop",
            "auto_zoom_crop_skipped",
            "device",
            "android_api",
        )

        internal fun extractValidOcrRows(rows: List<Map<String, String>>): List<Map<String, String>>? {
            val ocrRows = rows.filter { row ->
                row["candidate"] in OCR_CANDIDATES && row["stage"] !in POST_OCR_STAGES
            }.map { row ->
                if ("raw_text" in row) {
                    val copy = LinkedHashMap(row)
                    copy["raw_text"] = TextNorm.scrub(copy["raw_text"].orEmpty())
                    copy
                } else {
                    row
                }
            }
            val mlSummary = ocrRows.lastOrNull {
                it["candidate"] == "mlkit_reference" && it["stage"] == "summary"
            } ?: return null
            if (mlSummary["status"] !in MLKIT_DONE) return null

            val spec = PaddleCandidates.all.single { it.id == "candidate_3" }
            val paddleSummary = ocrRows.lastOrNull {
                it["candidate"] == "candidate_3" && it["stage"] == "candidate_summary"
            } ?: return null
            if (paddleSummary["status"] !in PADDLE_DONE) return null

            val detSha = paddleSummary["detector_sha256"].orEmpty()
            val recSha = paddleSummary["recognizer_sha256"].orEmpty()
            val dictSha = paddleSummary["dictionary_sha256"].orEmpty()
            if (detSha.isNotBlank() && detSha != spec.detector.sha) return null
            if (recSha.isNotBlank() && recSha != spec.recognizer.sha) return null
            if (dictSha.isNotBlank() && dictSha != spec.dictionary.sha) return null

            if (!hasValidReadings(ocrRows)) return null
            return ocrRows
        }

        private fun hasValidReadings(ocrRows: List<Map<String, String>>): Boolean {
            for (candidate in listOf("mlkit_reference", "candidate_3")) {
                val paddle = candidate == "candidate_3"
                val scoreKey = if (paddle) "ctc_posterior" else "mlkit_confidence"
                for (row in ocrRows) {
                    if (row["candidate"] != candidate) continue
                    if (!row["pass_id"].isNullOrEmpty()) continue
                    if (row["stage"] != "recognition") continue
                    if (row["status"] !in setOf("complete", "greedy_fallback")) continue
                    if (row["raw_text"].isNullOrBlank()) continue
                    if (!isValidNormalizedBox(row["base_box_normalized"])) return false
                    val origBox = row["original_box_normalized"]
                    if (!origBox.isNullOrBlank() && !isValidNormalizedBox(origBox)) return false
                    val score = row[scoreKey]?.toFloatOrNull() ?: return false
                    if (!score.isFinite() || score !in 0f..1f) return false
                }
            }
            return true
        }

        private fun isValidNormalizedBox(value: String?): Boolean {
            val p = value?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() } ?: return false
            return p.size == 4 && p.all { it.isFinite() && it in 0f..1f } && p[2] > p[0] && p[3] > p[1]
        }

        private fun modelSignature(): String {
            val paddle = PaddleCandidates.all.joinToString("|") { spec ->
                listOf(
                    spec.id,
                    spec.detector.sha,
                    spec.recognizer.sha,
                    spec.dictionary.sha,
                    spec.threshold.toString(),
                    spec.boxThreshold.toString(),
                    spec.unclip.toString(),
                ).joinToString(":")
            }
            return "mlkit-latin-bundled-v1|$paddle"
        }

        private fun anchor(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return digest.joinToString("") { "%02x".format(it.toInt() and 255) }.take(16)
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
    }
}
