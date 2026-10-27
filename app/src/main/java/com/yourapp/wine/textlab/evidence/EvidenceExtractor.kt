package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.extract.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

internal data class Hypothesis(val values:Map<FieldId,String>,val phrase:Phrase,val rank:Double,val reason:String,val place:Place?=null)
internal data class EvidenceResult(val fill:FillResult,val graph:EvidenceGraph,val hypotheses:List<Hypothesis>,val elapsedMs:Long,val trace:EvidenceTrace)

/** Independent lab-only solver. Shared lexical catalogs, not production line/merge/extract/validate rules. */
internal class EvidenceExtractor(internal val baseKb:WineKnowledgeBase,private val typedIdentity:Boolean=true,private val search:EvidenceSearchConfig=EvidenceSearchConfig(),private val ranking:EvidenceRankingConfig=EvidenceRankingConfig(),private val placeRepair:PlaceRepair.Config=PlaceRepair.Config.off) {
    companion object { const val VERSION="evidence-lab-v4-context";const val DEFAULT_BUDGET_MS=60_000L;const val MAX_HYPOTHESES=384 }
    private val kb=SupplementedWineKnowledgeBase(baseKb)
    private val gazetteer=PlaceGazetteer(kb)
    private val hierarchy=PlaceHierarchy(kb)
    private val view=hierarchy.snapshot()
    private val repairs=PlaceRepair(kb,hierarchy)
    private val roles=ProducerLineSelector()
    private fun family(engineId:String)=if(engineId.startsWith("paddle")) "paddle" else engineId
    private val foundingYearCue = Regex("\\b(?:fondee?|founded|established|est\\.?|since|depuis|first\\s+vintage|anno|dal)\\b")
    private val leadingYearPattern = Regex("^((?:19|20)\\d{2})\\s*[-\\u2013\\u2014\\u00b7,:|/ ]+\\s*(.+)$")
    private val trailingYearPattern = Regex("^(.+?)\\s*[-\\u2013\\u2014\\u00b7,:|/ ]+\\s*((?:19|20)\\d{2})$")
    private val harvestYearPattern = Regex("(?i)\\b(?:[cgos]osecha|vendimia|annata|millesime|recolte|harvest)\\s+((?:19|20)\\d{2})\\b")

    private fun detachEdgeVintage(raw: String, maxYear: Int): Pair<String, String?> {
        val trimmed = raw.trim()
        val norm = TextNorm.normalize(trimmed)
        if (foundingYearCue.containsMatchIn(norm)) return trimmed to null
        val lead = leadingYearPattern.matchEntire(trimmed)
        if (lead != null) {
            val yr = lead.groupValues[1]
            val rest = lead.groupValues[2].trim()
            if (yr.toInt() in 1900..maxYear && rest.count(Char::isLetter) >= 3) return rest to yr
        }
        val trail = trailingYearPattern.matchEntire(trimmed)
        if (trail != null) {
            val rest = trail.groupValues[1].trim()
            val yr = trail.groupValues[2]
            if (yr.toInt() in 1900..maxYear && rest.count(Char::isLetter) >= 3) return rest to yr
        }
        return trimmed to null
    }

    private fun explicitHarvestVintage(norm: String, maxYear: Int): String? {
        if (foundingYearCue.containsMatchIn(norm)) return null
        val yr = harvestYearPattern.find(norm)?.groupValues?.get(1) ?: return null
        return yr.takeIf { it.toInt() in 1900..maxYear }
    }

    /** Maps an LWIN `country_group` string to a verified catalog country name, or null when unknown. */
    fun canonicalCountryName(countryGroup:String):String? {
        val clean=TextNorm.scrub(countryGroup).trim()
        if(clean.isBlank()) return null
        val mapped=if(clean.equals("Russian Federation",ignoreCase=true)) "Russia" else clean
        return ReferenceWineCatalog.countryCodes.keys.firstOrNull { it.equals(mapped,ignoreCase=true) }
    }

