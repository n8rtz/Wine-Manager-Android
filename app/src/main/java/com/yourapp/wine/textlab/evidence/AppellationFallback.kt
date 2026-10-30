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
    // Appellations live in the supplemented origins view (as on the Database
    // Lookup page); the raw KB alone contains only the fixture places, which
    // silently limited this fallback to a handful of names (e.g. Saint-Emilion
    // fired, Montagne-Saint-Emilion could never match).
    private val supplemented=SupplementedWineKnowledgeBase(kb)
    private val gazetteer=PlaceGazetteer(supplemented)
    private val hierarchy=PlaceHierarchy(supplemented)
    private val cuvee=Regex("\\bcuvee\\b")

    // Bounded one-edit repair index over canonical appellation spellings,
    // used only when the exact printed-form path above finds nothing (an
    // OCR-garbled single line such as PO MER ROL for Pomerol). A wrong fill
    // scores exactly like the blank it replaces, and competing one-edit
    // appellations still abstain, so this path can only help.
    private val appellationFormKeys by lazy {
        supplemented.places().filter { it.type=="appellation" }.flatMap { place ->
            (listOf(place.name)+BundledOrigins.spellingForms(place)).map { EvidenceRanking.nameKey(it) }.distinct()
                .map { place to it }
        }
    }
    private val exactFormIndex by lazy { appellationFormKeys.groupBy { it.second } }
    private val deletionFormIndex by lazy {
        val index=mutableMapOf<String,MutableList<Pair<com.yourapp.wine.kb.Place,String>>>()
        for(entry in appellationFormKeys) {
            val key=entry.second
            for(i in key.indices) index.getOrPut(key.removeRange(i,i+1)) { mutableListOf() } += entry
        }
        index
    }
    private fun oneEditPlaces(key:String):Set<com.yourapp.wine.kb.Place> {
        if(key.length<6) return emptySet()
        val found=linkedSetOf<com.yourapp.wine.kb.Place>()
        fun consider(entry:Pair<com.yourapp.wine.kb.Place,String>) {
            if(entry.second!=key && withinOneEdit(key,entry.second)) found+=entry.first
        }
        for(i in key.indices) {
            val deleted=key.removeRange(i,i+1)
            exactFormIndex[deleted]?.forEach(::consider)
            deletionFormIndex[deleted]?.forEach(::consider)
        }
        deletionFormIndex[key]?.forEach(::consider)
        return found
    }
    private fun withinOneEdit(a:String,b:String):Boolean {
        if(kotlin.math.abs(a.length-b.length)>1) return false
        var i=0; var j=0; var edits=0
        while(i<a.length && j<b.length) {
            if(a[i]==b[j]) { i++; j++ }
            else {
                if(++edits>1) return false
                if(a.length>b.length) i++ else if(b.length>a.length) j++ else { i++; j++ }
            }
        }
        if(i<a.length || j<b.length) edits++
        return edits==1
    }

    fun combine(baseline:Map<FieldId,String>,records:List<TokenRecord>,config:Config,deepestAppellation:Boolean=false,styleAppellationWine:Boolean=false,geographyAppellationWine:Boolean=false):Map<FieldId,String> {
        val wine=baseline[FieldId.WINE_NAME].orEmpty()
        if(wine.isNotBlank() && !(config.replaceMetadata && MetadataRules.isBoilerplate(wine))) return baseline
        // Lab v7 (geography_appellation_wine): the resolved appellation
        // subregion names the wine when no wine was selected (Pessac-Leognan).
        if(geographyAppellationWine && wine.isBlank() &&
            !records.any { cuvee.containsMatchIn(TextNorm.normalize(it.textRaw)) }) {
            val subregion=baseline[FieldId.SUBREGION].orEmpty()
            if(subregion.isNotBlank() && baseline[FieldId.COUNTRY].orEmpty().isNotBlank() &&
                baseline[FieldId.REGION].orEmpty().isNotBlank() &&
                gazetteer.matches(TextNorm.normalize(subregion)).any { it.type=="appellation" }) {
                return baseline+ (FieldId.WINE_NAME to subregion)
            }
        }
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
                r.bboxRect.width()>0 && r.bboxRect.height()>0 &&
                (!MetadataRules.isBoilerplate(r.textRaw) || WineDesignations.framedAppellation(r.textRaw)!=null)
        }.mapNotNull { o ->
            val rawText=o.record.textRaw
            val candidateText=WineDesignations.framedAppellation(rawText) ?: rawText
            val key=EvidenceRanking.nameKey(candidateText)
            val matched=gazetteer.matches(TextNorm.normalize(candidateText)).any { place ->
                // Exact complete observed form only: never truncate a longer cuvée or borrow a town name.
                (place.type=="appellation" || WineDesignations.isAppellationName(candidateText)) &&
                    (listOf(place.name)+BundledOrigins.spellingForms(place)).any { EvidenceRanking.nameKey(it)==key } &&
                    selected.all { alternatives -> alternatives.any { view.compatible(place,it) } }
            }
            if(!matched) null else o to candidateText
        }.filter { (o,_) ->
            config.engines==1 || EvidenceRanking.independentEngines(Phrase(o.id,o.record.textRaw,setOf(o.id),o.record.bboxRect,"observed"),graph).size>=config.engines
        }.groupBy { (_,candidateText) -> EvidenceRanking.nameKey(candidateText) }
        if(choices.size==1) {
            val value=choices.values.single().maxBy { (o,_) -> o.record.score }.second.trim()
            return baseline+ (FieldId.WINE_NAME to value)
        }
        // Gap E (Lab deepest_appellation_fallback): competing printed
        // appellations resolve to the deepest mutually compatible one
        // (Pessac-Leognan printed under Bordeaux outranks the wider name).
        if(deepestAppellation && choices.size>1) {
            val resolved=choices.entries.mapNotNull { (key,entries) ->
                val text=entries.first().second
                val place=gazetteer.matches(TextNorm.normalize(text))
                    .firstOrNull { it.type=="appellation" } ?: return@mapNotNull null
                key to place
            }
            if(resolved.size==choices.size) {
                val deepest=resolved.filter { (key,place) ->
                    resolved.all { (otherKey,other) ->
                        otherKey==key || (view.compatible(place,other) &&
                            view.ancestors(place).size>view.ancestors(other).size)
                    }
                }
                if(deepest.size==1) {
                    val value=choices.getValue(deepest.single().first)
                        .maxBy { (o,_) -> o.record.score }.second.trim()
                    return baseline+ (FieldId.WINE_NAME to value)
                }
            }
        }
        if(choices.isNotEmpty()) return baseline // Competing visible appellations remain unresolved.
        // Gap E (Lab style_appellation_wine): a style appellation split over
        // two lines (ROSSO / DI MONTALCINO) is not a Place and no single-line
        // path can produce it; compose vertically adjacent record pairs.
        if(styleAppellationWine) {
            val styleChoices=records.groupBy { it.engineId to it.variantId }.values.flatMap { pass ->
                val sorted=pass.filter { it.bboxRect.width()>0 && it.bboxRect.height()>0 }
                    .sortedBy { it.bboxRect.centerY() }
                sorted.zipWithNext().mapNotNull { (upper,lower) ->
                    val maxH=maxOf(upper.bboxRect.height(),lower.bboxRect.height())
                    if(lower.bboxRect.top-upper.bboxRect.bottom>maxH*.8f ||
                        kotlin.math.abs(upper.bboxRect.centerX()-lower.bboxRect.centerX())>
                            maxOf(upper.bboxRect.width(),lower.bboxRect.width())*.35f) return@mapNotNull null
                    val combined=(TextNorm.scrub(upper.textRaw)+" "+TextNorm.scrub(lower.textRaw)).trim()
                    if(!WineDesignations.isAppellationName(combined)) return@mapNotNull null
                    val places=(gazetteer.matches(TextNorm.normalize(combined))+
                        gazetteer.matches(TextNorm.normalize(lower.textRaw))+
                        gazetteer.matches(TextNorm.normalize(upper.textRaw))).distinct()
                    if(places.isEmpty() || !places.any { place ->
                            selected.all { alternatives -> alternatives.any { view.compatible(place,it) } }
                        }) return@mapNotNull null
                    EvidenceRanking.nameKey(combined) to combined
                }
            }.groupBy { it.first }
            if(styleChoices.size==1) {
                return baseline+ (FieldId.WINE_NAME to styleChoices.values.single().first().second)
            }
        }
        // No exact printed appellation: try the bounded one-edit repair over
        // the same passing observations, canonical spelling as the value.
        val fuzzyChoices=graph.observations.filter { o ->
            val r=o.record
            // Boilerplate lines are welcome here: the framing-strip below is
            // exactly what makes a garbled legal line usable, and the exact
            // path above already had first refusal on non-boilerplate text.
            r.score >= (if(r.engineId.startsWith("paddle")) .35f else .20f) &&
                r.bboxRect.width()>0 && r.bboxRect.height()>0
        }.mapNotNull { o ->
            val rawText=o.record.textRaw
            // Also try the legal framing stripped off, so a garbled framed
            // line (`Appellation Bordeaus superieur coFoat`) can still offer
            // its inner appellation to the one-edit repair.
            val framingWords=setOf("appellation","origine","controlee","protegee","controllata","garantita","denominazione","indicazione","geografica","tipica","d","de","di","aoc","aop","doc","docg")
            val stripped=rawText.split(Regex("\\s+")).filter { TextNorm.normalize(it).trimStart('.', ',', ';', ':').trimEnd('.', ',', ';', ':') !in framingWords }.joinToString(" ")
            val strippedTokens=stripped.split(" ").filter(String::isNotBlank)
            val candidateTexts=(listOf(WineDesignations.framedAppellation(rawText) ?: rawText, stripped)+
                (if(strippedTokens.size>=3) listOf(strippedTokens.dropLast(1).joinToString(" "), strippedTokens.drop(1).joinToString(" ")) else emptyList())).distinct()
            val places=candidateTexts.flatMap { oneEditPlaces(EvidenceRanking.nameKey(it)) }.distinct().filter { place ->
                selected.all { alternatives -> alternatives.any { view.compatible(place,it) } }
            }
            if(places.size!=1) null else o to places.single()
        }.filter { (o,_) ->
            config.engines==1 || EvidenceRanking.independentEngines(Phrase(o.id,o.record.textRaw,setOf(o.id),o.record.bboxRect,"observed"),graph).size>=config.engines
        }.groupBy { (_,place) -> EvidenceRanking.nameKey(place.name) }
        if(fuzzyChoices.size!=1) return baseline
        val place=fuzzyChoices.values.single().first().second
        return baseline+ (FieldId.WINE_NAME to place.name)
    }
}
