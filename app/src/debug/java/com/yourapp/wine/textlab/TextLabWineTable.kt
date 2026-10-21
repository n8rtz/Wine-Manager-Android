package com.yourapp.wine.textlab

/** One candidate/pass per image; never combines repeated passes into independent evidence. */
internal object TextLabWineTable {
    val fields=listOf("producer","wine_name","country","region","subregion","vintage")
    val headers=listOf("Image","Candidate / pass","OCR / field status","Producer","Wine name","Country","Region","Subregion","Vintage")
    fun groups(journal:TextLabJournal):List<Pair<String,String>> = buildList {
        add("mlkit_reference" to "")
        TextLabChoices.selected(journal.selected).forEach { choice ->
            add(choice.id to "")
        }
    }
    fun readings(journal:TextLabJournal,candidate:String,pass:String):List<Map<String,String>> =
        journal.rows.filter { it["candidate"]==candidate && it["pass_id"].orEmpty()==pass &&
            it["stage"]=="recognition" && it["status"] in setOf("complete","greedy_fallback") && !it["raw_text"].isNullOrBlank() }
    fun rows(journals:List<TextLabJournal>):List<List<String>> = journals.flatMap { journal ->
        journal.rows.filter { it["stage"]=="field_summary" }.map { row ->
            listOf("${journal.metadata["image_index"]}: ${journal.metadata["image_name"]}",
                (if(row["candidate"]=="mlkit_reference") "ML Kit reference" else TextLabChoices.all.firstOrNull { it.id==row["candidate"] }?.title ?: row["candidate"].orEmpty())+row["pass_id"].orEmpty().let { if(it.isEmpty()) "" else " / $it" },
                "OCR: "+row["ocr_status"].orEmpty()+" / "+when(row["status"]) {
                    "failed" -> "extraction failed"
                    "no_readings" -> "no accepted OCR lines"
                    "no_fields" -> "no fields identified"
                    "suggestions" -> "${row["field_values_found"]} fields suggested"
                    else -> "field extraction ${row["status"].orEmpty()}"
                })+
                fields.map { key -> row[key].orEmpty().ifBlank { "—" }+if(!row["${key}_edit_distance"].isNullOrBlank() && !row[key].isNullOrBlank()) " †" else "" }
        }
    }
    fun text(journals:List<TextLabJournal>):String=buildString {
        appendLine("HEAD-TO-HEAD WINE FIELDS (suggestions, not ground truth)")
        appendLine("— = unknown. † = spelling suggestion; CSV records the original text, edits and support. Each row uses only that engine/pass. All values require confirmation.")
        appendLine(headers.joinToString(" | "))
        rows(journals).forEach { row -> appendLine(row.joinToString(" | ") { it.replace('\n',' ').replace('|','/') }) }
    }
}
