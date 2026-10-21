package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.TextNorm

/** Bounded joint assignment of identities, coherent geography bundle and vintage, with abstention. */
internal object EvidenceSolver {
    fun solve(input:List<Hypothesis>,hierarchy:PlaceHierarchy.View,graph:EvidenceGraph,checkBudget:()->Unit):List<List<Hypothesis>> {
        val observations=graph.observations.associateBy { it.id }
        fun overlaps(a:Hypothesis,b:Hypothesis):Boolean = a.phrase.observations.any { ai -> b.phrase.observations.any { bi ->
            val x=observations.getValue(ai).record;val y=observations.getValue(bi).record
            ai==bi || (BoxMath.iou(x.bboxRect,y.bboxRect)>.25f &&
                (TextNorm.jaroWinkler(TextNorm.normalize(x.textRaw),TextNorm.normalize(y.textRaw))>.8 ||
                    TextNorm.normalize(x.textRaw).contains(TextNorm.normalize(y.textRaw)) || TextNorm.normalize(y.textRaw).contains(TextNorm.normalize(x.textRaw))))
        } }
        data class State(val selected:List<Hypothesis>,val score:Double)
        var beam=listOf(State(emptyList(),0.0))
        val groups=listOf(setOf(FieldId.PRODUCER),setOf(FieldId.WINE_NAME),setOf(FieldId.COUNTRY,FieldId.REGION,FieldId.SUBREGION),setOf(FieldId.VINTAGE))
        for(group in groups) {
            val options=input.filter { it.values.keys.any(group::contains) }.sortedByDescending { it.rank }
                .distinctBy { it.values to it.phrase.observations }.take(16)
            beam=beam.flatMap { state ->
                checkBudget()
                listOf(state)+options.mapNotNull { next ->
                    if(state.selected.any { old ->
                        val samePrintedAppellation=next.place!=null && old.values[FieldId.WINE_NAME]?.let { TextNorm.normalize(it)==next.place.nameNorm }==true
                        !samePrintedAppellation && (overlaps(old,next) ||
                        BoxMath.iou(old.phrase.box,next.phrase.box)>.5f) }) return@mapNotNull null
                    var rank=next.rank
                    // Geography must explain all competing explicit geographic observations, not producer headquarters.
                    if(next.place!=null && input.any { it.place!=null && it.phrase.observations.intersect(next.phrase.observations).isEmpty() &&
                            !hierarchy.compatible(next.place,it.place) }) rank-=45
                    State(state.selected+next,state.score+rank)
                }
            }.sortedByDescending { it.score }.distinctBy { state -> state.selected.map { it.values to it.phrase.observations } }.take(EvidenceExtractor.BEAM)
        }
        val best=beam.firstOrNull()?.score ?: 0.0
        return beam.filter { best-it.score<=3.0 }.map { it.selected }
    }
}
