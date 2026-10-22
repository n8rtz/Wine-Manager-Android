package com.yourapp.wine.textlab.evidence

import android.os.SystemClock
import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.extract.WineDesignations
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

/** Producer-only experiment. No reference answers, model calls, fuzzy name fabrication or form writes. */
internal class ProducerRepair(base:WineKnowledgeBase) {
    data class Config(val id:String,val minimumEngines:Int,val compose:Boolean=false,val replaceUnsupported:Boolean=false,val refined:Boolean=false,val titleRole:Boolean=false)
    data class Decision(val value:String,val reason:String,val candidates:List<Map<String,String>>)
    companion object {
        val experiments=listOf(Config("producer-direct-2",2),Config("producer-direct-1",1),
            Config("producer-compose-1",1,true),Config("producer-consensus-2",2,true,true))
        val refinedExperiments=listOf(Config("producer-refined-2",2,true,true,true),
            Config("producer-refined-1",1,true,false,true),Config("producer-title-2",2,true,true,true,true),
            Config("producer-title-1",1,true,false,true,true))
    }
    private val kb=SupplementedWineKnowledgeBase(base)
    private val places=PlaceGazetteer(kb)
    private val named=Regex("^(?:(?:la|le|les)\\s+)?(?:chateau|domaine|maison|cave|caves|bodega|bodegas|vignoble|vignobles|winery|estate)\\b")
    private val strongNamed=Regex("^(?:(?:la|le|les)\\s+)?(?:chateau|domaine|maison|cave|caves|bodega|bodegas|winery|estate)\\b")
    private val wineTerms by lazy { (BundledGrapes.terms+BundledWineStyles.terms).flatMap { listOf(it.name)+it.aliases }.map(::key).toSet() }
    private val bottler=Regex("\\b(?:mis en bouteille par|bottled by|imported by)\\b")
    private fun headingOnly(text:String):Boolean {
        val norm=TextNorm.normalize(text)
        if(!named.containsMatchIn(norm)) return EvidenceIdentityRoles.headingOnly(text)
        val rest=named.replaceFirst(norm,"").split(Regex("[^a-z0-9]+")).filter(String::isNotBlank)
        return rest.all { it in setOf("de","du","des","la","le","les","l","d","di","da","del","family") }
    }
    private fun family(engine:String)=if(engine.startsWith("paddle")) "paddle" else engine
    private fun key(text:String)=EvidenceRanking.nameKey(text)
    private fun contentKey(text:String)=key(TextNorm.normalize(text).replace(Regex("^(?:la|le|les)\\s+"),""))
    private fun cleanIdentity(text:String)=MetadataRules.isIdentity(text) && !EvidenceRanking.namedCuvee(text) &&
        !WineDesignations.isDesignation(text) && !WineDesignations.isAppellationName(text) && !text.any(Char::isDigit)

