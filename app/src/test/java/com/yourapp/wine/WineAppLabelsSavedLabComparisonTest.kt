package com.yourapp.wine

import com.yourapp.wine.phonecorpus.PhoneCorpusRunner
import com.yourapp.wine.phonecorpus.WineAppLabelsSavedLabComparison
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WineAppLabelsSavedLabComparisonTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pipeline = PhoneCorpusRunner.Mode.LAB.pipelineId

    private fun run(name: String, created: Long, signature: String, pipelineId: String = pipeline): File {
        val dir = temporary.newFolder(name)
        JSONObject().put("mode", "LAB").put("pipeline", pipelineId).put("build", name)
            .put("lab_modules_label", "config-$name").put("created_ms", created)
            .put("corpus_manifest", JSONObject().put("inputs_sha256", signature)
                .put("evaluation_set_sha256", "evaluation").put("scoring_sha256", "scoring"))
            .let { File(dir, "run.json").writeText(it.toString()) }
        return dir
    }

    private fun prediction(dir: File, image: String, sha: String, pipelineId: String = pipeline) {
        val row = JSONObject().put("image", image).put("image_sha256", sha).put("status", "complete")
            .put("pipelines", JSONArray().put(JSONObject().put("pipeline", pipelineId).put("status", "complete")))
        File(dir, "current").mkdirs()
        File(dir, "current/$image.json").writeText(row.toString())
    }

    @Test fun olderCorpusIsRescoredReadOnlyAndOnlyHashMatchedRowsSurvive() {
        val old = run("old", 1, "old-inputs")
        prediction(old, "a", "sha-a")
        prediction(old, "b", "wrong-sha")
        prediction(old, "c", "sha-c", "lab-v6-expanded-producers")
        File(old, "current/bad.json").writeText("not json")
        val original = File(old, "current/a.json").readText()
        run("new-empty", 2, "new-inputs")
        val oldPipeline = run("new-v6", 3, "new-inputs", "lab-v6-expanded-producers")
        prediction(oldPipeline, "d", "sha-d")

        val saved = WineAppLabelsSavedLabComparison.latest(temporary.root,
            mapOf("a" to "sha-a", "b" to "sha-b", "c" to "sha-c", "d" to "sha-d"),
            "new-inputs:evaluation:scoring")!!
        assertEquals(listOf("a"), saved.predictions.map { it.getString("image") })
        assertEquals("old", saved.build)
        assertEquals("config-old", saved.modules)
        assertTrue(saved.rescored)
        assertEquals(original, File(old, "current/a.json").readText())
        assertNull(WineAppLabelsSavedLabComparison.latest(temporary.root, mapOf("a" to "different"),
            "new-inputs:evaluation:scoring"))
    }

    @Test fun usesNewestNonemptyRunWithoutMixingConfigurations() {
        val old = run("older", 1, "same")
        prediction(old, "a", "sha-a")
        val recent = run("recent", 2, "same")
        prediction(recent, "b", "sha-b")
        val saved = WineAppLabelsSavedLabComparison.latest(temporary.root,
            mapOf("a" to "sha-a", "b" to "sha-b"), "same:evaluation:scoring")!!
        assertEquals("recent", saved.build)
        assertEquals(listOf("b"), saved.predictions.map { it.getString("image") })
        assertEquals(false, saved.rescored)
    }
}
