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
class ProducerRepairDeviceTest {
    @Test fun producerRepairExperiments() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("producerRepair")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val input=JSONArray(File(root,"input.json").readText())
        assertEquals(168,input.length())
        val configs=ProducerRepair.experiments
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val repair=ProducerRepair(kb)
            val box=RectF(.1f,.1f,.8f,.2f)
            fun token(text:String,engine:String="mlkit",rect:RectF=box)=TokenRecord(text,text,.95f,rect,rect,emptyList(),engine,"V0",true)
            val both=listOf(token("Domaine des Astres"),token("Domaine des Astres","paddle-v6-medium"))
            val strict=configs[0]
            assertEquals("Domaine des Astres",repair.repair("",both,emptyList(),strict).value)
            assertEquals("",repair.repair("",listOf(both[0],both[0]),emptyList(),strict).value)
            assertEquals("",repair.repair("",listOf(both[0],token("Domaine des Astres","paddle-v6-medium",RectF(.1f,.7f,.8f,.8f))),emptyList(),strict).value)
            assertEquals("Domaine des Aurores",repair.repair("Domaine des Aurores",both,emptyList(),strict).value)
            assertEquals("",repair.repair("",emptyList(),listOf("Domaine des Astres"),strict).value)
            assertEquals("",repair.repair("",listOf(token("Mis en bouteille par Domaine des Astres")),emptyList(),configs[1]).value)
            assertEquals("",repair.repair("",listOf(token("Cuvée des Astres")),emptyList(),configs[1]).value)
            assertEquals("",repair.repair("",both+listOf(token("Domaine des Aurores"),token("Domaine des Aurores","paddle-v6-medium")),emptyList(),strict).value)
            assertEquals("",repair.repair("",listOf(token("La Cave")),emptyList(),configs[1]).value)
            val split=listOf(token("Domaine"),token("des Astres",rect=RectF(.1f,.21f,.8f,.31f)))
            assertEquals("",repair.repair("",split,emptyList(),configs[1]).value)
            assertEquals("Domaine des Astres",repair.repair("",split,emptyList(),configs[2]).value)
            assertEquals("Domaine des Astres",repair.repair("Mis en bouteille à la propriété Domaine des Astres",both,emptyList(),strict).value)
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
                    val anchor=FieldId.entries.associateWith { item.getJSONObject("anchor_fields").getString(it.name.lowercase()) }
                    val savedAlternatives=item.getJSONArray("producer_alternatives")
                    val alternatives=(0 until savedAlternatives.length()).map { savedAlternatives.getString(it) }
                    fun write(variant:String,fields:Map<FieldId,String>,elapsed:Long,decision:ProducerRepair.Decision) {
                        val values=JSONObject()
                        fields.forEach { (f,v) -> values.put(f.name.lowercase(),JSONObject().put("value",v).put("state",if(v.isBlank()) "RED" else "YELLOW")) }
                        val row=JSONObject().put("image",image).put("variant",variant).put("build",BuildConfig.BUILD_COMMIT)
                            .put("status","complete").put("fields",values).put("elapsed_ms",elapsed)
                            .put("producer_reason",decision.reason).put("producer_candidates",JSONArray(decision.candidates.map { JSONObject(it) }))
                        writer.appendLine(row.toString());writer.flush()
                    }
                    for(offset in configs.indices) {
                        val config=configs[(i+offset)%configs.size]
                        File(root,"progress.json").writeText(JSONObject().put("image",image).put("index",i+1).put("variant",config.id).toString())
                        try {
                            val start=android.os.SystemClock.elapsedRealtime()
                            val decision=repair.repair(anchor.getValue(FieldId.PRODUCER),records,alternatives,config)
                            val fields=anchor+(FieldId.PRODUCER to decision.value)
                            val elapsed=android.os.SystemClock.elapsedRealtime()-start
                            check(elapsed<=60000L) { "Producer repair exceeded 60-second diagnostic budget" }
                            write(config.id,fields,elapsed,decision)
                        } catch(error:Exception) {
                            if(error is InterruptedException || error is kotlinx.coroutines.CancellationException) throw error
                            failures+="$image/${config.id}: $error"
                            writer.appendLine(JSONObject().put("image",image).put("variant",config.id).put("status","failed").put("error",error.toString()).toString());writer.flush()
                        }
                    }
                }
            }
            File(root,"validation.json").writeText(JSONObject().put("images",168).put("variants",JSONArray(configs.map { it.id }))
                .put("corpus_budget_ms",60000L).put("baseline_executions",0).put("old_experimental_executions",0)
                .put("failures",JSONArray(failures)).toString(2))
            assertTrue("Producer repair failures: ${failures.take(8)}",failures.isEmpty())
        } finally { kb.close() }
    }
}
