package com.yourapp.wine.gliner

import com.yourapp.wine.fill.FieldCandidate
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.FillResult
import com.yourapp.wine.fill.UiState
import java.util.Locale

/** Converts the lab's already-cellar-formatted output into editable Add Wine suggestions. */
internal object GlinerProductionAdapter {
    fun fill(fields:Map<String,String>, states:Map<String,String>, changes:List<String>):FillResult {
        val candidates=FieldId.entries.associateWith { field ->
            val key=field.name.lowercase(Locale.ROOT)
            val value=fields[key].orEmpty().trim()
            // Full-frame lab OCR has no production quality/localization validation or FieldCandidate
            // provenance. Never claim GREEN; also do not resurrect a RED/unknown lab candidate.
            val supported=value.isNotEmpty() && states[key] in setOf("GREEN","YELLOW")
            FieldCandidate(field,if(supported) value else "",if(supported) UiState.YELLOW else UiState.RED,
                if(supported) "GLiNER full-photo suggestion — confirm against the label"
                else "No supported GLiNER suggestion — enter manually or retake",emptyList())
        }
        return FillResult(candidates,"GLiNER read the full photo offline. Confirm all suggestions against the bottle.",
            postProcessing=changes.map { "GLiNER: $it" })
    }
}
