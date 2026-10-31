package com.yourapp.wine

import android.app.Application
import com.yourapp.wine.gliner.GlinerAuditStore
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class GlinerAuditStoreTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    private val hash="a".repeat(64)
    private val id="custom-$hash.img"
    @Before fun reset() { File(app.noBackupFilesDir,"gliner-audit").deleteRecursively() }
    @After fun cleanup() { reset() }

    private fun exported():JSONObject {
        val reviews=JSONObject()
        for(field in PhoneCorpusMath.fields) reviews.put(field,JSONObject()
            .put("expected_status",if(field=="subregion") "expected_blank" else "expected_value")
            .put("expected",if(field=="subregion") "" else "Côtes du Rhône")
            .put("verdict",if(field=="subregion") "incorrect" else "correct"))
        return JSONObject().put("custom_images",JSONArray().put(JSONObject().put("image",id)
            .put("sha256",hash).put("source_filename","wine.jpg")
            .put("fields",JSONObject().put("subregion","")).put("manual_review",reviews)))
    }

    @Test fun referencesRoundTripAndExpectedBlanksAreNotUnreviewed() {
        val refs=GlinerAuditStore.parse(exported())
        assertEquals(1,refs.single().warnings.size)
        assertEquals("expected_blank",refs.single().targets.getValue("subregion").status)
        val augmented=refs.single().copy(targets=refs.single().targets +
            ("region" to GlinerAuditStore.target("expected_value","Valle de Uco",listOf("Uco Valley"))))
        val references=listOf(augmented)
        GlinerAuditStore.saveReferences(app,references)
        val restored=GlinerAuditStore.loadReferences(app)
        assertEquals(references,restored)
        val scores=GlinerAuditStore.compare(restored.single(),mapOf("producer" to "Cotes du Rhone", "region" to "Uco Valley", "subregion" to ""),false)
        assertEquals("correct_value",scores["region"])
        assertEquals("correct_value",scores["producer"])
        assertEquals("correct_blank",scores["subregion"])
        assertEquals("execution_failed",GlinerAuditStore.compare(restored.single(),emptyMap(),true)["subregion"])
    }

    @Test fun separateRunsKeepTheirOwnReferencesAndCheckpoints() {
        val refs=GlinerAuditStore.parse(exported())
        val payload=JSONObject().put("audit_references",JSONArray(refs.map(GlinerAuditStore::referenceJson)))
            .put("audit_results",JSONArray()).put("status","running")
        GlinerAuditStore.saveRun(app,"1234-1234abcd",payload)
        payload.put("status","complete")
        GlinerAuditStore.saveRun(app,"1234-1234abcd",payload)
        GlinerAuditStore.saveRun(app,"1235-1234abcd",JSONObject().put("status","stopped"))
        val history=GlinerAuditStore.history(app)
        assertEquals(2,history.size)
        assertEquals("complete",history[0].getString("status"))
        assertEquals("stopped",GlinerAuditStore.loadLatest(app)?.getString("status"))
    }

    @Test fun rejectsMismatchedIdentityAndMalformedReferences() {
        val input=exported()
        input.getJSONArray("custom_images").getJSONObject(0).put("image","../../other")
        assertTrue(runCatching { GlinerAuditStore.parse(input) }.isFailure)
        val empty=exported()
        empty.getJSONArray("custom_images").getJSONObject(0).getJSONObject("manual_review")
            .getJSONObject("producer").put("expected","")
        assertTrue(runCatching { GlinerAuditStore.parse(empty) }.isFailure)
        assertFalse(File(app.noBackupFilesDir,"outside").exists())
    }
}
