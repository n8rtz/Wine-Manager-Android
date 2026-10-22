package com.yourapp.wine.textlab

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.textlab.evidence.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchSweepDeviceTest {
    @Test fun sweepOnlyRevisedSearches() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("searchSweep")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val input=JSONArray(File(root,"input.json").readText())
        assertEquals(168,input.length())
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            // Synthetic regression: repeated support consumes all 16 slots, hiding
            // the only producer compatible with the high-ranked wine interpretation.
            val observations=(0..16).map { n ->
                val box=if(n==16) RectF(.1f,.7f,.6f,.8f) else RectF(.1f,.1f,.6f,.2f)
                Observation(n,TokenRecord("Synthetic", "synthetic",.95f,box,box,emptyList(),"mlkit","V0",true))
            }
            val phrases=observations.map { Phrase(it.id,"Synthetic",setOf(it.id),it.record.bboxRect,"observed") }
            val graph=EvidenceGraph(observations,phrases,false)
            val hypotheses=(0..16).map { n -> Hypothesis(mapOf(FieldId.PRODUCER to if(n==16) "Distinct Maker" else "Repeated Maker"),phrases[n],if(n==16) 49.0 else 50.0,"synthetic") }+
                Hypothesis(mapOf(FieldId.WINE_NAME to "Distinct Wine"),phrases[0],100.0,"synthetic")
            val hierarchy=PlaceHierarchy(kb).snapshot()
            fun solve(config:EvidenceSearchConfig)=EvidenceSolver.solve(hypotheses,hierarchy,graph,{},EvidenceTrace(),config)
            assertTrue(solve(EvidenceSearchConfig()).first().none { FieldId.PRODUCER in it.values })
            for(config in EvidenceSearchConfig.experiments) {
                val solution=solve(config).first()
                if(config.id=="beam-16-256") {
                    assertTrue(solution.none { FieldId.PRODUCER in it.values })
                    continue
                }
                assertEquals("Distinct Maker",solution.first { FieldId.PRODUCER in it.values }.values[FieldId.PRODUCER])
                assertEquals(149.0,solution.sumOf { it.rank },0.0)
            }
            assertThrows(SearchSpaceTooLargeException::class.java) { solve(EvidenceSearchConfig(exhaustiveMaxCombinations=1)) }
            val engines=EvidenceSearchConfig.experiments.associateWith { EvidenceExtractor(kb,search=it) }
            val warm=TokenRecord("Domaine des Asters","domaine des asters",.95f,RectF(.1f,.1f,.8f,.2f),RectF(.1f,.1f,.8f,.2f),emptyList(),"mlkit","V0",true)
            engines.values.first().extract(listOf(warm),2026)
            val failures=mutableListOf<String>()
            File(root,"results.jsonl").bufferedWriter().use { writer ->
                for(i in 0 until input.length()) {
                    val item=input.getJSONObject(i);val image=item.getString("image")
                    val journal=TextLabJournal(setOf("candidate_3"),BuildConfig.BUILD_COMMIT,true)
                    val saved=item.getJSONArray("rows")
                    for(j in 0 until saved.length()) {
                        val row=saved.getJSONObject(j)
                        journal.rows+=row.keys().asSequence().associateWith { row.getString(it) }
                    }
                    val records=TextLabFormPreview.records(journal)
                    // Rotate execution order deterministically to reduce fixed-order timing bias.
                    val configs=EvidenceSearchConfig.experiments
                    for(offset in configs.indices) {
                        val config=configs[(i+offset)%configs.size]
                        File(root,"progress.json").writeText(JSONObject().put("index",i+1).put("image",image).put("variant",config.id).toString())
                        val row=JSONObject().put("image",image).put("variant",config.id).put("build",BuildConfig.BUILD_COMMIT)
                            .put("candidate_limit",config.candidateLimit).put("beam_limit",config.beamLimit)
                            .put("diversify",config.diversify).put("exhaustive_cap",config.exhaustiveMaxCombinations ?: JSONObject.NULL)
                        val started=System.nanoTime()
                        try {
                            val result=engines.getValue(config).extract(records,2026)
                            val fields=JSONObject()
                            for(field in FieldId.entries) {
                                val v=result.fill.fields.getValue(field)
                                fields.put(field.name.lowercase(),JSONObject().put("value",v.value).put("state",v.state.name).put("reason",v.reason))
                            }
                            row.put("status","complete").put("fields",fields)
                                .put("trace",JSONArray(result.trace.rows)).put("trace_counts",JSONObject(result.trace.counts))
                        } catch(skip:SearchSpaceTooLargeException) {
                            row.put("status","skipped_space_limit").put("combinations",skip.combinations)
                        } catch(error:Exception) {
                            if(error is InterruptedException || error is kotlinx.coroutines.CancellationException) throw error
                            failures+="$image/${config.id}: $error"
                            row.put("status","failed").put("error",error.toString())
                        }
                        row.put("elapsed_ms",(System.nanoTime()-started)/1_000_000)
                        writer.appendLine(row.toString());writer.flush()
                    }
                }
            }
            File(root,"validation.json").writeText(JSONObject().put("images",168).put("variants",JSONArray(EvidenceSearchConfig.experiments.map { it.id }))
                .put("corpus_budget_ms",EvidenceExtractor.DEFAULT_BUDGET_MS).put("baseline_executions",0)
                .put("old_experimental_executions",0).put("failures",JSONArray(failures)).toString(2))
            assertTrue("Search replay failures: ${failures.take(8)}",failures.isEmpty())
        } finally { kb.close() }
    }
}
