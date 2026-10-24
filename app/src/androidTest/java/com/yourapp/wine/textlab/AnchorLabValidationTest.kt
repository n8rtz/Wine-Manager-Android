package com.yourapp.wine.textlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.textlab.evidence.EvidenceAnchorHybrid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real lab preview routing on synthetic readings. No corpus, baseline extractor or OCR execution. */
@RunWith(AndroidJUnit4::class)
class AnchorLabValidationTest {
    @Test fun defaultOffAndExplicitHybridPreview() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("anchorLabValidation")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            assertFalse(TextLabState().anchorHybrid)
            val defaults=TextLabBatch(listOf(TextLabImageKey("synthetic","Synthetic")),setOf("candidate_3"),"test",true)
            assertFalse(defaults.anchorHybrid)
            assertEquals("baseline",defaults.journals.single().metadata["extraction_mode"])
            val batch=TextLabBatch(listOf(TextLabImageKey("synthetic","Synthetic")),setOf("candidate_3"),"test",true,true,true)
            val journal=batch.journals.single()
            assertEquals("baseline_and_anchor_hybrid_v1",journal.metadata["extraction_mode"])
            for(candidate in listOf("mlkit_reference","candidate_3")) {
                journal.add(candidate,"recognition",values=mapOf("raw_text" to "Cuvée des Astres","region_id" to "0",
                    "base_box_normalized" to "0.1 0.1 0.8 0.2","original_box_normalized" to "0.1 0.1 0.8 0.2",
                    "ctc_posterior" to "0.95","mlkit_confidence" to "0.95","confidence_estimated" to "true"))
            }
            val baseline=TextLabWineTable.fields.associateWith { when(it) {
                "producer" -> "Château de"
                "vintage" -> "2020"
                else -> ""
            } }
            journal.add("production_combined","form_summary","suggestions",baseline)
            val preserved=journal.rows.last()
            val engine=TextLabEvidencePreview.engine(kb,true)
            TextLabEvidencePreview.append(journal,engine,2026,true,EvidenceAnchorHybrid(kb))
            val summary=journal.rows.single { it["stage"]=="evidence_summary" }
            assertEquals("suggestions",summary["status"])
            assertEquals(EvidenceAnchorHybrid.VERSION,summary["post_processing_version"])
            assertEquals("",summary["producer"])
            assertEquals("RED",summary["producer_state"])
            assertEquals("2020",summary["vintage"])
            assertEquals("YELLOW",summary["vintage_state"])
            assertEquals("Cuvée des Astres",summary["wine_name"])
            assertFalse(TextLabWineTable.fields.any { summary["${it}_state"]=="GREEN" })
            assertEquals(preserved,journal.rows.single { it["stage"]=="form_summary" })
            assertTrue(TextLabWineTable.formRows(listOf(journal)).last()[1].startsWith("Guarded hybrid"))
            TextLabEvidencePreview.append(journal,engine,2026,true,EvidenceAnchorHybrid(kb))
            assertEquals(1,journal.rows.count { it["stage"]=="evidence_summary" })
            // Missing baseline is an explicit failure, not an implicit old extractor execution.
            val missing=TextLabJournal(setOf("candidate_3"),"test",true)
            TextLabEvidencePreview.append(missing,engine,2026,true,EvidenceAnchorHybrid(kb))
            assertEquals("failed",missing.rows.single()["status"])
            assertTrue(missing.rows.none { it["stage"]=="form_summary" })
        } finally { kb.close() }
    }
}
