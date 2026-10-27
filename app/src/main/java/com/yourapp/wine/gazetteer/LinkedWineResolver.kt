package com.yourapp.wine.gazetteer

import com.yourapp.wine.diagnostics.PipelineFunnel
import com.yourapp.wine.merge.TextNorm

/** A linked name match needs its own OCR evidence, not merely a producer's catalogue membership. */
internal class LinkedWineResolver(private val index:Index,
    private val onFailureTelemetry:(Map<String,String>)->Unit={}) {
    data class Wine(val id:String,val name:String,val key:String,val producerId:String,val lwin7:String)
    interface Index:ProducerResolver.Index {
        fun lookupWines(key:String,producerIds:Set<String>,limit:Int,checkBudget:()->Unit):List<Wine>
    }
    data class Match(val wine:Wine,val span:ProducerResolver.Span,val distance:Int)
    data class Decision(val producer:String,val wine:String,val reason:String,val matches:List<Match> = emptyList(),
        val producerId:String?=null,val wineId:String?=null,val lwin7:String="",val telemetry:Map<String,String> = emptyMap())
    companion object {
        const val VERSION="linked-wine-v1"
        private val noise=Regex("\\b(?:imported|importer|bottled|bottler|produced by|embouteille|mis en bouteille|distributed|alcohol|alc|vol|ml|cl|litres?|liters?|barcode|ean|upc|contains|sulfites|sulphites|government|warning|appellation|controlee|protegee|product of|produit de|www|http|llc|inc|corp|ltd)\\b")
        private val generic=setOf("wine","vin","red wine","white wine","estate","reserve","reserva","grand cru","premier cru","appellation")
        fun eligible(text:String):Boolean {
            val key=ProducerResolver.normalize(text)
            return text.length<=120 && key.length>=4 && key.split(' ').size<=12 && key.any(Char::isLetter) &&
                !Regex("\\b(?:19|20)\\d{2}\\b").containsMatchIn(key) && '%' !in text && '\n' !in text &&
                !Regex("\\b\\d+(?:ml|cl|l)\\b").containsMatchIn(key) && key !in generic && !noise.containsMatchIn(key)
        }
        private fun independent(rows:List<ProducerResolver.Match>)=rows.any { a -> rows.any { b ->
            a.engine!=b.engine && a.observationIds.intersect(b.observationIds).isEmpty() } }
    }
    fun resolve(existingProducer:String,existingWine:String,producer:ProducerResolver.Decision,
        spans:List<ProducerResolver.Span>,checkBudget:()->Unit={},policy:ProducerResolver.Policy=ProducerResolver.Policy.prod):Decision {
        val trace=PipelineFunnel.Recorder("wine.")
        trace.visit("invoked",true)
        fun unchanged(reason:String,matches:List<Match> = emptyList(),completed:Boolean=true):Decision {
            trace.visit("execution",completed,reason)
            return Decision(TextNorm.scrub(producer.value),TextNorm.scrub(existingWine),reason,matches,telemetry=trace.fields())
        }
        // The wine index is the verified primary export in both index variants; supplemental
        // Lab producers simply have no linked wines and abstain below.
        val indexPermitted=index.primaryVerified || index.labSupplemental
        trace.visit("primary",indexPermitted,"primary wine index unavailable")
        if(!indexPermitted) return unchanged("primary wine index unavailable")
        trace.visit("spans",spans.size<=ProducerResolver.MAX_SPANS,"wine span limit")
        if(spans.size>ProducerResolver.MAX_SPANS) return unchanged("wine span limit; unchanged")
        val matches=mutableListOf<Match>()
        try {
            checkBudget()
            val producers=producer.matches.groupBy { it.entity.id }.filterValues { rows -> policy.supports(rows) }.toMutableMap()
            // Validate an already-present single-word producer by exact OCR and exact primary name only.
            // This does not allow a wine name to introduce or rename a producer.
            val effectiveExistingProducer=if(policy.exactMatchFastPath && producer.acceptedId!=null) producer.value else existingProducer
            if(effectiveExistingProducer.isNotBlank()) {
                val key=ProducerResolver.normalize(effectiveExistingProducer)
                val observed=spans.filter { ProducerResolver.normalize(it.text)==key && it.observationIds.isNotEmpty() && it.engine.isNotBlank() }
                if(observed.map { it.engine }.distinct().size>=policy.minimumIndependentEngines) {
                    val exact=index.lookup(key,33,checkBudget)
                    if(exact.size<=32) for(e in exact.filter { it.key==key }) {
                        val rows=observed.map { ProducerResolver.Match(e,it.text,it.engine,it.observationIds,0) }
                        if(policy.supports(rows)) producers[e.id]=rows
                    }
                }
            }
            trace.visit("producer_observed",producers.isNotEmpty(),"no independently observed producer")
            // Protect a different existing producer. Only existing/correctable independently read names can pair.
            val old=ProducerResolver.normalize(effectiveExistingProducer)
            val candidates=producers.filterValues { rows ->
                old.isBlank() || rows.first().entity.key==old ||
                    (ProducerResolver.distance(old,rows.first().entity.key)<=1 && rows.any { ProducerResolver.normalize(it.observed)==old })
            }
            trace.visit("producer_compatible",candidates.isNotEmpty(),if(producers.isEmpty()) "no independently observed producer" else "different existing producer protected")
            if(candidates.isEmpty()) return unchanged("no independently observed producer for wine link")
            trace.visit("producer_bound",candidates.size<=8,"too many producer identities")
            if(candidates.size>8) return unchanged("too many producer identities; wine link abstained")
            for(span in spans) {
                checkBudget()
                val engine=if(span.engine=="mlkit") "mlkit" else if(span.engine.startsWith("paddle")) "paddle" else "other"
                val valid=span.engine.isNotBlank() && span.observationIds.isNotEmpty() && eligible(span.text)
                trace.visit("$engine.eligible",valid,"wine span eligibility / provenance rejected")
                if(!valid) continue
                val key=ProducerResolver.normalize(span.text)
                trace.visit("$engine.lookup",false,"linked lookup did not complete")
                val wines=index.lookupWines(key,candidates.keys,ProducerResolver.MAX_FUZZY_CANDIDATES,checkBudget)
                trace.visit("$engine.lookup",true)
                trace.visit("$engine.retrieved",wines.isNotEmpty(),"no producer-linked wine candidates")
                for(wine in wines) {
                    checkBudget()
                    if(wine.producerId !in candidates || wine.key.length>120) continue
                    // Producer and wine cannot reuse the same text or overlapping observations as two fields.
                    val pr=candidates.getValue(wine.producerId)
                    val disjoint=wine.key!=pr.first().entity.key && pr.none { it.observationIds.intersect(span.observationIds).isNotEmpty() }
                    trace.visit("$engine.disjoint",disjoint,"wine reuses producer name / observations")
                    if(!disjoint) continue
                    val distance=ProducerResolver.distance(key,wine.key)
                    val close=distance<=if(key.length>=20) 2 else if(key.length>=8) 1 else 0
                    trace.visit("$engine.distance",close,"linked name outside edit-distance bound")
                    if(close) matches+=Match(wine,span,distance)
                }
            }
            checkBudget()
            val resolvedProducerKey=ProducerResolver.normalize(producer.value)
            if(policy.exactMatchFastPath) {
                val exactGroups=matches.filter { it.distance==0 }
                    .groupBy { candidates.getValue(it.wine.producerId).first().entity.key to it.wine.key }
                    .filterValues { rows -> policy.minimumIndependentEngines<2 || rows.any { a -> rows.any { b ->
                        a.span.engine!=b.span.engine && a.span.observationIds.intersect(b.span.observationIds).isEmpty() } } }
                if(exactGroups.size==1) {
                    val (_,rows)=exactGroups.entries.single()
                    val bestMatch=rows.minWith(compareBy<Match> { it.distance }.thenBy { it.wine.name })
                    val wine=bestMatch.wine
                    val p=candidates.getValue(wine.producerId).first().entity
                    val consistent=producer.acceptedId==null || producer.acceptedId==p.id || p.key==resolvedProducerKey
                    if(consistent) {
                        val previous=ProducerResolver.normalize(existingWine)
                        val staleRawProducer=ProducerResolver.normalize(existingProducer)
                        val existingIsDistinctCuvee=existingWine.isNotBlank() &&
                            previous!=wine.key && previous!=p.key && previous!=old && previous!=staleRawProducer &&
                            !policy.nonProducerTerm(existingWine) && policy.nonProducerTerm(wine.name)
                        if(!existingIsDistinctCuvee) {
                            checkBudget()
                            trace.visit("independent",true);trace.visit("unambiguous",true);trace.visit("separated",true)
                            trace.visit("producer_consistent",true);trace.visit("existing",true)
                            trace.visit("selected",true);trace.visit("execution",true)
                            trace.visit("changed",previous!=wine.key,"existing supported wine retained")
                            val agreement=if(rows.any { a -> rows.any { b ->
                                    a.span.engine!=b.span.engine && a.span.observationIds.intersect(b.span.observationIds).isEmpty() } })
                                "independent producer and wine OCR agree with primary LWIN relationship (d=0 exact match); confirm manually"
                                else "single-engine producer and wine OCR agree with primary LWIN relationship under the lab single-reading policy (d=0 exact match); confirm manually"
                            val cleanExistingProducer=TextNorm.scrub(effectiveExistingProducer)
                            val cleanProducerName=TextNorm.scrub(p.name)
                            val cleanExistingWine=TextNorm.scrub(existingWine)
                            val cleanWineName=TextNorm.scrub(wine.name)
                            val finalProducerId=if(producer.acceptedId!=null && p.key==resolvedProducerKey) producer.acceptedId else p.id
                            return Decision(if(old==p.key) cleanExistingProducer else cleanProducerName,
                                if(ProducerResolver.normalize(cleanExistingWine)==wine.key) cleanExistingWine else cleanWineName,
                                agreement,matches,finalProducerId,wine.id,wine.lwin7,trace.fields())
                        }
                    }
                }
            }
            // Multiple LWIN products with the same producer + normalized WINE text are one name suggestion,
            // not independent support or permission to infer colour, vintage, site or other product attributes.
            val groups=matches.groupBy { it.wine.producerId to it.wine.key }
            val viable=groups.filterValues { rows -> policy.minimumIndependentEngines<2 || rows.any { a -> rows.any { b ->
                a.span.engine!=b.span.engine && a.span.observationIds.intersect(b.span.observationIds).isEmpty() } } }
            trace.visit("independent",viable.isNotEmpty(),"no independently observed linked wine")
            if(viable.isNotEmpty()) trace.visit("unambiguous",viable.size==1,"ambiguous linked wines/producers")
            if(viable.size!=1) return unchanged(if(viable.isEmpty()) "no independently observed linked wine" else "ambiguous linked wines/producers; unchanged",matches)
            val (pair,rows)=viable.entries.single();val best=rows.minOf { it.distance }
            val separated=groups.none { (other,rivals) -> other!=pair && rivals.minOf { it.distance }<=best+1 }
            trace.visit("separated",separated,"linked wine rival too close")
            if(!separated) return unchanged("linked wine rival too close; unchanged",matches)
            val p=candidates.getValue(pair.first).first().entity
            val wine=rows.minWith(compareBy<Match> { it.distance }.thenBy { it.wine.name }).wine
            val consistent=producer.acceptedId==null || producer.acceptedId==p.id ||
                (policy.exactMatchFastPath && p.key==resolvedProducerKey)
            trace.visit("producer_consistent",consistent,"wine conflicts with independently selected producer")
            if(!consistent) return unchanged("wine conflicts with independently selected producer",matches)
            if(existingWine.isNotBlank()) {
                val previous=ProducerResolver.normalize(existingWine)
                if(previous!=wine.key && (ProducerResolver.distance(previous,wine.key)>1 || rows.none { ProducerResolver.normalize(it.span.text)==previous })) {
                    trace.visit("existing",false,"different existing wine protected")
                    return unchanged("different existing wine protected",matches)
                }
            }
            checkBudget()
            trace.visit("existing",true);trace.visit("selected",true);trace.visit("execution",true)
            trace.visit("changed",ProducerResolver.normalize(existingWine)!=wine.key,"existing supported wine retained")
            // State the support that exists: under the lab policy a two-engine win keeps the Prod wording.
            val agreement=if(rows.any { a -> rows.any { b ->
                    a.span.engine!=b.span.engine && a.span.observationIds.intersect(b.span.observationIds).isEmpty() } })
                "independent producer and wine OCR agree with primary LWIN relationship; confirm manually"
                else "single-engine producer and wine OCR agree with primary LWIN relationship under the lab single-reading policy; confirm manually"
            val cleanExistingProducer=TextNorm.scrub(effectiveExistingProducer)
            val cleanProducerName=TextNorm.scrub(p.name)
            val cleanExistingWine=TextNorm.scrub(existingWine)
            val cleanWineName=TextNorm.scrub(wine.name)
            return Decision(if(old==p.key) cleanExistingProducer else cleanProducerName,
                if(ProducerResolver.normalize(cleanExistingWine)==wine.key) cleanExistingWine else cleanWineName,
                agreement,matches,p.id,wine.id,wine.lwin7,trace.fields())
        } catch(_:ProducerResolver.BudgetExceeded) { return unchanged("linked wine budget reached; unchanged",completed=false) }
        catch(error:Exception) {
            trace.visit("execution",false,error.javaClass.simpleName)
            onFailureTelemetry(trace.fields())
            throw error // Do not turn a failed lookup into a partial selection.
        }
    }
}
