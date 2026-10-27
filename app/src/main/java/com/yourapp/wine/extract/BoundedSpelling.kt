package com.yourapp.wine.extract

import com.yourapp.wine.fill.SpellingSuggestion
import com.yourapp.wine.merge.TextNorm
import kotlin.math.abs

/** Whole-field lexical matching, never an OCR confidence adjustment or substring completion. */
data class SpellingTerm(val id:String,val name:String,val forms:List<String>)
data class SpellingMatch(val term:SpellingTerm,val suggestion:SpellingSuggestion)
object BoundedSpelling {
    const val VERSION="field-spelling-v1"
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
    fun wordCount(normalizedKey: String): Int =
        if (normalizedKey.isEmpty()) 0 else normalizedKey.count { it == ' ' } + 1
    fun key(text:String)=TextNorm.normalize(text).replace(nonAlnum," ").trim()
    fun distance(observed:String,form:String):Int? {
        val a=key(observed);val b=key(form)
        return distanceNormalized(a,b)
    }
    fun distanceNormalized(
        a:String,
        b:String,
        aWords:Int=wordCount(a),
        bWords:Int=wordCount(b),
    ):Int? {
        if(a==b) return 0
        if(a.length !in 5..64 || b.length !in 5..64 || a.any(Char::isDigit) || b.any(Char::isDigit)) return null
        if(aWords>6 || abs(aWords-bWords)>1) return null
        val limit=if(minOf(a.length,b.length)>=10) 2 else 1
        if(abs(a.length-b.length)>limit) return null
        return TextNorm.editDistance(a,b,limit)?.takeIf { it.toDouble()/maxOf(a.length,b.length)<=.2 }
    }
    fun matches(observed:String,terms:List<SpellingTerm>,domain:String):List<SpellingMatch> {
        val query=key(observed)
        val queryWords=wordCount(query)
        if(query.length !in 5..64 || query.any(Char::isDigit) || queryWords>6) return emptyList()
        var hasExact=false
        val matches=terms.mapNotNull { term ->
            val best=(listOf(term.name)+term.forms).distinct().mapNotNull { form ->
                val formKey=key(form)
                distanceNormalized(query,formKey,queryWords)?.let { Triple(form,formKey,it) }
            }.minByOrNull { it.third } ?: return@mapNotNull null
            if(best.third==0) { hasExact=true;return@mapNotNull null }
            val a=query;val b=best.second
            val kind=when { a.length<b.length -> "missing_characters";a.length>b.length -> "extra_characters";else -> "substitution" }
            SpellingMatch(term,SpellingSuggestion(domain,observed,best.first,term.name,best.third,kind,
                if(best.third==1) "near_match" else "plausible_match","lexical only; confirm"))
        }.sortedWith(compareBy<SpellingMatch> { it.suggestion.editDistance }.thenBy { it.term.id })
        // Keep all equally close identities, not the first dictionary entry. Callers bound displayed alternatives.
        return if(hasExact) emptyList() else matches.distinctBy { it.term.id }
    }
    fun reason(s:SpellingSuggestion)="$VERSION ${s.domain}: '${s.observed}' -> '${s.proposed}' via '${s.matchedForm}'; edits=${s.editDistance} ${s.editKind}; ${s.strength}; ${s.support}; raw OCR/score unchanged"
}
