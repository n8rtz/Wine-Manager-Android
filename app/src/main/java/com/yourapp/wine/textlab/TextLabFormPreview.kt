package com.yourapp.wine.textlab

import android.graphics.RectF
import com.yourapp.wine.fill.*
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

/** Combined saved-crop replay using production gates, not an end-to-end shutter/quad benchmark. */
internal object TextLabFormPreview {
    fun append(journal:TextLabJournal,engine:FillEngine?,year:Int) {
        if(journal.rows.any { it["stage"]=="form_summary" }) return
        val values=linkedMapOf("preview_policy" to "production FillEngine + cellar presentation; saved crop, not shutter replay",
            "field_year" to year.toString())
        try {
            check(engine!=null) { "Field engine unavailable" }
            val records=records(journal)
            val partial=journal.rows.any { it["stage"] in setOf("candidate_summary","summary") && it["status"]!="complete" }
            val fill=CellarFieldPresentation.apply(engine.fill(records,year,ConfidenceContext(labelFound=false,weakQuality=partial,
                hint="Saved-crop form preview; confirm suggestions. Camera localization and shutter budget were not replayed.")))
            FieldId.entries.forEach { field ->
                val key=field.name.lowercase(java.util.Locale.ROOT);val candidate=fill.fields.getValue(field)
                values[key]=if(candidate.state==UiState.RED) "" else candidate.value
                values["${key}_state"]=candidate.state.name
                values["${key}_reason"]=candidate.reason
                values["${key}_alternatives"]=fill.alternatives[field].orEmpty().joinToString(" | ") { it.value+": "+it.reason }
            }
            values["field_input_lines"]=records.size.toString()
            values["post_processing_version"]=com.yourapp.wine.extract.IdentityAssignment.VERSION
            val count=FieldId.entries.count { values[it.name.lowercase(java.util.Locale.ROOT)].orEmpty().isNotBlank() }
            values["field_values_found"]=count.toString()
            journal.add("production_combined","form_summary",if(count==0) "no_fields" else if(partial) "partial_suggestions" else "suggestions",values,
                "Combination of available ML Kit and Medium readings; not a best-engine selection. No manual form values changed.")
        } catch(error:Exception) {
            journal.add("production_combined","form_summary","failed",values,details="${error.javaClass.simpleName}: ${error.message}")
        }
    }
    fun records(journal:TextLabJournal):List<TokenRecord> = listOf("mlkit_reference","candidate_3").flatMap { candidate ->
        TextLabWineTable.readings(journal,candidate,"").map { row ->
            val text=TextNorm.scrub(row.getValue("raw_text"))
            val box=parseBox(row["base_box_normalized"]) ?: if(!journal.detectLines) RectF(0f,0f,1f,1f)
                else error("Missing/invalid normalized box for $candidate region ${row["region_id"]}")
            val paddle=candidate=="candidate_3"
            val score=row[if(paddle) "ctc_posterior" else "mlkit_confidence"]?.toFloatOrNull()
            require(score!=null && score.isFinite() && score in 0f..1f) { "Missing/invalid saved confidence for $candidate" }
            TokenRecord(text,TextNorm.normalize(text),score,parseBox(row["original_box_normalized"]) ?: box,box,emptyList(),
                if(paddle) "paddle-v6-medium" else "mlkit","V0",
                // Matches PaddleMediumOcrEngine: posterior is not calibrated support for GREEN.
                scoreIsEstimated=paddle || row["confidence_estimated"]!="false")
        }
    }
    private fun parseBox(value:String?):RectF? {
        val p=value?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if(p.size==4 && p.all { it.isFinite() && it in 0f..1f } && p[2]>p[0] && p[3]>p[1]) RectF(p[0],p[1],p[2],p[3]) else null
    }
}
