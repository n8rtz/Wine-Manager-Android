package com.yourapp.wine.gliner

import com.yourapp.wine.fill.FieldCandidate
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.FillResult
import com.yourapp.wine.fill.UiState
import java.util.Locale

/** Maps the existing workflow's output to the normal editable Add Wine fields. */
internal object GlinerProductionAdapter {
    fun fill(fields:Map<String,String>, states:Map<String,String>, changes:List<String>):FillResult {
        val candidates=FieldId.entries.associateWith { field ->
            val key=field.name.lowercase(Locale.ROOT)
            val value=fields[key].orEmpty().trim()
            // Preserve the workflow's own confidence state: do not promote an unvalidated value or
            // force already-validated GREEN fields to need a new, scanner-specific confirmation.
            val state=when {
                value.isEmpty() -> UiState.RED
                states[key]=="GREEN" -> UiState.GREEN
                states[key]=="YELLOW" -> UiState.YELLOW
                else -> UiState.RED
            }
            FieldCandidate(field,if(state==UiState.RED) "" else value,state,
                if(state==UiState.GREEN) "Supported by label evidence"
                else if(state==UiState.YELLOW) "Check against the label"
                else "No supported suggestion — enter manually or retake",emptyList())
        }
        return FillResult(candidates,null,postProcessing=changes)
    }
}
