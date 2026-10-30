package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.extract.*
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.*
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

/** Year-sharing provenance is assigned from original source context, never parsed from diagnostic wording. */
internal data class Hypothesis(val values:Map<FieldId,String>,val phrase:Phrase,val rank:Double,val reason:String,val place:Place?=null,val baseRank:Double=rank,val sharesVintageSource:Boolean=false)
internal data class EvidenceResult(val fill:FillResult,val graph:EvidenceGraph,val hypotheses:List<Hypothesis>,val elapsedMs:Long,val trace:EvidenceTrace,val generatedHypotheses:List<Hypothesis> = hypotheses)

/** Independent lab-only solver. Shared lexical catalogs, not production line/merge/extract/validate rules. */
internal class EvidenceExtractor(internal val baseKb:WineKnowledgeBase,private val typedIdentity:Boolean=true,private val search:EvidenceSearchConfig=EvidenceSearchConfig(),private val ranking:EvidenceRankingConfig=EvidenceRankingConfig(),private val placeRepair:PlaceRepair.Config=PlaceRepair.Config.off,private val allowWineTitlePairs:Boolean=false,internal val modules:LabPostOcrModules=LabPostOcrModules.unitTestDefault) {
    companion object {
        const val VERSION="evidence-lab-v5-span-provenance-v2"
        const val DEFAULT_BUDGET_MS=60_000L
        const val MAX_HYPOTHESES=384
    }
    internal data class RawOcrSubregionMatch(
        val country:String,
        val region:String,
        val subregion:String,
        val place:Place,
        val matchedForm:String,
        val observedSpan:String,
        val editDistance:Int,
        val engineId:String,
    )
    private data class RawOcrAppellationForm(
        val country:String,
        val region:String,
        val subregion:String,
        val place:Place,
        val matchedForm:String,
        val compactKey:String,
        val contextRequired:Boolean,
    )
    private val deferRawOcrSubregionFallback=ThreadLocal<Boolean>()
    internal fun <T> withDeferredBlankSubregionFallback(block:()->T):T {
        val prev=deferRawOcrSubregionFallback.get()
        deferRawOcrSubregionFallback.set(true)
        return try { block() } finally {
            if(prev==null) deferRawOcrSubregionFallback.remove() else deferRawOcrSubregionFallback.set(prev)
        }
    }
    private val kb=SupplementedWineKnowledgeBase(baseKb)
    private val gazetteer=PlaceGazetteer(kb)
    private val hierarchy=PlaceHierarchy(kb)
    private val view=hierarchy.snapshot()
    private val repairs=PlaceRepair(kb,hierarchy)
    private val roles=ProducerLineSelector()
    private fun family(engineId:String)=if(engineId.startsWith("paddle")) "paddle" else engineId

    private val brandLeadingArticles=setOf("la","le","les","el","los","las","il","the")
    private val rawOcrSubregionByCompactLen:Map<Int,List<RawOcrAppellationForm>> by lazy {
        val entries=mutableListOf<RawOcrAppellationForm>()
        for(place in kb.places()) {
            if(place.type=="country") continue
            val effectivePlace=WineDesignations.classificationBase(place.name)?.let { base ->
                view.byName[TextNorm.normalize(base)].orEmpty()
                    .filter { it.countryCode==place.countryCode && it.type!="country" }
                    .singleOrNull()
            } ?: place
            if(effectivePlace.type in setOf("country","region") || isLabRegionWide(effectivePlace,true)) continue
            val ancestors=view.ancestors(effectivePlace)
            val rawCountry=ancestors.lastOrNull { it.type=="country" }?.name.orEmpty()
            val multiRegion=(ReferenceWineCatalog.origin(effectivePlace)?.regions?.size ?: 0)>1
            val rawRegion=if(!multiRegion) ancestors.firstOrNull { it.type=="region" }?.name.orEmpty() else ""
            val rawSubregion=if(!effectivePlace.name.equals(rawRegion,ignoreCase=true)) effectivePlace.name else ""
            if(rawSubregion.isBlank()) continue
            val presented=CellarFieldPresentation.apply(
                FillResult(
                    FieldId.entries.associateWith { fid ->
                        val v=when(fid) {
                            FieldId.COUNTRY -> rawCountry
                            FieldId.REGION -> rawRegion
                            FieldId.SUBREGION -> rawSubregion
                            else -> ""
                        }
                        FieldCandidate(fid,v,if(v.isBlank()) UiState.RED else UiState.YELLOW,"",emptyList())
                    },
                    "",
                    emptyMap(),
                    emptyList(),
                    geographyPath=ancestors,
                )
            )
            val presCountry=presented.fields[FieldId.COUNTRY]?.value.orEmpty()
            val presRegion=presented.fields[FieldId.REGION]?.value.orEmpty()
            val presSubregion=presented.fields[FieldId.SUBREGION]?.value.orEmpty()
            if(presSubregion.isBlank() || presRegion.isBlank()) continue
            val contextReq=ReferenceWineCatalog.origin(effectivePlace)?.contextRequired==true
            for(form in (listOf(place.name)+BundledOrigins.spellingForms(place)).distinct()) {
                val compact=BoundedSpelling.key(form).replace(" ","")
                if(compact.length !in 5..64 || compact.any(Char::isDigit)) continue
                entries+=RawOcrAppellationForm(presCountry,presRegion,presSubregion,effectivePlace,form,compact,contextReq)
            }
        }
        entries.groupBy { it.compactKey.length }
    }

    private fun compactAppellationDistance(observedCompact:String,formCompact:String,maxEditsOverride:Int?=null):Int? {
        if(observedCompact==formCompact) return 0
        if(observedCompact.length !in 5..64 || formCompact.length !in 5..64) return null
        if(observedCompact.any(Char::isDigit) || formCompact.any(Char::isDigit)) return null
        val defaultLimit=if(minOf(observedCompact.length,formCompact.length) >= 10) 2 else 1
        val limit=if(maxEditsOverride!=null) minOf(defaultLimit,maxEditsOverride) else defaultLimit
        if(kotlin.math.abs(observedCompact.length-formCompact.length)>limit) return null
        return TextNorm.editDistance(observedCompact,formCompact,limit)
            ?.takeIf { it.toDouble()/maxOf(observedCompact.length,formCompact.length) <= .2 }
    }

