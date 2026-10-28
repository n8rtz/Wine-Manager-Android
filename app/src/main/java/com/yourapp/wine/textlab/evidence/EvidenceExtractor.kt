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
    companion object {
        const val VERSION="evidence-lab-v5-vintage-recovery"
        const val DEFAULT_BUDGET_MS=60_000L
        const val MAX_HYPOTHESES=384
        private const val LOW_CONFIDENCE_PLACE_REPAIR_MIN_SCORE=.15f
    }
    private val kb=SupplementedWineKnowledgeBase(baseKb)
    private val gazetteer=PlaceGazetteer(kb)
    private val hierarchy=PlaceHierarchy(kb)
    private val view=hierarchy.snapshot()
    private val repairs=PlaceRepair(kb,hierarchy)
    private val roles=ProducerLineSelector()
    private fun family(engineId:String)=if(engineId.startsWith("paddle")) "paddle" else engineId

    /**
     * Search the full phrase, its catalogued classification base, and—only when that base ends in a
     * solitary OCR "O"—a lookup-only form without that stray character. Keep the printed/raw text
     * untouched, and abstain if these narrowly-derived queries disagree on the place.
     */
    private fun resolvePlaceRepair(
        observed:String,
        subject:String,
        readings:List<PlaceRepair.Reading>,
        anchors:List<Place>,
        countryHints:Set<String>,
    ):PlaceRepair.Resolution? {
        data class Query(val text:String,val note:String)
        val queries=mutableListOf(Query(subject,""))
        val base=WineDesignations.classificationBase(subject)
        if(base!=null) {
            if(base!=subject) queries+=Query(base,"classification base used for lookup")
            val withoutOrphanO=base.replace(Regex("(?i)\\s+o$"),"").trim()
            if(withoutOrphanO!=base) queries+=Query(withoutOrphanO,"classification base used for lookup; isolated OCR O ignored")
        }
        val repairReadings=readings.flatMap { reading ->
            val phrase=WineDesignations.framedAppellation(reading.text) ?: reading.text
            val readingBase=WineDesignations.classificationBase(phrase) ?: return@flatMap listOf(reading)
            val candidates=mutableListOf(reading)
            if(readingBase!=phrase) candidates+=reading.copy(text=readingBase)
            val withoutOrphanO=readingBase.replace(Regex("(?i)\\s+o$"),"").trim()
            if(withoutOrphanO!=readingBase) candidates+=reading.copy(text=withoutOrphanO)
            candidates
        }.distinctBy { it.family to it.text }
        val matches=queries.distinctBy { it.text }.mapNotNull { query ->
            repairs.resolve(query.text,repairReadings,anchors,placeRepair,countryHints)?.let { query to it }
        }
        if(matches.map { it.second.place.id }.distinct().size!=1) return null
        val (query,resolution)=matches.minByOrNull { it.second.editDistance } ?: return null
        return resolution.copy(
            observed=observed,
            anchorKind=resolution.anchorKind+(query.note.takeIf { it.isNotBlank() }?.let { "; $it" } ?: ""),
        )
    }

    private val foundingYearCue = Regex("\\b(?:fondee?|founded|established|est\\.?|since|depuis|first\\s+vintage|anno|dal)\\b")
    // Keep the repair narrowly in a four-digit year context: I/l can stand for the leading 1,
    // and O can stand for the zero in 20xx. Whitespace between digits is allowed for tracked type.
    private val yearTokenPattern = "(?:[il1]\\s*9|2\\s*[o0])\\s*\\d\\s*\\d"
    private val canonicalYearPattern = Regex("(?:19|20)\\d{2}")
    private val leadingYearPattern = Regex("^($yearTokenPattern)\\s*[-\\u2013\\u2014\\u00b7,:|/ ]+\\s*(.+)$",RegexOption.IGNORE_CASE)
    private val trailingYearPattern = Regex("^(.+?)\\s*[-\\u2013\\u2014\\u00b7,:|/ ]+\\s*($yearTokenPattern)$",RegexOption.IGNORE_CASE)
    private val harvestYearPattern = Regex("(?i)\\b(?:[cgos]osecha|vendimia|annata|millesime|recolte|harvest)\\s+($yearTokenPattern)\\b")
    private val originFramingWrapper = Regex(
        "(?i)^(?:product\\s+of|produce\\s+of|produced\\s+in|produit\\s+de|wine\\s+of|" +
            "vin\\s+de|vin\\s+du|vin\\s+des|grand\\s+vin\\s+de|grand\\s+vin\\s+du|grand\\s+vin\\s+des)\\s+(.+?)(?:\\s+wine)?$"
    )

    private fun isLabRegionWide(place: Place, exactGeographyFastPath: Boolean): Boolean =
        ReferenceWineCatalog.isRegionWideIndication(place) ||
            (exactGeographyFastPath && place.countryCode == "FR" && EvidenceRanking.nameKey(place.name) == "bourgogne")

    private fun compatibleGeography(a: Place, b: Place, exactGeographyFastPath: Boolean): Boolean {
        if (view.compatible(a, b)) return true
        if (!exactGeographyFastPath || a.countryCode != b.countryCode) return false
        val regA = view.ancestors(a).firstOrNull { it.type == "region" }?.nameNorm
        val regB = view.ancestors(b).firstOrNull { it.type == "region" }?.nameNorm
        return regA != null && regA == regB && (isLabRegionWide(a, true) || isLabRegionWide(b, true))
    }

    private fun isExactWholePhrasePlace(
        place: Place,
        path: List<Place>,
        framed: String?,
        placeSubject: String,
        yearlessText: String,
    ): Boolean {
        val placeKeys = (listOf(place.name) + BundledOrigins.spellingForms(place))
            .map(EvidenceRanking::nameKey)
            .filter(String::isNotEmpty)
            .toSet()
        if (placeKeys.isEmpty()) return false
        val originStripped = originFramingWrapper.matchEntire(yearlessText.trim())?.groupValues?.get(1)?.trim()
        val directForms = listOfNotNull(
            framed,
            placeSubject,
            WineDesignations.classificationBase(placeSubject),
            originStripped,
            originStripped?.let(WineDesignations::classificationBase),
        )
        if (directForms.any { EvidenceRanking.nameKey(it) in placeKeys }) return true
        val chainKeys = path.flatMap { anc -> listOf(anc.name) + BundledOrigins.spellingForms(anc) }
            .map(EvidenceRanking::nameKey)
            .filter(String::isNotEmpty)
            .toSet() + placeKeys
        val segments = yearlessText.split(Regex("[-\\u2013\\u2014\\u00b7,|/]+")).map(String::trim).filter(String::isNotEmpty)
        if (segments.size in 2..3 &&
            segments.all { EvidenceRanking.nameKey(it) in chainKeys } &&
            segments.any { EvidenceRanking.nameKey(it) in placeKeys }
        ) return true
        val words = yearlessText.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return words.size == 2 &&
            words.all { EvidenceRanking.nameKey(it) in chainKeys } &&
            words.any { EvidenceRanking.nameKey(it) in placeKeys }
    }

    private fun normalizeVintageCandidate(raw:String,maxYear:Int):String? {
        val compact=TextNorm.normalize(raw).filter(Char::isLetterOrDigit)
        if(compact.length!=4) return null
        val chars=compact.toCharArray()
        if(chars[0] in setOf('i','l') && chars[1]=='9' && chars.drop(2).all(Char::isDigit)) chars[0]='1'
        if(chars[0]=='2' && chars[1]=='o') chars[1]='0'
        val year=String(chars)
        if(!canonicalYearPattern.matches(year)) return null
        val numeric=year.toIntOrNull() ?: return null
        return year.takeIf { numeric in 1900..maxYear }
    }

    private fun detachEdgeVintage(raw: String, maxYear: Int): Pair<String, String?> {
        val trimmed = raw.trim()
        val norm = TextNorm.normalize(trimmed)
        if (foundingYearCue.containsMatchIn(norm)) return trimmed to null
        val lead = leadingYearPattern.matchEntire(trimmed)
        if (lead != null) {
            val yr = normalizeVintageCandidate(lead.groupValues[1],maxYear)
            val rest = lead.groupValues[2].trim()
            if (yr!=null && rest.count(Char::isLetter) >= 3) return rest to yr
        }
        val trail = trailingYearPattern.matchEntire(trimmed)
        if (trail != null) {
            val rest = trail.groupValues[1].trim()
            val yr = normalizeVintageCandidate(trail.groupValues[2],maxYear)
            if (yr!=null && rest.count(Char::isLetter) >= 3) return rest to yr
        }
        return trimmed to null
    }

    private fun explicitHarvestVintage(norm: String, maxYear: Int): String? {
        if (foundingYearCue.containsMatchIn(norm)) return null
        val yr = harvestYearPattern.find(norm)?.groupValues?.get(1) ?: return null
        return normalizeVintageCandidate(yr,maxYear)
    }

    /** Maps an LWIN `country_group` string to a verified catalog country name, or null when unknown. */
    fun canonicalCountryName(countryGroup:String):String? {
        val clean=TextNorm.scrub(countryGroup).trim()
        if(clean.isBlank()) return null
        val mapped=if(clean.equals("Russian Federation",ignoreCase=true)) "Russia" else clean
        return ReferenceWineCatalog.countryCodes.keys.firstOrNull { it.equals(mapped,ignoreCase=true) }
    }

    /**
     * Lab v5 only: when `LinkedWineResolver` confirms a `supported_pair` LWIN wine whose name (or
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

    private fun catalogWordKey(text:String):String = java.text.Normalizer.normalize(
        TextNorm.scrub(text).lowercase(java.util.Locale.ROOT).replace('’','\''),
        java.text.Normalizer.Form.NFKD,
    ).replace(Regex("\\p{M}+"),"").replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(Regex("\\s+")," ")

    private val broadGeographyKeys:Set<String> by lazy {
        buildSet {
            for(place in kb.places().filter { it.type in setOf("country","region","subregion") }) {
                add(catalogWordKey(place.name))
                BundledOrigins.spellingForms(place).forEach { add(catalogWordKey(it)) }
            }
            for(origin in ReferenceWineCatalog.origins.filter { it.nameRole=="geography" }) {
                add(catalogWordKey(origin.name));origin.aliases.forEach { add(catalogWordKey(it)) }
                origin.regions.forEach { add(catalogWordKey(it)) };origin.path.forEach { add(catalogWordKey(it)) }
            }
            remove("")
        }
    }
    private val broadGeographyWords:Set<String> by lazy {
        broadGeographyKeys.flatMap { it.split(' ') }.filter(String::isNotBlank).toSet()
    }
    private val broadGeographyMaxWords:Int by lazy {
        broadGeographyKeys.maxOfOrNull { it.count { c -> c==' ' }+1 } ?: 0
    }
    private val geographyGlueWords=setOf("de","del","du","des","d","la","le","les","the","of","and")
    fun isBroadGeographyLine(rawText:String):Boolean {
        val key=catalogWordKey(rawText)
        if(key.isEmpty()) return false
        if(key in broadGeographyKeys) return true
        val words=key.split(' ')
        val geographicContent=words.filterNot { it in geographyGlueWords }
        return words.size in 2..(broadGeographyMaxWords+2) && geographicContent.size>=2 &&
            geographicContent.all { it in broadGeographyWords }
    }

    private val spatialSemanticBoundaryYear=Regex(
        """(?<![\p{L}\p{N}])(?:18|19|20|[il1]\s*9|2\s*[o0])\s*\d\s*\d(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
    private val spatialVintageFraming=Regex("(?iu)\\b(?:vintage|millesime|annata|cosecha|vendimia|harvest)\\b")
    private fun hasSpatialSemanticBoundary(text:String):Boolean = spatialSemanticBoundaryYear.containsMatchIn(text) ||
        spatialVintageFraming.containsMatchIn(TextNorm.normalize(text)) || MetadataRules.isBoilerplate(text) ||
        MetadataRules.isPackaging(text) || WineDesignations.framedAppellation(text)!=null

    /** Spatial supplemental spans are producer lookups only, never rewritten readings. */
    internal fun allowsSpatialProducerJoin(upperText:String,lowerText:String):Boolean {
        val upper=TextNorm.scrub(upperText).trim();val lower=TextNorm.scrub(lowerText).trim()
        val combined="$upper $lower"
        if(upper.isBlank() || lower.isBlank() || hasSpatialSemanticBoundary(upper) || hasSpatialSemanticBoundary(lower) ||
            hasSpatialSemanticBoundary(combined) || isBroadGeographyLine(upper) || isBroadGeographyLine(lower) ||
            isBroadGeographyLine(combined)) return false
        val producerCue=roles.hasProducerCue(upper)
        if(roles.isWineDescription(upper) || WineDesignations.isDesignation(upper) ||
            WineDesignations.isBareClassification(upper) || WineDesignations.framedAppellation(upper)!=null) return false
        val lowerAppellation=WineDesignations.isAppellationName(lower) || WineDesignations.hasNameAndDesignation(lower)
        val lowerVarietalOrStyle=roles.isWineDescription(lower) && !lowerAppellation
        if(lowerVarietalOrStyle) return false
        if(lowerAppellation || isNonProducerCatalogTerm(lower)) return producerCue
        return true
    }

    private val grapeCandidateKeys:Set<String> by lazy {
        BundledGrapes.terms.flatMap { listOf(it.name)+it.aliases }.map(::catalogWordKey).filter(String::isNotBlank).toSet()
    }
    private val geographicCandidateKeys:Set<String> by lazy {
        buildSet {
            for(place in kb.places()) {
                add(catalogWordKey(place.name))
                BundledOrigins.spellingForms(place).forEach { add(catalogWordKey(it)) }
            }
            for(origin in ReferenceWineCatalog.origins) {
                add(catalogWordKey(origin.name));add(catalogWordKey(origin.country))
                origin.regions.forEach { add(catalogWordKey(it)) }
                origin.aliases.forEach { add(catalogWordKey(it)) };origin.path.forEach { add(catalogWordKey(it)) }
            }
            for(term in BundledOrigins.terms) {
                add(catalogWordKey(term.name));add(catalogWordKey(term.country))
                if(term.region.isNotBlank()) add(catalogWordKey(term.region))
                term.aliases.forEach { add(catalogWordKey(it)) }
            }
            remove("")
        }
    }
    private val grapeCandidateMaxWords:Int by lazy { grapeCandidateKeys.maxOfOrNull { it.count { c -> c==' ' }+1 } ?: 0 }
    private val geographicCandidateMaxWords:Int by lazy { geographicCandidateKeys.maxOfOrNull { it.count { c -> c==' ' }+1 } ?: 0 }
    private fun containsCatalogSequence(words:List<String>,terms:Set<String>,maxLength:Int):Boolean {
        if(words.isEmpty() || maxLength==0) return false
        for(start in words.indices) for(size in 1..minOf(maxLength,words.size-start))
            if(words.subList(start,start+size).joinToString(" ") in terms) return true
        return false
    }

    private val nonProducerCatalogKeys:Set<String> by lazy {
        buildSet {
            addAll(geographicCandidateKeys);addAll(grapeCandidateKeys)
            for(term in BundledWineStyles.terms) {
                add(catalogWordKey(term.name));term.aliases.forEach { add(catalogWordKey(it)) }
            }
            for(term in ReferenceWineCatalog.labelTerms) add(catalogWordKey(term.name))
            remove("")
        }
    }

    /** True when [rawText] is a known geographic origin, varietal, wine style, classification, or label designation. */
    fun isNonProducerCatalogTerm(rawText:String):Boolean {
        val clean=TextNorm.scrub(rawText).trim()
        if(clean.isBlank()) return false
        val key=catalogWordKey(clean)
        if(key.isEmpty()) return false
        if(key in nonProducerCatalogKeys) return true
        if(WineDesignations.isDesignation(clean) || WineDesignations.isBareClassification(clean) || WineDesignations.isAppellationName(clean)) return true
        val split=WineDesignations.split(clean)
        if(split.suffix!=null && catalogWordKey(split.base) in nonProducerCatalogKeys) return true
        return false
    }

    /** Edit-equivalent discouragement passed to producer lookup: grape terms are a hard penalty;
     * a geographic term costs only one character so a real place-named estate can still win. */
    fun producerCandidatePenalty(rawText:String):Int {
        val words=catalogWordKey(rawText).split(' ').filter(String::isNotBlank)
        if(containsCatalogSequence(words,grapeCandidateKeys,grapeCandidateMaxWords)) return 3
        return if(containsCatalogSequence(words,geographicCandidateKeys,geographicCandidateMaxWords)) 1 else 0
    }

    fun extract(records:List<TokenRecord>,year:Int,budgetMs:Long=DEFAULT_BUDGET_MS,exactGeographyFastPath:Boolean=false):EvidenceResult {
        val started=System.nanoTime()
        fun checkBudget() {
            if(Thread.currentThread().isInterrupted) throw InterruptedException("Evidence extraction interrupted")
            check((System.nanoTime()-started)/1_000_000<budgetMs) { "Evidence extraction budget exceeded" }
        }
        val trace=EvidenceTrace()
        val knownProducerLineCache=mutableMapOf<String,Boolean>()
        fun isKnownProducerLine(text:String):Boolean {
            val key=catalogWordKey(text)
            return knownProducerLineCache.getOrPut(key) { kb.findProducer(text).any { it.editDistance==0 } }
        }
        val graph=EvidenceLexicon.expand(
            EvidenceGraphBuilder.build(
                records,::checkBudget,typedIdentity,
                isBroadGeographyLine={ text -> isBroadGeographyLine(text) },
                isKnownProducerLine={ text -> isKnownProducerLine(text) },
                allowWineTitlePairs=true,
            ),
            ::checkBudget,
        )
        trace.add("graph", "observations=${graph.observations.size}; phrases=${graph.phrases.size}; truncated=${graph.truncated}")
        data class ExactGeoCandidate(val hypothesis:Hypothesis,val place:Place,val depth:Int)
        val hypotheses=mutableListOf<Hypothesis>()
        val exactGeoCandidates=mutableListOf<ExactGeoCandidate>()
        val partialGeoHypotheses=mutableSetOf<Hypothesis>()
        val roleNotes=mutableListOf<String>()
        val labelContext=if(exactGeographyFastPath) LabelContext.collect(graph,gazetteer,view,kb,typedIdentity,true) else LabelContext.collect(graph,gazetteer,view,kb,typedIdentity)
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
            var lowConfidenceRepair:PlaceRepair.Resolution?=null
            if(!supported) {
                if(exactGeographyFastPath && sources.any { it.record.score >= .45f } &&
                    !LabelContext.isNonOriginAdministrative(text,norm) &&
                    !(typedIdentity && EvidenceIdentityRoles.headingOnly(text)) &&
                    !(typedIdentity && EvidenceIdentityRoles.namedEstate(text)) &&
                    graph.observations.any { other ->
                        sources.none { it.record.engineId == other.record.engineId } &&
                            other.record.score >= (if(other.record.engineId.startsWith("paddle")) .75f else .60f) &&
                            com.yourapp.wine.merge.BoxMath.iou(other.record.bboxRect,p.box) > .10f
                    }
                ) {
                    val rescuedFramed=WineDesignations.framedAppellation(text)
                    val (rescuedYearless,_)=if(rescuedFramed==null) detachEdgeVintage(text,year) else (text to null)
                    val rescuedSubject=rescuedFramed ?: rescuedYearless
                    val rescuedNorm=TextNorm.normalize(rescuedSubject)
                    if(rescuedNorm.count(Char::isLetter) >= 8) {
                        val rescuedMatches=gazetteer.matches(
                            rescuedNorm,
                            labelContext.contextCountriesForPhrase(p.id),
                            labelContext.contextRegionsForPhrase(p.id),
                            true,
                        ).filter { it.type != "country" }
                        val rescuedPlace=rescuedMatches.singleOrNull()
                        if(rescuedPlace!=null) {
                            val rescuedPath=view.ancestors(rescuedPlace)
                            if(isExactWholePhrasePlace(rescuedPlace,rescuedPath,rescuedFramed,rescuedSubject,rescuedYearless) && hypotheses.size<MAX_HYPOTHESES) {
                                val rescuedValues=mutableMapOf<FieldId,String>()
                                rescuedPath.lastOrNull { it.type=="country" }?.let { rescuedValues[FieldId.COUNTRY]=it.name }
                                val multiRegion=(ReferenceWineCatalog.origin(rescuedPlace)?.regions?.size ?: 0)>1
                                if(!multiRegion) rescuedPath.firstOrNull { it.type=="region" }?.let { rescuedValues[FieldId.REGION]=it.name }
                                if(rescuedPlace.type !in setOf("country","region") && rescuedValues[FieldId.REGION]!=rescuedPlace.name)
                                    rescuedValues[FieldId.SUBREGION]=rescuedPlace.name
                                val regionWide=isLabRegionWide(rescuedPlace,true)
                                if(regionWide) rescuedValues.remove(FieldId.SUBREGION)
                                val effectiveDepth=if(regionWide && rescuedPath.size>2) 2 else rescuedPath.size
                                val rescuedHyp=Hypothesis(
                                    rescuedValues,
                                    p,
                                    50.0+effectiveDepth*5+lexicalBonus,
                                    "observed geography and actual ancestors; no producer-origin inference; corroborated d=0 appellation",
                                    rescuedPlace,
                                )
                                hypotheses+=rescuedHyp
                                exactGeoCandidates+=ExactGeoCandidate(rescuedHyp,rescuedPlace,effectiveDepth)
                                trace.add("exact_geography_rescued","phrase=${p.id}; place=${rescuedPlace.name}")
                                continue
                            }
                        }
                    }
                }
                val weakNonOriginAdmin=LabelContext.isNonOriginAdministrative(text,norm)
                val weakFramed=WineDesignations.framedAppellation(text)
                val weakOriginFramingAdmin=!weakNonOriginAdmin && MetadataRules.isBoilerplate(text)
                val weakRepairEligible=placeRepair.enabled &&
                    sources.any { it.record.score.isFinite() && it.record.score>=LOW_CONFIDENCE_PLACE_REPAIR_MIN_SCORE } &&
                    !weakNonOriginAdmin && (!weakOriginFramingAdmin || weakFramed!=null) &&
                    !(typedIdentity && EvidenceIdentityRoles.headingOnly(text)) &&
                    !(typedIdentity && EvidenceIdentityRoles.namedEstate(text))
                if(weakRepairEligible) {
                    val (weakYearless,_)=if(weakFramed==null) detachEdgeVintage(text,year) else (text to null)
                    val weakSubject=weakFramed ?: weakYearless
                    val weakAnchors=labelContext.anchorsExcluding(p.id)
                    val weakCountryHints=labelContext.countryHintsExcluding(p.id)+
                        labelContext.contextCountriesForPhrase(p.id).takeIf { weakFramed!=null }.orEmpty()
                    val weakReadings=sources.map { source ->
                        val raw=source.record.textRaw
                        PlaceRepair.Reading(family(source.record.engineId),
                            if(weakFramed!=null) WineDesignations.framedAppellation(raw) ?: raw else raw)
                    }
                    lowConfidenceRepair=resolvePlaceRepair(text,weakSubject,weakReadings,weakAnchors,weakCountryHints)
                }
                val admittedRepair=lowConfidenceRepair
                if(admittedRepair==null) {
                    trace.add("unsupported", "phrase=${p.id}");continue
                }
                val maxWeakScore=sources.maxOfOrNull { it.record.score } ?: 0f
                trace.add("low_confidence_place_repair",
                    "phrase=${p.id}; max_score=$maxWeakScore; ${admittedRepair.reason}")
            } // original reading retained, or admitted only by a distinctive Lab place repair
            val nonOriginAdmin=LabelContext.isNonOriginAdministrative(text,norm)
            val originFramingAdmin=!nonOriginAdmin && MetadataRules.isBoilerplate(text)
            val administrative=nonOriginAdmin || originFramingAdmin
            val boundedTitlePair=p.construction=="bounded vertical wine-title pair"
            val paragraph=!boundedTitlePair && p.box.width()<.25f && graph.phrases.count { other -> other.id!=p.id &&
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
            // Lab-only: a bounded repair may name a place the exact trie could not read. It uses
            // explicit origin anchors or legal/producer country cues where available; otherwise it
            // requires a long, globally distinctive appellation with a strict edit-distance margin.
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
                lowConfidenceRepair ?: resolvePlaceRepair(text,placeSubject,readings,anchors,countryHints)
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
            fun offer(values:Map<FieldId,String>,rank:Double,why:String,place:Place?=null):Hypothesis? {
                if(fragmentOnly && values.keys.all { it==FieldId.PRODUCER || it==FieldId.WINE_NAME }) {
                    trace.add("fragment_identity","phrase=${p.id}; text=$text");return null
                }
                val weakContent=sources.count { TextNorm.normalize(it.record.textRaw) !in setOf("de","du","di","des") && it.record.score < if(it.record.engineId.startsWith("paddle")) .75f else .60f }
                if(hypotheses.size>=MAX_HYPOTHESES) return null
                val added=Hypothesis(values,p,rank-weakContent*10-incomplete*6,why+if(incomplete>0) "; overlapping engine supplies a fuller reading" else "",place)
                hypotheses+=added
                return added
            }
            val standaloneYear=normalizeVintageCandidate(text,year)
            if(!originFramingAdmin && standaloneYear!=null)
                offer(mapOf(FieldId.VINTAGE to standaloneYear),55.0,"complete observed vintage")
            else if(!originFramingAdmin) {
                val embeddedYear=(attachedYear?.takeIf { places.isNotEmpty() }) ?: explicitHarvestVintage(norm,year)
                if(embeddedYear!=null) offer(mapOf(FieldId.VINTAGE to embeddedYear),52.0,"embedded observed vintage")
            }
            val coherentPhrasePlaces=exactGeographyFastPath && places.isNotEmpty() &&
                places.all { a -> places.all { b -> compatibleGeography(a,b,true) } }
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
                val regionWide=ReferenceWineCatalog.isRegionWideIndication(place) || isLabRegionWide(place,exactGeographyFastPath)
                if(regionWide) values.remove(FieldId.SUBREGION)
                val repairPenalty=if(repair!=null) maxOf(placeRepair.rankPenalty,repair.penalty) else 0.0
                val effectiveDepth=if(regionWide && path.size>2) 2 else path.size
                val offered=offer(values,50.0+effectiveDepth*5+lexicalBonus-repairPenalty,
                    "observed geography and actual ancestors; no producer-origin inference"+
                        (if(unresolvedSuffix) "; unresolved appellation suffix: finer locality withheld" else "")+
                        (repair?.let { "; "+it.reason } ?: ""),place)
                if(exactGeographyFastPath && offered!=null && repair==null && !unresolvedSuffix) {
                    if(coherentPhrasePlaces && isExactWholePhrasePlace(place,path,framed,placeSubject,yearlessText)) {
                        exactGeoCandidates+=ExactGeoCandidate(offered,place,effectiveDepth)
                    } else {
                        partialGeoHypotheses+=offered
                    }
                }
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
            if(boundedTitlePair) {
                val lowerTitleLine=sources.maxByOrNull { it.record.bboxRect.centerY() }?.record?.textRaw.orEmpty()
                val lowerIsCatalogAppellation=WineDesignations.isAppellationName(lowerTitleLine) ||
                    WineDesignations.hasNameAndDesignation(lowerTitleLine)
                val placeMatchesAreAppellations=places.isEmpty() ||
                    (lowerIsCatalogAppellation && places.all { WineDesignations.isAppellationName(it.name) })
                if(MetadataRules.isIdentity(text) && placeMatchesAreAppellations && !endsConnector && !beginsConnector)
                    offer(mapOf(FieldId.WINE_NAME to WineDesignations.orderName(text)),116.0,
                        "bounded two-line cuvee/title plus varietal or appellation; no geography or vintage boundary crossed")
                trace.add("bounded_title_pair","phrase=${p.id}; text=$text")
                continue
            }
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
            val producerRolePenalty=producerCandidatePenalty(text)*24.0
            if(!titleCue) offer(mapOf(FieldId.PRODUCER to text),
                (if(roles.hasProducerCue(text)) 85.0 else if(joined) 82.0 else 32.0)+lexicalBonus-producerRolePenalty,
                "producer/brand interpretation; ${p.construction}"+(if(producerRolePenalty>0) "; grape/geography role penalty=$producerRolePenalty" else ""))
            if(!namedEstate) offer(mapOf(FieldId.WINE_NAME to text),(if(joined) 88.0 else 43.0)+lexicalBonus,"title interpretation; ${p.construction}")
        }
        fun coherentExactPair(a:ExactGeoCandidate,b:ExactGeoCandidate):Boolean {
            if(compatibleGeography(a.place,b.place,true)) return true
            if(a.place.countryCode!=b.place.countryCode) return false
            val regA=view.ancestors(a.place).firstOrNull { it.type=="region" }?.nameNorm
            val regB=view.ancestors(b.place).firstOrNull { it.type=="region" }?.nameNorm
            val keyA=EvidenceRanking.nameKey(a.place.name)
            val keyB=EvidenceRanking.nameKey(b.place.name)
            return regA!=null && regA==regB && keyA.isNotEmpty() && keyB.isNotEmpty() &&
                (keyA.contains(keyB) || keyB.contains(keyA)) &&
                com.yourapp.wine.merge.BoxMath.iou(a.hypothesis.phrase.box,b.hypothesis.phrase.box) > .08f
        }
        val bestExactGeo=if(exactGeographyFastPath && exactGeoCandidates.isNotEmpty() &&
            exactGeoCandidates.all { a -> exactGeoCandidates.all { b -> coherentExactPair(a,b) } }
        ) {
            val pool=exactGeoCandidates.filter { it.place.type!="country" }.ifEmpty { exactGeoCandidates }
            pool.maxWithOrNull(
                compareBy<ExactGeoCandidate> {
                    if(FieldId.SUBREGION in it.hypothesis.values) 3
                    else if(FieldId.REGION in it.hypothesis.values) 2
                    else 1
                }.thenBy { it.depth }
                    .thenBy { it.place.nameNorm.length }
                    .thenBy { it.hypothesis.rank }
            )
        } else null
        val exactHypothesisSet=exactGeoCandidates.map { it.hypothesis }.toSet()
        val filteredHypotheses=if(bestExactGeo!=null) {
            trace.add("exact_geography_fast_path","place=${bestExactGeo.place.name}; values=${bestExactGeo.hypothesis.values}; phrase=${bestExactGeo.hypothesis.phrase.id}")
            hypotheses.filterNot { h ->
                h.place!=null && (
                    (!compatibleGeography(h.place,bestExactGeo.place,true) && h !in exactHypothesisSet) ||
                        (FieldId.REGION in bestExactGeo.hypothesis.values &&
                            FieldId.SUBREGION !in bestExactGeo.hypothesis.values &&
                            h in partialGeoHypotheses &&
                            FieldId.SUBREGION in h.values)
                    )
            }
        } else hypotheses
        val ranked=filteredHypotheses.map { EvidenceRanking.adjust(it,graph,ranking) }
        val solution=EvidenceSolver.solve(ranked,view,graph,::checkBudget,trace,search)
        val exactLockedGeo:Pair<Hypothesis,Place>?=if(bestExactGeo!=null) {
            val solverGeo=solution.mapNotNull { assignment -> assignment.firstOrNull { it.place!=null } }
            val distinctSolverPlaces=solverGeo.mapNotNull { it.place }.distinctBy { it.id }
            val singleDeeperCompatible=if(solution.isNotEmpty() && solverGeo.size==solution.size && distinctSolverPlaces.size==1) {
                val candidatePlace=distinctSolverPlaces.single()
                val candidateHyp=solverGeo.first()
                val strictlyDeeper=(FieldId.SUBREGION in candidateHyp.values && FieldId.SUBREGION !in bestExactGeo.hypothesis.values) ||
                    (FieldId.REGION in candidateHyp.values && FieldId.REGION !in bestExactGeo.hypothesis.values)
                if(strictlyDeeper && compatibleGeography(candidatePlace,bestExactGeo.place,true)) candidateHyp to candidatePlace else null
            } else null
            singleDeeperCompatible ?: (bestExactGeo.hypothesis to bestExactGeo.place)
        } else null
        val geoFields=setOf(FieldId.COUNTRY,FieldId.REGION,FieldId.SUBREGION)
        val fields=FieldId.entries.associateWith { field ->
            if(exactLockedGeo!=null && field in geoFields) {
                val (lockedHyp,_)=exactLockedGeo
                val lockedVal=lockedHyp.values[field].orEmpty()
                FieldCandidate(
                    field,
                    lockedVal,
                    if(lockedVal.isBlank()) UiState.RED else UiState.YELLOW,
                    if(lockedVal.isBlank()) "d=0 exact geography fast-path: finer locality not claimed"
                    else "d=0 exact geography fast-path; ${lockedHyp.reason}; phrase=${lockedHyp.phrase.id}; observations=${lockedHyp.phrase.observations}; confirm",
                    emptyList(),
                )
            } else {
                val values=solution.map { assignment -> assignment.firstOrNull { field in it.values }?.values?.get(field).orEmpty() }.distinctBy { value ->
                    if(ranking.equivalentNames && field in setOf(FieldId.PRODUCER,FieldId.WINE_NAME)) EvidenceRanking.nameKey(value) else TextNorm.normalize(value)
                }
                val winner=solution.firstOrNull()?.firstOrNull { field in it.values }
                val value=values.singleOrNull().orEmpty()
                FieldCandidate(field,value,if(value.isBlank()) UiState.RED else UiState.YELLOW,
                    if(value.isBlank()) "Experimental abstention: missing or competing assignments" else "${winner?.reason}; phrase=${winner?.phrase?.id}; observations=${winner?.phrase?.observations}; experimental rank, not probability; confirm",
                    emptyList())
            }
        }
        val path=(exactLockedGeo?.second ?: solution.firstOrNull()?.firstOrNull { it.place!=null }?.place)?.let(view::ancestors).orEmpty()
        val alternatives=FieldId.entries.associateWith { f -> ranked.filter { f in it.values }.sortedByDescending { it.rank }.distinctBy { it.values[f] }.take(6).map {
            FieldCandidate(f,it.values.getValue(f),UiState.YELLOW,"${it.reason}; phrase=${it.phrase.id}; observations=${it.phrase.observations}; rank=${it.rank}",emptyList())
        } }
        val fill=CellarFieldPresentation.apply(FillResult(fields,"Experimental saved-reading extraction; not production autofill",alternatives,
            roleNotes.take(384)+"observations=${graph.observations.size}; phrases=${graph.phrases.size}; hypotheses=${hypotheses.size}; truncated=${graph.truncated || hypotheses.size>=MAX_HYPOTHESES}",geographyPath=path))
        return EvidenceResult(fill,graph,ranked,(System.nanoTime()-started)/1_000_000,trace)
    }
}
