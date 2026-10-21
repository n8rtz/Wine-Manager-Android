package com.yourapp.wine.extract

import com.yourapp.wine.fill.FieldCandidate
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.fill.SpellingSuggestion
import com.yourapp.wine.kb.Place
import com.yourapp.wine.kb.BundledProducerNames
import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.kb.PlaceGazetteer
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.MergedToken

/** Place claims preserve explicit OCR contradictions instead of silently correcting a country. */
data class PlaceClaim(val place: Place, val sources: List<MergedToken>)
data class ExtractionResult(val candidates: Map<FieldId, List<FieldCandidate>>, val placeClaims: List<PlaceClaim>, val roles:List<RoleEvidence> = emptyList(),
    val alternatives:Map<FieldId,List<FieldCandidate>> = emptyMap(),val spellingSuggestions:List<SpellingSuggestion> = emptyList(),val geographyPath:List<Place> = emptyList())

class FieldExtractor(private val kb: WineKnowledgeBase, private val vintage: VintageExtractor = VintageExtractor(),
                     private val hierarchy: PlaceHierarchy = PlaceHierarchy(kb),
                     private val normalizer: WineTextNormalizer = WineTextNormalizer(kb)) {
    private val gazetteer = PlaceGazetteer(kb)
    private val producerLines = ProducerLineSelector()
    private val geographySpelling=GeographySpelling(kb,gazetteer,hierarchy)
    private val styleTerms=(com.yourapp.wine.kb.BundledGrapes.terms+com.yourapp.wine.kb.BundledWineStyles.terms)
        .map { SpellingTerm(TextNorm.normalize(it.name),it.name,it.aliases) }
    private val bundledProducerTerms=BundledProducerNames.terms.map { SpellingTerm(TextNorm.normalize(it.name),it.name,it.aliases) }
    private var indexedRolePlaces:List<Place>?=null
    private var roleOriginTypes:Map<String,Set<String>> = emptyMap()
    @Synchronized private fun originTypeIndex():Map<String,Set<String>> {
        val places=kb.places()
        if(places!==indexedRolePlaces) {
            roleOriginTypes=IdentityRoles.originTypes(places);indexedRolePlaces=places
        }
        return roleOriginTypes
    }
    init { gazetteer.matches(""); normalizer.repair("") } // App-scoped Default initialization, not a first-scan regex/trie burst.
    /** Extracts from line strings, then whole-label text; evidence remains normalized rectified-label boxes. */
    fun extract(rawLines: List<LabelLine>, currentYear: Int, titleConnectors:List<LabelLine> = emptyList()): ExtractionResult {
        val normalized = rawLines.map { line -> line.copy(text = normalizer.repair(line.text).text) }
        val contextual = ContextualAppellationRepair(gazetteer, hierarchy).repair(normalized)
        val geoSpelling=geographySpelling.suggest(contextual.lines)
        val spellingSuggestions=(contextual.suggestions+geoSpelling.suggestions).toMutableList()
        val layoutLines=geoSpelling.lines.map(LabelLayoutContext::repair)
        val layoutRepairs=geoSpelling.lines.zip(layoutLines).filter { it.first.text!=it.second.text }
        spellingSuggestions+=layoutRepairs.map { LabelLayoutContext.suggestion(it.first,it.second) }
        val repeated=com.yourapp.wine.extract.LabelAlternativeEvidence.repeatedTails(layoutLines)
        spellingSuggestions+=repeated.changes.map { LabelAlternativeEvidence.suggestion(it.first,it.second) }
        val lines = joinPrintedAppellations(repeated.lines)
        val paragraphSources=LabelLayoutContext.paragraphLines(lines).flatMap { it.tokens }.toSet()
        val result = mutableMapOf<FieldId, List<FieldCandidate>>()
        result[FieldId.VINTAGE] = vintage.extract(lines.filterNot { MetadataRules.isPackaging(it.text) ||
            com.yourapp.wine.kb.ReferenceWineCatalog.isAdministrative(it.text) }, currentYear)
        val geography = hierarchy.snapshot()
        val originTypes=originTypeIndex()
        val roles=lines.map { IdentityRoles.classify(it,gazetteer.matches(TextNorm.normalize(it.text)),originTypes) }
        val excluded=roles.filter { IdentityRole.PACKAGING in it.roles || IdentityRole.ADMINISTRATIVE in it.roles || IdentityRole.ORIGIN_SUSPECT in it.roles }.map { it.line }.toSet()
        val safeLines=lines.filterNot { it in excluded }
        val whole = safeLines.joinToString(" ") { it.text }
        // Importer/distributor and warning lines are not wine-origin evidence, in any catalog language.
        val originLines = safeLines.filterNot { com.yourapp.wine.kb.ReferenceWineCatalog.isAdministrative(it.text) }
        val claims = (originLines.flatMap { matchPlaces(it.text, it.tokens) } +
            matchPlaces(originLines.joinToString(" ") { it.text }, originLines.flatMap { it.tokens }))
            .distinctBy { it.place.id }.sortedWith(compareByDescending<PlaceClaim> { geography.ancestors(it.place).size }
                .thenByDescending { it.place.nameNorm.length }.thenByDescending { vote(it.sources) })
        val primary = claims.firstOrNull()
        if (primary != null) {
            val path = geography.ancestors(primary.place)
            for (field in listOf(FieldId.COUNTRY, FieldId.REGION, FieldId.SUBREGION)) {
                val explicit = claims.filter { fieldFor(it.place) == field }
                val inherited = when (field) {
                    FieldId.COUNTRY -> path.firstOrNull { it.type == "country" }
                    FieldId.REGION -> if ((com.yourapp.wine.kb.ReferenceWineCatalog.origin(primary.place)?.regions?.size ?: 0) > 1)
                        null else path.firstOrNull { it.type == "region" }
                    else -> path.firstOrNull { it.type in setOf("subregion", "appellation") }
                }
                val candidates = explicit.map { claim -> placeCandidate(field, claim.place, claim.sources, "Label gazetteer match") } +
                    listOfNotNull(inherited?.let { placeCandidate(field, it, primary.sources, "KB parent of ${primary.place.name}") })
                result[field] = candidates.distinctBy { TextNorm.normalize(it.value) }
            }
        }
        val placeLines=roles.filter { IdentityRole.ORIGIN in it.roles && IdentityRole.APPELLATION !in it.roles }.map { it.line }.toSet()
        val identityLines = LabelNamePolicy.withoutAwards(safeLines).filter { it !in placeLines }.mapNotNull { line ->
            val cleaned = MetadataRules.identityText(line.text)
            if (MetadataRules.isIdentity(cleaned) && !producerLines.isAdministrative(cleaned)) line.copy(text = cleaned) else null
        }
        val styleMatches=identityLines.associateWith { line ->
            if(producerLines.hasProducerCue(line.text) || BundledProducerNames.exactName(line.text)!=null ||
                producerLines.isWineDescription(line.text) || kb.findProducer(line.text).any { it.editDistance==0 }) emptyList()
            else BoundedSpelling.matches(line.text,styleTerms,"varietal/style")
        }
        val producerCandidates = producerLines.candidates(identityLines).filterNot { line ->
            styleMatches.any { (source,matches) -> source.tokens==line.tokens && matches.isNotEmpty() }
        }
        // FTS is a shortlist, not evidence that an unknown winery is a familiar producer.
        // Require a bounded full-name match here; an exact whole-label phrase can still match below.
        val knownLines = (identityLines + producerCandidates).distinct().flatMap { line ->
            kb.findProducer(line.text).filter { hit -> hit.editDistance==0 }.take(3).map { hit ->
                hit to FieldCandidate(FieldId.PRODUCER, hit.producer.name,
                    if (hit.editDistance == 0 && line.vote >= 0.6f) UiState.GREEN else UiState.YELLOW,
                    if (hit.editDistance == 0) "Normalized exact producer in local KB"
                    else "Producer fuzzy edit distance ${hit.editDistance}; confirm", line.tokens)
            }
        }
        // Whole-label fallback can recover a producer split across lines or sharing a line with a year.
        val producerContexts=safeLines+safeLines.flatMapIndexed { index,line ->
            safeLines.drop(index+1).take(3).filter { other ->
                val a=line.bboxRect;val b=other.bboxRect
                maxOf(0f,maxOf(a.top,b.top)-minOf(a.bottom,b.bottom)) <= (if(producerLines.isMarker(line.text)) 3f else 1.5f)*maxOf(a.height(),b.height()) &&
                    minOf(a.right,b.right)-maxOf(a.left,b.left) >= .4f*minOf(a.width(),b.width())
            }.map { other -> LabelLine(line.text+" "+other.text,line.tokens+other.tokens,
                com.yourapp.wine.merge.BoxMath.union(listOf(line.bboxRect,other.bboxRect))) }
        }
        val knownWhole = kb.findProducer(whole).mapNotNull { hit ->
            val pattern=Regex("(?<![\\p{L}\\p{N}])${Regex.escape(hit.matchedNorm)}(?![\\p{L}\\p{N}])")
            val context=producerContexts.firstOrNull { pattern.containsMatchIn(TextNorm.normalize(it.text)) } ?: return@mapNotNull null
            val sources=context.tokens
            hit.copy(editDistance = 0) to FieldCandidate(FieldId.PRODUCER, hit.producer.name,
                if (vote(sources) >= .6f) UiState.GREEN else UiState.YELLOW, "Normalized exact producer in spatially contiguous text", sources)
        }
        val known = (knownLines + knownWhole).sortedWith(
            compareBy<Pair<com.yourapp.wine.kb.ProducerHit, FieldCandidate>> { it.first.editDistance ?: Int.MAX_VALUE }
                .thenByDescending { vote(it.second.sources) })
        val curated=producerCandidates.mapNotNull { line -> BundledProducerNames.exactName(line.text)?.let {
            IdentityOption(FieldCandidate(FieldId.PRODUCER,it,UiState.YELLOW,"Exact authored producer alias; confirm",line.tokens),95.0)
        } }
        val fuzzyProducers=producerCandidates.flatMap { line ->
            if(known.any { (_,candidate) -> candidate.sources.any { it in line.tokens } } ||
                curated.any { option -> option.candidate.sources.any { it in line.tokens } }) emptyList() else {
                val local=kb.findProducerFuzzy(line.text).map { it.producer }.distinctBy { it.id }.map {
                    SpellingTerm(TextNorm.normalize(it.name),it.name,it.aliases)
                }
                val matches=BoundedSpelling.matches(line.text,local+bundledProducerTerms,"producer")
                matches.take(8).map { match ->
                    val records=line.tokens.flatMap { it.members }
                    val observedEngines=records.filter { BoundedSpelling.key(it.textRaw)==BoundedSpelling.key(line.text) ||
                        BoundedSpelling.distance(it.textRaw,match.suggestion.matchedForm)?.let { d -> d>0 }==true }.map { it.engineId }.toSet()
                    val corroboration=records.filter { BoundedSpelling.key(it.textRaw)==BoundedSpelling.key(match.suggestion.matchedForm) &&
                        it.engineId !in observedEngines && !it.scoreIsEstimated && it.score >= if(it.engineId.startsWith("paddle")) .75f else .6f }.map { it.engineId }.distinct()
                    val support=when {
                        corroboration.isNotEmpty() -> "separate engine exact reading: "+corroboration.joinToString()+"; confirm"
                        producerLines.hasProducerCue(line.text) -> "producer marker supports role, not identity; confirm"
                        else -> "lexical only; confirm"
                    }
                    val suggestion=match.suggestion.copy(support=support)
                    spellingSuggestions+=suggestion
                    IdentityOption(FieldCandidate(FieldId.PRODUCER,match.term.name,UiState.YELLOW,
                        BoundedSpelling.reason(suggestion),line.tokens,suggestion),if(suggestion.editDistance==1) 72.0 else 68.0)
                }
            }
        }
        val established=known.map { (hit,candidate) -> IdentityOption(candidate,if(hit.editDistance==0) 100.0 else 70.0) }+curated+fuzzyProducers
        val producerOptions=established+
            producerCandidates.filterNot { line ->
                established.any { match -> match.candidate.sources.any { it in line.tokens } } ||
                (!producerLines.hasProducerCue(line.text) && LabelNamePolicy.hasAdjacentStyle(line,identityLines))
            }.map { line -> IdentityOption(FieldCandidate(FieldId.PRODUCER,line.text,UiState.YELLOW,
                "OCR-only producer; unknown makers allowed",line.tokens),
                (if(producerLines.hasProducerCue(line.text)) 65.0 else 30.0)+line.bboxRect.height().coerceIn(0f,.2f)*20 -
                    (if(line.tokens.isNotEmpty() && line.tokens.all { it in paragraphSources }) 20.0 else 0.0)) }
        val rankedProducers=producerOptions.sortedWith(compareByDescending<IdentityOption> { it.priority }.thenByDescending { it.candidate.sources.size }).distinctBy { TextNorm.normalize(it.candidate.value) }
        result[FieldId.PRODUCER]=rankedProducers.map { it.candidate }.take(4)
        // A cuvee lookup is allowed only for an unambiguous, full exact producer match.
        val producerId=known.firstOrNull()?.takeIf { entry -> entry.first.editDistance==0 &&
            known.filter { it.first.editDistance==0 }.map { it.first.producer.id }.distinct().size==1 &&
            rankedProducers.firstOrNull()?.candidate?.value==entry.second.value }?.first?.producer?.id
        val producerSources = result[FieldId.PRODUCER]?.firstOrNull()?.sources.orEmpty().toSet()
        val selectedProducer = result[FieldId.PRODUCER]?.firstOrNull()?.value
        val producerSpellings = known.firstOrNull()?.first?.producer?.let { listOf(it.name) + it.aliases } ?: listOfNotNull(selectedProducer)
        val wines = identityLines.mapNotNull { line ->
            if (producerLines.isMarker(line.text)) return@mapNotNull null
            var distinctive = line.text
            producerSpellings.sortedByDescending { it.length }.forEach { producerName ->
                distinctive = removeNormalizedPhrase(distinctive, producerName)
            }
            // A fuzzy-only producer line must not be duplicated into the wine-name field.
            if (distinctive == line.text && line.tokens.any { it in producerSources }) return@mapNotNull null
            // A printed appellation may itself name the wine. Keep its observed suffix (even truncated),
            // rather than stripping the geographic core and promoting the leftover fragment.
            if (!WineDesignations.isAppellationName(distinctive)) {
                claims.sortedByDescending { it.place.name.length }.forEach { claim ->
                    distinctive = removeNormalizedPhrase(distinctive, claim.place.name)
                }
            }
            distinctive = distinctive.replace(Regex("\\s+"), " ").trim()
            if (!MetadataRules.isIdentity(distinctive)) return@mapNotNull null
            val hit = producerId?.let { kb.findCuvee(it, distinctive) }?.takeIf { it.editDistance==0 }
            FieldCandidate(FieldId.WINE_NAME, WineDesignations.orderName(hit?.cuvee?.name ?: distinctive),
                if (hit?.editDistance == 0 && line.vote >= 0.6f) UiState.GREEN else UiState.YELLOW,
                if (hit == null) "Distinctive OCR-only wine name; confirm" +
                    (if (line.tokens.any { normalizer.repair(it.canonical).changed }) "; verified term spacing normalized" else "")
                else "Known cuvée for selected producer (edit ${hit.editDistance})",
                line.tokens)
        }.sortedWith(compareBy<FieldCandidate> { if (it.state == UiState.GREEN) 0 else 1 }
            .thenBy { if (producerLines.isWineDescription(it.value)) 1 else 0 }
            .thenByDescending { (vote(it.sources) * 1000).toInt() }
            .thenByDescending { it.sources.maxOfOrNull { t -> t.bboxRect.height() } ?: 0f })
        // A printed style/appellation can be the only wine name (e.g. Prosecco or Champagne).
        // Reuse actual accepted label text only, never infer a cuvee from its geography or producer.
        val styleFallback = placeLines.mapNotNull { line ->
            val raw = rawLines.firstOrNull { it.tokens == line.tokens }?.text ?: line.text
            val display = WineTermSpacing.wineWords.repair(MetadataRules.identityText(raw)).text
            if (!producerLines.isWineDescription(display) || (selectedProducer == null && !WineDesignations.isAppellationName(display))) null else FieldCandidate(FieldId.WINE_NAME, display,
                UiState.YELLOW, "Printed wine style/appellation used as name — confirm", line.tokens)
        }.sortedByDescending { vote(it.sources) }
        val fuzzyNames=styleMatches.flatMap { (line,matches) -> matches.take(8).map { match ->
            val suggestion=match.suggestion
            spellingSuggestions+=suggestion
            FieldCandidate(FieldId.WINE_NAME,match.term.name,UiState.YELLOW,BoundedSpelling.reason(suggestion),line.tokens,suggestion)
        } } + if(producerId==null) emptyList() else wines.filterNot { it.reason.startsWith("Known cuvée") }.flatMap { candidate ->
            val terms=kb.findCuvees(producerId,candidate.value).map { SpellingTerm(TextNorm.normalize(it.cuvee.name),it.cuvee.name,emptyList()) }
            BoundedSpelling.matches(candidate.value,terms,"cuvee").take(8).map { match ->
                val suggestion=match.suggestion.copy(support="unambiguous exact producer: $selectedProducer; confirm")
                spellingSuggestions+=suggestion
                candidate.copy(value=match.term.name,state=UiState.YELLOW,reason=BoundedSpelling.reason(suggestion),spelling=suggestion)
            }
        }
        val correctedSources=fuzzyNames.flatMap { it.sources }.toSet()
        val montalcinoName=MontalcinoNameContext.supported(safeLines)
        val layoutNames=LabelLayoutContext.titles(safeLines+titleConnectors).map { candidate ->
            if(titleConnectors.any { line -> candidate.sources.any { it in line.tokens } }) candidate.copy(
                reason=candidate.reason+"; weak connector retained only for aligned title; no OCR confidence increase") else candidate
        }
        spellingSuggestions+=layoutNames.mapNotNull { it.spelling }
        val allNames=(layoutNames+wines.filterNot { it.spelling==null && it.sources.any { source -> source in correctedSources } }+styleFallback+fuzzyNames+listOfNotNull(montalcinoName)).sortedBy { it.spelling?.editDistance ?: 0 }.distinctBy { TextNorm.normalize(it.value) }
        val designations=allNames.filter { WineDesignations.isDesignation(it.value) }
        val nameOptions=allNames.filter { !WineDesignations.isDesignation(it.value) || allNames.all { n -> WineDesignations.isDesignation(n.value) } }.map { candidate ->
            val joined=LabelNamePolicy.joinStyle(listOf(candidate)+designations,selectedProducer) ?: candidate
            val priority=when {
                candidate.reason.startsWith("Aligned stacked title") -> 88.0
                candidate.reason.contains("contextual wine-name suggestion") -> 75.0
                candidate.spelling?.domain=="cuvee" -> if(candidate.spelling.editDistance==1) 88.0 else 84.0
                candidate.spelling!=null -> if(candidate.spelling.editDistance==1) 55.0 else 51.0
                candidate.reason.startsWith("Known cuvée") -> 100.0
                joined.value!=candidate.value || WineDesignations.hasNameAndDesignation(candidate.value) -> 80.0
                !producerLines.isWineDescription(candidate.value) && candidate.value.split(Regex("\\s+")).count { word -> word.count(Char::isLetter)>=3 }>=2 -> 70.0
                WineDesignations.isAppellationName(candidate.value) -> 60.0
                producerLines.isWineDescription(candidate.value) -> 65.0
                else -> 45.0
            }
            val nearLegal=roles.any { role ->
                IdentityRole.ADMINISTRATIVE in role.roles && LabelTextRoles.isLegalStatement(role.line.text) && candidate.sources.any { source ->
                    val a=source.bboxRect;val b=role.line.bboxRect
                    maxOf(0f,maxOf(a.top,b.top)-minOf(a.bottom,b.bottom)) <= maxOf(a.height(),b.height()) &&
                        minOf(a.right,b.right)-maxOf(a.left,b.left) >= .5f*minOf(a.width(),b.width())
                }
            }
            val paragraph=candidate.sources.isNotEmpty() && candidate.sources.all { it in paragraphSources }
            val penalty=if((paragraph || nearLegal) && !producerLines.isWineDescription(candidate.value) && priority<90) 30.0 else 0.0
            IdentityOption(if(penalty>0) joined.copy(reason=joined.reason+"; paragraph/adjacent legal block lowers unsupported name rank") else joined,priority-penalty)
        }
        val selection=IdentityAssignment.select(producerOptions,nameOptions)
        result.putAll(selection.fields)
        result.replaceAll { _, candidates -> candidates.map { candidate ->
            val repair=repeated.changes.firstOrNull { (original,_) -> candidate.sources.any { it in original.tokens } }
            if(repair!=null && candidate.state!=UiState.RED) candidate.copy(state=UiState.YELLOW,
                spelling=LabelAlternativeEvidence.suggestion(repair.first,repair.second),
                reason=candidate.reason+"; alternative OCR/cross-label repair '${repair.first.text}' -> '${repair.second.text}'; confirm; raw readings retained") else candidate
        } }
        result.replaceAll { _, candidates -> candidates.map { candidate ->
            val repairs=layoutRepairs.filter { (original,_) -> candidate.sources.any { it in original.tokens } }
            if(repairs.isNotEmpty() && candidate.state!=UiState.RED) candidate.copy(state=UiState.YELLOW,
                spelling=candidate.spelling ?: LabelLayoutContext.suggestion(repairs.first().first,repairs.first().second),
                reason=candidate.reason+"; bounded role/appellation repair: "+repairs.joinToString { "'${it.first.text}' -> '${it.second.text}'" }+"; confirm; raw OCR unchanged") else candidate
        } }
        // A lexical suggestion is not measured evidence: cap all dependent fields and inherited geography.
        if (contextual.repaired.isNotEmpty() || geoSpelling.repaired.isNotEmpty()) result.replaceAll { _, candidates -> candidates.map { candidate ->
            if (candidate.state!=UiState.RED && candidate.sources.any { it in contextual.repaired || it in geoSpelling.repaired }) candidate.copy(state = UiState.YELLOW,
                reason = candidate.reason + "; contextual appellation spelling suggestion supported by separate geography — confirm; raw OCR unchanged")
            else candidate
        } }
        // Attach the lexical cause to any geography derived from the repaired observation.
        result.replaceAll { _,candidates -> candidates.map { candidate ->
            val suggestion=(contextual.suggestions+geoSpelling.suggestions).firstOrNull { spelling -> candidate.sources.any { token ->
                (token in geoSpelling.repaired || token in contextual.repaired) && BoundedSpelling.key(token.canonical)==BoundedSpelling.key(spelling.observed)
            } }
            if(suggestion!=null && candidate.state!=UiState.RED) candidate.copy(state=UiState.YELLOW,spelling=suggestion,
                reason=candidate.reason+"; "+BoundedSpelling.reason(suggestion)) else candidate
        } }
        val referenceSources = claims.filter { com.yourapp.wine.kb.ReferenceWineCatalog.requiresConfirmation(it.place) }
            .flatMap { it.sources }.toSet()
        if (referenceSources.isNotEmpty()) result.replaceAll { _, candidates -> candidates.map { candidate ->
            if (candidate.state != UiState.RED && candidate.sources.any { it in referenceSources }) candidate.copy(state = UiState.YELLOW,
                reason = candidate.reason + "; expanded reference-catalog origin — confirm; not a legal certification")
            else candidate
        } }
        return ExtractionResult(result, claims,roles,selection.alternatives,spellingSuggestions.distinct().take(64),primary?.let { geography.ancestors(it.place) }.orEmpty())
    }

    /** Only two adjacent observed pieces whose entire combined text is a verified appellation. */
    private fun joinPrintedAppellations(input:List<LabelLine>):List<LabelLine> {
        val ordered=input.sortedBy { it.bboxRect.centerY() };val used=mutableSetOf<LabelLine>();val output=mutableListOf<LabelLine>()
        for((index,line) in ordered.withIndex()) {
            if(line in used) continue
            val next=ordered.drop(index+1).take(4).firstOrNull { other ->
                if(other in used) false else {
                    val a=line.bboxRect;val b=other.bboxRect
                    val gap=maxOf(0f,b.top-a.bottom)
                    val aligned=minOf(a.right,b.right)-maxOf(a.left,b.left) >= .4f*minOf(a.width(),b.width())
                    val text=normalizer.repair(line.text+" "+other.text).text
                    val compact=TextNorm.normalize(text).filter(Char::isLetterOrDigit)
                    aligned && gap<=1.5f*maxOf(a.height(),b.height()) && WineDesignations.isAppellationName(text) &&
                        gazetteer.matches(TextNorm.normalize(text)).any { place ->
                            (listOf(place.name)+com.yourapp.wine.kb.BundledOrigins.spellingForms(place)).any {
                                TextNorm.normalize(it).filter(Char::isLetterOrDigit)==compact
                            }
                        }
                }
            }
            if(next==null) output+=line else {
                used+=next
                output+=LabelLine(normalizer.repair(line.text+" "+next.text).text,line.tokens+next.tokens,
                    com.yourapp.wine.merge.BoxMath.union(listOf(line.bboxRect,next.bboxRect)))
            }
        }
        return output
    }

    private fun removeNormalizedPhrase(raw: String, phrase: String): String {
        // Preserve display characters: locate spans by normalized word sequences, not normalized-string offsets.
        val words = Regex("[\\p{L}\\p{M}\\p{N}'’-]+").findAll(raw).toList()
        val expected = Regex("[\\p{L}\\p{M}\\p{N}'’-]+").findAll(TextNorm.normalize(phrase)).map { it.value }.toList()
        if (expected.isEmpty() || words.size < expected.size) return raw
        for (start in 0..words.size - expected.size) {
            if (words.subList(start, start + expected.size).map { TextNorm.normalize(it.value) } == expected) {
                return raw.removeRange(words[start].range.first, words[start + expected.size - 1].range.last + 1).trim()
            }
        }
        return raw
    }

    private fun matchPlaces(text: String, sources: List<MergedToken>): List<PlaceClaim> {
        val normalized = TextNorm.normalize(text)
        return gazetteer.matches(normalized).map { p ->
            val words = p.nameNorm.split(' ').toSet()
            val evidence = sources.filter { token -> TextNorm.normalize(token.canonical).split(' ').any { it in words } }
            PlaceClaim(p, evidence.ifEmpty { sources })
        }
    }

    private fun fieldFor(place: Place): FieldId = when (place.type) {
        "country" -> FieldId.COUNTRY
        "region" -> FieldId.REGION
        else -> FieldId.SUBREGION
    }

    private fun placeCandidate(field: FieldId, p: Place, sources: List<MergedToken>, reason: String) =
        FieldCandidate(field, p.name, if (vote(sources) >= 0.6f) UiState.GREEN else UiState.YELLOW, reason, sources)

    private fun vote(tokens: List<MergedToken>): Float = tokens.minOfOrNull { it.vote } ?: 0f
}
