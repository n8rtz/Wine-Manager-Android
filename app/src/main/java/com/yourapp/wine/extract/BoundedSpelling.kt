package com.yourapp.wine.extract

import com.yourapp.wine.fill.SpellingSuggestion
import com.yourapp.wine.merge.TextNorm
import kotlin.math.abs

/** Whole-field lexical matching, never an OCR confidence adjustment or substring completion. */
data class SpellingTerm(val id:String,val name:String,val forms:List<String>)
data class SpellingMatch(val term:SpellingTerm,val suggestion:SpellingSuggestion)
object BoundedSpelling {
    const val VERSION="field-spelling-v1"
    fun key(text:String)=TextNorm.normalize(text).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun distance(observed:String,form:String):Int? {
        val a=key(observed);val b=key(form)
        if(a==b) return 0
        if(a.length !in 5..64 || b.length !in 5..64 || a.any(Char::isDigit) || b.any(Char::isDigit)) return null
        if(a.split(' ').size>6 || abs(a.split(' ').size-b.split(' ').size)>1) return null
        val limit=if(minOf(a.length,b.length)>=10) 2 else 1
        if(abs(a.length-b.length)>limit) return null
        return TextNorm.editDistance(a,b,limit)?.takeIf { it.toDouble()/maxOf(a.length,b.length)<=.2 }
    }
    fun matches(observed:String,terms:List<SpellingTerm>,domain:String):List<SpellingMatch> {
        val query=key(observed)
        if(query.length !in 5..64 || query.any(Char::isDigit) || query.split(' ').size>6) return emptyList()
        var hasExact=false
        val matches=terms.mapNotNull { term ->
            val best=(listOf(term.name)+term.forms).distinct().mapNotNull { form ->
                distance(observed,form)?.let { form to it }
            }.minByOrNull { it.second } ?: return@mapNotNull null
            if(best.second==0) { hasExact=true;return@mapNotNull null }
            val a=key(observed);val b=key(best.first)
            val kind=when { a.length<b.length -> "missing_characters";a.length>b.length -> "extra_characters";else -> "substitution" }
            SpellingMatch(term,SpellingSuggestion(domain,observed,best.first,term.name,best.second,kind,
                if(best.second==1) "near_match" else "plausible_match","lexical only; confirm"))
        }.sortedWith(compareBy<SpellingMatch> { it.suggestion.editDistance }.thenBy { it.term.id })
        // Keep all equally close identities, not the first dictionary entry. Callers bound displayed alternatives.
        return if(hasExact) emptyList() else matches.distinctBy { it.term.id }
    }
    fun reason(s:SpellingSuggestion)="$VERSION ${s.domain}: '${s.observed}' -> '${s.proposed}' via '${s.matchedForm}'; edits=${s.editDistance} ${s.editKind}; ${s.strength}; ${s.support}; raw OCR/score unchanged"
}
