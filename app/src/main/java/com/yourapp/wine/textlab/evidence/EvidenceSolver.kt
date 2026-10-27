package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.extract.WineDesignations
import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.kb.Place
import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.TextNorm

/** Bounded joint assignment of identities, coherent geography bundle and vintage, with abstention. */
internal object EvidenceSolver {
    private fun samePrintedAppellation(old:Hypothesis,place:Place?):Boolean {
        if(place==null || old.values.keys!=setOf(FieldId.WINE_NAME)) return false
        val wineName=old.values[FieldId.WINE_NAME] ?: return false
        val base=WineDesignations.classificationBase(wineName) ?: wineName
        val baseKey=EvidenceRanking.nameKey(base)
        return baseKey.isNotEmpty() && (listOf(place.name)+BundledOrigins.spellingForms(place)).any {
            EvidenceRanking.nameKey(it)==baseKey
        }
    }

    private fun compatibleEmbeddedVintage(old:Hypothesis,next:Hypothesis):Boolean =
        next.values.keys==setOf(FieldId.VINTAGE) && next.reason.startsWith("embedded observed vintage") &&
            FieldId.VINTAGE !in old.values

    fun solve(input:List<Hypothesis>,hierarchy:PlaceHierarchy.View,graph:EvidenceGraph,checkBudget:()->Unit,trace:EvidenceTrace=EvidenceTrace(),config:EvidenceSearchConfig=EvidenceSearchConfig()):List<List<Hypothesis>> {
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
        val allOptions=groups.map { group -> input.filter { it.values.keys.any(group::contains) }
            .sortedByDescending { it.rank }.distinctBy { it.values to it.phrase.observations } }
        val combinations=allOptions.fold(1L) { total,options -> total*(options.size+1) }
        config.exhaustiveMaxCombinations?.let { cap ->
            if(combinations>cap) throw SearchSpaceTooLargeException(combinations)
        }
        trace.add("search_config", "id=${config.id}; combinations=$combinations; exhaustive=${config.exhaustiveMaxCombinations!=null}")
        for((groupIndex,group) in groups.withIndex()) {
            val offered=allOptions[groupIndex]
            // Round-robin normalized values without merging scores or losing provenance.
            // Alternative support sets stay distinct and can be selected in later rounds.
            val ordered=if(config.diversify) {
                val buckets=offered.groupBy { h -> h.values.entries.sortedBy { it.key.ordinal }
                    .map { it.key to TextNorm.normalize(it.value) } }.values.toList()
                (0 until (buckets.maxOfOrNull { it.size } ?: 0)).flatMap { round -> buckets.mapNotNull { it.getOrNull(round) } }
            } else offered
            val options=if(config.exhaustiveMaxCombinations!=null) ordered else ordered.take(config.candidateLimit)
            trace.count("candidate_pruned_${groupIndex}",offered.size-options.size)
            trace.add("candidate_pruning", "group=$group; offered=${offered.size}; retained=${options.size}; pruned=${offered.size-options.size}")
            val expanded=beam.flatMap { state ->
                checkBudget()
                listOf(state)+options.mapNotNull { next ->
                    if(state.selected.any { old ->
                        !samePrintedAppellation(old,next.place) && !compatibleEmbeddedVintage(old,next) && (overlaps(old,next) ||
                        BoxMath.iou(old.phrase.box,next.phrase.box)>.5f) }) { trace.add("ownership_conflict") { "phrase=${next.phrase.id}; values=${next.values}" };return@mapNotNull null }
                    var rank=next.rank
                    // Geography must explain all competing explicit geographic observations, not producer headquarters.
                    if(next.place!=null && input.any { it.place!=null && it.phrase.observations.intersect(next.phrase.observations).isEmpty() &&
                            !hierarchy.compatible(next.place,it.place) }) rank-=45
                    State(state.selected+next,state.score+rank)
                }
            }.sortedByDescending { it.score }.distinctBy { state -> state.selected.map { it.values to it.phrase.observations } }
            beam=if(config.exhaustiveMaxCombinations!=null) expanded else expanded.take(config.beamLimit)
            trace.count("beam_pruned_${groupIndex}",expanded.size-beam.size)
            trace.add("beam_pruning", "group=$group; distinct_expanded=${expanded.size}; retained=${beam.size}; pruned=${expanded.size-beam.size}")
        }
        val best=beam.firstOrNull()?.score ?: 0.0
        val finalists=beam.filter { best-it.score<=3.0 }
        trace.add("bounded_search", "beam=${beam.size}; limit=${if(config.exhaustiveMaxCombinations!=null) "exhaustive" else config.beamLimit}; finalists=${finalists.size}; best=$best; ambiguity_margin=3.0")
        finalists.forEach { state -> trace.add("finalist") { "score=${state.score}; choices=${state.selected.map { it.phrase.id to it.values }}" } }
        return finalists.map { it.selected }
    }
}
