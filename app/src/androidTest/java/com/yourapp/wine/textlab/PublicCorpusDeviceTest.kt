package com.yourapp.wine.textlab

import android.os.SystemClock
import com.yourapp.wine.capture.WineScannerServices
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.n8rtz.winetracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real Android OCR Lab replay. No mocked OCR, alternate weights or benchmark answers. */
@RunWith(AndroidJUnit4::class)
class PublicCorpusDeviceTest {
    @Test fun replayPrivateCorpus() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit corpus invocation required", args.getString("privateCorpus") == "true")
        withContext(Dispatchers.Default) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.filesDir, "private-corpus")
            val images = File(root, "images").listFiles().orEmpty()
                .filter { it.isFile && it.extension == "jpg" }.sortedBy { it.name }
            assertTrue("No corpus images staged", images.isNotEmpty())
            val output = File(root, "results").apply { mkdirs() }
            // One image per checkpoint limits retained rows/memory and preserves partial runs.
            var failures = 0
            val enginesWithText = mutableSetOf<String>()
            for (image in images) {
                val batch = TextLabBatch(listOf(TextLabImageKey(image.nameWithoutExtension, image.name)),
                    setOf("candidate_3"), BuildConfig.BUILD_COMMIT, true, true)
                val journal = batch.journals.single()
                journal.metadata["evaluation_runtime"] = "Android instrumentation; emulator timings are not phone performance"
                val preview = File(context.cacheDir, "corpus-preview.png")
                val crop = File(context.cacheDir, "corpus-crop.png")
                var status = "complete"
                try {
                    batch.save(output)
                    // Separate normal scanning entrypoint: retains its real 15-second shared budget,
                    // rectification, ensemble, merge and fill policy. Not the lab's 60-second path.
                    val start = SystemClock.elapsedRealtime()
                    val report = WineScannerServices.get(context).scan(image, start)
                    val fields = JSONObject()
                    report.fill.fields.forEach { (id, value) -> fields.put(id.name, JSONObject()
                        .put("value", value.value).put("state", value.state.name).put("reason", value.reason)) }
                    val tokens = JSONArray()
                    report.tokens.forEach { token -> tokens.put(JSONObject()
                        .put("raw", token.textRaw).put("engine", token.engineId)
                        .put("score", token.score).put("scoreEstimated", token.scoreIsEstimated)
                        .put("originalBox", token.bboxOrig.toString()).put("rectifiedBox", token.bboxRect.toString())) }
                    File(output, "${image.nameWithoutExtension}-production.json").writeText(JSONObject()
                        .put("image", image.name).put("build", BuildConfig.BUILD_COMMIT)
                        .put("pipeline", "OfflineWineScanner.scan; unchanged FILE input path")
                        .put("elapsedMs", report.elapsedMs).put("deadlineCancelled", report.deadlineCancelled)
                        .put("fields", fields).put("tokens", tokens)
                        .put("timings", JSONArray(report.timings.map { it.toString() }))
                        .put("diagnostics", report.diagnostics.toString())
                        .put("peakWorkingBitmaps", report.peakWorkingBitmaps)
                        .put("workingBitmapsAfter", report.workingBitmapsAfter).toString(2))
                    check(report.peakWorkingBitmaps <= 2 && report.workingBitmapsAfter == 0) { "Bitmap lifetime violation" }
                    val photo = TextLabRunner.preview(image, preview)
                    TextLabRunner.recognize(context, photo, TextCrop(0f, 0f, 1f, 1f), false,
                        crop, journal) { batch.save(output) }
                    val ml = journal.rows.lastOrNull { it["candidate"] == "mlkit_reference" && it["stage"] == "summary" }
                    val paddle = journal.rows.lastOrNull { it["candidate"] == "candidate_3" && it["stage"] == "candidate_summary" }
                    if (ml?.get("status") !in setOf("complete", "no_text") ||
                        paddle?.get("status") !in setOf("complete", "no_text", "no_regions")) status = "engine_incomplete"
                    journal.rows.filter { it["stage"] == "recognition" && !it["raw_text"].isNullOrBlank() }
                        .mapNotNullTo(enginesWithText) { it["candidate"] }
                    TextLabWineExtraction.append(context, batch)
                    if (journal.rows.any { it["stage"] in setOf("field_summary", "form_summary", "evidence_summary") && it["status"] == "failed" } ||
                        journal.rows.none { it["stage"] == "form_summary" } || journal.rows.none { it["stage"] == "evidence_summary" }) status = "extraction_failed"
                } catch (cancel: CancellationException) {
                    status = "cancelled"
                    throw cancel
                } catch (error: Exception) {
                    status = "failed"
                    journal.add("shared", "error", status, details = error.stackTraceToString().take(4000))
                } finally {
                    if (status != "complete") failures++
                    batch.finishImage(0, status)
                    journal.metadata["batch_status"] = status
                    batch.save(output)
                    preview.delete(); crop.delete()
                }
            }
            assertTrue("Both real engines must demonstrate recognition: $enginesWithText",
                enginesWithText.containsAll(setOf("mlkit_reference", "candidate_3")))
            assertTrue("$failures/${images.size} images had incomplete engine/extraction runs; inspect CSV", failures == 0)
        }
    }
}