    /**
     * Lab v4 only: when `LinkedWineResolver` confirms a `supported_pair` LWIN wine whose name (or
     * classification/designation base) is itself an unambiguous catalog appellation compatible with
     * any already-resolved geography and producer country, returns only the still-missing ancestors.
     */
    fun resolveLinkedWineGeography(
        wineName:String,
        existingCountry:String,
        existingRegion:String,
        existingSubregion:String,
        producerCountryGroup:String="",
    ):Map<FieldId,String> {
        val clean=TextNorm.scrub(wineName).trim()
        if(clean.isBlank()) return emptyMap()
        val existingPlaces=listOf(existingCountry,existingRegion,existingSubregion).filter(String::isNotBlank)
            .map { gazetteer.matches(TextNorm.normalize(it)) }
        if(existingPlaces.any { it.isEmpty() }) return emptyMap()
        val producerCountry=canonicalCountryName(producerCountryGroup)
        val producerCode=producerCountry?.let { ReferenceWineCatalog.countryCodes[it] }
        val contextCountries=setOfNotNull(
            existingCountry.takeIf(String::isNotBlank)?.let { c -> ReferenceWineCatalog.countryCodes.entries.firstOrNull { it.key.equals(TextNorm.scrub(c),true) }?.value },
            producerCode,
        )
        val contextRegions=existingRegion.takeIf(String::isNotBlank)?.let { setOf(TextNorm.normalize(it)) }.orEmpty()
        val candidateForms=listOfNotNull(
            WineDesignations.classificationBase(clean),
            clean,
            WineDesignations.split(clean).base.takeIf { it!=clean && WineDesignations.isAppellationName(it) },
        ).distinct()
        for(candidate in candidateForms) {
            val key=EvidenceRanking.nameKey(candidate)
            if(key.isEmpty()) continue
            val matches=gazetteer.matches(TextNorm.normalize(candidate),contextCountries,contextRegions,true)
                .filter { place ->
                    place.type!="country" &&
                        (listOf(place.name)+BundledOrigins.spellingForms(place)).any { EvidenceRanking.nameKey(it)==key } &&
                        (producerCode==null || place.countryCode==producerCode) &&
                        existingPlaces.all { alternatives -> alternatives.any { view.compatible(place,it) } }
                }
            if(matches.size!=1) continue
            val place=matches.single()
            val path=view.ancestors(place)
            val missing=linkedMapOf<FieldId,String>()
            if(existingCountry.isBlank()) path.lastOrNull { it.type=="country" }?.let { missing[FieldId.COUNTRY]=it.name }
            val multiRegion=(ReferenceWineCatalog.origin(place)?.regions?.size ?: 0)>1
            if(existingRegion.isBlank() && !multiRegion) path.firstOrNull { it.type=="region" }?.let { missing[FieldId.REGION]=it.name }
            val resolvedRegion=existingRegion.ifBlank { missing[FieldId.REGION].orEmpty() }
            if(existingSubregion.isBlank() && place.type !in setOf("country","region") && !place.name.equals(resolvedRegion,ignoreCase=true) && !ReferenceWineCatalog.isRegionWideIndication(place)) {
                missing[FieldId.SUBREGION]=place.name
            }
            return missing
        }
        return emptyMap()
    }