    fun repair(original:String,records:List<TokenRecord>,alternatives:List<String>,config:Config):Decision {
        val deadline=SystemClock.elapsedRealtime()+60000L
        fun budget() { check(SystemClock.elapsedRealtime()<deadline) { "Producer repair exceeded 60-second budget" } }
        fun isHeading(text:String)=headingOnly(text) || (config.refined && EvidenceIdentityRoles.headingOnly(text))
        val graph=EvidenceGraphBuilder.build(records,::budget,true)
        if(graph.truncated) return Decision(original,"Candidate graph truncated; preserve original",emptyList())
        val obs=graph.observations.associateBy { it.id }
        fun reliable(p:Phrase):Boolean {
            val parts=p.observations.map { obs.getValue(it).record.textRaw }
            if(config.refined && parts.size>1 && parts.any { strongNamed.containsMatchIn(TextNorm.normalize(it)) && !isHeading(it) } &&
                parts.any { key(it) in wineTerms || WineDesignations.isAppellationName(it) || places.matches(TextNorm.normalize(it)).any { place -> key(place.name)==key(it) } }) return false
            return p.observations.isNotEmpty() && p.observations.all { id ->
            val r=obs.getValue(id).record
            r.score >= (if(r.engineId.startsWith("paddle")) .75f else .60f) &&
                !MetadataRules.isBoilerplate(r.textRaw) && !MetadataRules.isPackaging(r.textRaw)
        }
        }
        fun legalContext(p:Phrase):Boolean=graph.observations.any { o ->
            bottler.containsMatchIn(TextNorm.normalize(o.record.textRaw)) &&
                !(config.refined && TextNorm.normalize(o.record.textRaw).contains("produced and bottled by")) &&
                (BoxMath.iou(o.record.bboxRect,p.box)>.1f ||
                    (o.record.bboxRect.bottom<=p.box.top && p.box.top-o.record.bboxRect.bottom<=p.box.height()*.65f &&
                        kotlin.math.abs(o.record.bboxRect.centerX()-p.box.centerX())<p.box.width()*.25f))
        }
        val exactCache=mutableMapOf<String,Boolean>()
        fun titleContent(text:String):Boolean = cleanIdentity(text) && !isHeading(text) &&
            key(text) !in wineTerms && !Regex("\\b(?:cuvee|vineyard|vineyards|vignoble|vignobles)\\b").containsMatchIn(TextNorm.normalize(text)) &&
            !text.contains("@") && !text.contains("://") && !text.lowercase().startsWith("www.") &&
            places.matches(TextNorm.normalize(text)).none { key(it.name)==key(text) }
        val titleHeight=graph.observations.filter { titleContent(it.record.textRaw) }.maxOfOrNull { it.record.bboxRect.height() } ?: 1f
        fun producerRole(text:String):Boolean {
            if(!cleanIdentity(text) || isHeading(text)) return false
            if((if(config.refined) strongNamed else named).containsMatchIn(TextNorm.normalize(text))) return true
            if(config.titleRole && titleContent(text) && graph.phrases.any { p ->
                key(p.text)==key(text) && p.box.height()>=titleHeight*.6f && reliable(p) && !legalContext(p)
            }) return true
            return exactCache.getOrPut(key(text)) { kb.findProducer(text).any { hit ->
                (listOf(hit.producer.name)+hit.producer.aliases).any { key(it)==key(text) }
            } }
        }
        val phrases=graph.phrases.filter { config.compose || it.construction=="observed" }.toMutableList()
        if(config.compose) {
            val singles=graph.phrases.filter { it.construction=="observed" }
            for(a in singles) for(b in singles) {
                budget()
                if(phrases.size>=512) return Decision(original,"Composition cap reached; preserve original",emptyList())
                if((!producerRole(a.text) && !isHeading(a.text)) || !cleanIdentity(b.text) || producerRole(b.text)) continue
                if(a.observations.intersect(b.observations).isNotEmpty()) continue
                if(family(obs.getValue(a.observations.single()).record.engineId)!=family(obs.getValue(b.observations.single()).record.engineId)) continue
                if(places.matches(TextNorm.normalize(b.text)).any { key(it.name)==key(b.text) }) continue
                val gap=b.box.top-a.box.bottom
                if(gap<0 || gap>maxOf(a.box.height(),b.box.height())*.65f ||
                    kotlin.math.abs(a.box.centerX()-b.box.centerX())>maxOf(a.box.width(),b.box.width())*.2f) continue
                phrases+=Phrase(phrases.size+1000,a.text+" "+b.text,a.observations+b.observations,
                    BoxMath.union(listOf(a.box,b.box)),"same-engine adjacent producer continuation")
            }
        }
        val groups=phrases.groupBy { key(it.text) }
        val texts=(phrases.map { it.text }+alternatives.take(64)).distinctBy(::key)
        val trace=mutableListOf<Map<String,String>>()
        data class Candidate(val value:String,val engines:Set<String>)
        val candidates=mutableListOf<Candidate>()
        for(text in texts) {
            budget()
            val versions=groups[key(text)].orEmpty()
            // A complete observed phrase or a same-engine composition supplies one vote, never one per variant.
            val supported=versions.filter { reliable(it) && !legalContext(it) &&
                it.observations.map { id -> family(obs.getValue(id).record.engineId) }.distinct().size==1 }
            val best=supported.maxByOrNull { anchor -> supported.filter { BoxMath.iou(anchor.box,it.box)>=.25f }
                .flatMap { p -> p.observations.map { family(obs.getValue(it).record.engineId) } }.distinct().size }
            val engines=if(best==null) emptySet() else supported.filter { BoxMath.iou(best.box,it.box)>=.25f }
                .flatMap { p -> p.observations.map { family(obs.getValue(it).record.engineId) } }.toSet()
            val reason=when {
                !producerRole(text) -> "reject: no supported producer role / incomplete or administrative text"
                supported.isEmpty() -> "reject: no reliable non-legal observed extent"
                engines.size<config.minimumEngines -> "reject: insufficient co-located independent engine support"
                else -> "eligible producer candidate"
            }
            trace+=mapOf("text" to text,"reason" to reason,"engines" to engines.sorted().joinToString(),
                "observations" to versions.flatMap { it.observations }.distinct().joinToString(),
                "construction" to versions.map { it.construction }.distinct().joinToString())
            if(reason=="eligible producer candidate") candidates+=Candidate(best!!.text,engines)
        }
        val originalKey=contentKey(original)
        val unsafe=original.isBlank() || isHeading(original) || MetadataRules.isBoilerplate(original) || MetadataRules.isPackaging(original)
        val originalSupported=phrases.any { key(it.text)==key(original) && reliable(it) && !legalContext(it) }
        if(config.refined && originalSupported && !unsafe) return Decision(original,"Preserve reliable original extent; no appended fragments",trace)
        val repairs=candidates.filter { c -> unsafe || contentKey(c.value)==originalKey ||
            (originalKey.length>=5 && contentKey(c.value).startsWith(originalKey)) ||
            (config.replaceUnsupported && !originalSupported && c.engines.size>=2) }
        // Prefer a complete extension over its own prefix, but never resolve unrelated identities by rank alone.
        val maximal=repairs.filter { c -> repairs.none { other ->
            contentKey(other.value)!=contentKey(c.value) && contentKey(other.value).startsWith(contentKey(c.value))
        } }.distinctBy { contentKey(it.value) }
        if(maximal.size!=1) return Decision(original,"Preserve original: ${maximal.size} distinct eligible repairs",trace)
        val chosen=maximal.single().value
        return Decision(if(key(chosen)==key(original)) original else chosen,
            "Unique supported producer; ${config.id}; original retained unless blank/unsafe/extended or explicitly unsupported consensus",trace)
    }
}
