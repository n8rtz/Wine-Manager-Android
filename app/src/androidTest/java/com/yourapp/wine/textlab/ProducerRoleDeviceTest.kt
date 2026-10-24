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
class ProducerRoleDeviceTest {
    @Test fun producerRepairExperiments() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("producerRole")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val input=JSONArray(File(root,"input.json").readText())
        assertEquals(168,input.length())
        val configs=ProducerRepair.roleExperiments
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val repair=ProducerRepair(kb)
            val nameBox=RectF(.1f,.3f,.8f,.4f)
            val above=RectF(.1f,.19f,.8f,.29f)
            val below=RectF(.1f,.41f,.8f,.51f)
            fun token(text:String,engine:String="mlkit",rect:RectF=nameBox,variant:String="V0",score:Float=.95f)=TokenRecord(text,text,score,rect,rect,emptyList(),engine,variant,true)
            val names=listOf(token("Astrel & Fils"),token("Astrel & Fils","paddle-v6-medium"))
            val markers=listOf(token("Produced by",rect=above),token("Produced by","paddle-v6-medium",above))
            val strict=configs[0]
            assertEquals("Astrel & Fils",repair.repair("",names+markers,emptyList(),strict).value)
            assertEquals("",repair.repair("",names,emptyList(),strict).value)
            assertEquals("",repair.repair("",names+markers.take(1),emptyList(),strict).value)
            assertEquals("Astrel & Fils",repair.repair("",names+markers.take(1),emptyList(),configs[1]).value)
            assertEquals("",repair.repair("",names.take(1)+markers,emptyList(),configs[1]).value)
            assertEquals("",repair.repair("",names+markers.map { it.copy(variantId="V1") },emptyList(),strict).value)
            assertEquals("",repair.repair("",names+markers.map { it.copy(score=.2f) },emptyList(),strict).value)
            assertEquals("",repair.repair("",names+markers.map { it.copy(bboxRect=RectF(.1f,.01f,.8f,.11f)) },emptyList(),strict).value)
            assertEquals("",repair.repair("",names+markers.map { it.copy(textRaw="Bottled by") },emptyList(),strict).value)
            assertEquals("",repair.repair("",names+markers.map { it.copy(textRaw="Imported by") },emptyList(),strict).value)
            val signatures=markers.map { it.copy(textRaw="Proprietaire recoltant",bboxRect=below) }
            assertEquals("",repair.repair("",names+signatures,emptyList(),strict).value)
            assertEquals("Astrel & Fils",repair.repair("",names+signatures,emptyList(),configs[2]).value)
            assertEquals("",repair.repair("",names.map { it.copy(textRaw="Merlot") }+markers,emptyList(),strict).value)
            assertEquals("",repair.repair("",names.map { it.copy(engineId="paddle-v6-medium") }+markers.map { it.copy(engineId="paddle-v6-medium") },emptyList(),strict).value)
            val prior=listOf(token("Domaine des Aurores"),token("Domaine des Aurores","paddle-v6-medium"))
            assertEquals("Domaine des Aurores",repair.repair("Domaine des Aurores",prior+names+markers,emptyList(),strict).value)
            assertEquals("",repair.repair("",names+names.map { it.copy(textRaw="Asterline Collective") }+markers,emptyList(),strict).value)
            val provenance=repair.repair("",names+markers,emptyList(),strict).candidates.first { it["text"]=="Astrel & Fils" }
            assertEquals("explicit maker context",provenance["role"])
            assertTrue(provenance.getValue("maker_observations").isNotBlank())
            // Alias collisions do not select an arbitrary catalogue identity; no reference names involved.
            val ambiguous=object:com.yourapp.wine.kb.WineKnowledgeBase {
                override fun places()=emptyList<com.yourapp.wine.kb.Place>()
                override fun findProducer(text:String)=listOf("Asterline North","Asterline South").mapIndexed { i,n ->
                    com.yourapp.wine.kb.ProducerHit(com.yourapp.wine.kb.Producer(i.toLong(),n,"",null,listOf("Astrel & Fils")),"astrel fils",0)
                }
                override fun findCuvee(producerId:Long,text:String):com.yourapp.wine.kb.CuveeHit?=null
            }
            val aliasRepair=ProducerRepair(ambiguous)
            assertEquals("Astrel & Fils",aliasRepair.repair("",names,emptyList(),strict).value)
            assertEquals("",aliasRepair.repair("",names,emptyList(),configs[3]).value)
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
                    val alternatives=emptyList<String>()
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