    fun extract(records:List<TokenRecord>,year:Int,budgetMs:Long=DEFAULT_BUDGET_MS):EvidenceResult {
        val started=System.nanoTime()
        fun checkBudget() {
            if(Thread.currentThread().isInterrupted) throw InterruptedException("Evidence extraction interrupted")
            check((System.nanoTime()-started)/1_000_000<budgetMs) { "Evidence extraction budget exceeded" }
        }
        val trace=EvidenceTrace()
        val graph=EvidenceLexicon.expand(EvidenceGraphBuilder.build(records,::checkBudget,typedIdentity),::checkBudget)
        trace.add("graph", "observations=${graph.observations.size}; phrases=${graph.phrases.size}; truncated=${graph.truncated}")
        val hypotheses=mutableListOf<Hypothesis>()
        val roleNotes=mutableListOf<String>()
        val labelContext=LabelContext.collect(graph,gazetteer,view,kb,typedIdentity)
        trace.add("label_context",labelContext.traceSummary)
        for(p in graph.phrases.sortedByDescending { it.construction.contains("alternative") }) {
            checkBudget()
            if(hypotheses.size>=MAX_HYPOTHESES) { trace.add("hypothesis_limit", "remaining phrases not searched");break }
            val text=WineTermSpacing.wineWords.repair(p.text).text;val norm=TextNorm.normalize(text)
            val endsConnector=Regex("\\b(de|du|di|des)$").containsMatchIn(norm)
            val beginsConnector=Regex("^(de|du|di|des)\\b").containsMatchIn(norm)
            val lexicalBonus=6.0*p.construction.split("alternative").lastIndex
            val sources=graph.observations.filter { it.id in p.observations }
            val supported=sources.any { it.record.score >= if(it.record.engineId.startsWith("paddle")) .75f else .60f }
            if(!supported) { trace.add("unsupported", "phrase=${p.id}");continue } // original reading retained
            val nonOriginAdmin=LabelContext.isNonOriginAdministrative(text,norm)
            val originFramingAdmin=!nonOriginAdmin && MetadataRules.isBoilerplate(text)
            val administrative=nonOriginAdmin || originFramingAdmin
            val paragraph=p.box.width()<.25f && graph.phrases.count { other -> other.id!=p.id &&
                kotlin.math.abs(other.box.left-p.box.left)<.04f && kotlin.math.abs(other.box.centerY()-p.box.centerY())<.12f }>=3
            val headingOnly=typedIdentity && EvidenceIdentityRoles.headingOnly(text)
            val namedEstate=typedIdentity && EvidenceIdentityRoles.namedEstate(text)
            if(nonOriginAdmin || headingOnly) { trace.add(if(nonOriginAdmin) "administrative" else "incomplete_producer_heading", "phrase=${p.id}; text=$text");continue }
            val framed=WineDesignations.framedAppellation(text)
            val (yearlessText,attachedYear)=if(framed==null) detachEdgeVintage(text,year) else (text to null)
            val placeSubject=framed ?: yearlessText
            val subjectNorm=TextNorm.normalize(placeSubject)
            val contextCountries=labelContext.contextCountriesForPhrase(p.id)
            val contextRegions=labelContext.contextRegionsForPhrase(p.id)
            val standaloneOrFramed=framed!=null || (!originFramingAdmin && subjectNorm.split(' ').size<=4)
            val exact=if(namedEstate) emptyList() else {
                val subjectMatches=gazetteer.matches(subjectNorm,contextCountries,contextRegions,standaloneOrFramed)
                if(subjectMatches.isNotEmpty() || framed!=null) {
                    if(subjectMatches.isNotEmpty() || framed==null) subjectMatches
                    else gazetteer.matches(norm,contextCountries,contextRegions,false)
                } else emptyList()
            }
            // A classification-qualified catalog name (`Saint-Emilion Grand Cru`, `Bordeaux Superieur`)
            // names the same place as its base: the geography comes from the base, the printed form
            // stays available for the wine name below. Zone-forming suffixes (`Chianti Classico`) are
            // deliberately not split, so they keep their own hierarchy.
            val printedBase=WineDesignations.classificationBase(placeSubject)
            val baseFromPrinted=if(namedEstate) emptyList() else printedBase?.let { base ->
                gazetteer.matches(TextNorm.normalize(base),contextCountries,contextRegions,true).filter { it.type!="country" }
            }.orEmpty()
            val baseFromCatalog=exact.mapNotNull { place -> WineDesignations.classificationBase(place.name) }.firstOrNull()
                ?.let { base -> gazetteer.matches(TextNorm.normalize(base),contextCountries,contextRegions,true).filter { it.type!="country" } }.orEmpty()
            val classificationName=baseFromCatalog.isNotEmpty() || (baseFromPrinted.isNotEmpty() && exact.isEmpty())
            val matched=when {
                baseFromCatalog.isNotEmpty() -> baseFromCatalog
                exact.isNotEmpty() -> exact
                else -> baseFromPrinted
            }
            // Lab-only, off by default: a bounded repair may name a place the exact trie could not read.
            // Uses explicit country/region anchors first, then LabelContext legal/producer country cues,
            // then unanchored distinctive >=8-letter appellations separated from rivals by >= best+2 edits.
            val repair=if(placeRepair.enabled && matched.isEmpty() && (!originFramingAdmin || framed!=null) && !namedEstate) {
                val anchors=labelContext.anchorsExcluding(p.id)
                val countryHints=labelContext.countryHintsExcluding(p.id)+(labelContext.contextCountriesForPhrase(p.id).takeIf { framed!=null }.orEmpty())
                val readings=if(framed!=null) {
                    sources.map { source ->
                        val inner=WineDesignations.framedAppellation(source.record.textRaw) ?: source.record.textRaw
                        PlaceRepair.Reading(family(source.record.engineId),inner)
                    }
                } else {
                    sources.map { source -> PlaceRepair.Reading(family(source.record.engineId),source.record.textRaw) }
                }
                repairs.resolve(placeSubject,readings,anchors,placeRepair,countryHints)
                    ?: printedBase?.takeIf { it!=placeSubject }?.let { base -> repairs.resolve(base,readings,anchors,placeRepair,countryHints) }
            } else null
            if(repair!=null) trace.add("place_repair","phrase=${p.id}; ${repair.reason}")
            val places=repair?.let { listOf(it.place) } ?: matched
            roleNotes+="phrase=${p.id}; administrative=$administrative; paragraph=$paragraph; places=${places.joinToString { it.name }}"+(if(repair!=null) "; ${repair.reason}" else "")
            val incomplete=sources.count { source ->
                val key=TextNorm.normalize(source.record.textRaw).filter(Char::isLetter)
                key.length>=3 && graph.observations.any { other ->
                    val full=TextNorm.normalize(other.record.textRaw).filter(Char::isLetter)
                    other.record.engineId!=source.record.engineId && other.record.score>=.75f &&
                        full.length>=key.length+3 && full.startsWith(key) &&
                        com.yourapp.wine.merge.BoxMath.iou(other.record.bboxRect,source.record.bboxRect)>.08f
                }
            }
            // One word that another engine read as the start of a longer overlapping line is a fragment
            // of that line (CRUCLASS for CRU CLASSE, HATEAU for CHATEAU), never a producer or wine name.
            val fragmentOnly=norm.split(' ').size==1 && incomplete>0
            fun offer(values:Map<FieldId,String>,rank:Double,why:String,place:Place?=null) {
                if(fragmentOnly && values.keys.all { it==FieldId.PRODUCER || it==FieldId.WINE_NAME }) {
                    trace.add("fragment_identity","phrase=${p.id}; text=$text");return
                }
                val weakContent=sources.count { TextNorm.normalize(it.record.textRaw) !in setOf("de","du","di","des") && it.record.score < if(it.record.engineId.startsWith("paddle")) .75f else .60f }
                if(hypotheses.size<MAX_HYPOTHESES) hypotheses+=Hypothesis(values,p,rank-weakContent*10-incomplete*6,why+if(incomplete>0) "; overlapping engine supplies a fuller reading" else "",place)
            }
            if(!originFramingAdmin && Regex("(?:19|20)\\d{2}").matches(norm) && norm.toInt() in 1900..year)
                offer(mapOf(FieldId.VINTAGE to norm),55.0,"complete observed vintage")
            else if(!originFramingAdmin) {
                val embeddedYear=(attachedYear?.takeIf { places.isNotEmpty() }) ?: explicitHarvestVintage(norm,year)
                if(embeddedYear!=null) offer(mapOf(FieldId.VINTAGE to embeddedYear),52.0,"embedded observed vintage")
            }
            for(place in places.take(4)) {
                val path=view.ancestors(place)
                val values=mutableMapOf<FieldId,String>()
                path.lastOrNull { it.type=="country" }?.let { values[FieldId.COUNTRY]=it.name }
                val multiRegion=(ReferenceWineCatalog.origin(place)?.regions?.size ?: 0)>1
                if(!multiRegion) path.firstOrNull { it.type=="region" }?.let { values[FieldId.REGION]=it.name }
                val core=place.nameNorm.filter(Char::isLetterOrDigit)
                val compact=subjectNorm.filter(Char::isLetterOrDigit)
                val suffix=if(compact.startsWith(core)) compact.removePrefix(core) else ""
                val unresolvedSuffix=repair==null && !classificationName && place.type=="appellation" && !originFramingAdmin && suffix.length in 1..8 && suffix.all(Char::isLetter) &&
                    suffix !in setOf("aoc","aop","doc","docg","dop","igp") && gazetteer.matches(suffix).none { it.type=="country" } &&
                    places.none { it.nameNorm.filter(Char::isLetterOrDigit)==compact }
                // One name may fill one level only: a collapsed hierarchy must not repeat the region.
                if(place.type !in setOf("country","region") && !unresolvedSuffix && values[FieldId.REGION]!=place.name)
                    values[FieldId.SUBREGION]=place.name
                val regionWide=ReferenceWineCatalog.isRegionWideIndication(place)
                if(regionWide) values.remove(FieldId.SUBREGION)
                val repairPenalty=if(repair!=null) maxOf(placeRepair.rankPenalty,repair.penalty) else 0.0
                val effectiveDepth=if(regionWide && path.size>2) 2 else path.size
                offer(values,50.0+effectiveDepth*5+lexicalBonus-repairPenalty,
                    "observed geography and actual ancestors; no producer-origin inference"+
                        (if(unresolvedSuffix) "; unresolved appellation suffix: finer locality withheld" else "")+
                        (repair?.let { "; "+it.reason } ?: ""),place)
            }
            if(originFramingAdmin) {
                if(repair==null && framed!=null && places.isNotEmpty()) {
                    if(classificationName)
                        offer(mapOf(FieldId.WINE_NAME to framed),64.0,"framed printed classification qualifies the matched appellation")
                    else if(places.any { TextNorm.normalize(it.name)==subjectNorm } && WineDesignations.isAppellationName(framed))
                        offer(mapOf(FieldId.WINE_NAME to framed),62.0,"framed printed appellation may also name the wine")
                }
                trace.add("administrative", "phrase=${p.id}; text=$text; places=${places.size}")
                continue
            }
            if(repair==null && classificationName && places.isNotEmpty() && printedBase!=null &&
                places.any { pl -> (listOf(pl.name)+BundledOrigins.spellingForms(pl)).any { EvidenceRanking.nameKey(it)==EvidenceRanking.nameKey(printedBase) } })
                offer(mapOf(FieldId.WINE_NAME to placeSubject),70.0,"printed classification qualifies the matched appellation")
            else if(repair==null && places.any { TextNorm.normalize(it.name)==norm } && WineDesignations.isAppellationName(text))
                offer(mapOf(FieldId.WINE_NAME to text),65.0,"exact printed appellation may also name the wine")
            else if(repair==null && attachedYear!=null && places.any { TextNorm.normalize(it.name)==subjectNorm } && WineDesignations.isAppellationName(placeSubject))
                offer(mapOf(FieldId.WINE_NAME to placeSubject),65.0,"exact printed appellation may also name the wine")
            if(!MetadataRules.isIdentity(text) || paragraph || places.isNotEmpty() || endsConnector || beginsConnector) { trace.add("identity_filtered", "phrase=${p.id}; paragraph=$paragraph; places=${places.size}; connector=${endsConnector || beginsConnector}");continue }
            if(ranking.cuveeRole && EvidenceRanking.namedCuvee(text)) {
                offer(mapOf(FieldId.WINE_NAME to text),75.0,"explicit named cuvee role; not producer attribution")
                trace.add("explicit_cuvee_role", "phrase=${p.id}")
                continue
            }
            BundledProducerNames.exactName(text)?.let { offer(mapOf(FieldId.PRODUCER to it),105.0,"exact authored producer alias; no origin inference") }
            val known=kb.findProducer(text).filter { it.editDistance==0 }.take(3)
            known.forEach { offer(mapOf(FieldId.PRODUCER to it.producer.name),110.0,"exact local producer; catalog location is not wine origin") }
            if(p.construction.contains("designation-linked") && !WineDesignations.isDesignation(text)) {
                offer(mapOf(FieldId.WINE_NAME to WineDesignations.orderName(text)),90.0+lexicalBonus,"complete name with adjacent designation")
                continue
            }
            // A bare designation (`GRAND CRU`, `DOCG`, `RISERVA`) qualifies a name and never is one.
            if(WineDesignations.isDesignation(text)) { trace.add("bare_designation","phrase=${p.id}; text=$text");continue }
            if(WineDesignations.isBareClassification(text)) { trace.add("bare_classification","phrase=${p.id}; text=$text");continue }
            if(roles.isWineDescription(text)) {
                offer(mapOf(FieldId.WINE_NAME to (WineDesignations.display(text) ?: text)),75.0,"observed varietal")
                continue
            }
            if(norm.count(Char::isLetter)<4 || norm.any(Char::isDigit)) continue
            val joined=p.construction.contains("connector")
            val titleCue=Regex("\\b(reserve|reserva|riserva)\\b").containsMatchIn(norm)
            if(!titleCue) offer(mapOf(FieldId.PRODUCER to text),(if(roles.hasProducerCue(text)) 85.0 else if(joined) 82.0 else 32.0)+lexicalBonus,"producer/brand interpretation; ${p.construction}")
            if(!namedEstate) offer(mapOf(FieldId.WINE_NAME to text),(if(joined) 88.0 else 43.0)+lexicalBonus,"title interpretation; ${p.construction}")
        }
        val ranked=hypotheses.map { EvidenceRanking.adjust(it,graph,ranking) }
        val solution=EvidenceSolver.solve(ranked,view,graph,::checkBudget,trace,search)
        val fields=FieldId.entries.associateWith { field ->
            val values=solution.map { assignment -> assignment.firstOrNull { field in it.values }?.values?.get(field).orEmpty() }.distinctBy { value ->
                if(ranking.equivalentNames && field in setOf(FieldId.PRODUCER,FieldId.WINE_NAME)) EvidenceRanking.nameKey(value) else TextNorm.normalize(value)
            }
            val winner=solution.firstOrNull()?.firstOrNull { field in it.values }
            val value=values.singleOrNull().orEmpty()
            FieldCandidate(field,value,if(value.isBlank()) UiState.RED else UiState.YELLOW,
                if(value.isBlank()) "Experimental abstention: missing or competing assignments" else "${winner?.reason}; phrase=${winner?.phrase?.id}; observations=${winner?.phrase?.observations}; experimental rank, not probability; confirm",
                emptyList())
        }
        val path=solution.firstOrNull()?.firstOrNull { it.place!=null }?.place?.let(view::ancestors).orEmpty()
        val alternatives=FieldId.entries.associateWith { f -> ranked.filter { f in it.values }.sortedByDescending { it.rank }.distinctBy { it.values[f] }.take(6).map {
            FieldCandidate(f,it.values.getValue(f),UiState.YELLOW,"${it.reason}; phrase=${it.phrase.id}; observations=${it.phrase.observations}; rank=${it.rank}",emptyList())
        } }
        val fill=CellarFieldPresentation.apply(FillResult(fields,"Experimental saved-reading extraction; not production autofill",alternatives,
            roleNotes.take(384)+"observations=${graph.observations.size}; phrases=${graph.phrases.size}; hypotheses=${hypotheses.size}; truncated=${graph.truncated || hypotheses.size>=MAX_HYPOTHESES}",geographyPath=path))
        return EvidenceResult(fill,graph,ranked,(System.nanoTime()-started)/1_000_000,trace)
    }
}
