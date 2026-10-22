package com.yourapp.wine.textlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.textlab.evidence.EvidenceExtractor
import com.yourapp.wine.textlab.evidence.EvidenceIdentityRoles
import com.n8rtz.winetracker.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Executes real Android/Kotlin field assignment on frozen OCR. No OCR/model/photo substitutes. */
@RunWith(AndroidJUnit4::class)
class SavedEvidenceReplayTest {
    @Test fun replayFrozenReadings() {
        org.junit.Assume.assumeTrue("Explicit replay invocation required",
            InstrumentationRegistry.getArguments().getString("savedEvidenceReplay")=="true")
        assertTrue(EvidenceIdentityRoles.headingOnly("Château de la"))
        assertTrue(EvidenceIdentityRoles.headingOnly("Estate"))
        assertFalse(EvidenceIdentityRoles.headingOnly("Domaine des Asters"))
        assertTrue(EvidenceIdentityRoles.namedEstate("Domaine des Asters"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.filesDir,"saved-evidence")
        val input=JSONArray(File(root,"input.json").readText())
        assertEquals(168,input.length())
        val output=File(root,"results.jsonl")
        val kb=SqliteWineKnowledgeBase.open(context,0)
        try {
            val revised=EvidenceExtractor(kb,typedIdentity=true)
            fun token(text:String,top:Float):com.yourapp.wine.ocr.TokenRecord {
                val box=android.graphics.RectF(.25f,top,.75f,top+.06f)
                return com.yourapp.wine.ocr.TokenRecord(text,com.yourapp.wine.merge.TextNorm.normalize(text),.95f,
                    box,box,emptyList(),"mlkit","V0",true)
            }
            val synthetic=listOf(token("Château",.20f),token("des Asters",.28f))
            val roleResult=revised.extract(synthetic,2026)
            assertTrue(roleResult.graph.phrases.any { it.text=="Château des Asters" && it.construction=="estate-heading composition" })
            assertFalse(roleResult.hypotheses.any { it.values[FieldId.PRODUCER]=="Château" })
            assertTrue(roleResult.hypotheses.any { it.values[FieldId.PRODUCER]=="Château des Asters" })
            val onlyHeading=revised.extract(listOf(token("Château",.20f)),2026)
            assertTrue(onlyHeading.fill.fields.getValue(FieldId.PRODUCER).value.isBlank())
            output.bufferedWriter().use { writer ->
                for(i in 0 until input.length()) {
                    val item=input.getJSONObject(i)
                    val name=item.getString("image")
                    val journal=TextLabJournal(setOf("candidate_3"),BuildConfig.BUILD_COMMIT,true)
                    val rows=item.getJSONArray("rows")
                    for(j in 0 until rows.length()) {
                        val row=rows.getJSONObject(j)
                        journal.rows+=row.keys().asSequence().associateWith { row.getString(it) }
                    }
                    val records=TextLabFormPreview.records(journal)
                    val result=revised.extract(records,2026)
                    val fields=JSONObject()
                    for(field in FieldId.entries) {
                        val key=field.name.lowercase(java.util.Locale.ROOT)
                        val value=result.fill.fields.getValue(field)
                        fields.put(key,JSONObject().put("value",value.value).put("state",value.state.name).put("reason",value.reason))
                    }
                    val record=JSONObject().put("image",name).put("build",BuildConfig.BUILD_COMMIT)
                        .put("version",EvidenceExtractor.VERSION).put("fields",fields)
                        .put("trace",JSONArray(result.trace.rows)).put("trace_counts",JSONObject(result.trace.counts))
                        .put("hypotheses",JSONArray(result.hypotheses.map { h -> JSONObject()
                            .put("phrase",h.phrase.text).put("observations",JSONArray(h.phrase.observations.toList()))
                            .put("rank",h.rank).put("values",h.values.toString()).put("reason",h.reason) }))
                    writer.appendLine(record.toString());writer.flush()
                }
            }
            File(root,"validation.json").writeText(JSONObject().put("images",input.length())
                .put("execution_policy","revised_experimental_only")
                .put("baseline_executions",0).put("old_experimental_executions",0).toString(2))
        } finally { kb.close() }
    }
}
