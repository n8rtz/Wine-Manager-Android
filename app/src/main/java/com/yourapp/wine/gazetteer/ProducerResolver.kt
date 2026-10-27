package com.yourapp.wine.gazetteer

import java.text.Normalizer
import java.util.Locale
import com.yourapp.wine.diagnostics.PipelineFunnel
import com.yourapp.wine.merge.TextNorm

/** Conservative resolver over integrity-checked producer/brand names; suggestions always require confirmation.
 * The primary index is the verified LWIN export. The Lab benchmark may substitute an expanded
 * index whose LWIN subset is that same export plus source-tagged supplemental candidates; the
 * evidence rules below (two engines, separation, existing-value protection) are unchanged. */
internal class ProducerResolver(private val index:Index,
    private val onFailureTelemetry:(Map<String,String>)->Unit={}) {
    data class Entity(val id:String,val name:String,val key:String,val support:Set<String> = emptySet(),val countryGroup:String="")
    data class Span(val text:String,val engine:String,val observationIds:Set<Int>,val estimated:Boolean=false)
    interface Index {
        val version:String
        /** Producer content is exactly the verified primary LWIN export. */
        val primaryVerified:Boolean
        /** Lab-benchmark only: integrity-checked expanded index (verified LWIN subset plus
         * supplemental candidates, each tagged per entity in Entity.support). */
        val labSupplemental:Boolean get()=false
        fun lookup(key:String,limit:Int,checkBudget:()->Unit):List<Entity>
    }
    data class Match(val entity:Entity,val observed:String,val engine:String,val observationIds:Set<Int>,val distance:Int)
    data class Decision(val value:String,val reason:String,val matches:List<Match>,val acceptedId:String?=null,
        val telemetry:Map<String,String> = emptyMap())
    /**
     * How much independent OCR support one identity needs before it may become a suggestion.
     *
     * Prod keeps the original two-engine/disjoint rule. The Lab v4 benchmark may lower the quorum to
     * one engine (owner, 2026-10-27): the ensemble is a wide net, not a vote, and ML Kit is the weaker
     * engine, so a name only Paddle read should still reach the filter. Everything else - eligibility,
     * edit distance, one identity group, separation from rivals, existing-value protection and the
     * producer/wine observation split - is identical in both modes, so the delta measures the quorum
     * and nothing else.
     */
    data class Policy(val minimumIndependentEngines:Int=2) {
        init { require(minimumIndependentEngines in 1..2) }
        var countryContext:String=""
            private set
        fun withCountryContext(country:String):Policy=
            if(country.isBlank()) this else Policy(minimumIndependentEngines).also { it.countryContext=country }
        fun supports(rows:List<Match>)=minimumIndependentEngines<2 || rows.any { a -> rows.any { b ->
            a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } }
        companion object {
            val prod=Policy()
            val singleReading=Policy(1)
        }
    }
    class BudgetExceeded:RuntimeException()
    companion object {
        const val VERSION="producer-resolver-v4"
        const val MAX_SPANS=128
        const val MAX_HITS=32

        /** Fuzzy-lookup candidate bound. This bounds recall only; precision is still gated by
         * edit distance, independent OCR observations, candidate separation and protection of an
         * existing producer. Candidates are not truth merely because a source lists them. */
        const val MAX_FUZZY_CANDIDATES=200
        private val noise=Regex("\\b(?:imported|importer|imports|distributed|distributor|bottled|bottler|embouteille|mis en bouteille|produced by|product of|produit de|appellation|controlee|protegee|alcohol|alc|vol|contains|sulfites|sulphites|government|warning|llc|inc|corp|ltd|www|http)\\b")
        private val generic=setOf("chateau","domaine","domaines","estate","winery","wine","wines","vineyard","vineyards","cellar","cellars","bodega","bodegas","maison","de","du","des","la","le","les","the","and","et","of","family","reserve","reserva","grand","cru")
        fun canonicalCountryKey(country:String):String {
            val clean=TextNorm.scrub(country).trim().lowercase(Locale.ROOT)
            return if(clean=="russian federation") "russia" else clean
        }
        fun normalize(text:String):String=Normalizer.normalize(TextNorm.scrub(text).lowercase(Locale.ROOT).replace('’','\''),Normalizer.Form.NFKD)
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
    fun resolve(existing:String,spans:List<Span>,checkBudget:()->Unit={},policy:Policy=Policy.prod):Decision {
        val trace=PipelineFunnel.Recorder("producer.")
        trace.visit("invoked",true)
        fun finish(value:String,reason:String,matches:List<Match>,id:String?=null,completed:Boolean=true):Decision {
            trace.visit("execution",completed,reason)
            return Decision(value,reason,matches,id,trace.fields())
        }
        val indexPermitted=index.primaryVerified || index.labSupplemental
        trace.visit("primary",indexPermitted,"primary LWIN export required")
        if(!indexPermitted) return finish(existing,"primary LWIN export required; unchanged",emptyList())
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
        val viableRaw=groups.filterValues { rows -> policy.supports(rows) }
        trace.visit("independent",viableRaw.isNotEmpty(),"no independently supported producer")
        if(viableRaw.isEmpty()) return finish(existing,"no independently supported producer; unchanged",matches)
        val targetCountry=canonicalCountryKey(policy.countryContext)
        val viable=if(viableRaw.size>1 && targetCountry.isNotEmpty() &&
            viableRaw.values.all { it.first().entity.countryGroup.isNotBlank() }) {
            val inCountry=viableRaw.filterValues { canonicalCountryKey(it.first().entity.countryGroup)==targetCountry }
            if(inCountry.size==1) inCountry else viableRaw
        } else viableRaw
        trace.visit("unambiguous",viable.size==1,"ambiguous producer identities")
        if(viable.size!=1) return finish(existing,"ambiguous producer identities; unchanged",matches)
        val (id,winner)=viable.entries.single()
        val best=winner.minOf { it.distance }
        val entity=winner.first().entity
        // Even a rival seen by just one engine prevents a weak fuzzy win or an exact collision,
        // unless Lab country context rules out an explicitly foreign homonym.
        val separated=groups.none { (other,rows) ->
            other!=id && rows.minOf { it.distance }<=best+1 &&
                !(targetCountry.isNotEmpty() &&
                    canonicalCountryKey(entity.countryGroup)==targetCountry &&
                    rows.first().entity.countryGroup.isNotBlank() &&
                    canonicalCountryKey(rows.first().entity.countryGroup)!=targetCountry)
        }
        trace.visit("separated",separated,"insufficient separation from competing producer")
        if(!separated) return finish(existing,"insufficient separation from competing producer; unchanged",matches)
        val cleanName=TextNorm.scrub(entity.name)
        val cleanExisting=TextNorm.scrub(existing)
        if(cleanExisting.isNotBlank()) {
            val old=normalize(cleanExisting)
            if(old==entity.key) {
                trace.visit("existing",true);trace.visit("selected",true)
                trace.visit("changed",false,"existing supported producer retained")
                return finish(cleanExisting,"existing producer retained",matches)
            }
            if(winner.none { normalize(it.observed)==old } || distance(old,entity.key)>1) {
                trace.visit("existing",false,"different existing producer protected")
                return finish(cleanExisting,"different existing producer protected",matches)
            }
        }
        trace.visit("existing",true);trace.visit("selected",true)
        trace.visit("changed",cleanName!=cleanExisting,"existing supported producer retained")
        val supplementalSources=entity.support.filter { it!="lwin" }.sorted()
        // The reason states the support that actually exists, not the policy minimum, so a two-engine
        // win still reads as two engines when the lab policy is on.
        val support=if(winner.any { a -> winner.any { b -> a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } })
            "two engines" else "one engine reading under the lab single-reading policy"
        val reason=if(supplementalSources.isEmpty())
            "primary LWIN producer/brand name supported by $support; confirm manually"
            else "Lab expanded producer candidate (${supplementalSources.joinToString()}) supported by $support; confirm manually"
        return finish(cleanName,reason,matches,id)
    }
}