    /**
     * Last-resort Lab fallback invoked ONLY when `SUBREGION` would otherwise be left blank:
     * searches raw OCR streams across any contiguous token span (any length, number of spaces, or
     * number of lines, plus glued multi-word tokens) for an unambiguous near match of a subregion-
     * producing appellation entry compatible with any already-resolved Country/Region.
     */
    internal fun fallbackBlankSubregionFromRawOcr(
        records:List<TokenRecord>,
        existingCountry:String="",
        existingRegion:String="",
        existingProducer:String="",
        existingWine:String="",
        knownProducerLineObservations:Set<String> = emptySet(),
        checkBudget:()->Unit={},
    ):RawOcrSubregionMatch? {
        if(records.isEmpty()) return null
        val index=rawOcrSubregionByCompactLen
        if(index.isEmpty()) return null
        val existingProducerCompact=BoundedSpelling.key(existingProducer).replace(" ","")
        val producerLineCompacts=buildSet {
            if(existingProducerCompact.length >= 4) add(existingProducerCompact)
            for(obs in knownProducerLineObservations) {
                val c=BoundedSpelling.key(obs).replace(" ","")
                if(c.length >= 4) add(c)
            }
        }
        data class OcrWordToken(
            val word:String,
            val lineIdx:Int,
            val wordIdxInLine:Int,
            val lineWordCount:Int,
            val lineWords:List<String>,
            val lineIsUnframedProducer:Boolean,
            val lineIsFramed:Boolean,
        )
        data class BestCandidate(
            val match:RawOcrSubregionMatch,
            val compactLen:Int,
        )
        val bestBySubregion=mutableMapOf<Triple<String,String,String>,BestCandidate>()
        fun consider(form:RawOcrAppellationForm,dist:Int,observedSpan:String,engineId:String) {
            if(existingCountry.isNotBlank() && !form.country.equals(existingCountry,ignoreCase=true)) return
            if(existingRegion.isNotBlank() && !form.region.equals(existingRegion,ignoreCase=true)) return
            // Gap H: the appellation already used as the wine name is not also a subregion.
            if(modules.appellationSubregionDemotion && existingWine.isNotBlank() &&
                EvidenceRanking.nameKey(form.subregion)==EvidenceRanking.nameKey(existingWine)) return
            if(existingCountry.isBlank() && existingRegion.isBlank() && (form.compactKey.length<10 || form.contextRequired)) return
            if(form.contextRequired && existingRegion.isBlank()) return
            val key=Triple(form.country.lowercase(java.util.Locale.ROOT),form.region.lowercase(java.util.Locale.ROOT),form.subregion.lowercase(java.util.Locale.ROOT))
            val prev=bestBySubregion[key]
            val better=prev==null ||
                dist<prev.match.editDistance ||
                (dist==prev.match.editDistance && form.compactKey.length>prev.compactLen)
            if(better) {
                bestBySubregion[key]=BestCandidate(
                    RawOcrSubregionMatch(form.country,form.region,form.subregion,form.place,form.matchedForm,observedSpan,dist,engineId),
                    form.compactKey.length,
                )
            }
        }

        val streams=records.take(EvidenceGraphBuilder.MAX_OBSERVATIONS)
            .groupBy { it.engineId to it.variantId }
        for((passKey,passRecords) in streams) {
            val valid=passRecords.filter { r ->
                val b=r.bboxRect
                TextNorm.scrub(r.textRaw).isNotBlank() &&
                    b.width()>0f && b.height()>0f &&
                    listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() && it in 0f..1f }
            }
            if(valid.isEmpty()) continue
            val byY=valid.sortedWith(compareBy<TokenRecord> { it.bboxRect.centerY() }.thenBy { it.bboxRect.centerX() })
            val rows=mutableListOf<MutableList<TokenRecord>>()
            for(rec in byY) {
                val matchedRow=rows.lastOrNull { row ->
                    val ref=row.last()
                    val minH=minOf(rec.bboxRect.height(),ref.bboxRect.height())
                    kotlin.math.abs(rec.bboxRect.centerY()-ref.bboxRect.centerY()) <= minH*.5f ||
                        com.yourapp.wine.merge.BoxMath.verticalOverlap(rec.bboxRect,ref.bboxRect) >= .45f
                }
                if(matchedRow!=null) matchedRow+=rec else rows+=mutableListOf(rec)
            }
            val orderedLines=rows.flatMap { row -> row.sortedBy { it.bboxRect.centerX() } }
            val tokens=mutableListOf<OcrWordToken>()
            for((lineIdx,rec) in orderedLines.withIndex()) {
                val scrubbed=TextNorm.scrub(rec.textRaw).trim()
                val words=BoundedSpelling.key(scrubbed).split(' ').filter(String::isNotBlank)
                if(words.isEmpty()) continue
                val lineCompact=words.joinToString("")
                val framed=WineDesignations.framedAppellation(scrubbed)!=null
                val producerLine=!framed && (
                    roles.hasProducerCue(scrubbed) ||
                        lineCompact in producerLineCompacts ||
                        (words.size in 2..5 && kb.findProducer(scrubbed).any { it.editDistance != null && it.editDistance <= 1 })
                    )
                for((wIdx,w) in words.withIndex()) {
                    tokens+=OcrWordToken(w,lineIdx,wIdx,words.size,words,producerLine,framed)
                }
            }
            var ops=0
            for(start in tokens.indices) {
                val acc=StringBuilder()
                for(end in start until tokens.size) {
                    if((++ops and 63)==0) checkBudget()
                    val tok=tokens[end]
                    if(tok.word.any(Char::isDigit)) break
                    acc.append(tok.word)
                    if(acc.length>64) break
                    if(acc.length<5) continue
                    val startTok=tokens[start]
                    val singleLineSubSpan=startTok.lineIdx==tok.lineIdx && (end-start+1)<startTok.lineWordCount
                    if(singleLineSubSpan && startTok.lineIsUnframedProducer) continue
                    val compactSpan=acc.toString()
                    val fullResolvedProducerLine=startTok.lineIdx==tok.lineIdx &&
                        (end-start+1)==startTok.lineWordCount &&
                        !startTok.lineIsFramed &&
                        existingProducerCompact.isNotBlank() &&
                        compactSpan==existingProducerCompact
                    if(fullResolvedProducerLine) continue
                    val omittedArticle=if(startTok.lineIdx==tok.lineIdx && startTok.wordIdxInLine==1) {
                        startTok.lineWords[0].takeIf { it in brandLeadingArticles }
                    } else null
                    val displaySpan=tokens.subList(start,end+1).joinToString(" ") { it.word }
                    for(len in (compactSpan.length-2)..(compactSpan.length+2)) {
                        for(form in index[len].orEmpty()) {
                            if(omittedArticle!=null && !form.compactKey.startsWith(omittedArticle)) continue
                            val dist=compactAppellationDistance(compactSpan,form.compactKey) ?: continue
                            consider(form,dist,displaySpan,passKey.first)
                        }
                    }
                }
            }
            // Also scan glued multi-word tokens (>=16 letters with no spaces) for embedded >=10-letter appellations.
            for(tok in tokens) {
                if(tok.word.length<16 || tok.word.any(Char::isDigit) || tok.lineIsUnframedProducer) continue
                val glued=tok.word
                val maxSub=minOf(64,glued.length)
                for(subLen in 10..maxSub) {
                    for(s in 0..(glued.length-subLen)) {
                        if((++ops and 63)==0) checkBudget()
                        val sub=glued.substring(s,s+subLen)
                        for(len in (subLen-1)..(subLen+1)) {
                            for(form in index[len].orEmpty()) {
                                if(form.compactKey.length<10) continue
                                val dist=compactAppellationDistance(sub,form.compactKey,maxEditsOverride=1) ?: continue
                                consider(form,dist,sub,passKey.first)
                            }
                        }
                    }
                }
            }
        }
        if(bestBySubregion.isEmpty()) return null
        val ranked=bestBySubregion.values.sortedWith(
            compareBy<BestCandidate> { it.match.editDistance }.thenByDescending { it.compactLen }
        )
        val winner=ranked.first()
        val tied=ranked.count {
            it.match.editDistance==winner.match.editDistance && it.compactLen==winner.compactLen
        }
        return if(tied==1) winner.match else null
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
        val norm=TextNorm.normalize(raw)
        if(!modules.vintageRecovery) {
            if(!canonicalYearPattern.matches(norm)) return null
            val numeric=norm.toIntOrNull() ?: return null
            return norm.takeIf { numeric in 1900..maxYear }
        }
        val compact=norm.filter(Char::isLetterOrDigit)
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

