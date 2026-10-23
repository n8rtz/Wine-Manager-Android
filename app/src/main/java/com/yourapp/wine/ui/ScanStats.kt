package com.yourapp.wine.ui

import com.yourapp.wine.fill.*

/** Immutable automatic scan statistics. Never reads current form edits or treats filled as correct. */
object ScanStats {
    val headers=listOf("Field","Scan value","Review state","Fallback choices")
    fun rows(fill:FillResult,year:Int=java.time.Year.now().value):List<List<String>> {
        val values=FieldId.entries.associateWith { f -> fill.fields[f]?.let { if(it.state==UiState.RED) "" else it.value }.orEmpty() }
        return FieldId.entries.map { f ->
            val value=values.getValue(f);val c=fill.fields[f]
            val state=when {
                value.isNotBlank() -> if(c?.state==UiState.GREEN) "Validated (not ground truth)" else "Confirm / edit"
                f in fill.intentionalBlankFields -> "Intentional blank"
                else -> "Unassigned"
            }
            val options=if(fill.fields.keys.containsAll(FieldId.entries)) ScanChoicePolicy.choices(f,fill,values,year) else emptyList()
            listOf(f.name.lowercase().replace('_',' '),value.ifBlank { "—" },state,options.size.toString())
        }
    }
    fun text(fill:FillResult,elapsedMs:Long?=null,year:Int=java.time.Year.now().value):String=buildString {
        val rows=rows(fill,year)
        val filled=fill.fields.values.count { it.state!=UiState.RED && it.value.isNotBlank() }
        appendLine("AUTOMATIC SCAN STATS — not accuracy")
        appendLine("Filled: $filled/6 | Blank: ${6-filled} | Fields with fallback choices: ${rows.count { it[3]!="0" }}")
        elapsedMs?.let { appendLine("Scan elapsed: $it ms") }
        appendLine(headers.joinToString(" | "))
        rows.forEach { appendLine(it.joinToString(" | ") { v -> v.replace('|','/').replace('\n',' ').replace('\r',' ') }) }
        appendLine("Original scan only; later selections/edits are not included. Correctness requires comparison with the bottle/reference.")
    }
}
