package com.yourapp.wine.textlab

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.textlab.evidence.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuleIterationDeviceTest {
    @Test fun newRulesAndBaselineCorrections() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("ruleIteration")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val input=JSONArray(File(root,"input.json").readText())
        assertEquals(168,input.length())
        val configs=EvidenceRankingConfig.experiments
        val hybrids=listOf("baseline-repair","baseline-cuvee-repair")
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val engines=configs.associateWith { EvidenceExtractor(kb,ranking=it) }
            val hybrid=EvidenceHybrid(kb)
            assertEquals(EvidenceRanking.nameKey("Domaine A & B"),EvidenceRanking.nameKey("DOMAINE A&B"))
            assertNotEquals(EvidenceRanking.nameKey("Saint-Émilion"),EvidenceRanking.nameKey("Saint-Émilion Grand Cru"))
            val box=RectF(.1f,.1f,.8f,.2f)
            fun token(engine:String)=TokenRecord("Cuvée des Astres","cuvee des astres",.95f,box,box,emptyList(),engine,"V0",true)
            val warm=engines.getValue(configs.last()).extract(listOf(token("mlkit"),token("paddle-v6-medium")),2026)
            assertTrue(warm.hypotheses.any { it.values[FieldId.WINE_NAME]=="Cuvee des Astres" })
            assertFalse(warm.hypotheses.any { it.values[FieldId.PRODUCER]=="Cuvee des Astres" })
            val phrase=Phrase(0,"Cuvée des Astres",setOf(0),box,"observed")
            val only=EvidenceGraph(listOf(Observation(0,token("mlkit")),Observation(1,token("mlkit"))),listOf(phrase),false)
            assertEquals(1,EvidenceRanking.independentEngines(phrase,only).size)
            val both=only.copy(observations=listOf(Observation(0,token("mlkit")),Observation(1,token("paddle-v6-medium"))))
            assertEquals(2,EvidenceRanking.independentEngines(phrase,both).size)
            val failures=mutableListOf<String>()
            File(root,"results.jsonl").bufferedWriter().use { writer ->
                for(i in 0 until input.length()) {
                    val item=input.getJSONObject(i);val image=item.getString("image")
                    val journal=TextLabJournal(setOf("candidate_3"),BuildConfig.BUILD_COMMIT,true)
                    val saved=item.getJSONArray("rows")
                    for(j in 0 until saved.length()) {
                        val row=saved.getJSONObject(j);journal.rows+=row.keys().asSequence().associateWith { row.getString(it) }
                    }
                    val records=TextLabFormPreview.records(journal)
                    val baseline=FieldId.entries.associateWith { item.getJSONObject("baseline_fields").getString(it.name.lowercase()) }
                    fun write(variant:String,fields:Map<FieldId,String>,elapsed:Long,result:EvidenceResult?=null) {
                        val values=JSONObject()
                        fields.forEach { (f,v) -> values.put(f.name.lowercase(),JSONObject().put("value",v).put("state",if(v.isBlank()) "RED" else "YELLOW")) }
                        val row=JSONObject().put("image",image).put("variant",variant).put("build",BuildConfig.BUILD_COMMIT)
                            .put("status","complete").put("fields",values).put("elapsed_ms",elapsed)
                        if(result!=null) row.put("trace",JSONArray(result.trace.rows)).put("trace_counts",JSONObject(result.trace.counts))
                        writer.appendLine(row.toString());writer.flush()
                    }
                    for(offset in configs.indices) {
                        val config=configs[(i+offset)%configs.size]
                        File(root,"progress.json").writeText(JSONObject().put("image",image).put("index",i+1).put("variant",config.id).toString())
                        try {
                            val result=engines.getValue(config).extract(records,2026)
                            write(config.id,result.fill.fields.mapValues { it.value.value },result.elapsedMs,result)
                            if(config.id=="rules-111") {
                                // Reuse this extraction; never execute the baseline or an extra old extractor.
                                for(name in hybrids) write(name,hybrid.combine(baseline,result,name=="baseline-cuvee-repair"),result.elapsedMs)
                            }
                        } catch(error:Exception) {
                            if(error is InterruptedException || error is kotlinx.coroutines.CancellationException) throw error
                            failures+="$image/${config.id}: $error"
                            writer.appendLine(JSONObject().put("image",image).put("variant",config.id).put("status","failed").put("error",error.toString()).toString());writer.flush()
                        }
                    }
                }
            }
            File(root,"validation.json").writeText(JSONObject().put("images",168).put("variants",JSONArray(configs.map { it.id }+hybrids))
                .put("corpus_budget_ms",EvidenceExtractor.DEFAULT_BUDGET_MS).put("baseline_executions",0).put("old_experimental_executions",0)
                .put("failures",JSONArray(failures)).toString(2))
            assertTrue("Rule iteration failures: ${failures.take(8)}",failures.isEmpty())
        } finally { kb.close() }
    }
}
