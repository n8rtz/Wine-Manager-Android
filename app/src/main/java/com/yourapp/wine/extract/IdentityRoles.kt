package com.yourapp.wine.extract

import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.kb.BundledProducerNames
import com.yourapp.wine.kb.Place
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm

/** Roles may overlap (an appellation can name a wine). Original evidence is never rewritten. */
enum class IdentityRole { PACKAGING, ADMINISTRATIVE, ORIGIN, ORIGIN_SUSPECT, APPELLATION, STYLE, PRODUCER_CUE, EXACT_PRODUCER, NAME }
data class RoleEvidence(val line:LabelLine,val roles:Set<IdentityRole>,val reason:String)
object IdentityRoles {
    private val producers=ProducerLineSelector()
    fun classify(line:LabelLine,places:List<Place>,originTypes:Map<String,Set<String>> = emptyMap()):RoleEvidence {
        val text=line.text
        val roles=linkedSetOf<IdentityRole>()
        if(MetadataRules.isPackaging(text)) roles+=IdentityRole.PACKAGING
        if(MetadataRules.isBoilerplate(text) || producers.isAdministrative(text)) roles+=IdentityRole.ADMINISTRATIVE
        if(WineDesignations.isAppellationName(text)) roles+=IdentityRole.APPELLATION
        if(producers.isWineDescription(text)) roles+=IdentityRole.STYLE
        if(producers.hasProducerCue(text)) roles+=IdentityRole.PRODUCER_CUE
        if(originOnly(text,places)) roles+=IdentityRole.ORIGIN
        val words=TextNorm.normalize(text).split(' ')
        if(words.size>=2 && !producers.hasProducerCue(text) && words.last().length>=4 &&
            originTypes[words.dropLast(1).joinToString(" ")].orEmpty().any { words.last()!=it && TextNorm.editDistance(words.last(),it,2)!=null })
            roles+=IdentityRole.ORIGIN_SUSPECT
        // A locality alias is not evidence of the similarly named winery.
        if(BundledProducerNames.exactName(text)!=null && IdentityRole.ORIGIN !in roles && IdentityRole.ORIGIN_SUSPECT !in roles && IdentityRole.APPELLATION !in roles)
            roles+=IdentityRole.EXACT_PRODUCER
        if(roles.none { it in setOf(IdentityRole.PACKAGING,IdentityRole.ADMINISTRATIVE,IdentityRole.ORIGIN,IdentityRole.ORIGIN_SUSPECT) } &&
            MetadataRules.isIdentity(MetadataRules.identityText(text))) roles+=IdentityRole.NAME
        return RoleEvidence(line,roles,"roles-v1: "+roles.joinToString("+"))
    }
    /** Closed geographic type suffixes; an exact observed prefix is required. This does not create place claims. */
    fun originTypes(places:List<Place>):Map<String,Set<String>> = places.map { TextNorm.normalize(it.name) }
        .filter { it.substringAfterLast(' ') in setOf("district","valley","county","coast","region") && ' ' in it }
        .groupBy { it.substringBeforeLast(' ') }.mapValues { (_,names) -> names.map { it.substringAfterLast(' ') }.toSet() }
    /** Remove only whole verified place forms; never arbitrary place substrings inside a name. */
    fun originOnly(text:String,places:List<Place>):Boolean {
        if(places.isEmpty()) return false
        var remainder=TextNorm.normalize(text)
        val forms=places.flatMap { listOf(it.name)+BundledOrigins.spellingForms(it) }
            .map(TextNorm::normalize).distinct().sortedByDescending(String::length)
        for(form in forms) remainder=remainder.replace(Regex("(?<![\\p{L}\\p{N}])${Regex.escape(form)}(?![\\p{L}\\p{N}])")," ")
        return remainder.replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().isEmpty()
    }
}
