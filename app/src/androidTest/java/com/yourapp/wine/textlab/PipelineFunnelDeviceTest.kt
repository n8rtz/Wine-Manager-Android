package com.yourapp.wine.textlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourapp.wine.diagnostics.PipelineFunnel as F
import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.gazetteer.ProducerGazetteer
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.phonecorpus.PhoneCorpusFunnelJson
import com.yourapp.wine.phonecorpus.PhoneCorpusPipelineDiagnostics
import com.yourapp.wine.phonecorpus.PhoneCorpusRunner
import com.yourapp.wine.phonecorpus.PhoneCorpusScoreboard
import com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid
import com.yourapp.wine.textlab.evidence.ProducerRepair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Disposable emulator only. Real bundled SQLite + final preview/checkpoint/scorer/CSV; no OCR rerun. */
@RunWith(AndroidJUnit4::class)
class PipelineFunnelDeviceTest {
    @Test fun nativeAssetGatesAndDecisionsReachTheCheckpointAndCsv() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val setup = F.Recorder()
        ProducerGazetteer.open(context, onStep = { id, passed -> setup.visit(id, passed, "native asset step incomplete") }).use { index ->
            assertEquals(F.State.PASSED, setup.snapshot().getValue("asset.producer").state)
            assertEquals(F.State.PASSED, setup.snapshot().getValue("asset.wine").state)
            val journal = TextLabJournal(setOf("candidate_3"), "device-funnel-fixture", true)
            for (candidate in listOf("mlkit_reference", "candidate_3")) {
                for ((text, box) in listOf("Roche Mazet" to "0.1 0.1 0.8 0.2", "Cabernet Sauvignon" to "0.1 0.5 0.8 0.6")) {
                    journal.add(candidate, "recognition", values = mapOf("raw_text" to text, "base_box_normalized" to box,
                        "original_box_normalized" to box, "mlkit_confidence" to "0.9", "ctc_posterior" to "0.9", "confidence_estimated" to "false"))
                }
                journal.add(candidate, if (candidate == "mlkit_reference") "summary" else "candidate_summary", "complete")
            }
            val kb = FixtureKnowledgeBase()
            TextLabFormPreview.append(journal, FillEngine(kb), 2026)
            TextLabSpatialPreview.append(journal, TextLabEvidencePreview.engine(kb, true), EvidenceAnchorHybrid(kb), ProducerRepair(kb), 2026, index)
            val summary = journal.rows.single { it["stage"] == "spatial_evidence_summary" }
            assertEquals("Roche Mazet", summary["producer"])
            assertEquals("Cabernet Sauvignon", summary["wine_name"])
            assertEquals("supported_pair", summary["wine_gazetteer_status"])
            assertEquals("PASSED", summary["funnel.wine.independent.status"])
            assertEquals(4, journal.rows.count { it["stage"] == "recognition" })
            // The retired 168-photo debug corpus is gone; use the live 124-label corpus.
            val inputs = JSONObject(context.assets.open("wine-app-labels-v1/inputs.json").bufferedReader().use { it.readText() })
            val image = inputs.getJSONArray("images").getJSONObject(0).getString("image")
            val names = listOf("producer", "wine_name", "country", "region", "subregion", "vintage")
            val fields = names.associateWith { summary[it].orEmpty() }
            val output = JSONObject().put("image", image).put("attempt", 1).put("status", "complete")
                .put("pipelines", JSONArray().put(JSONObject().put("pipeline", PhoneCorpusRunner.Mode.LAB.pipelineId)
                    .put("status", "complete").put("fields", JSONObject(fields))))
            val label = PhoneCorpusPipelineDiagnostics.capture(image, 1, "LAB", journal.rows, setup.snapshot(), fields, "complete", true)
            PhoneCorpusFunnelJson.write(output, label)
            // Match the small checkpoint path: the scorer must not need raw OCR/trace arrays.
            output.remove("trace");output.remove("tokens")
            val restored = PhoneCorpusFunnelJson.read(JSONObject(output.toString()), "LAB", PhoneCorpusRunner.Mode.LAB.pipelineId)
            assertEquals(F.State.PASSED, restored.gates.getValue("wine.selected").state)
            val board = PhoneCorpusScoreboard(context).build(listOf(output), PhoneCorpusRunner.Mode.LAB)
            assertEquals(1, board.funnel.single { it.step.id == "wine.selected" }.passed)
            assertTrue(board.csv.any { it["row_type"] == "field" && it["pipeline"] == PhoneCorpusRunner.Mode.LAB.pipelineId && it["field"] == "producer" && it["recognized"] == "Roche Mazet" })
            val file = File.createTempFile("pipeline-funnel-", ".csv", context.cacheDir)
            try {
                PhoneCorpusScoreboard.export(board, file, mapOf("test_fixture" to "not a recognition accuracy run"))
                assertTrue(file.readText().contains("pipeline_step"))
                assertTrue(file.readText().contains("Cabernet Sauvignon"))
            } finally { file.delete() }
        }
    }
}
