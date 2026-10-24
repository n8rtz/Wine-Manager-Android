package com.yourapp.wine.textlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.textlab.evidence.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProducerLabValidationTest {
    @Test fun labRoutingAndCachedOutputParity() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("producerLabValidation")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            assertFalse(TextLabState().repairProducer)
            val repair=ProducerRepair(kb)
            val engine=TextLabEvidencePreview.engine(kb,true)
            fun journal(enabled:Boolean):TextLabJournal {
                val batch=TextLabBatch(listOf(TextLabImageKey("synthetic","Synthetic")),setOf("candidate_3"),"test",true,true,true,enabled)
                val j=batch.journals.single()
                for(candidate in listOf("mlkit_reference","candidate_3")) for((n,text) in listOf("Domaine des Astres","Cuvée des Comètes").withIndex()) {
                    j.add(candidate,"recognition",values=mapOf("raw_text" to text,"region_id" to n.toString(),
                        "base_box_normalized" to if(n==0) "0.1 0.1 0.8 0.2" else "0.1 0.4 0.8 0.5",
                        "ctc_posterior" to "0.95","mlkit_confidence" to "0.95","confidence_estimated" to "true"))
                }
                j.add("production_combined","form_summary","suggestions",TextLabWineTable.fields.associateWith { if(it=="vintage") "2020" else "" })
                TextLabEvidencePreview.append(j,engine,2026,true,EvidenceAnchorHybrid(kb),enabled,if(enabled) repair else null)
                return j
            }
            val control=journal(false);val selected=journal(true)
            assertEquals("baseline_and_producer_repair_v2",selected.metadata["extraction_mode"])
            val a=control.rows.single { it["stage"]=="evidence_summary" };val b=selected.rows.single { it["stage"]=="evidence_summary" }
            assertEquals("suggestions",b["status"])
            assertEquals("",a["producer"]);assertEquals("Domaine des Astres",b["producer"])
            for(f in TextLabWineTable.fields.filter { it!="producer" }) assertEquals(a[f],b[f])
            assertEquals("YELLOW",b["producer_state"]);assertEquals(ProducerRepair.VERSION,b["post_processing_version"])
            assertTrue(selected.rows.single { it["stage"]=="form_summary" }["producer"].isNullOrEmpty())
            assertTrue(TextLabWineTable.formRows(listOf(selected)).last()[1].startsWith("Producer repair"))
            val missing=TextLabJournal(setOf("candidate_3"),"test",true)
            TextLabEvidencePreview.append(missing,engine,2026,true,EvidenceAnchorHybrid(kb),true,null)
            assertEquals("failed",missing.rows.single()["status"])
            val input=JSONArray(File(root,"input.json").readText());assertEquals(168,input.length())
            val mismatches=mutableListOf<String>();val timings=mutableListOf<Long>()
            for(i in 0 until input.length()) {
                val item=input.getJSONObject(i);val j=TextLabJournal(setOf("candidate_3"),BuildConfig.BUILD_COMMIT,true)
                val saved=item.getJSONArray("rows")
                for(k in 0 until saved.length()) { val r=saved.getJSONObject(k);j.rows+=r.keys().asSequence().associateWith { r.getString(it) } }
                val start=android.os.SystemClock.elapsedRealtime()
                val result=repair.repair(item.getJSONObject("anchor_fields").getString("producer"),TextLabFormPreview.records(j),emptyList(),ProducerRepair.recommendedConfig)
                timings+=android.os.SystemClock.elapsedRealtime()-start
                if(result.value!=item.getString("preceding_producer_output")) mismatches+=item.getString("image")
                File(root,"progress.json").writeText(JSONObject().put("image",item.getString("image")).put("index",i+1).toString())
            }
            File(root,"validation.json").writeText(JSONObject().put("images",168).put("mismatches",JSONArray(mismatches)).put("elapsed_ms",JSONArray(timings))
                .put("source_commit",BuildConfig.BUILD_COMMIT).put("lab_synthetic_passed",true).put("baseline_executions",0).put("old_experimental_executions",0).toString(2))
            assertTrue("Cached producer parity mismatches: $mismatches",mismatches.isEmpty())
        } finally { kb.close() }
    }
}
