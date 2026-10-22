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
class AppellationFallbackDeviceTest {
    @Test fun appellationFallbackExperiments() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("appellationFallback")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val input=JSONArray(File(root,"input.json").readText())
        assertEquals(168,input.length())
        val configs=AppellationFallback.experiments
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val fallback=AppellationFallback(kb)
            val box=RectF(.1f,.1f,.8f,.2f)
            fun token(engine:String,text:String="Bordeaux",rect:RectF=box)=TokenRecord(text,text,.95f,rect,rect,emptyList(),engine,"V0",true)
            val base=FieldId.entries.associateWith { when(it) {
                FieldId.COUNTRY -> "France"
                FieldId.REGION -> "Bordeaux"
                FieldId.PRODUCER -> "Domaine des Astres"
                FieldId.VINTAGE -> "2020"
                else -> ""
            } }
            val both=listOf(token("mlkit"),token("paddle-v6-medium"))
            val strict=AppellationFallback.Config(2,false)
            val filled=fallback.combine(base,both,strict)
            assertEquals("Bordeaux",filled[FieldId.WINE_NAME])
            for(f in FieldId.entries.filter { it!=FieldId.WINE_NAME }) assertEquals(base[f],filled[f])
            assertEquals(base,fallback.combine(base,listOf(token("mlkit"),token("mlkit")),strict))
            assertEquals(base,fallback.combine(base,listOf(token("mlkit"),token("paddle-v6-medium",rect=RectF(.1f,.7f,.8f,.8f))),strict))
            assertEquals(base,fallback.combine(base,both+token("mlkit","Cuvée des Astres"),strict))
            assertEquals(base,fallback.combine(base,both+token("mlkit","Médoc"),AppellationFallback.Config(1,false)))
            val wrongOrigin=base+(FieldId.COUNTRY to "Spain")
            assertEquals(wrongOrigin,fallback.combine(wrongOrigin,both,strict))
            val noRegion=base+(FieldId.REGION to "")
            assertEquals(noRegion,fallback.combine(noRegion,both,strict))
            val named=base+(FieldId.WINE_NAME to "Les Astres")
            assertEquals(named,fallback.combine(named,both,AppellationFallback.Config(2,true)))
            val metadata=base+(FieldId.WINE_NAME to "Mis en bouteille")
            assertEquals(metadata,fallback.combine(metadata,both,strict))
            assertEquals("Bordeaux",fallback.combine(metadata,both,AppellationFallback.Config(2,true))[FieldId.WINE_NAME])
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
                            val fields=fallback.combine(baseline,records,config)
                            val elapsed=android.os.SystemClock.elapsedRealtime()-start
                            check(elapsed<=60000L) { "Fallback exceeded 60-second diagnostic budget" }
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
            assertTrue("Appellation fallback failures: ${failures.take(8)}",failures.isEmpty())
        } finally { kb.close() }
    }
}
