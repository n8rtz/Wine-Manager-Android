package com.yourapp.wine.gazetteer

import java.text.Normalizer
import java.util.Locale

/** Lab-only, primary producer/brand names only. Supporting layers never supply replacement names. */
internal class ProducerResolver(private val index:Index) {
    data class Entity(val id:String,val name:String,val key:String,val support:Set<String> = emptySet())
    data class Span(val text:String,val engine:String,val observationIds:Set<Int>,val estimated:Boolean=false)
    interface Index {
        val version:String
        val primaryVerified:Boolean
        fun lookup(key:String,limit:Int,checkBudget:()->Unit):List<Entity>
    }
    data class Match(val entity:Entity,val observed:String,val engine:String,val observationIds:Set<Int>,val distance:Int)
    data class Decision(val value:String,val reason:String,val matches:List<Match>,val acceptedId:String?=null)
    class BudgetExceeded:RuntimeException()
    companion object {
        const val VERSION="producer-primary-v1"
        const val MAX_SPANS=128
        const val MAX_HITS=32

        /** Fuzzy-lookup candidate bound. This bounds *recall* only; the precision decisions are
         * the edit-distance gate below plus the two-engine/disjoint-observation rule, the
         * separation rule and the existing-producer protection, all of which still run on every
         * candidate this bound returns.
         *
         * It replaces the previous `MAX_HITS+1` fetch. That fetch returned 33 rows and was
         * followed by `if(hits.size > MAX_HITS) continue`, so any lookup whose FTS window was full
         * dropped the whole span. Measured against the packaged producer index over the owner's
         * 168-image Lab export, 1 147 of the 1 230 eligible spans had a full FTS window and were
         * dropped; the fuzzy branch could therefore only ever contribute exact primary-key hits. */
        const val MAX_FUZZY_CANDIDATES=200
        private val noise=Regex("\\b(?:imported|importer|imports|distributed|distributor|bottled|bottler|embouteille|mis en bouteille|produced by|product of|produit de|appellation|controlee|protegee|alcohol|alc|vol|contains|sulfites|sulphites|government|warning|llc|inc|corp|ltd|www|http)\\b")
        private val generic=setOf("chateau","domaine","domaines","estate","winery","wine","wines","vineyard","vineyards","cellar","cellars","bodega","bodegas","maison","de","du","des","la","le","les","the","and","et","of","family","reserve","reserva","grand","cru")
        fun normalize(text:String):String=Normalizer.normalize(text.lowercase(Locale.ROOT).replace('’','\''),Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"),"").replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(Regex("\\s+")," ")
        fun eligible(text:String):Boolean {
            if(text.length>96 || text.any(Char::isDigit) || '%' in text || '\n' in text) return false
            val q=normalize(text);val words=q.split(' ')
            // Single names can be place names. Leave them to existing extraction rather than force a dictionary fill.
            return q.length>=8 && words.size in 2..8 && !noise.containsMatchIn(q) && words.any { it.length>=4 && it !in generic }
        }
        fun distance(a:String,b:String):Int {
            if(kotlin.math.abs(a.length-b.length)>2) return 3
            var prev=IntArray(b.length+1) { it }
            for(i in a.indices) {
                val curr=IntArray(b.length+1);curr[0]=i+1
                for(j in b.indices) curr[j+1]=minOf(curr[j]+1,prev[j+1]+1,prev[j]+if(a[i]==b[j]) 0 else 1)
                prev=curr
            }
            return prev[b.length]
        }
    }
    fun resolve(existing:String,spans:List<Span>,checkBudget:()->Unit={}):Decision {
        if(!index.primaryVerified) return Decision(existing,"primary LWIN export required; unchanged",emptyList())
        if(spans.size>MAX_SPANS) return Decision(existing,"span limit; abstained",emptyList())
        val matches=mutableListOf<Match>()
        try {
            checkBudget()
            for(s in spans) {
                checkBudget()
                if(s.engine.isBlank() || s.observationIds.isEmpty() || !eligible(s.text)) continue
                val q=normalize(s.text)
                val hits=index.lookup(q,MAX_FUZZY_CANDIDATES,checkBudget)
                for(e in hits.distinctBy { it.id }) {
                    checkBudget()
                    if(e.key.length>96) continue
                    val d=distance(q,e.key)
                    if(d<=if(q.length>=20) 2 else 1) matches+=Match(e,s.text,s.engine,s.observationIds,d)
                }
            }
            checkBudget()
        } catch(_:BudgetExceeded) { return Decision(existing,"producer budget reached; unchanged",emptyList()) }
        val groups=matches.groupBy { it.entity.id }
        val viable=groups.filterValues { rows -> rows.any { a -> rows.any { b -> a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } } }
        if(viable.isEmpty()) return Decision(existing,"no independently supported producer; unchanged",matches)
        if(viable.size!=1) return Decision(existing,"ambiguous producer identities; unchanged",matches)
        val (id,winner)=viable.entries.single()
        val best=winner.minOf { it.distance }
        // Even a rival seen by just one engine prevents a weak fuzzy win or an exact collision.
        if(groups.any { (other,rows) -> other!=id && rows.minOf { it.distance }<=best+1 })
            return Decision(existing,"insufficient separation from competing producer; unchanged",matches)
        val entity=winner.first().entity
        if(existing.isNotBlank()) {
            val old=normalize(existing)
            if(old==entity.key) return Decision(existing,"existing producer retained",matches)
            if(winner.none { normalize(it.observed)==old } || distance(old,entity.key)>1)
                return Decision(existing,"different existing producer protected",matches)
        }
        return Decision(entity.name,"primary producer/brand name supported by two engines; confirm manually",matches,id)
    }
}
