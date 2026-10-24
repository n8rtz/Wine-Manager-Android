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
class AnchorHybridDeviceTest {
    @Test fun anchorHybridExperiments() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("anchorHybrid")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val input=JSONArray(File(root,"input.json").readText())
        assertEquals(168,input.length())
        val configs=EvidenceAnchorHybrid.experiments
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val hybrid=EvidenceAnchorHybrid(kb)
            val baseline=FieldId.entries.associateWith { when(it) {
                FieldId.PRODUCER -> "Château de"
                FieldId.VINTAGE -> "2020"
                else -> ""
            } }
            val revised=FieldId.entries.associateWith { when(it) {
                FieldId.COUNTRY -> "France"
                FieldId.REGION -> "Bordeaux"
                FieldId.WINE_NAME -> "Cuvée des Astres"
                else -> ""
            } }
            val guarded=hybrid.combine(baseline,revised,emptyList(),EvidenceAnchorHybrid.Config(true))
            assertEquals("",guarded[FieldId.PRODUCER])
            assertEquals("2020",guarded[FieldId.VINTAGE])
            for(f in listOf(FieldId.COUNTRY,FieldId.REGION,FieldId.SUBREGION,FieldId.WINE_NAME)) assertEquals(revised[f],guarded[f])
            val named=baseline+(FieldId.PRODUCER to "Château des Astres")
            assertEquals("Château des Astres",hybrid.combine(named,revised,emptyList(),EvidenceAnchorHybrid.Config(true))[FieldId.PRODUCER])
            assertEquals("Château de",hybrid.combine(baseline,revised,emptyList(),EvidenceAnchorHybrid.Config(false))[FieldId.PRODUCER])
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
                    val revised=FieldId.entries.associateWith { item.getJSONObject("revised_fields").getString(it.name.lowercase()) }
                    fun write(variant:String,fields:Map<FieldId,String>,elapsed:Long) {
                        val values=JSONObject()
                        fields.forEach { (f,v) -> values.put(f.name.lowercase(),JSONObject().put("value",v).put("state",if(v.isBlank()) "RED" else "YELLOW")) }
                        val row=JSONObject().put("image",image).put("variant",variant).put("build",BuildConfig.BUILD_COMMIT)
                            .put("status","complete").put("fields",values).put("elapsed_ms",elapsed)
                        writer.appendLine(row.toString());writer.flush()
                    }
                    for(offset in configs.indices) {
                        val config=configs[(i+offset)%configs.size]
                        File(root,"progress.json").writeText(JSONObject().put("image",image).put("index",i+1).put("variant",config.id).toString())
                        try {
                            val start=android.os.SystemClock.elapsedRealtime()
                            val fields=hybrid.combine(baseline,revised,records,config)
                            val elapsed=android.os.SystemClock.elapsedRealtime()-start
                            check(elapsed<=60000L) { "Hybrid exceeded 60-second diagnostic budget" }
                            write(config.id,fields,elapsed)
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
            assertTrue("Anchor hybrid failures: ${failures.take(8)}",failures.isEmpty())
        } finally { kb.close() }
    }
}
