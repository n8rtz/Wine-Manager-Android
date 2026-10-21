package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.RectF
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.SqliteWineKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import java.time.Year
import kotlinx.coroutines.CancellationException

/** Post-OCR reporting only, outside engine timings; uses the same local KB/rules for every contender. */
internal object TextLabWineExtraction {
    fun append(context:Context,batch:TextLabBatch,skip:Boolean=false) {
        var kb:SqliteWineKnowledgeBase?=null
        var setupError=if(skip) "Skipped after memory pressure" else ""
        val engine=try {
            if(skip) null else {
                kb=SqliteWineKnowledgeBase.open(context.applicationContext,0)
                TextLabFieldExtractor(kb!!)
            }
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) { setupError="${error.javaClass.simpleName}: ${error.message}";null }
        try {
            for(journal in batch.journals) for((candidate,pass) in TextLabWineTable.groups(journal)) {
                if(journal.rows.any { it["stage"]=="field_summary" && it["candidate"]==candidate && it["pass_id"].orEmpty()==pass }) continue
                val run=journal.rows.lastOrNull { it["candidate"]==candidate && it["pass_id"].orEmpty()==pass &&
                    it["stage"] in setOf("candidate_summary","pass_summary","summary") }
                val values=mutableMapOf("pass_id" to pass,"ocr_status" to (run?.get("status") ?: "not_run"),
                    "field_extractor" to "local lab extractor v2; production field rules without confidence merge gate; isolated candidate/pass",
                    "field_year" to Year.now().value.toString())
                var status="complete";var details="All nonblank fields require confirmation; not ground truth."
                try {
                    check(engine!=null) { setupError }
                    val records=TextLabWineTable.readings(journal,candidate,pass).map { row ->
                        val text=row.getValue("raw_text")
                        val baseBox=box(row["base_box_normalized"]) ?: if(!journal.detectLines) RectF(0f,0f,1f,1f)
                            else error("Missing/invalid base coordinates for region ${row["region_id"]}")
                        // Scores are intentionally estimated: the table cannot promote a lab reading to GREEN.
                        TokenRecord(text,TextNorm.normalize(text),.5f,box(row["original_box_normalized"]) ?: baseBox,
                            baseBox,emptyList(),candidate,pass.ifEmpty { "single" },scoreIsEstimated=true)
                    }
                    val result=engine.extract(records,Year.now().value)
                    values["post_processing_version"]=com.yourapp.wine.extract.IdentityAssignment.VERSION
                    values["spelling_suggestions"]=result.spellingSuggestions.joinToString("\n",transform=com.yourapp.wine.extract.BoundedSpelling::reason)
                    values["role_diagnostics"]=result.postProcessing.joinToString("\n")
                    FieldId.entries.forEach { field ->
                        val key=field.name.lowercase(java.util.Locale.ROOT)
                        val value=result.fields[field]
                        values[key]=value?.value.orEmpty()
                        values["${key}_state"]=value?.state?.name ?: "RED"
                        values["${key}_reason"]=value?.reason.orEmpty()
                        value?.spelling?.let { spelling ->
                            values["${key}_observed"]=spelling.observed
                            values["${key}_matched_form"]=spelling.matchedForm
                            values["${key}_edit_distance"]=spelling.editDistance.toString()
                            values["${key}_edit_kind"]=spelling.editKind
                            values["${key}_match_strength"]=spelling.strength
                            values["${key}_spelling_support"]=spelling.support
                        }
                        values["${key}_alternatives"]=result.alternatives[field].orEmpty().joinToString(" | ") { it.value+": "+it.reason }
                    }
                    values["field_input_lines"]=records.size.toString()
                    val count=result.fields.values.count { it.value.isNotBlank() }
                    values["field_values_found"]=count.toString()
                    status=when { records.isEmpty() -> "no_readings";count==0 -> "no_fields";else -> "suggestions" }
                } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) { status="failed";details="${error.javaClass.simpleName}: ${error.message}" }
                journal.add(candidate,"field_summary",status,values,details)
            }
        } finally { kb?.close() }
    }
    private fun box(value:String?):RectF? {
        val v=value?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if(v.size==4 && v.all { it.isFinite() && it in 0f..1f } && v[2]>v[0] && v[3]>v[1])
            RectF(v[0],v[1],v[2],v[3]) else null
    }
}
