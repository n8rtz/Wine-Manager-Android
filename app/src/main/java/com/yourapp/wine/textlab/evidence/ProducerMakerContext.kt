package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.merge.TextNorm

/** Explicit nearby role wording, never font prominence or reference-derived names. */
internal object ProducerMakerContext {
    private val before=setOf("producer","produced by","produced and bottled by","producteur","produit par","elabore par","elabore et mis en bouteille par")
    private val after=setOf("vigneron recoltant","vignerons recoltants","proprietaire recoltant","proprietaires recoltants")
    fun marker(text:String,signature:Boolean):Boolean {
        val normalized=TextNorm.normalize(text).trim(' ',':','-','.')
        return normalized in before || (signature && normalized in after)
    }
    fun sources(phrase:Phrase,observations:List<Observation>,signature:Boolean):List<Observation> {
        val names=observations.filter { it.id in phrase.observations }
        if(names.isEmpty()) return emptyList()
        return observations.filter { o ->
            val r=o.record
            val norm=TextNorm.normalize(r.textRaw).trim(' ',':','-','.')
            val prefix=norm in before
            if(!prefix && !(signature && norm in after)) return@filter false
            if(o.id in phrase.observations || r.score < (if(r.engineId.startsWith("paddle")) .75f else .60f)) return@filter false
            // A relationship must exist within one real reading, not across preprocessing passes.
            if(names.any { it.record.engineId!=r.engineId || it.record.variantId!=r.variantId }) return@filter false
            val a=r.bboxRect;val b=phrase.box
            val gap=if(prefix) b.top-a.bottom else a.top-b.bottom
            gap>=0f && gap<=maxOf(a.height(),b.height())*.65f &&
                kotlin.math.abs(a.centerX()-b.centerX())<=maxOf(a.width(),b.width())*.2f
        }
    }
}
