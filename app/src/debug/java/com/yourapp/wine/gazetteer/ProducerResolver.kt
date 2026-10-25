package com.yourapp.wine.gazetteer

import java.text.Normalizer
import java.util.Locale
import com.yourapp.wine.diagnostics.PipelineFunnel

/** Lab-only, primary producer/brand names only. Supporting layers never supply replacement names. */
internal class ProducerResolver(private val index:Index,
    private val onFailureTelemetry:(Map<String,String>)->Unit={}) {
    data class Entity(val id:String,val name:String,val key:String,val support:Set<String> = emptySet())
    data class Span(val text:String,val engine:String,val observationIds:Set<Int>,val estimated:Boolean=false)
    interface Index {
        val version:String
        val primaryVerified:Boolean
        fun lookup(key:String,limit:Int,checkBudget:()->Unit):List<Entity>
    }
    data class Match(val entity:Entity,val observed:String,val engine:String,val observationIds:Set<Int>,val distance:Int)
    data class Decision(val value:String,val reason:String,val matches:List<Match>,val acceptedId:String?=null,
        val telemetry:Map<String,String> = emptyMap())
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
        fun eligible(text:String):Boolean = ineligibilityReason(text)==null
        fun ineligibilityReason(text:String):String? {
            if(text.length>96) return "span longer than 96 characters"
            if(text.any(Char::isDigit)) return "span contains a digit"
            if('%' in text || '\n' in text) return "percentage or multiline span"
            val q=normalize(text);val words=q.split(' ')
            if(q.length<8) return "normalized name shorter than 8 characters"
            // Same eligibility rules; expose the actual rejecting gate without relaxing it.
            if(words.size !in 2..8) return if(words.size<2) "single-word producer span" else "more than 8 words"
            if(noise.containsMatchIn(q)) return "administrative / boilerplate word"
            if(words.none { it.length>=4 && it !in generic }) return "no distinctive content word"
            return null
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
        val trace=PipelineFunnel.Recorder("producer.")
        trace.visit("invoked",true)
        fun finish(value:String,reason:String,matches:List<Match>,id:String?=null,completed:Boolean=true):Decision {
            trace.visit("execution",completed,reason)
            return Decision(value,reason,matches,id,trace.fields())
        }
        trace.visit("primary",index.primaryVerified,"primary LWIN export required")
        if(!index.primaryVerified) return finish(existing,"primary LWIN export required; unchanged",emptyList())
        trace.visit("spans",spans.size<=MAX_SPANS,"span limit")
        if(spans.size>MAX_SPANS) return finish(existing,"span limit; abstained",emptyList())
        val matches=mutableListOf<Match>()
        try {
            checkBudget()
            for(s in spans) {
                checkBudget()
                val engine=if(s.engine=="mlkit") "mlkit" else if(s.engine.startsWith("paddle")) "paddle" else "other"
                val rejection=if(s.engine.isBlank() || s.observationIds.isEmpty()) "missing engine / original observation IDs" else ineligibilityReason(s.text)
                trace.visit("$engine.eligible",rejection==null,rejection.orEmpty())
                if(rejection!=null) continue
                val q=normalize(s.text)
                trace.visit("$engine.lookup",false,"lookup did not complete")
                val hits=index.lookup(q,MAX_FUZZY_CANDIDATES,checkBudget)
                trace.visit("$engine.lookup",true)
                trace.visit("$engine.retrieved",hits.isNotEmpty(),"no reference candidates")
                if(hits.isNotEmpty()) trace.visit("$engine.distance",false,"no retrieved name passed distance / length bounds")
                for(e in hits.distinctBy { it.id }) {
                    checkBudget()
                    if(e.key.length>96) continue
                    val d=distance(q,e.key)
                    if(d<=if(q.length>=20) 2 else 1) {
                        trace.visit("$engine.distance",true)
                        matches+=Match(e,s.text,s.engine,s.observationIds,d)
                    }
                }
            }
            checkBudget()
        } catch(_:BudgetExceeded) { return finish(existing,"producer budget reached; unchanged",emptyList(),completed=false) }
        catch(error:Exception) {
            trace.visit("execution",false,error.javaClass.simpleName)
            onFailureTelemetry(trace.fields())
            throw error // Preserve the original failure/abstention path; retain only diagnostic progress.
        }
        val groups=matches.groupBy { it.entity.id }
        val viable=groups.filterValues { rows -> rows.any { a -> rows.any { b -> a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } } }
        trace.visit("independent",viable.isNotEmpty(),"no independently supported producer")
        if(viable.isEmpty()) return finish(existing,"no independently supported producer; unchanged",matches)
        trace.visit("unambiguous",viable.size==1,"ambiguous producer identities")
        if(viable.size!=1) return finish(existing,"ambiguous producer identities; unchanged",matches)
        val (id,winner)=viable.entries.single()
        val best=winner.minOf { it.distance }
        // Even a rival seen by just one engine prevents a weak fuzzy win or an exact collision.
        val separated=groups.none { (other,rows) -> other!=id && rows.minOf { it.distance }<=best+1 }
        trace.visit("separated",separated,"insufficient separation from competing producer")
        if(!separated) return finish(existing,"insufficient separation from competing producer; unchanged",matches)
        val entity=winner.first().entity
        if(existing.isNotBlank()) {
            val old=normalize(existing)
            if(old==entity.key) {
                trace.visit("existing",true);trace.visit("selected",true)
                trace.visit("changed",false,"existing supported producer retained")
                return finish(existing,"existing producer retained",matches)
            }
            if(winner.none { normalize(it.observed)==old } || distance(old,entity.key)>1) {
                trace.visit("existing",false,"different existing producer protected")
                return finish(existing,"different existing producer protected",matches)
            }
        }
        trace.visit("existing",true);trace.visit("selected",true)
        trace.visit("changed",entity.name!=existing,"existing supported producer retained")
        return finish(entity.name,"primary producer/brand name supported by two engines; confirm manually",matches,id)
    }
}
