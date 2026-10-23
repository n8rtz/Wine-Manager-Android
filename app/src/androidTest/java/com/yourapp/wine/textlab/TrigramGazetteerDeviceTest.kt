package com.yourapp.wine.textlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.gazetteer.TrigramGazetteer
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrigramGazetteerDeviceTest {
    @Test fun realBundledTrigramAndPreparser() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("trigramGazetteer")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val input=JSONArray(File(root,"input.json").readText())
        val resolver=TrigramGazetteer.open(context)
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            assertFalse(TextLabState().compareGazetteer)
            val mismatches=mutableListOf<String>();val timings=mutableListOf<Long>()
            var positives=0;var correct=0;var negatives=0;var falsePositives=0
            for(i in 0 until input.length()) {
                val item=input.getJSONObject(i);val q=item.getString("query")
                val start=android.os.SystemClock.elapsedRealtime();val r=resolver.resolve(q)
                timings+=android.os.SystemClock.elapsedRealtime()-start
                val actual=r.accepted?.entity?.id
                val expected=if(item.isNull("expected_id")) null else item.getLong("expected_id")
                val host=if(item.isNull("host_id")) null else item.getLong("host_id")
                if(expected==null) { negatives++;if(actual!=null) falsePositives++ }
                else { positives++;if(actual==expected) correct++ }
                if(actual!=host || r.reason!=item.getString("host_reason")) mismatches+="$q: host=$host Android=$actual; ${r.reason}"
                File(root,"progress.json").writeText(JSONObject().put("index",i+1).put("cases",input.length()).toString())
            }
            val engine=FillEngine(kb)
            fun journal(texts:List<String>):TextLabJournal {
                val j=TextLabJournal(setOf("candidate_3"),"synthetic",true)
                texts.forEachIndexed { i,t ->
                    j.add("mlkit_reference","recognition",values=mapOf("raw_text" to t,"region_id" to i.toString(),
                        "base_box_normalized" to "0.1 ${.1+i*.2} 0.8 ${.2+i*.2}","original_box_normalized" to "0.1 ${.1+i*.2} 0.8 ${.2+i*.2}",
                        "mlkit_confidence" to "0.95","confidence_estimated" to "true"))
                }
                j.add("production_combined","form_summary","suggestions",mapOf("producer" to "baseline unchanged"))
                return j
            }
            val j=journal(listOf("Pauilac","Domaine des Astres","2019"))
            val originals=j.rows.toList()
            TextLabGazetteerPreview.append(j,resolver,engine,2026)
            assertEquals(originals,j.rows.take(originals.size))
            val summary=j.rows.single { it["stage"]=="gazetteer_summary" }
            assertEquals("suggestions",summary["status"])
            assertEquals("France",summary["country"]);assertEquals("Bordeaux",summary["region"]);assertEquals("Pauillac",summary["subregion"])
            assertNotEquals("Pauilac",summary["producer"]);assertEquals("2019",summary["vintage"])
            assertFalse(summary.any { it.key.endsWith("_state") && it.value=="GREEN" })
            assertTrue(j.rows.any { it["stage"]=="gazetteer_resolution" && it["category"]=="APPELLATION" && it["raw_ocr_input"]=="Pauilac" })
            assertTrue(TextLabWineTable.formRows(listOf(j)).last()[1].startsWith("Trigram gazetteer"))
            val california=resolver.resolve("California").accepted!!.entity
            assertEquals(mapOf(com.yourapp.wine.fill.FieldId.COUNTRY to "California",com.yourapp.wine.fill.FieldId.REGION to "",com.yourapp.wine.fill.FieldId.SUBREGION to ""),TextLabGazetteerPreview.geography(california))
            val oakville=TextLabGazetteerPreview.geography(resolver.resolve("Oakville").accepted!!.entity)
            assertEquals("Napa Valley",oakville[com.yourapp.wine.fill.FieldId.REGION]);assertEquals("Oakville",oakville[com.yourapp.wine.fill.FieldId.SUBREGION])
            val conflict=journal(listOf("Pauilac","Oakville"));TextLabGazetteerPreview.append(conflict,resolver,engine,2026)
            val conflictRow=conflict.rows.single { it["stage"]=="gazetteer_summary" }
            assertEquals("true",conflictRow["gazetteer_conflict"])
            for(f in listOf("country","region","subregion")) assertEquals("",conflictRow[f])
            val missing=journal(listOf("Pauilac"));TextLabGazetteerPreview.append(missing,null,engine,2026)
            assertEquals("failed",missing.rows.single { it["stage"]=="gazetteer_summary" }["status"])
            File(root,"validation.json").writeText(JSONObject().put("cases",input.length()).put("positive_cases",positives).put("correct",correct)
                .put("negative_cases",negatives).put("false_positives",falsePositives).put("mismatches",JSONArray(mismatches)).put("elapsed_ms",JSONArray(timings))
                .put("source_commit",BuildConfig.BUILD_COMMIT).put("synthetic_routing_passed",true).put("ocr_executions",0).put("corpus_baseline_executions",0).toString(2))
            assertTrue("Host/native resolver mismatches: $mismatches",mismatches.isEmpty())
            assertEquals(0,falsePositives);assertTrue(correct.toDouble()/positives>=.9)
        } finally { kb.close();resolver.close() }
    }
}
