package com.yourapp.wine.phonecorpus

/** Real saved baseline: full saved cohort before a run, matched GLiNER photos once it starts. */
internal data class GlinerBaseline(
    val title:String,
    val summary:List<String>?,
    val fields:Map<String,WineAppLabelsBuildLog.FieldScore>,
    val missing:Int,
    val available:Int,
)

internal fun glinerLabBaselines(state:WineAppLabelsState,completedImages:Set<String>):List<GlinerBaseline> = listOf(
    baseline(state,PhoneCorpusRunner.Mode.LAB,completedImages),
    baseline(state,PhoneCorpusRunner.Mode.PRODUCTION_V6,completedImages),
)

private fun baseline(state:WineAppLabelsState,mode:PhoneCorpusRunner.Mode,completedImages:Set<String>):GlinerBaseline {
    val saved=state.comparisonFieldRows[mode].orEmpty()
    // Reject incomplete/corrupt per-image rows, rather than making them look like a correct blank.
    val valid=saved.mapNotNull { (image,rows) ->
        val byField=rows.mapNotNull { row -> row["field"]?.let { it to row } }.toMap()
        if(rows.size!=PhoneCorpusMath.fields.size || PhoneCorpusMath.fields.any { field ->
            val row=byField[field]
            row==null || row["reference_status"].isNullOrBlank() || row["outcome"].isNullOrBlank()
        }) null else image to byField
    }.toMap()
    // Before GLiNER has results, show the saved baseline itself, not an invented 0/0.
    // During a GLiNER run only score the overlapping photo identities.
    val cohort=if(completedImages.isEmpty()) valid.keys else completedImages
    val matched=cohort.mapNotNull { valid[it] }
    val missing=if(completedImages.isEmpty()) 0 else completedImages.size-matched.size
    if(matched.isEmpty()) return GlinerBaseline(mode.title,null,emptyMap(),missing,valid.size)
    val scores=PhoneCorpusMath.fields.associateWith { field ->
        val rows=matched.mapNotNull { it[field] }.filter { it["reference_status"]!="unresolved" }
        WineAppLabelsBuildLog.FieldScore(rows.count { PhoneCorpusMath.correct(it["outcome"].orEmpty()) },
            rows.count { !PhoneCorpusMath.correct(it["outcome"].orEmpty()) })
    }
    val eligible=matched.filter { fields -> PhoneCorpusMath.fields.all { fields[it]?.get("reference_status")!="unresolved" } }
    fun errors(fields:Map<String,Map<String,String>>):Int =
        PhoneCorpusMath.fields.count { !PhoneCorpusMath.correct(fields[it]?.get("outcome").orEmpty()) }
    val failed=matched.count { fields -> fields.values.any { it["form_status"]=="execution_failed" } }
    val row=listOf(mode.title,matched.size.toString(),eligible.size.toString(),
        "${eligible.count { errors(it)==0 }}/${eligible.size}",
        eligible.count { errors(it)==1 }.toString(),eligible.count { errors(it)>1 }.toString(),failed.toString())
    return GlinerBaseline(mode.title,row,scores,missing,valid.size)
}
