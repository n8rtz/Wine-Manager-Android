package com.yourapp.wine.gazetteer

import java.text.Normalizer
import java.util.Locale
import com.yourapp.wine.diagnostics.PipelineFunnel
import com.yourapp.wine.merge.TextNorm

/** Conservative resolver over integrity-checked producer/brand names; suggestions always require confirmation.
 * The primary index is the verified LWIN export. The Lab benchmark may substitute an expanded
 * index whose LWIN subset is that same export plus source-tagged supplemental candidates. OCR-span
 * resolution keeps its support gates; a separate final-value pass canonicalizes the closest database
 * name within its own small edit-distance bound, using FTS rank to break equal-distance ties.
 */
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
    data class FinalNearMatch(val entity:Entity,val distance:Int)

    /**
     * How much independent OCR support one identity needs before it may become a suggestion.
     *
     * Prod keeps the original two-engine/disjoint rule. The Lab v5 benchmark may lower the quorum to
     * one engine (owner, 2026-10-27): the ensemble is a wide net, not a vote, and ML Kit is the weaker
     * engine, so a name only Paddle read should still reach the filter. In this span-based resolver,
     * a one-engine fuzzy correction is still limited to verified primary LWIN names; a supplemental
     * name may confirm exact OCR, but needs independent engine support to overrule a near miss. A
     * separate final-value database pass is intentionally evaluated after these span-based safeguards.
     */
    data class Policy(val minimumIndependentEngines:Int=2) {
        init { require(minimumIndependentEngines in 1..2) }
        var countryContext:String=""
            private set
        var exactMatchFastPath:Boolean=false
            private set
        var nonProducerTerm:(String)->Boolean={ false }
            private set
        var producerPenalty:(String)->Int={ 0 }
            private set
        var useParticleAndRoleRules:Boolean=true
            private set
        private fun copyWith(
            country:String=countryContext,
            exactFastPath:Boolean=exactMatchFastPath,
            excluded:(String)->Boolean=nonProducerTerm,
            penalty:(String)->Int=producerPenalty,
            particleAndRole:Boolean=useParticleAndRoleRules,
        ):Policy=Policy(minimumIndependentEngines).also {
            it.countryContext=country
            it.exactMatchFastPath=exactFastPath
            it.nonProducerTerm=excluded
            it.producerPenalty=penalty
            it.useParticleAndRoleRules=particleAndRole
        }
        fun withCountryContext(country:String):Policy=
            if(country.isBlank()) this else copyWith(country=country)
        fun withExactMatchFastPath(excluded:(String)->Boolean={ false }):Policy=
            copyWith(exactFastPath=true,excluded=excluded)
        fun withProducerPenalty(penalty:(String)->Int):Policy=
            copyWith(penalty=penalty)
        fun withParticleAndRoleRules(enabled:Boolean):Policy=
            copyWith(particleAndRole=enabled)
        fun supports(rows:List<Match>)=minimumIndependentEngines<2 || rows.any { a -> rows.any { b ->
            a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } }
        companion object {
            val prod=Policy()
            val singleReading=Policy(1)
        }
    }
    class BudgetExceeded:RuntimeException()
    companion object {
        const val VERSION="producer-resolver-v7"
        const val MAX_SPANS=128
        /** Existing wine-name shortlist; kept at its prior cost bound. */
        const val MAX_FUZZY_CANDIDATES=200
        /** Producer FTS shortlist. In the Lab-expanded index, verified Château La Tour de By ranks
         * 225th for `Chateau La Cour de By`; 256 clears that measured cutoff without widening wine lookup. */
        const val MAX_PRODUCER_FUZZY_CANDIDATES=256
        /** Maximum normalized edit distance for the final database-name canonicalization pass. */
        const val MAX_FINAL_NEAR_MATCH_DISTANCE=1
        private val finalNearMatchLeadingArticles=setOf("la","le","les","the")
        private val minorProducerParticles=setOf("de")
        private val noise=Regex("\\b(?:imported|importer|imports|distributed|distributor|bottled|bottler|embouteille|mis en bouteille|produced by|product of|produit de|appellation|controlee|protegee|alcohol|alc|vol|contains|sulfites|sulphites|government|warning|llc|inc|corp|ltd|www|http)\\b")
        private val generic=setOf("chateau","domaine","domaines","estate","winery","wine","wines","vineyard","vineyards","cellar","cellars","bodega","bodegas","maison","de","du","des","la","le","les","the","and","et","of","family","reserve","reserva","grand","cru")
        internal val estatePrefixes=setOf("chateau","domaine","domaines","bodega","bodegas","maison","tenuta","castello","clos","mas","cave","caves")
        internal val exactFastPathStopTerms=setOf(
            "old vines","vieilles vignes","gran reserva","grand cru","premier cru","1er cru",
            "grand vin","red wine","white wine","rose wine","vin rouge","vin blanc","vin rose",
            "brut","extra brut","sec","demi sec","cuvee","reserve","reserva","crianza",
            "vintage","millesime","recolte","cosecha","vendimia","annata","estate bottled",
            "single vineyard","barrel aged","oak aged","unfiltered","organic wine","biodynamic",
            "chapelle","bastide","abbaye","moulin","chateau","domaine","bodega","bodegas",
        )
        internal fun exactFastPathShortEligible(text:String):Boolean {
            if(text.length>96 || text.any(Char::isDigit) || '%' in text || '\n' in text) return false
            val q=normalize(text)
            if(q.length<5 || q in generic || q in exactFastPathStopTerms || noise.containsMatchIn(q)) return false
            val words=q.split(' ')
            return (words.size==1 && q.length>=5) ||
                (words.size in 2..8 && q.length in 5..7 && words.any { it.length>=4 && it !in generic })
        }
        internal fun containsWordSequence(longer:List<String>,shorter:List<String>):Boolean {
            if(shorter.isEmpty() || shorter.size>=longer.size) return false
            return (0..(longer.size-shorter.size)).any { start -> longer.subList(start,start+shorter.size)==shorter }
        }
        internal fun isCleanEstatePrefixed(raw:String):Boolean {
            if(raw.isBlank() || raw.any(Char::isDigit)) return false
            val q=normalize(raw)
            val words=q.split(' ')
            return words.size in 2..5 && words.first() in estatePrefixes && !noise.containsMatchIn(q)
        }
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
        private fun characterDistance(a:String,b:String):Int {
            if(kotlin.math.abs(a.length-b.length)>2) return 3
            var prev=IntArray(b.length+1) { it }
            for(i in a.indices) {
                val curr=IntArray(b.length+1);curr[0]=i+1
                for(j in b.indices) curr[j+1]=minOf(curr[j]+1,prev[j+1]+1,prev[j]+if(a[i]==b[j]) 0 else 1)
                prev=curr
            }
            return prev[b.length]
        }
        /** Bounded character distance shared with linked-wine matching; no particle rewriting. */
        fun distance(a:String,b:String):Int=characterDistance(a,b)

        /** Producer-name comparison only: omission of the minor French particle `de` costs zero. */
        fun producerDistance(a:String,b:String):Int {
            val raw=distance(a,b)
            val aWithoutParticles=a.split(Regex("\\s+")).filterNot { it.lowercase(Locale.ROOT) in minorProducerParticles }.joinToString(" ")
            val bWithoutParticles=b.split(Regex("\\s+")).filterNot { it.lowercase(Locale.ROOT) in minorProducerParticles }.joinToString(" ")
            return minOf(raw,distance(aWithoutParticles,bWithoutParticles))
        }
    }

    /**
     * Last-resort canonicalization for the field extractor's completed producer value. This pass
     * deliberately does not require OCR-span eligibility/support; it compares the final string with
     * the bounded database shortlist and returns the closest non-generic name within two edits. A
     * leading French/English article is ignored (`Trancette` / `La Francette`), while producer
     * omission of the minor French particle `de` is zero-cost. Grape terms and regional wording may
     * add a producer-role penalty. Exact values are retained; equal-distance candidates use the FTS
     * relevance order as a deterministic tie-break.
     */
    fun finalNearMatch(value:String,checkBudget:()->Unit={},protectExact:Boolean=false,observedExact:List<String> = emptyList(),producerPenalty:(String)->Int={0}):FinalNearMatch? {
        if(!index.primaryVerified && !index.labSupplemental) return null
        val query=normalize(value)
        if(query.isBlank()) return null
        checkBudget()
        val hits=index.lookup(query,MAX_PRODUCER_FUZZY_CANDIDATES,checkBudget).distinctBy { it.id }
        if(hits.any { it.key==query }) return null // already a database value
        if(protectExact) {
            val observed=observedExact.map(::normalize).toSet()
            val exact=hits.filter { it.key in observed && producerDistance(query,it.key)<=1 }
            if(exact.size>1) return null
            if(exact.size==1) return FinalNearMatch(exact.single(),0)
        }
        fun comparable(key:String):String {
            val words=key.split(' ').filter(String::isNotBlank)
            return if(words.firstOrNull()?.let { it in finalNearMatchLeadingArticles }==true)
                words.drop(1).joinToString(" ") else words.joinToString(" ")
        }
        val q=comparable(query)
        if(q.isBlank()) return null
        val rolePenalty=producerPenalty(value).coerceAtLeast(0)
        val candidates=hits.mapNotNull { entity ->
            val candidateKey=comparable(entity.key)
            val candidateWords=candidateKey.split(' ').filter(String::isNotBlank)
            if(candidateWords.isEmpty() || candidateWords.none { it.length>=4 && it !in generic }) null else {
                val d=producerDistance(q,candidateKey)+rolePenalty
                if(d<=MAX_FINAL_NEAR_MATCH_DISTANCE) FinalNearMatch(entity,d) else null
            }
        }
        val bestDistance=candidates.minOfOrNull { it.distance } ?: return null
        if(protectExact && candidates.count { it.distance==bestDistance }!=1) return null
        // Legacy comparators retain FTS ordering; protected experiments abstain on identity ties.
        return candidates.firstOrNull { it.distance==bestDistance }
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
        val normalizedSpans=if(policy.exactMatchFastPath) spans.map { normalize(it.text) }.filter(String::isNotEmpty) else emptyList()
        fun isSingleWordEstateFragment(key:String):Boolean {
            val words=key.split(' ')
            if(words.size!=1) return false
            val word=words[0]
            return normalizedSpans.any { sp ->
                sp!=key && word in sp.split(' ') && sp.split(' ').any { it in estatePrefixes }
            }
        }
        try {
            checkBudget()
            for(s in spans) {
                checkBudget()
                val engine=if(s.engine=="mlkit") "mlkit" else if(s.engine.startsWith("paddle")) "paddle" else "other"
                val rejection=if(s.engine.isBlank() || s.observationIds.isEmpty()) "missing engine / original observation IDs" else ineligibilityReason(s.text)
                val q=normalize(s.text)
                val rolePenalty=if(policy.useParticleAndRoleRules) policy.producerPenalty(s.text).coerceAtLeast(0) else 0
                val exactShortAllowed=rejection!=null && rolePenalty==0 && policy.exactMatchFastPath &&
                    s.engine.isNotBlank() && s.observationIds.isNotEmpty() &&
                    exactFastPathShortEligible(s.text) && !policy.nonProducerTerm(s.text) && !isSingleWordEstateFragment(q)
                trace.visit("$engine.eligible",rejection==null || exactShortAllowed,rejection.orEmpty())
                if(rejection!=null && !exactShortAllowed) continue
                trace.visit("$engine.lookup",false,"lookup did not complete")
                val candidateLimit=if(policy.useParticleAndRoleRules) MAX_PRODUCER_FUZZY_CANDIDATES else MAX_FUZZY_CANDIDATES
                val hits=index.lookup(q,candidateLimit,checkBudget)
                trace.visit("$engine.lookup",true)
                trace.visit("$engine.retrieved",hits.isNotEmpty(),"no reference candidates")
                if(hits.isNotEmpty()) trace.visit("$engine.distance",false,"no retrieved name passed distance / length bounds")
                for(e in hits.distinctBy { it.id }) {
                    checkBudget()
                    if(e.key.length>96) continue
                    val matchDist=if(policy.useParticleAndRoleRules) {
                        val d=producerDistance(q,e.key)+rolePenalty
                        d
                    } else distance(q,e.key)
                    val allowed=if(exactShortAllowed) matchDist==0 && (index.primaryVerified || "lwin" in e.support)
                        else matchDist<=if(q.length >= 20) 2 else 1
                    if(allowed) {
                        trace.visit("$engine.distance",true)
                        matches+=Match(e,s.text,s.engine,s.observationIds,matchDist)
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
        val targetCountry=canonicalCountryKey(policy.countryContext)
        if(policy.exactMatchFastPath) {
            val exactMatches=matches.filter { m ->
                m.distance==0 &&
                    m.entity.key !in exactFastPathStopTerms &&
                    !policy.nonProducerTerm(m.observed) &&
                    !policy.nonProducerTerm(m.entity.name) &&
                    !isSingleWordEstateFragment(m.entity.key)
            }
            val exactByKey=exactMatches.groupBy { it.entity.key }.filterValues { rows -> policy.supports(rows) }
            if(exactByKey.isNotEmpty()) {
                val cleanExisting=TextNorm.scrub(existing)
                val old=normalize(cleanExisting)
                if(cleanExisting.isNotBlank() && old in exactByKey) {
                    trace.visit("independent",true);trace.visit("unambiguous",true);trace.visit("separated",true)
                    trace.visit("existing",true);trace.visit("selected",true)
                    trace.visit("changed",false,"existing supported producer retained")
                    return finish(cleanExisting,"existing producer retained",matches)
                }
                val reducedKeys=exactByKey.keys.filter { key ->
                    exactByKey.keys.none { other -> other!=key && containsWordSequence(other.split(' '),key.split(' ')) }
                }
                if(reducedKeys.size==1) {
                    val winnerKey=reducedKeys.single()
                    if(!(isCleanEstatePrefixed(cleanExisting) && winnerKey.split(' ').firstOrNull() !in estatePrefixes)) {
                        val winnerRows=exactByKey.getValue(winnerKey)
                        val chosen=winnerRows.sortedWith(
                            compareByDescending<Match> { targetCountry.isNotEmpty() && canonicalCountryKey(it.entity.countryGroup)==targetCountry }
                                .thenByDescending { index.primaryVerified || "lwin" in it.entity.support }
                                .thenBy { it.entity.id }
                        ).first()
                        val entity=chosen.entity
                        val cleanName=TextNorm.scrub(entity.name)
                        trace.visit("independent",true);trace.visit("unambiguous",true);trace.visit("separated",true)
                        trace.visit("existing",true);trace.visit("selected",true)
                        trace.visit("changed",cleanName!=cleanExisting,"existing supported producer retained")
                        val supplementalSources=entity.support.filter { it!="lwin" }.sorted()
                        val support=if(winnerRows.any { a -> winnerRows.any { b -> a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } })
                            "two engines" else "one engine reading under the lab single-reading policy"
                        val reason=if(supplementalSources.isEmpty())
                            "primary LWIN producer/brand name supported by $support (d=0 exact match); confirm manually"
                            else "Lab expanded producer candidate (${supplementalSources.joinToString()}) supported by $support (d=0 exact match); confirm manually"
                        return finish(cleanName,reason,matches,entity.id)
                    }
                }
            }
        }
        val groups=matches.groupBy { it.entity.id }
        fun independent(rows:List<Match>)=rows.any { a -> rows.any { b ->
            a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } }
        fun blockedSingleReadingSupplementalFuzzy(rows:List<Match>)=
            policy.useParticleAndRoleRules && index.labSupplemental && policy.minimumIndependentEngines<2 &&
                rows.none { it.distance==0 } && rows.none { "lwin" in it.entity.support } &&
                !independent(rows)
        val supplementalFuzzyBlocked=groups.values.any { rows -> blockedSingleReadingSupplementalFuzzy(rows) }
        val viableRaw=groups.filterValues { rows ->
            policy.supports(rows) && !blockedSingleReadingSupplementalFuzzy(rows)
        }
        val independentReason=if(supplementalFuzzyBlocked)
            "single-engine fuzzy supplemental candidate needs LWIN or independent engines"
            else "no independently supported producer"
        trace.visit("independent",viableRaw.isNotEmpty(),independentReason)
        if(viableRaw.isEmpty()) return finish(existing,
            if(supplementalFuzzyBlocked) "single-engine fuzzy supplemental candidate needs LWIN or independent engines; unchanged"
            else "no independently supported producer; unchanged",matches)
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
        var replacedStrongGrapeGuess=false
        if(cleanExisting.isNotBlank()) {
            val old=normalize(cleanExisting)
            if(old==entity.key) {
                trace.visit("existing",true);trace.visit("selected",true)
                trace.visit("changed",false,"existing supported producer retained")
                return finish(cleanExisting,"existing producer retained",matches)
            }
            val cmpDist=if(policy.useParticleAndRoleRules) producerDistance(old,entity.key) else distance(old,entity.key)
            val conflictsWithObservedWinner=winner.none { normalize(it.observed)==old } || cmpDist>1
            val existingRolePenalty=if(policy.useParticleAndRoleRules) policy.producerPenalty(cleanExisting).coerceAtLeast(0) else 0
            if(conflictsWithObservedWinner && existingRolePenalty<3) {
                trace.visit("existing",false,"different existing producer protected")
                return finish(cleanExisting,"different existing producer protected",matches)
            }
            replacedStrongGrapeGuess=conflictsWithObservedWinner && existingRolePenalty >= 3
        }
        if(replacedStrongGrapeGuess) trace.visit("existing",true,"replaced baseline with strong grape-variety cue")
        else trace.visit("existing",true)
        trace.visit("selected",true)
        trace.visit("changed",cleanName!=cleanExisting,"existing supported producer retained")
        val supplementalSources=entity.support.filter { it!="lwin" }.sorted()
        // The reason states the support that actually exists, not the policy minimum, so a two-engine
        // win still reads as two engines when the lab policy is on.
        val support=if(winner.any { a -> winner.any { b -> a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } })
            "two engines" else "one engine reading under the lab single-reading policy"
        val baseReason=if(supplementalSources.isEmpty())
            "primary LWIN producer/brand name supported by $support"
            else "Lab expanded producer candidate (${supplementalSources.joinToString()}) supported by $support"
        val reason=baseReason+(if(replacedStrongGrapeGuess) "; replaced a baseline guess with a strong grape-variety cue" else "")+
            "; confirm manually"
        return finish(cleanName,reason,matches,id)
    }
}
