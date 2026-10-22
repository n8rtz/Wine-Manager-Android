package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.merge.BoxMath

/** Factorial rule experiments. All switches default off; existing v3 stays unchanged. */
internal data class EvidenceRankingConfig(val equivalentNames:Boolean=false,val engineAgreement:Boolean=false,val cuveeRole:Boolean=false) {
    val id:String get()="rules-${if(equivalentNames) 1 else 0}${if(engineAgreement) 1 else 0}${if(cuveeRole) 1 else 0}"
    companion object { val experiments=(1..7).map { EvidenceRankingConfig(it and 4!=0,it and 2!=0,it and 1!=0) } }
}
internal object EvidenceRanking {
    fun nameKey(text:String)=TextNorm.normalize(text.replace("&"," et ")).filter(Char::isLetterOrDigit)
    fun namedCuvee(text:String)=Regex("^cuvee\\s+.*[a-z]{2}").containsMatchIn(TextNorm.normalize(text))
    fun independentEngines(phrase:Phrase,graph:EvidenceGraph):Set<String> {
        val key=nameKey(phrase.text)
        if(key.length<4) return emptySet()
        // Only full observed readings, never derived phrases counted as extra votes.
        return graph.observations.filter { o -> nameKey(o.record.textRaw)==key &&
            o.record.score >= if(o.record.engineId.startsWith("paddle")) .75f else .60f &&
            BoxMath.iou(o.record.bboxRect,phrase.box)>=.25f
        }.map { if(it.record.engineId.startsWith("paddle")) "paddle" else it.record.engineId }.toSet()
    }
    fun adjust(h:Hypothesis,graph:EvidenceGraph,config:EvidenceRankingConfig):Hypothesis {
        if(!config.engineAgreement || h.values.keys.none { it==FieldId.PRODUCER || it==FieldId.WINE_NAME }) return h
        val engines=independentEngines(h.phrase,graph)
        return if(engines.size>=2) h.copy(rank=h.rank+6.0,reason=h.reason+"; full co-located reading agreed by independent engines; ordinal bonus=6, not probability") else h
    }
}