    /** Early alternatives retain the original observation IDs: never additional engine votes. */
    private fun discoverSpans(graph:EvidenceGraph,year:Int,checkBudget:()->Unit,trace:EvidenceTrace):EvidenceGraph {
        if(!(modules.geographySpans || modules.producerSpans || modules.grapeSpans ||
            modules.classificationSpans || modules.vintageSpans || modules.wineNameSpans)) return graph
        val out=graph.phrases.toMutableList()
        var truncated=graph.truncated
        for(o in graph.observations) {
            if(modules.administrativeFiltering && com.yourapp.wine.extract.LabelTextRoles.isAdministrativeIdentity(o.record.textRaw)) continue
            val tokens=Regex("[\\p{L}\\p{N}]+(?:[-’'][\\p{L}\\p{N}]+)*").findAll(o.record.textRaw).take(24).toList()
            // Longest first preserves compound terms before shorter competing interpretations.
            for(size in minOf(6,tokens.size) downTo 1) for(start in 0..tokens.size-size) {
                checkBudget()
                val text=o.record.textRaw.substring(tokens[start].range.first,tokens[start+size-1].range.last+1)
                if(TextNorm.normalize(text)==TextNorm.normalize(o.record.textRaw)) continue
                val fields=linkedSetOf<FieldId>()
                if(modules.geographySpans && (isBroadGeographyLine(text) ||
                    repairs.resolve(text,emptyList(),emptyList(),PlaceRepair.Config.experimental)!=null))
                    fields.addAll(listOf(FieldId.COUNTRY,FieldId.REGION,FieldId.SUBREGION))
                if(modules.producerSpans && kb.findProducerFuzzy(text).any { (it.editDistance ?: Int.MAX_VALUE)<=1 }) fields+=FieldId.PRODUCER
                if(modules.grapeSpans && catalogWordKey(text) in grapeCandidateKeys) fields+=FieldId.WINE_NAME
                if(modules.classificationSpans && (WineDesignations.isDesignation(text) || WineDesignations.classificationBase(text)!=null)) fields+=FieldId.WINE_NAME
                if(modules.vintageSpans && normalizeVintageCandidate(text,year)!=null) {
                    if(foundingYearCue.containsMatchIn(TextNorm.normalize(o.record.textRaw)))
                        trace.add("vintage_context_rejected","observation=${o.id}; reason=founding_context")
                    else fields+=FieldId.VINTAGE
                }
                if(modules.wineNameSpans && size>=2 && text.count(Char::isLetter)>=5 && !text.any(Char::isDigit)) fields+=FieldId.WINE_NAME
                if(fields.isEmpty()) continue
                if(out.size>=EvidenceGraphBuilder.MAX_PHRASES) { truncated=true;continue }
                out+=Phrase(out.size,text,setOf(o.id),android.graphics.RectF(o.record.bboxRect),
                    "candidate-span:"+fields.joinToString(",") { it.name })
            }
        }
        return graph.copy(phrases=out,truncated=truncated)
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
                allowWineTitlePairs=allowWineTitlePairs || modules.verticalWineTitlePairs || modules.boundedWineTitlePairs,
                allowHorizontalPhrasePairs=modules.horizontalPhrasePairs,
                wineTitlePairMaxHeightRatio=if(modules.boundedWineTitlePairs) 5.5f else 1.45f,
                allowProducerSpans=modules.splitProducerSpans,
                allowStackedWineTitles=modules.stackedWineTitles,
                allowStackedAppellations=exactGeographyFastPath && (modules.stackedAppellations || modules.broaderDiscovery),
                requireRoleAwareJoins=modules.roleAwareJoins,
                filterAdministrative=modules.administrativeFiltering,
                allowSplitCrestVintage=modules.vintageRecovery,
                enforceSpatialSemanticGuards=modules.spatialSemanticGuards,
            ),
            ::checkBudget,
        ).let { discoverSpans(it,year,::checkBudget,trace) }
        trace.add("graph", "observations=${graph.observations.size}; phrases=${graph.phrases.size}; truncated=${graph.truncated}")
        data class ExactGeoCandidate(val hypothesis:Hypothesis,val place:Place,val depth:Int)
        val hypotheses=mutableListOf<Hypothesis>()
        val exactGeoCandidates=mutableListOf<ExactGeoCandidate>()
        val partialGeoHypotheses=mutableSetOf<Hypothesis>()
        val roleNotes=mutableListOf<String>()
        val contextGraph=if(modules.candidateIsolation) graph.copy(phrases=graph.phrases.filterNot { it.construction.startsWith("candidate-span:") }) else graph
        val labelContext=if(exactGeographyFastPath) LabelContext.collect(contextGraph,gazetteer,view,kb,typedIdentity,true) else LabelContext.collect(contextGraph,gazetteer,view,kb,typedIdentity)
        trace.add("label_context",labelContext.traceSummary)
        // Item 1a: lower-line texts of bounded title pairs, across all engines.
        // A single-engine reading of the same varietal line must not compete
        // with the pair that subsumes it (Inscription PINOT NOIR vs PINOT NOIR).
        val pairLowerTextKeys=if(modules.boundedWineTitlePairs) graph.phrases
            .filter { it.construction=="bounded vertical wine-title pair" || it.construction=="three-line wine-title stack" }
            .mapNotNull { pair ->
                val lowerObs=pair.observations.maxByOrNull { id ->
                    graph.observations.firstOrNull { it.id==id }?.record?.bboxRect?.centerY() ?: 0f
                }
                graph.observations.firstOrNull { it.id==lowerObs }?.record?.textRaw
            }
            .map { catalogWordKey(it) }
            .toSet()
        else emptySet()
        for(p in graph.phrases.sortedByDescending { it.construction.contains("alternative") }) {
            checkBudget()
            if(hypotheses.size >= MAX_HYPOTHESES) { trace.add("hypothesis_limit", "remaining phrases not searched");break }
            val text=WineTermSpacing.wineWords.repair(p.text).text;val norm=TextNorm.normalize(text)
            val endsConnector=Regex("\\b(de|du|di|des)$").containsMatchIn(norm)
            val beginsConnector=Regex("^(de|du|di|des)\\b").containsMatchIn(norm)
            val lexicalBonus=6.0*p.construction.split("alternative").lastIndex
            val sources=graph.observations.filter { it.id in p.observations }
            fun permits(field:FieldId)=!p.construction.startsWith("candidate-span:") ||
                field.name in p.construction.substringAfter(":").split(",")
            val administrativeSource=modules.administrativeFiltering && sources.any {
                com.yourapp.wine.extract.LabelTextRoles.isAdministrativeIdentity(it.record.textRaw)
            }
            val foundingSource=sources.any { foundingYearCue.containsMatchIn(TextNorm.normalize(it.record.textRaw)) }
            // A subspan is not a new observation: retain the eligibility of the full source line.
            fun sharesVintageContext(value:String)=sources.any { source ->
                normalizeVintageCandidate(source.record.textRaw,year)==null &&
                    (detachEdgeVintage(source.record.textRaw,year).second==value ||
                        explicitHarvestVintage(TextNorm.normalize(source.record.textRaw),year)==value)
            }
            val supported=sources.any { it.record.score >= if(it.record.engineId.startsWith("paddle")) .75f else .60f }
            if(!supported) {
                if((!modules.candidateIsolation || permits(FieldId.VINTAGE)) && !administrativeSource && !foundingSource && exactGeographyFastPath && modules.vintageRecovery && sources.any { it.record.score >= .45f } && !LabelContext.isNonOriginAdministrative(text,norm)) {
                    val rescuedYear=normalizeVintageCandidate(text,year)
                    if(rescuedYear!=null && hypotheses.size<MAX_HYPOTHESES) {
                        hypotheses+=Hypothesis(
                            mapOf(FieldId.VINTAGE to rescuedYear),
                            p,
                            55.0-10.0,
                            "complete observed vintage; moderate-confidence Lab year normalization",
                            sharesVintageSource=sharesVintageContext(rescuedYear),
                        )
                        trace.add("vintage_rescued","phrase=${p.id}; vintage=$rescuedYear")
                        continue
                    }
                }
                if((!modules.candidateIsolation || permits(FieldId.SUBREGION)) && exactGeographyFastPath && sources.any { it.record.score >= .45f } &&
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
                        val rescuedContextCountries=labelContext.contextCountriesForPhrase(p.id)
                        val rescuedContextRegions=labelContext.contextRegionsForPhrase(p.id)
                        val rescuedMatches=gazetteer.matches(
                            rescuedNorm,
                            rescuedContextCountries,
                            rescuedContextRegions,
                            true,
                        ).filter { it.type != "country" }
                        val rawRescuedPlace=rescuedMatches.singleOrNull()
                        val rescuedPlace=if(modules.blankSubregionFuzzy) {
                            rawRescuedPlace?.let { rp ->
                                WineDesignations.classificationBase(rp.name)?.let { base ->
                                    gazetteer.matches(TextNorm.normalize(base),rescuedContextCountries,rescuedContextRegions,true)
                                        .filter { it.type!="country" }.singleOrNull()
                                } ?: rp
                            }
                        } else rawRescuedPlace
                        if(rawRescuedPlace!=null && rescuedPlace!=null) {
                            val rawRescuedPath=view.ancestors(rawRescuedPlace)
                            val rescuedPath=view.ancestors(rescuedPlace)
                            if(isExactWholePhrasePlace(rawRescuedPlace,rawRescuedPath,rescuedFramed,rescuedSubject,rescuedYearless) && hypotheses.size<MAX_HYPOTHESES) {
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
                trace.add("unsupported", "phrase=${p.id}");continue
            } // original reading retained; weak lines do not enter main hypothesis competition
            val nonOriginAdmin=LabelContext.isNonOriginAdministrative(text,norm)
            val originFramingAdmin=!nonOriginAdmin && MetadataRules.isBoilerplate(text)
            val administrative=nonOriginAdmin || originFramingAdmin
            val boundedTitlePair=p.construction=="bounded vertical wine-title pair" || p.construction=="three-line wine-title stack"
            val nearbyLines=if(modules.candidateIsolation) graph.observations.filter { other ->
                other.id !in p.observations &&
                    sources.any { it.record.engineId==other.record.engineId && it.record.variantId==other.record.variantId } &&
                    kotlin.math.abs(other.record.bboxRect.left-p.box.left)<.04f &&
                    kotlin.math.abs(other.record.bboxRect.centerY()-p.box.centerY())<.12f
            }.size else graph.phrases.count { other -> other.id!=p.id &&
                kotlin.math.abs(other.box.left-p.box.left)<.04f && kotlin.math.abs(other.box.centerY()-p.box.centerY())<.12f }
            val paragraph=!boundedTitlePair && p.box.width()<.25f && nearbyLines>=3
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
            val repairConfig=if(modules.geographySpans && p.construction.startsWith("candidate-span:")) PlaceRepair.Config.experimental else placeRepair
            val repair=if(repairConfig.enabled && matched.isEmpty() && (!originFramingAdmin || framed!=null) && !namedEstate) {
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
                repairs.resolve(placeSubject,readings,anchors,repairConfig,countryHints)
                    ?: printedBase?.let { base -> repairs.resolve(base,readings,anchors,repairConfig,countryHints) }
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
            fun offer(values:Map<FieldId,String>,rank:Double,why:String,place:Place?=null,sharesVintageSource:Boolean=false):Hypothesis? {
                if(p.construction.startsWith("candidate-span:") &&
                    values.keys.any { it.name !in p.construction.substringAfter(":").split(",") }) {
                    trace.add("candidate_filtered","phrase=${p.id}; values=$values; reason=field_scope; base_rank=$rank");return null
                }
                if(modules.administrativeFiltering && values.keys.any { it==FieldId.PRODUCER || it==FieldId.WINE_NAME } &&
                    (administrativeSource || com.yourapp.wine.extract.LabelTextRoles.isAdministrativeIdentity(text))) {
                    trace.add("candidate_filtered","phrase=${p.id}; values=$values; reason=administrative_role; base_rank=$rank");return null
                }
                if(fragmentOnly && values.keys.all { it==FieldId.PRODUCER || it==FieldId.WINE_NAME }) {
                    trace.add("fragment_identity","phrase=${p.id}; text=$text");return null
                }
                val weakContent=sources.count { TextNorm.normalize(it.record.textRaw) !in setOf("de","du","di","des") && it.record.score < if(it.record.engineId.startsWith("paddle")) .75f else .60f }
                if(hypotheses.size>=MAX_HYPOTHESES) {
                    trace.add("hypothesis_limit","phrase=${p.id}; values=$values");return null
                }
                val added=Hypothesis(values,p,rank-weakContent*10-incomplete*6,why+if(incomplete>0) "; overlapping engine supplies a fuller reading" else "",place,baseRank=rank,sharesVintageSource=sharesVintageSource)
                hypotheses+=added
                return added
            }
            val standaloneYear=normalizeVintageCandidate(text,year)
            if(foundingSource && (standaloneYear!=null || p.construction.startsWith("candidate-span:")))
                trace.add("vintage_context_rejected","phrase=${p.id}; source_observations=${p.observations}; reason=founding_context")
            if(!originFramingAdmin && !foundingSource && standaloneYear!=null)
                offer(mapOf(FieldId.VINTAGE to standaloneYear),55.0,"complete observed vintage",
                    sharesVintageSource=sharesVintageContext(standaloneYear))
            else if(!originFramingAdmin && !foundingSource) {
                val embeddedYear=(attachedYear?.takeIf { places.isNotEmpty() }) ?: explicitHarvestVintage(norm,year)
                if(embeddedYear!=null) offer(mapOf(FieldId.VINTAGE to embeddedYear),52.0,"embedded observed vintage",sharesVintageSource=true)
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
                        offer(mapOf(FieldId.WINE_NAME to framed),if(modules.appellationClassificationPrecedence) 96.0 else 64.0,"framed printed classification qualifies the matched appellation")
                    else if(places.any { TextNorm.normalize(it.name)==subjectNorm } && WineDesignations.isAppellationName(framed))
                        offer(mapOf(FieldId.WINE_NAME to framed),62.0,"framed printed appellation may also name the wine")
                }
                trace.add("administrative", "phrase=${p.id}; text=$text; places=${places.size}")
                continue
            }
            if(repair==null && classificationName && places.isNotEmpty() && printedBase!=null &&
                places.any { pl -> (listOf(pl.name)+BundledOrigins.spellingForms(pl)).any { EvidenceRanking.nameKey(it)==EvidenceRanking.nameKey(printedBase) } })
                offer(mapOf(FieldId.WINE_NAME to placeSubject),if(modules.appellationClassificationPrecedence) 96.0 else 70.0,"printed classification qualifies the matched appellation")
            else if(repair==null && places.any { TextNorm.normalize(it.name)==norm } && WineDesignations.isAppellationName(text))
                offer(mapOf(FieldId.WINE_NAME to text),65.0,"exact printed appellation may also name the wine")
            else if(repair==null && attachedYear!=null && places.any { TextNorm.normalize(it.name)==subjectNorm } && WineDesignations.isAppellationName(placeSubject))
                offer(mapOf(FieldId.WINE_NAME to placeSubject),65.0,"exact printed appellation may also name the wine")
            if(boundedTitlePair) {
                val lowerTitleLine=sources.maxByOrNull { it.record.bboxRect.centerY() }?.record?.textRaw.orEmpty()
                val upperTitleLine=sources.minByOrNull { it.record.bboxRect.centerY() }?.record?.textRaw.orEmpty()
                // Item 1a: an upper line that is a proper token-subset of a catalog
                // producer name is a producer fragment (CAMBRAS of Les Ormes de
                // Cambras), not a wine title; it must not head a pair.
                val upperIsProducerFragment=modules.boundedWineTitlePairs && run {
                    val upperTokens=TextNorm.normalize(upperTitleLine).split(' ').filter { it.length>=3 }
                    upperTokens.isNotEmpty() && kb.findProducer(upperTitleLine).any { hit ->
                        val producerTokens=hit.producer.nameNorm.split(' ').filter(String::isNotBlank)
                        producerTokens.size>upperTokens.size && upperTokens.all { token -> producerTokens.any { it==token } }
                    }
                }
                val lowerIsCatalogAppellation=WineDesignations.isAppellationName(lowerTitleLine) ||
                    WineDesignations.hasNameAndDesignation(lowerTitleLine) ||
                    (modules.stackedWineTitles && TextNorm.normalize(lowerTitleLine) in setOf("port","porto") &&
                        (WineDesignations.isDesignation(upperTitleLine) ||
                            Regex("""(?iu)\b(?:reserve|reserva|riserva|signature|series|brut|late bottled|lbv|blanc de)\b""")
                                .containsMatchIn(TextNorm.normalize(upperTitleLine))))
                val placeMatchesAreAppellations=places.isEmpty() ||
                    (lowerIsCatalogAppellation && places.all { WineDesignations.isAppellationName(it.name) })
                // Item 1a refinement: the pair names the wine only over a varietal
                // lower line. Over an appellation lower line the appellation alone
                // names the wine (exact/classification offers and the appellation
                // fallback own that case); a cuvee+appellation pair there only
                // prepends producer fragments (BOUSQUET COTES DE BOURG).
                // Item 4b exception: a tier/designation upper (LATE BOTTLED
                // VINTAGE over PORT) heads the wine name even over an appellation.
                val upperIsTierTitle=modules.stackedWineTitles &&
                    (WineDesignations.isDesignation(upperTitleLine) ||
                        Regex("""(?iu)\b(?:reserve|reserva|riserva|signature|series|brut|late bottled|lbv|blanc de)\b""")
                            .containsMatchIn(TextNorm.normalize(upperTitleLine)))
                if(MetadataRules.isIdentity(text) && placeMatchesAreAppellations && !endsConnector && !beginsConnector &&
                    !upperIsProducerFragment &&
                    !(modules.boundedWineTitlePairs && WineDesignations.isAppellationName(lowerTitleLine) && !upperIsTierTitle))
                    offer(mapOf(FieldId.WINE_NAME to WineDesignations.orderName(text)),
                        // Item 1a: a bounded pair must not outrank a classification-qualified
                        // appellation (96) or a named cuvee (100) printed on the same label.
                        // A tall script/display upper (>=2.5x the varietal) is the confident
                        // cuvee pattern and may outrank the varietal alone (75); similar-height
                        // stacked lines are estate/producer stacks and stay below it.
                        if(modules.boundedWineTitlePairs) {
                            val upperBox=sources.minByOrNull { it.record.bboxRect.centerY() }?.record?.bboxRect
                            val lowerBox=sources.maxByOrNull { it.record.bboxRect.centerY() }?.record?.bboxRect
                            when {
                                // Item 4b: a bounded three-line stack is the most
                                // specific title reading on the label.
                                modules.stackedWineTitles && p.construction=="three-line wine-title stack" -> 92.0
                                modules.stackedWineTitles && upperIsTierTitle -> 90.0
                                upperBox!=null && lowerBox!=null && lowerBox.height()>0f &&
                                    upperBox.height()>=2.5f*lowerBox.height() -> 88.0
                                else -> 70.0
                            }
                        } else 116.0,
                        "bounded two-line cuvee/title plus varietal or appellation; no geography or vintage boundary crossed")
                trace.add("bounded_title_pair","phrase=${p.id}; text=$text")
                continue
            }
            // Item 1a: paragraph density must not suppress a pure varietal line.
            // A narrow varietal in a dense label area (Merlot under Stags Leap)
            // is still the printed wine description; only its varietal offer is
            // admitted, not generic title/producer interpretations. The line must
            // be exactly a grape term (not a style word like Brut), and a bounded
            // pair covering the same observations subsumes the single-line offer.
            val paragraphVarietal=modules.boundedWineTitlePairs && paragraph && places.isEmpty() &&
                !endsConnector && !beginsConnector && catalogWordKey(text) !in pairLowerTextKeys &&
                BundledGrapes.terms.any { term ->
                    val key=catalogWordKey(text)
                    key==catalogWordKey(term.name) || term.aliases.any { alias -> key==catalogWordKey(alias) }
                }
            if(!MetadataRules.isIdentity(text) || (paragraph && !paragraphVarietal) || places.isNotEmpty() || endsConnector || beginsConnector) { trace.add("identity_filtered", "phrase=${p.id}; paragraph=$paragraph; places=${places.size}; connector=${endsConnector || beginsConnector}");continue }
            if(paragraphVarietal) {
                offer(mapOf(FieldId.WINE_NAME to (WineDesignations.display(text) ?: text)),75.0,"observed varietal; paragraph density does not suppress a varietal line")
                continue
            }
            if(ranking.cuveeRole && EvidenceRanking.namedCuvee(text)) {
                offer(mapOf(FieldId.WINE_NAME to text),if(modules.appellationClassificationPrecedence) 100.0 else 75.0,"explicit named cuvee role; not producer attribution")
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
            val numericTitle=modules.broaderDiscovery && com.yourapp.wine.extract.LabelTextRoles.isNumericTitle(text)
            if(norm.count(Char::isLetter)<4 || (norm.any(Char::isDigit) && !numericTitle)) {
                trace.add("identity_filtered","phrase=${p.id}; reason=short_or_numeric_identity; text=$text");continue
            }
            if(numericTitle) {
                offer(mapOf(FieldId.WINE_NAME to text),75.0,"printed classification-qualified numeric title")
                continue
            }
            val joined=p.construction.contains("connector") || p.construction.contains("producer span")
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
        hypotheses.filter { it !in filteredHypotheses }.forEach {
            trace.add("candidate_filtered","phrase=${it.phrase.id}; values=${it.values}; reason=exact_geography_lock; rank=${it.rank}")
        }
        // ---- Lab gap modules: hypothesis arbitration transforms (default off) ----
        var effectiveHypotheses=filteredHypotheses
        fun identityTokens(value:String):List<String> =
            TextNorm.normalize(value).split(Regex("\\s+")).filter(String::isNotBlank)
        if(modules.producerHeadedWineGuard) {
            // Gap B: a wine stack headed by a line that is itself a producer
            // candidate (DECOY over Pinot Noir) loses to the bare lower line.
            val producerHyps=effectiveHypotheses.filter { it.values.keys==setOf(FieldId.PRODUCER) }
            effectiveHypotheses=effectiveHypotheses.filterNot { h ->
                if(h.values.keys!=setOf(FieldId.WINE_NAME)) false else {
                    val wineTokens=identityTokens(h.values.getValue(FieldId.WINE_NAME))
                    producerHyps.any { p ->
                        isKnownProducerLine(p.values.getValue(FieldId.PRODUCER)) &&
                        p.phrase.observations.size<h.phrase.observations.size &&
                            p.phrase.observations.all { it in h.phrase.observations } &&
                            identityTokens(p.values.getValue(FieldId.PRODUCER)).let { pt ->
                                pt.size<wineTokens.size && wineTokens.subList(0,pt.size)==pt
                            }
                    }.also { if(it) trace.add("producer_headed_wine_guard","phrase=${h.phrase.id}; values=${h.values}") }
                }
            }
        }
        if(modules.identityTextQualityGate) {
            // Gap C: long legal/back-label text must not win an identity field.
            val boilerplate=Regex("\\b(mis|bouteille\\w*|proprietaire|bottled|embouteille\\w*|contains|sulfites|sulphites|warning|imported|government)\\b")
            effectiveHypotheses=effectiveHypotheses.filterNot { h ->
                val field=h.values.keys.singleOrNull()
                if(field!=FieldId.PRODUCER && field!=FieldId.WINE_NAME) false else {
                    val value=h.values.getValue(field)
                    val tokens=identityTokens(value)
                    val anchored=(field==FieldId.PRODUCER && isKnownProducerLine(value)) ||
                        WineDesignations.isAppellationName(value) ||
                        gazetteer.matches(TextNorm.normalize(value)).isNotEmpty()
                    (!anchored && (tokens.size>=8 ||
                        (tokens.size>=6 && boilerplate.containsMatchIn(TextNorm.normalize(value)))))
                        .also { if(it) trace.add("identity_text_quality_gate","phrase=${h.phrase.id}; values=${h.values}") }
                }
            }
        }
        if(modules.appellationTitleArbitration) {
            // Gap A: when an appellation-as-wine hypothesis competes with a clean
            // title line printed above it (and below another identity line), the
            // title outranks the appellation for the wine slot.
            val wineHyps=effectiveHypotheses.filter { it.values.keys==setOf(FieldId.WINE_NAME) }
            val appellationHyps=wineHyps.filter {
                WineDesignations.isAppellationName(it.values.getValue(FieldId.WINE_NAME))
            }
            if(appellationHyps.isNotEmpty()) {
                val identityTopY=(wineHyps+effectiveHypotheses.filter { it.values.keys==setOf(FieldId.PRODUCER) })
                    .minOfOrNull { it.phrase.box.centerY() } ?: 0f
                val classificationTokens=(WineDesignations.classificationCodes+WineDesignations.names)
                    .flatMap { TextNorm.normalize(it).split(Regex("\\s+")) }.filter(String::isNotBlank).toSet()
                val producerHypsByPhrase=effectiveHypotheses.filter { it.values.keys==setOf(FieldId.PRODUCER) }
                    .map { it.phrase.id to identityTokens(it.values.getValue(FieldId.PRODUCER)) }
                val otherIdentityKeys=effectiveHypotheses
                    .filter { it.values.keys==setOf(FieldId.PRODUCER) || it.values.keys==setOf(FieldId.WINE_NAME) }
                    .map { EvidenceRanking.nameKey(it.values.values.single()) }
                val topProducerKey=effectiveHypotheses.filter { it.values.keys==setOf(FieldId.PRODUCER) }
                    .maxByOrNull { it.rank }?.values?.getValue(FieldId.PRODUCER)
                    ?.let { EvidenceRanking.nameKey(it) }
                val boostedIds=wineHyps.filter { h ->
                    val value=h.values.getValue(FieldId.WINE_NAME)
                    val tokens=identityTokens(value)
                    val key=EvidenceRanking.nameKey(value)
                    h.phrase.observations.size==1 &&
                        EvidenceRanking.nameKey(value)!=topProducerKey &&
                        EvidenceRanking.independentEngines(h.phrase,graph).size>=2 &&
                        !WineDesignations.isAppellationName(value) &&
                        WineDesignations.classificationBase(value)==null &&
                        !WineDesignations.isDesignation(value) &&
                        tokens.none { it in classificationTokens } &&
                        !(tokens.size==1 && tokens.single() in setOf("moelleux","brut","sec","doux","dry","sweet","riserva","reserva","crianza","premier","cru","classe")) &&
                        producerHypsByPhrase.none { (phraseId,pt) -> phraseId!=h.phrase.id && pt!=tokens &&
                            tokens.any { t -> pt.any { p -> p==t || (t.length>=5 && p.length>=5 && TextNorm.editDistance(t,p,2)!=null) } } } &&
                        gazetteer.matches(TextNorm.normalize(value)).isEmpty() &&
                        !isKnownProducerLine(value) &&
                        !(typedIdentity && EvidenceIdentityRoles.namedEstate(value)) &&
                        value.count(Char::isLetter)>=4 &&
                        // Not a fragment of the appellation or of another title.
                        appellationHyps.none { a ->
                            val appKey=EvidenceRanking.nameKey(a.values.getValue(FieldId.WINE_NAME))
                            key.length>=4 && (appKey.contains(key) || key.contains(appKey))
                        } &&
                        otherIdentityKeys.none { other -> other!=key && other.length>key.length && other.contains(key) } &&
                        h.phrase.box.centerY()>identityTopY+0.01f &&
                        appellationHyps.any { a -> h.phrase.box.centerY()<a.phrase.box.centerY() }
                }.map { it.phrase.id to it.values }.toSet()
                if(boostedIds.isNotEmpty()) {
                    effectiveHypotheses=effectiveHypotheses.map { h ->
                        if((h.phrase.id to h.values) in boostedIds)
                            h.copy(rank=maxOf(h.rank,74.0),reason=h.reason+"; appellation-title arbitration")
                        else h
                    }
                    trace.add("appellation_title_arbitration","boosted=${boostedIds.size}")
                }
            }
        }
        if(modules.classificationWineName) {
            // Gap G: a standalone Premier Grand Cru Classe phrase can itself be
            // the expected wine name (suppressed today as a bare classification).
            val premierPhrase=graph.phrases.firstOrNull {
                EvidenceRanking.nameKey(it.text)=="premiergrandcruclasse"
            }
            if(premierPhrase!=null && effectiveHypotheses.none {
                    it.values[FieldId.WINE_NAME]?.let { v -> EvidenceRanking.nameKey(v) }=="premiergrandcruclasse"
                }) {
                effectiveHypotheses=effectiveHypotheses+ Hypothesis(
                    mapOf(FieldId.WINE_NAME to "Premier Grand Cru Classé"),
                    premierPhrase,72.0,"standalone Premier Grand Cru Classe as wine name")
                trace.add("classification_wine_name","phrase=${premierPhrase.id}")
            }
        }
        val ranked=effectiveHypotheses.map { EvidenceRanking.adjust(it,graph,ranking) }
        trace.add("extraction_limits","graph_truncated=${graph.truncated}; hypothesis_limit=${hypotheses.size>=MAX_HYPOTHESES}; hypotheses=${hypotheses.size}")
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
        for(field in FieldId.entries) {
            val selected=fields.getValue(field)
            trace.add("field_selection","field=$field; selected=${selected.value}; reason=${selected.reason}; candidates=${ranked.count { field in it.values }}; finalists=${solution.size}")
        }
        val path=(exactLockedGeo?.second ?: solution.firstOrNull()?.firstOrNull { it.place!=null }?.place)?.let(view::ancestors).orEmpty()
        val alternatives=FieldId.entries.associateWith { f -> ranked.filter { f in it.values }.sortedByDescending { it.rank }.distinctBy { it.values[f] }.take(6).map {
            FieldCandidate(f,it.values.getValue(f),UiState.YELLOW,"${it.reason}; phrase=${it.phrase.id}; observations=${it.phrase.observations}; rank=${it.rank}",emptyList())
        } }
        val fill=CellarFieldPresentation.apply(FillResult(fields,"Experimental saved-reading extraction; not production autofill",alternatives,
            roleNotes.take(384)+"observations=${graph.observations.size}; phrases=${graph.phrases.size}; hypotheses=${hypotheses.size}; truncated=${graph.truncated || hypotheses.size >= MAX_HYPOTHESES}",geographyPath=path))
        val finalFill=if(placeRepair.enabled && exactGeographyFastPath && modules.blankSubregionFuzzy &&
            deferRawOcrSubregionFallback.get()!=true &&
            fill.fields[FieldId.SUBREGION]?.value.isNullOrBlank()
        ) {
            val fallback=fallbackBlankSubregionFromRawOcr(
                records=records,
                existingCountry=fill.fields[FieldId.COUNTRY]?.value.orEmpty(),
                existingRegion=fill.fields[FieldId.REGION]?.value.orEmpty(),
                existingProducer=fill.fields[FieldId.PRODUCER]?.value.orEmpty(),
                existingWine=fill.fields[FieldId.WINE_NAME]?.value.orEmpty(),
                checkBudget=::checkBudget,
            )
            if(fallback!=null) {
                val reason="raw OCR appellation fallback: '${fallback.observedSpan}' -> '${fallback.matchedForm}'; edits=${fallback.editDistance}; engine=${fallback.engineId}; confirm"
                trace.add("raw_ocr_subregion_fallback","subregion=${fallback.subregion}; region=${fallback.region}; country=${fallback.country}; $reason")
                val updatedFields=fill.fields.toMutableMap()
                updatedFields[FieldId.SUBREGION]=FieldCandidate(FieldId.SUBREGION,fallback.subregion,UiState.YELLOW,reason,emptyList())
                if(updatedFields[FieldId.REGION]?.value.isNullOrBlank() && fallback.region.isNotBlank()) {
                    updatedFields[FieldId.REGION]=FieldCandidate(FieldId.REGION,fallback.region,UiState.YELLOW,reason,emptyList())
                }
                if(updatedFields[FieldId.COUNTRY]?.value.isNullOrBlank() && fallback.country.isNotBlank()) {
                    updatedFields[FieldId.COUNTRY]=FieldCandidate(FieldId.COUNTRY,fallback.country,UiState.YELLOW,reason,emptyList())
                }
                fill.copy(
                    fields=updatedFields,
                    geographyPath=if(fill.geographyPath.isEmpty()) view.ancestors(fallback.place) else fill.geographyPath,
                )
            } else fill
        } else fill
        return EvidenceResult(finalFill,graph,ranked,(System.nanoTime()-started)/1_000_000,trace,hypotheses)
    }
}
