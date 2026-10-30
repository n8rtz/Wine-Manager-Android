package com.yourapp.wine.phonecorpus

/** A baseline scored only on photos GLiNER has finished, from the Lab page's real per-photo rows. */
internal data class GlinerBaseline(
    val title:String,
    val summary:List<String>?,
    val fields:Map<String,WineAppLabelsBuildLog.FieldScore>,
    val missing:Int,
)

internal fun glinerLabBaselines(state:WineAppLabelsState,completedImages:Set<String>):List<GlinerBaseline> = listOf(
    baseline(state,PhoneCorpusRunner.Mode.LAB,completedImages),
    baseline(state,PhoneCorpusRunner.Mode.PRODUCTION_V6,completedImages),
)

private fun baseline(state:WineAppLabelsState,mode:PhoneCorpusRunner.Mode,completedImages:Set<String>):GlinerBaseline {
    val saved=state.comparisonFieldRows[mode].orEmpty()
    // Reject incomplete/corrupt per-image rows, rather than making them look like a correct blank.
    val matched=completedImages.mapNotNull { image ->
        val rows=saved[image] ?: return@mapNotNull null
        val byField=rows.mapNotNull { row -> row["field"]?.let { it to row } }.toMap()
        if(rows.size!=PhoneCorpusMath.fields.size || PhoneCorpusMath.fields.any { field ->
            val row=byField[field]
            row==null || row["reference_status"].isNullOrBlank() || row["outcome"].isNullOrBlank()
        }) null else byField
    }
    val missing=completedImages.size-matched.size
    if(saved.isEmpty()) return GlinerBaseline(mode.title,null,emptyMap(),missing)
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
    return GlinerBaseline(mode.title,row,scores,missing)
}
