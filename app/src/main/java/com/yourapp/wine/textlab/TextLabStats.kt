package com.yourapp.wine.textlab

/** Aggregate by pipeline, never mix contenders into one accuracy score. No verified answers are assumed. */
internal object TextLabStats {
    private val stages=setOf("form_summary","evidence_summary","gazetteer_summary","spatial_evidence_summary")
    fun rows(journals:List<TextLabJournal>):List<Map<String,String>> = summarize(journals.map { it.rows })
    // Pure row contract permits local JVM tests without Android or journal/model dependencies.
    fun summarize(images:List<List<Map<String,String>>>):List<Map<String,String>> {
        val entries=images.flatMapIndexed { image,rows -> rows.filter { it["stage"] in stages }
            .distinctBy { listOf(it["stage"],it["candidate"],it["post_processing_version"]) }.map { image to it } }
        return entries.groupBy { (_,r) -> r["stage"].orEmpty()+" / "+r["post_processing_version"].orEmpty().ifBlank { r["candidate"].orEmpty() } }.map { (pipeline,group) ->
            val successful=group.map { it.second }.filter { it["status"] in setOf("suggestions","partial_suggestions","no_fields","complete") }
            fun filled(r:Map<String,String>,f:String)=!r[f].isNullOrBlank() && r["${f}_state"]!="RED"
            val fields=listOf("producer","wine_name","country","region","subregion","vintage")
            linkedMapOf("stage" to "batch_stats","pipeline" to pipeline,"images_total" to images.size.toString(),
                "images_reported" to group.size.toString(),"images_not_reported" to (images.size-group.size).toString(),
                "successful_previews" to successful.size.toString(),"failed_or_incomplete_previews" to (group.size-successful.size).toString(),
                "six_fields_filled" to successful.count { r -> fields.all { filled(r,it) } }.toString(),
                "filled_slots" to successful.sumOf { r -> fields.count { filled(r,it) } }.toString(),
                "blank_slots" to successful.sumOf { r -> fields.count { !filled(r,it) } }.toString(),
                "accuracy" to "not measured; no verified reference",
                "note" to "Six filled is NOT six correct; blank may be correct. Counts exclude failed previews.").apply {
                fields.forEach { f -> put("${f}_filled",successful.count { filled(it,f) }.toString()) }
            }
        }
    }
    fun text(journals:List<TextLabJournal>):String=buildString {
        appendLine("BATCH STATS — execution/coverage, NOT accuracy")
        appendLine("Images: ${journals.size}. Blanks may be correct; failures are not counted as blank predictions.")
        appendLine("Pipeline | Reported | OK | Failed/incomplete | All 6 filled | Filled slots | Blank slots")
        rows(journals).forEach { r -> appendLine(listOf(r["pipeline"],"${r["images_reported"]}/${r["images_total"]}",r["successful_previews"],r["failed_or_incomplete_previews"],r["six_fields_filled"],r["filled_slots"],r["blank_slots"]).joinToString(" | ")) }
        appendLine("Pipeline | Producer | Wine name | Country | Region | Subregion | Vintage (filled counts)")
        rows(journals).forEach { r -> appendLine((listOf(r["pipeline"])+listOf("producer","wine_name","country","region","subregion","vintage").map { r["${it}_filled"] }).joinToString(" | ")) }
        if(rows(journals).isEmpty()) appendLine("No pipeline previews reported yet.")
        appendLine("Residual candidates are unassigned fragments, not filled fields; see residual summary below.")
    }
}
