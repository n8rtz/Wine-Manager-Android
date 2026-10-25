package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.extract.WineDesignations
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

/** Saved-baseline repair experiment only. Never wired into production or the default lab extractor. */
internal class AppellationFallback(private val kb:WineKnowledgeBase) {
    data class Config(val engines:Int,val replaceMetadata:Boolean) {
        val id get()="appellation-${engines}-${if(replaceMetadata) "metadata" else "blank"}"
    }
    companion object { val experiments=listOf(Config(1,false),Config(2,false),Config(1,true),Config(2,true)) }
    private val gazetteer=PlaceGazetteer(kb)
    private val hierarchy=PlaceHierarchy(kb)
    private val cuvee=Regex("\\bcuvee\\b")

    fun combine(baseline:Map<FieldId,String>,records:List<TokenRecord>,config:Config):Map<FieldId,String> {
        val wine=baseline[FieldId.WINE_NAME].orEmpty()
        if(wine.isNotBlank() && !(config.replaceMetadata && MetadataRules.isBoilerplate(wine))) return baseline
        // A printed named cuvée blocks this fallback, including one not successfully selected earlier.
        if(records.any { cuvee.containsMatchIn(TextNorm.normalize(it.textRaw)) }) return baseline
        val view=hierarchy.snapshot()
        val selected=listOf(FieldId.COUNTRY,FieldId.REGION,FieldId.SUBREGION).mapNotNull { field ->
            baseline[field]?.takeIf(String::isNotBlank)?.let { gazetteer.matches(TextNorm.normalize(it)) }
        }
        // Require existing, understood country AND region; unknown/contradictory origins cannot justify a name.
        if(baseline[FieldId.COUNTRY].isNullOrBlank() || baseline[FieldId.REGION].isNullOrBlank() || selected.any { it.isEmpty() }) return baseline
        val graph=EvidenceGraph(records.mapIndexed { i,r -> Observation(i,r) },emptyList(),false)
        val choices=graph.observations.filter { o ->
            val r=o.record
            r.score >= (if(r.engineId.startsWith("paddle")) .75f else .60f) &&
                r.bboxRect.width()>0 && r.bboxRect.height()>0 && !MetadataRules.isBoilerplate(r.textRaw)
        }.filter { o ->
            val text=o.record.textRaw;val key=EvidenceRanking.nameKey(text)
            gazetteer.matches(TextNorm.normalize(text)).any { place ->
                // Exact complete observed form only: never truncate a longer cuvée or borrow a town name.
                (place.type=="appellation" || WineDesignations.isAppellationName(text)) &&
                    (listOf(place.name)+BundledOrigins.spellingForms(place)).any { EvidenceRanking.nameKey(it)==key } &&
                    selected.all { alternatives -> alternatives.any { view.compatible(place,it) } }
            }
        }.filter { o ->
            config.engines==1 || EvidenceRanking.independentEngines(Phrase(o.id,o.record.textRaw,setOf(o.id),o.record.bboxRect,"observed"),graph).size>=config.engines
        }.groupBy { EvidenceRanking.nameKey(it.record.textRaw) }
        if(choices.size!=1) return baseline // Competing visible appellations remain unresolved.
        val value=choices.values.single().maxBy { it.record.score }.record.textRaw.trim()
        return baseline+ (FieldId.WINE_NAME to value)
    }
}
