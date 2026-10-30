package com.yourapp.wine.phonecorpus

/** The saved Lab screen's actual row and field scores, not best-per-metric composite values. */
internal data class GlinerBaseline(
    val title:String,
    val summary:List<String>?,
    val fields:Map<String,WineAppLabelsBuildLog.FieldScore>,
)

internal fun glinerLabBaselines(state:WineAppLabelsState):List<GlinerBaseline> = listOf(
    baseline(state,PhoneCorpusRunner.Mode.LAB,state.currentBuildFieldScores),
    baseline(state,PhoneCorpusRunner.Mode.PRODUCTION_V6,state.prodV6FieldScores),
)

private fun baseline(
    state:WineAppLabelsState,
    mode:PhoneCorpusRunner.Mode,
    fields:Map<String,WineAppLabelsBuildLog.FieldScore>,
):GlinerBaseline {
    // The scoreboard also contains a synthetic "Best build" row. Never use it as a run.
    val row=state.board?.rows?.firstOrNull { it.firstOrNull()==mode.title }
    val summary=row?.takeIf { it.size>=7 && (it[1].toIntOrNull() ?: 0)>0 }?.take(7)
    return GlinerBaseline(mode.title,summary,if(summary==null) emptyMap() else fields)
}
