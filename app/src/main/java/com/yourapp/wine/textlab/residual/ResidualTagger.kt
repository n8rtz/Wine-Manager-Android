package com.yourapp.wine.textlab.residual

import java.text.Normalizer
import java.util.Locale

/** Pure Kotlin, no Android/model/DB dependencies. Offsets are UTF-16 offsets into unchanged OCR. */
internal class ResidualTagger(grapes:List<String>,qualifiers:List<String>,protectedNames:List<String> = emptyList()) {
    enum class Kind { GEOGRAPHY, POSSIBLE_GEOGRAPHY, GRAPE, GRAPE_OVERLAP, VINTAGE_CANDIDATE, HISTORICAL_YEAR,
        NUMBER_NOT_VINTAGE, PACKAGING, LEGAL, DESIGNATION, ROLE_CUE, NOISE }
    data class Span(val start:Int,val end:Int) {
        fun text(raw:String)=raw.substring(start,end)
        fun overlaps(other:Span)=start<other.end && other.start<end
    }
    data class Tag(val span:Span,val kind:Kind,val subtract:Boolean,val canonical:String="",val reason:String="",val entityId:String="")
    data class Candidate(val span:Span,val text:String,val qualifiers:List<Span>,val warnings:List<String>)
    data class Result(val raw:String,val tags:List<Tag>,val candidates:List<Candidate>,val queries:Int)
    data class Geography(val accepted:Boolean,val exact:Boolean,val wholeSpan:Boolean,val canonical:String="",val id:String="",val reason:String="")
    private data class Word(val span:Span,val key:String)
    private val grapeForms=grapes.map(::normalize).filter { it.length>=3 }.distinct().sortedByDescending { it.length }
    private val qualifierForms=qualifiers.map(::normalize).filter(String::isNotBlank).distinct().sortedByDescending { it.length }
    private val protected=protectedNames.map(::normalize).toSet()
    companion object {
        const val VERSION="residual-tags-v1"
        fun normalize(s:String)=Normalizer.normalize(s.lowercase(Locale.ROOT),Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"),"").replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(Regex("\\s+")," ")
        private val wordPattern=Regex("[\\p{L}\\p{M}\\p{N}]+")
        private val yearPattern=Regex("(?<![\\p{L}\\p{N}])\\d{4}(?![\\p{L}\\p{N}])")
        private val historical=Regex("\\b(?:est|established|founded|since|depuis|fonde|fondee|first vintage|first harvest)\\s*$")
        private val ownerCue=Regex("\\b(?:chateau|chatau|domaine|maison|bodega|bodegas|winery|cuvee|produced(?: and bottled)? by|produit par)\\b|\\b(?:sons|fils|freres)\\s*$")
        private val explicitOrigin=Regex("\\b(?:appellation|denominazione|denominacion|aoc|aop|docg|doc|dop|igp|igt|ava)\\b")
        private val pack=Regex("(?i)(?<![\\p{L}\\p{N}])(?:\\d+(?:[.,]\\d+)?\\s*%\\s*(?:(?:alc(?:ohol)?|abv)\\s*(?:[/ ]\\s*)?(?:by\\s+)?vol\\.?)?|\\d+(?:[.,]\\d+)?\\s*(?:ml|cl|litres?|liters?|l|oz)\\b)")
        private val legalTail=Regex("(?i)\\b(?:contains?\\s+sul(?:f|ph)ites?|contient\\s+(?:des\\s+)?sulfites|government\\s+warning|imported\\s+by|distributed\\s+by|bottled\\s+by|mis\\s+en\\s+bouteille\\s+par)\\b")
        private val maker=Regex("(?i)\\b(?:produced\\s+(?:and\\s+bottled\\s+)?by|produit\\s+par)\\s*[:.-]?\\s*")
        private val wrappers=Regex("(?i)\\b(?:appellation|contr[oô]l[eé]e|prot[eé]g[eé]e|denominazione|denominacion|origine|origin|controllata|garantita|aoc|aop|docg|doc|dop|igp|igt|ava|estate\\s+bottled)\\b")
        private val dangling=setOf("de","du","des","la","le","les","l","d","de la","cuvee","domaine","chateau","maison","vignobles","and","et","by","par")
        private val generic=setOf("red wine","white wine","vin rouge","vin blanc","grand vin","fine wine","premium quality")
    }
    fun tag(raw:String,currentYear:Int,lookup:(String)->Geography?,checkBudget:()->Unit={}):Result {
        require(raw.length<=512) { "Residual line character cap exceeded" }
        require(currentYear in 1900..2200)
        checkBudget()
        val tags=mutableListOf<Tag>()
        fun add(span:Span,kind:Kind,canonical:String="",reason:String="",subtract:Boolean=true,id:String="") {
            require(span.start>=0 && span.end<=raw.length && span.start<span.end)
            if(subtract && tags.any { it.subtract && it.span.overlaps(span) }) return
            tags+=Tag(span,kind,subtract,canonical,reason,id)
        }
        fun free(span:Span)=tags.none { it.subtract && it.span.overlaps(span) }
        val segments=mutableListOf<Span>();var start=0
        for(m in Regex("[|;•\\n,]").findAll(raw)) { if(start<m.range.first) segments+=Span(start,m.range.first);start=m.range.last+1 }
        if(start<raw.length) segments+=Span(start,raw.length)
        // Never turn a bottler/importer name or warning/address tail into residual identity evidence.
        for(segment in segments) {
            val part=segment.text(raw)
            val marker=legalTail.find(part)
            if(marker!=null) {
                val global=segment.start+marker.range.first
                val combinedMaker=Regex("(?i)produced\\s+and\\s+$").containsMatchIn(raw.substring(segment.start,global))
                if(!combinedMaker) add(Span(global,Regex("[|;•\\n]").find(raw,global)?.range?.first ?: raw.length),Kind.LEGAL,reason="Legal/warning clause owns its following text")
            }
            for(m in maker.findAll(part)) add(Span(segment.start+m.range.first,segment.start+m.range.last+1),Kind.ROLE_CUE,reason="Explicit maker cue retained as provenance, not a name")
        }
        for(m in pack.findAll(raw)) add(Span(m.range.first,m.range.last+1),Kind.PACKAGING,reason="Volume/alcohol pattern, not identity")
        for(m in Regex("(?i)\\b(?:alc(?:ohol)?|abv|vol)\\b").findAll(raw)) {
            val span=Span(m.range.first,m.range.last+1)
            if(tags.any { it.kind==Kind.PACKAGING && it.span.end<=span.start && raw.substring(it.span.end,span.start).all { c -> c.isWhitespace() || c in "/." } })
                add(span,Kind.PACKAGING,reason="Alcohol unit continuation")
        }
        for(m in yearPattern.findAll(raw)) {
            val span=Span(m.range.first,m.range.last+1)
            val prefix=normalize(raw.substring(0,span.start).takeLast(45))
            val year=m.value.toInt()
            val kind=if(historical.containsMatchIn(prefix)) Kind.HISTORICAL_YEAR else if(year in 1900..currentYear) Kind.VINTAGE_CANDIDATE else Kind.NUMBER_NOT_VINTAGE
            add(span,kind,m.value,"Four digits are not automatically an accepted vintage")
            if(kind==Kind.HISTORICAL_YEAR) {
                val cue=Regex("(?i)\\b(?:est\\.?|established|founded|since|depuis|fond[eé]e?|first\\s+vintage|first\\s+harvest)\\s*[:.]?\\s*$").find(raw.substring(0,span.start))
                if(cue!=null) add(Span(cue.range.first,cue.range.last+1),Kind.LEGAL,reason="Historical date marker, not identity")
            }
        }
        fun unmasked(span:Span):String=buildString {
            for(i in span.start until span.end) append(if(tags.any { it.subtract && i>=it.span.start && i<it.span.end }) ' ' else raw[i])
        }
        fun nameOwned(segment:Span)=ownerCue.containsMatchIn(normalize(segment.text(raw))) || normalize(unmasked(segment)) in protected
        // Keep qualifiers alongside candidate fragments; don't confuse them with packaging/legal notices.
        for(segment in segments) {
            val name=normalize(unmasked(segment))
            val articleTitle=qualifierForms.any { q -> name in setOf("la $q","le $q","les $q","cuvee la $q","cuvee le $q") }
            val producerOwned=Regex("\\b(?:chateau|chatau|domaine|maison|bodega|bodegas|winery)\\b").containsMatchIn(normalize(segment.text(raw))) || name in protected
            if(!articleTitle) for((span,term) in matches(raw,segment,qualifierForms)) if(free(span))
                add(span,Kind.DESIGNATION,term,if(producerOwned) "Qualifier may belong to producer's proper name; preserve extent" else "Wine-name qualifier retained for later reconstruction, not standalone identity",subtract=!producerOwned)
        }
        for(segment in segments) {
            if(nameOwned(segment)) continue // Legal abbreviations can be part of an explicitly named identity.
            for(m in wrappers.findAll(segment.text(raw))) add(Span(segment.start+m.range.first,segment.start+m.range.last+1),Kind.LEGAL,reason="Origin/classification/estate-bottling marker")
        }
        for(segment in segments) {
            val found=matches(raw,segment,grapeForms).filter { free(it.first) }
            val afterGrapes=buildString {
                val available=unmasked(segment)
                for(i in segment.start until segment.end) append(if(found.any { i>=it.first.start && i<it.first.end }) ' ' else available[i-segment.start])
            }
            val knownBlend=normalize(afterGrapes) in setOf("","and","et","y")
            for((span,term) in found) {
                checkBudget()
                if(!free(span)) continue
                val owned=nameOwned(segment)
                val standalone=normalize(unmasked(segment))==term
                val safe=!owned && (standalone || knownBlend || term.contains(' '))
                add(span,if(safe) Kind.GRAPE else Kind.GRAPE_OVERLAP,term,
                    if(safe) "Exact whole-word grape vocabulary" else "Grape word may belong to a name; retain it",subtract=safe)
            }
        }
        // A chunk cannot cross a removed span or hard column separator. Never concatenate words across a deletion.
        fun chunks():List<Span> {
            val chunks=mutableListOf<Span>()
            for(segment in segments) {
                var begin:Int?=null
                for(i in segment.start..segment.end) {
                    val blocked=i==segment.end || tags.any { it.subtract && i>=it.span.start && i<it.span.end }
                    if(blocked) {
                        begin?.let { b ->
                            var left=b;var right=i
                            while(left<right && !raw[left].isLetterOrDigit()) left++
                            while(right>left && !raw[right-1].isLetterOrDigit()) right--
                            if(left<right) chunks+=Span(left,right)
                        };begin=null
                    } else if(begin==null) begin=i
                }
            }
            return chunks
        }
        var queries=0
        for(span in chunks()) {
            checkBudget()
            val text=span.text(raw);val segment=segments.first { span.start>=it.start && span.end<=it.end }
            if(nameOwned(segment) || normalize(text) in protected || text.count(Char::isLetter)<3 || text.length>160) continue
            check(++queries<=16) { "Residual per-line lookup cap exceeded" }
            val geo=lookup(text) ?: continue
            val originContext=explicitOrigin.containsMatchIn(normalize(segment.text(raw)))
            if(geo.accepted && geo.wholeSpan && (geo.exact || originContext))
                add(span,Kind.GEOGRAPHY,geo.canonical,"${geo.reason}; whole residual extent, not a substring of an owned name",id=geo.id)
            else add(span,Kind.POSSIBLE_GEOGRAPHY,geo.canonical,"${geo.reason}; insufficient evidence to subtract",subtract=false,id=geo.id)
        }
        val candidates=mutableListOf<Candidate>()
        for(span in chunks()) {
            val text=span.text(raw);val key=normalize(text)
            if(text.count(Char::isLetter)<2 || key in dangling || key in generic) {
                add(span,Kind.NOISE,reason="Numeric, dangling connector/heading, or generic copy; not a distinctive identity")
                continue
            }
            val segment=segments.first { span.start>=it.start && span.end<=it.end }
            candidates+=Candidate(span,text,tags.filter { it.kind==Kind.DESIGNATION && it.span.start>=segment.start && it.span.end<=segment.end }.map { it.span },
                listOf("Residual only: producer, cuvee or noise; no role or confidence assigned")+
                    if(tags.any { !it.subtract && it.span.overlaps(span) }) listOf("Overlapping vocabulary/geography evidence; manual review") else emptyList())
        }
        checkBudget()
        return Result(raw,tags.sortedWith(compareBy<Tag> { it.span.start }.thenBy { it.span.end }),candidates,queries)
    }
    private fun matches(raw:String,segment:Span,forms:List<String>):List<Pair<Span,String>> {
        val words=wordPattern.findAll(segment.text(raw)).map { Word(Span(segment.start+it.range.first,segment.start+it.range.last+1),normalize(it.value)) }.toList()
        val matches=mutableListOf<Pair<Span,String>>()
        for(term in forms) {
            val parts=term.split(' ')
            for(i in words.indices) {
                if(i+parts.size>words.size) continue
                val selected=words.subList(i,i+parts.size)
                if(selected.map { it.key }!=parts) continue
                if(selected.zipWithNext().any { (a,b) -> raw.substring(a.span.end,b.span.start).any { !it.isWhitespace() && it !in "-'’" } }) continue
                val span=Span(selected.first().span.start,selected.last().span.end)
                if(matches.none { it.first.overlaps(span) }) matches+=span to term
            }
        }
        return matches
    }
}
