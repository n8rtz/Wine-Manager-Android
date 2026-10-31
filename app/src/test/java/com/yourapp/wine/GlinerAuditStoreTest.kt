package com.yourapp.wine

import android.app.Application
import com.yourapp.wine.gliner.GlinerAuditStore
import com.yourapp.wine.gliner.GlinerAuditRow
import com.yourapp.wine.gliner.scoreGlinerAudit
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

    @Test fun bundledAuditHasCorrectedReferencesAndRescorableBaseline() {
        val document=GlinerAuditStore.readBundledDocument(app)
        val refs=GlinerAuditStore.parse(document)
        val predictions=document.getJSONArray("custom_images")
        assertEquals(26,refs.size)
        assertEquals(26,predictions.length())
        assertEquals("Sonoma County",refs[3].targets.getValue("region").display)
        assertEquals("Alexander Valley",refs[3].targets.getValue("subregion").display)
        assertEquals("expected_blank",refs[17].targets.getValue("subregion").status)
        assertEquals("Lodi",refs[22].targets.getValue("region").display)
        assertEquals("correct_value",GlinerAuditStore.compare(refs[16],mapOf("subregion" to "Uco Valley"),false)["subregion"])
        var fullyCorrect=0
        var correctFields=0
        val auditRows=mutableListOf<GlinerAuditRow>()
        for(i in refs.indices) {
            val row=predictions.getJSONObject(i)
            assertEquals(refs[i].image,row.getString("image"))
            val predicted=row.getJSONObject("fields")
            val values=PhoneCorpusMath.fields.associateWith { predicted.optString(it) }
            val outcomes=GlinerAuditStore.compare(refs[i],values,false)
            auditRows+=GlinerAuditRow(refs[i],"complete","",values,outcomes,false,row)
            correctFields+=outcomes.values.count(PhoneCorpusMath::correct)
            if(outcomes.values.all(PhoneCorpusMath::correct)) fullyCorrect++
            assertFalse(row.has("raw_readings"))
            assertFalse(row.has("ocr_lines"))
        }
        assertEquals(12,fullyCorrect)
        assertEquals(130,correctFields)
        val score=scoreGlinerAudit(auditRows)
        assertEquals(26,score.eligible)
        assertEquals(12,score.complete)
        assertEquals(130,score.fieldCorrect.values.sum())
        assertEquals(156,score.fieldTotal.values.sum())
        assertEquals(26,score.complete+score.oneError+score.multiError)
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
