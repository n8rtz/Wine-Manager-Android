package com.yourapp.wine.textlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourapp.wine.gazetteer.TrigramGazetteer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Authored, not executed: real DB/journal wiring check. Pure tagger cases run separately on a JVM. */
@RunWith(AndroidJUnit4::class)
class ResidualTaggingDeviceTest {
    @Test fun preservesReadingsAndForms() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("residualTagging")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(TextLabState().residualCandidates)
        val batch=TextLabBatch(listOf(TextLabImageKey("synthetic","Synthetic")),setOf("candidate_3"),"test",true,residualCandidates=true)
        val j=batch.journals.single()
        j.add("mlkit_reference","recognition",values=mapOf("raw_text" to "Astrel & Fils | Napa Valley | Cabernet Sauvignon | 2019 | 750ml",
            "region_id" to "0","base_box_normalized" to "0.1 0.1 0.9 0.2","original_box_normalized" to "0.1 0.1 0.9 0.2",
            "mlkit_confidence" to "0.95","confidence_estimated" to "true"))
        j.add("production_combined","form_summary","suggestions",TextLabWineTable.fields.associateWith { "baseline $it" })
        val prior=j.rows.toList();val form=TextLabWineTable.formRows(listOf(j))
        TrigramGazetteer.open(context).use { resolver -> TextLabResidualPreview.append(j,resolver,2026) }
        assertEquals(prior,j.rows.take(prior.size));assertEquals(form,TextLabWineTable.formRows(listOf(j)))
        assertEquals("true",j.metadata["residual_enabled"])
        val candidate=j.rows.single { it["stage"]=="residual_candidate" }
        assertEquals("Astrel & Fils",candidate["candidate_text"])
        assertEquals("unassigned",candidate["status"])
        assertEquals("not established",candidate["identity_confidence"])
        assertEquals("Astrel & Fils",candidate.getValue("raw_ocr_input").substring(candidate.getValue("span_start").toInt(),candidate.getValue("span_end").toInt()))
        for(kind in listOf("GEOGRAPHY","GRAPE","VINTAGE_CANDIDATE","PACKAGING")) assertTrue(j.rows.any { it["tag"]==kind && it["subtracted"]=="true" })
        assertTrue(TextLabResidualPreview.text(listOf(j)).contains("Astrel & Fils"))
        val before=j.rows.size
        TextLabResidualPreview.append(j,null,2026)
        assertEquals(before,j.rows.size) // Idempotent export.
        val missing=TextLabJournal(setOf("candidate_3"),"test",true)
        TextLabResidualPreview.append(missing,null,2026)
        assertEquals("failed",missing.rows.single()["status"])
        assertTrue(missing.rows.none { it["stage"]=="residual_candidate" })
    }
}
