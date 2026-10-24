package com.yourapp.wine.extract

import com.yourapp.wine.fill.SpellingSuggestion
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.MergedToken

/** A separate explicit origin anchor is required. Producer/cuvee metadata is never an anchor. */
class GeographySpelling(private val kb:WineKnowledgeBase,private val gazetteer:PlaceGazetteer,private val hierarchy:PlaceHierarchy) {
    data class Result(val lines:List<LabelLine>,val repaired:Set<MergedToken>,val suggestions:List<SpellingSuggestion>)
    fun suggest(lines:List<LabelLine>):Result {
        val view=hierarchy.snapshot();val selectors=ProducerLineSelector()
        val anchors=lines.associateWith { line ->
            val norm=TextNorm.normalize(line.text)
            if(MetadataRules.isPackaging(line.text) || selectors.hasProducerCue(line.text) ||
                ReferenceWineCatalog.isAdministrative(line.text)) emptyList() else gazetteer.matches(norm).filter { p ->
                p.type in setOf("country","region") && (IdentityRoles.originOnly(line.text,listOf(p)) ||
                    norm=="product of ${p.nameNorm}" || norm=="produced in ${p.nameNorm}")
            }
        }
        val repaired=mutableSetOf<MergedToken>();val suggestions=mutableListOf<SpellingSuggestion>()
        val output=lines.map { line ->
            if(MetadataRules.isPackaging(line.text) || MetadataRules.isBoilerplate(line.text) || selectors.hasProducerCue(line.text) ||
                BundledProducerNames.exactName(line.text)!=null || gazetteer.matches(TextNorm.normalize(line.text)).isNotEmpty() ||
                selectors.isWineDescription(line.text) || kb.findProducer(line.text).any { it.editDistance==0 }) return@map line
            val context=anchors.filterKeys { other -> other.tokens.none { it in line.tokens } }.values.flatten().distinctBy { it.id }
            if(context.isEmpty() || context.map { it.countryCode }.distinct().size!=1) return@map line
            val places=kb.places().filter { place -> context.all { view.compatible(place,it) } }
            val matches=BoundedSpelling.matches(line.text,places.map { SpellingTerm(it.id.toString(),it.name,BundledOrigins.spellingForms(it)) },"geography")
            val best=matches.firstOrNull()?.suggestion?.editDistance ?: return@map line
            val tied=matches.filter { it.suggestion.editDistance==best }
            val supported=tied.map { it.suggestion.copy(support="separate explicit origin: "+context.joinToString { it.name }+if(tied.size>1) "; competing places; not applied" else "; confirm") }
            suggestions+=supported.take(8)
            if(tied.size!=1) line else {
                repaired+=line.tokens
                line.copy(text=tied.single().term.name)
            }
        }
        return Result(output,repaired,suggestions)
    }
}
