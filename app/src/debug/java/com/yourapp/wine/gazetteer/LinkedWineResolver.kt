package com.yourapp.wine.gazetteer

/** A linked name match needs its own OCR evidence, not merely a producer's catalogue membership. */
internal class LinkedWineResolver(private val index:Index) {
    data class Wine(val id:String,val name:String,val key:String,val producerId:String,val lwin7:String)
    interface Index:ProducerResolver.Index {
        fun lookupWines(key:String,producerIds:Set<String>,limit:Int,checkBudget:()->Unit):List<Wine>
    }
    data class Match(val wine:Wine,val span:ProducerResolver.Span,val distance:Int)
    data class Decision(val producer:String,val wine:String,val reason:String,val matches:List<Match> = emptyList(),
        val producerId:String?=null,val wineId:String?=null,val lwin7:String="")
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
        spans:List<ProducerResolver.Span>,checkBudget:()->Unit={}):Decision {
        fun unchanged(reason:String,matches:List<Match> = emptyList())=Decision(producer.value,existingWine,reason,matches)
        if(!index.primaryVerified) return unchanged("primary wine index unavailable")
        if(spans.size>ProducerResolver.MAX_SPANS) return unchanged("wine span limit; unchanged")
        val matches=mutableListOf<Match>()
        try {
            checkBudget()
            val producers=producer.matches.groupBy { it.entity.id }.filterValues(::independent).toMutableMap()
            // Validate an already-present single-word producer by exact OCR and exact primary name only.
            // This does not allow a wine name to introduce or rename a producer.
            if(existingProducer.isNotBlank()) {
                val key=ProducerResolver.normalize(existingProducer)
                val observed=spans.filter { ProducerResolver.normalize(it.text)==key && it.observationIds.isNotEmpty() && it.engine.isNotBlank() }
                if(observed.map { it.engine }.distinct().size>=2) {
                    val exact=index.lookup(key,33,checkBudget)
                    if(exact.size<=32) for(e in exact.filter { it.key==key }) {
                        val rows=observed.map { ProducerResolver.Match(e,it.text,it.engine,it.observationIds,0) }
                        if(independent(rows)) producers[e.id]=rows
                    }
                }
            }
            // Protect a different existing producer. Only existing/correctable independently read names can pair.
            val old=ProducerResolver.normalize(existingProducer)
            val candidates=producers.filterValues { rows ->
                old.isBlank() || rows.first().entity.key==old ||
                    (ProducerResolver.distance(old,rows.first().entity.key)<=1 && rows.any { ProducerResolver.normalize(it.observed)==old })
            }
            if(candidates.isEmpty()) return unchanged("no independently observed producer for wine link")
            if(candidates.size>8) return unchanged("too many producer identities; wine link abstained")
            for(span in spans) {
                checkBudget()
                if(span.engine.isBlank() || span.observationIds.isEmpty() || !eligible(span.text)) continue
                val key=ProducerResolver.normalize(span.text)
                val wines=index.lookupWines(key,candidates.keys,33,checkBudget)
                if(wines.size>32) continue
                for(wine in wines) {
                    checkBudget()
                    if(wine.producerId !in candidates || wine.key.length>120) continue
                    // Producer and wine cannot reuse the same text or overlapping observations as two fields.
                    val pr=candidates.getValue(wine.producerId)
                    if(wine.key==pr.first().entity.key) continue
                    if(pr.any { it.observationIds.intersect(span.observationIds).isNotEmpty() }) continue
                    val distance=ProducerResolver.distance(key,wine.key)
                    if(distance<=if(key.length>=20) 2 else if(key.length>=8) 1 else 0)
                        matches+=Match(wine,span,distance)
                }
            }
            checkBudget()
            // Multiple LWIN products with the same producer + normalized WINE text are one name suggestion,
            // not independent support or permission to infer colour, vintage, site or other product attributes.
            val groups=matches.groupBy { it.wine.producerId to it.wine.key }
            val viable=groups.filterValues { rows -> rows.any { a -> rows.any { b ->
                a.span.engine!=b.span.engine && a.span.observationIds.intersect(b.span.observationIds).isEmpty() } } }
            if(viable.size!=1) return unchanged(if(viable.isEmpty()) "no independently observed linked wine" else "ambiguous linked wines/producers; unchanged",matches)
            val (pair,rows)=viable.entries.single();val best=rows.minOf { it.distance }
            if(groups.any { (other,rivals) -> other!=pair && rivals.minOf { it.distance }<=best+1 })
                return unchanged("linked wine rival too close; unchanged",matches)
            val p=candidates.getValue(pair.first).first().entity
            val wine=rows.minWith(compareBy<Match> { it.distance }.thenBy { it.wine.name }).wine
            if(producer.acceptedId!=null && producer.acceptedId!=p.id)
                return unchanged("wine conflicts with independently selected producer",matches)
            if(existingWine.isNotBlank()) {
                val previous=ProducerResolver.normalize(existingWine)
                if(previous!=wine.key && (ProducerResolver.distance(previous,wine.key)>1 || rows.none { ProducerResolver.normalize(it.span.text)==previous }))
                    return unchanged("different existing wine protected",matches)
            }
            checkBudget()
            return Decision(if(old==p.key) existingProducer else p.name,
                if(ProducerResolver.normalize(existingWine)==wine.key) existingWine else wine.name,
                "independent producer and wine OCR agree with primary LWIN relationship; confirm manually",matches,p.id,wine.id,wine.lwin7)
        } catch(_:ProducerResolver.BudgetExceeded) { return unchanged("linked wine budget reached; unchanged") }
    }
}
