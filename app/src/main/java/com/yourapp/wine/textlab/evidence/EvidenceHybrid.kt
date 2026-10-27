package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.TextNorm

/** Experimental corrections to a saved baseline. No baseline execution or reference answers. */
internal class EvidenceHybrid(base:WineKnowledgeBase) {
    private val places=PlaceGazetteer(SupplementedWineKnowledgeBase(base))
    fun combine(baseline:Map<FieldId,String>,experimental:EvidenceResult,replaceAppellation:Boolean):Map<FieldId,String> {
        val out=baseline.toMutableMap()
        fun supported(field:FieldId):Boolean {
            val value=experimental.fill.fields.getValue(field).value
            return value.isNotBlank() && experimental.hypotheses.any { h ->
                h.values[field]?.let { EvidenceRanking.nameKey(it)==EvidenceRanking.nameKey(value) }==true &&
                    EvidenceRanking.independentEngines(h.phrase,experimental.graph).size>=2
            }
        }
        val producer=baseline.getValue(FieldId.PRODUCER)
        val newProducer=experimental.fill.fields.getValue(FieldId.PRODUCER).value
        if((producer.isBlank() || EvidenceIdentityRoles.headingOnly(producer)) &&
            EvidenceIdentityRoles.namedEstate(newProducer) && supported(FieldId.PRODUCER)) out[FieldId.PRODUCER]=newProducer
        val wine=baseline.getValue(FieldId.WINE_NAME)
        val newWine=experimental.fill.fields.getValue(FieldId.WINE_NAME).value
        val exactAppellation=replaceAppellation && places.matches(TextNorm.normalize(wine)).any { TextNorm.normalize(it.name)==TextNorm.normalize(wine) }
        if((wine.isBlank() || exactAppellation) && EvidenceRanking.namedCuvee(newWine) && supported(FieldId.WINE_NAME) &&
            EvidenceRanking.nameKey(newWine)!=EvidenceRanking.nameKey(out.getValue(FieldId.PRODUCER))) out[FieldId.WINE_NAME]=newWine
        return out
    }
}
