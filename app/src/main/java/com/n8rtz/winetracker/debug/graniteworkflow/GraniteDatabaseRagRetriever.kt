package com.n8rtz.winetracker.debug.graniteworkflow

import android.content.Context
import android.os.SystemClock
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.gazetteer.LinkedWineResolver
import com.yourapp.wine.gazetteer.ProducerGazetteer
import com.yourapp.wine.gazetteer.ProducerResolver
import com.yourapp.wine.kb.BundledGrapes
import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.kb.BundledWineStyles
import com.yourapp.wine.kb.ReferenceWineCatalog
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.textlab.TextLabBudgets
import com.yourapp.wine.textlab.TextLabFormPreview
import com.yourapp.wine.textlab.TextLabJournal
import com.yourapp.wine.textlab.TextLabSpatialPhrases
import com.yourapp.wine.textlab.TextLabSpatialPreview
import com.yourapp.wine.textlab.TextLabWineExtraction
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Year
import java.util.Locale

/**
 * One producer candidate retrieved from the offline SQLite Producer Gazetteer
 * (`producer-gazetteer-expanded-v1/producers-expanded.sqlite` / verified LWIN + supplemental sources).
 */
data class GraniteProducerCandidate(
    val id: String,
    val name: String,
    val country: String,
    val distance: Int,
    val matchedSpan: String,
    val sources: String
)

/**
 * One producer-linked wine/cuvée candidate retrieved from the offline SQLite Wine Index
 * (`producer-gazetteer-v1/wines.sqlite`).
 */
data class GraniteLinkedWineCandidate(
    val id: String,
    val name: String,
    val producerId: String,
    val lwin7: String,
    val distance: Int,
    val matchedSpan: String
)

/**
 * One appellation / region / country hierarchy candidate retrieved from `ReferenceWineCatalog`,
 * `BundledOrigins`, `PlaceGazetteer`, and the regional geography tables shared with GLiNER.
 */
data class GraniteAppellationCandidate(
    val appellationOrSubregion: String,
    val region: String,
    val country: String,
    val matchedSpan: String,
    val distance: Int,
    val source: String
)

/**
 * Complete Candidate Retrieval (RAG) bundle produced from the exact same offline databases used
 * by `GLiNER lane lab` (`GlinerLabRunner` + `TextLabWineExtraction.WarmSession` + `ProducerGazetteer`
 * + `ReferenceWineCatalog` + `BundledOrigins` + `BundledGrapes` + `BundledWineStyles`).
 */
data class GraniteDatabaseRagContext(
    val enabled: Boolean = true,
    val elapsedMs: Long = 0L,
    val producerCandidates: List<GraniteProducerCandidate> = emptyList(),
    val linkedWineCandidates: List<GraniteLinkedWineCandidate> = emptyList(),
    val appellationCandidates: List<GraniteAppellationCandidate> = emptyList(),
    val matchedGrapes: List<String> = emptyList(),
    val matchedStyles: List<String> = emptyList(),
    val spatialContenderFields: Map<String, String> = emptyMap(),
    val spatialContenderStates: Map<String, String> = emptyMap(),
    val gazetteerVersion: String = "",
    val diagnosticNotes: List<String> = emptyList()
) {
    val hasCandidates: Boolean
        get() = producerCandidates.isNotEmpty() ||
            linkedWineCandidates.isNotEmpty() ||
            appellationCandidates.isNotEmpty() ||
            matchedGrapes.isNotEmpty() ||
            spatialContenderFields.values.any { it.isNotBlank() }

    /**
     * Formats the retrieved database candidates into a concise, structured RAG block for the
     * Granite 4.2 prompt.
     */
    fun toPromptRagSection(): String {
        if (!enabled) {
            return "(Database RAG disabled by toggle)"
        }
        return buildString {
            appendLine("1. LWIN / Expanded Producer Gazetteer Matches (producers-expanded.sqlite):")
            if (producerCandidates.isEmpty()) {
                appendLine("   (No close producer matches in SQLite gazetteer)")
            } else {
                producerCandidates.take(8).forEach { p ->
                    val countryPart = if (p.country.isNotBlank()) ", country=${p.country}" else ""
                    val srcPart = if (p.sources.isNotBlank()) ", source=${p.sources}" else ""
                    appendLine("   - \"${p.name}\" (dist=${p.distance}, span=\"${p.matchedSpan}\"$countryPart$srcPart)")
                }
            }

            appendLine("2. Producer-Linked Wine & Cuvée Matches (wines.sqlite / LWIN7):")
            if (linkedWineCandidates.isEmpty()) {
                appendLine("   (No linked wine matches in SQLite wine index)")
            } else {
                linkedWineCandidates.take(8).forEach { w ->
                    val lwinPart = if (w.lwin7.isNotBlank()) ", lwin7=${w.lwin7}" else ""
                    appendLine("   - \"${w.name}\" (dist=${w.distance}, span=\"${w.matchedSpan}\"$lwinPart)")
                }
            }

            appendLine("3. Appellation, Region & Country Catalog Matches (ReferenceWineCatalog / BundledOrigins / SqliteWineKnowledgeBase):")
            if (appellationCandidates.isEmpty()) {
                appendLine("   (No catalog appellation matches)")
            } else {
                appellationCandidates.take(8).forEach { a ->
                    val sub = a.appellationOrSubregion.ifBlank { "(none)" }
                    val reg = a.region.ifBlank { "(none)" }
                    val ctry = a.country.ifBlank { "(none)" }
                    appendLine("   - Subregion/Appellation=\"$sub\" -> Region=\"$reg\", Country=\"$ctry\" (dist=${a.distance}, span=\"${a.matchedSpan}\", src=${a.source})")
                }
            }

            appendLine("4. Grape Varietals & Wine Styles Catalog (BundledGrapes / BundledWineStyles):")
            val grapesText = if (matchedGrapes.isEmpty()) "(none)" else matchedGrapes.joinToString(", ")
            val stylesText = if (matchedStyles.isEmpty()) "(none)" else matchedStyles.joinToString(", ")
            appendLine("   - Grapes: $grapesText")
            appendLine("   - Styles/Classifications: $stylesText")

            if (spatialContenderFields.values.any { it.isNotBlank() }) {
                appendLine("5. Corroborated Database Spatial Contender (SqliteWineKnowledgeBase + ProducerResolver + LinkedWineResolver):")
                val p = spatialContenderFields["producer"].orEmpty().ifBlank { "null" }
                val w = spatialContenderFields["wine_name"].orEmpty().ifBlank { "null" }
                val c = spatialContenderFields["country"].orEmpty().ifBlank { "null" }
                val r = spatialContenderFields["region"].orEmpty().ifBlank { "null" }
                val s = spatialContenderFields["subregion"].orEmpty().ifBlank { "null" }
                val v = spatialContenderFields["vintage"].orEmpty().ifBlank { "null" }
                appendLine("   - producer=\"$p\", wine_name=\"$w\", country=\"$c\", region=\"$r\", subregion=\"$s\", vintage=\"$v\"")
            }
        }.trimEnd()
    }
}

/**
 * Provides the **Dual OCR + Granite 4.2 4B Workflow** with the exact same offline database access
 * used by `GLiNER lane lab` (`GlinerLabRunner`):
 *
 * 1. `SqliteWineKnowledgeBase` (`TextLabWineExtraction.WarmSession.knowledgeBase()`)
 * 2. `ProducerGazetteer.openExpanded` (`assets/producer-gazetteer-expanded-v1/producers-expanded.sqlite`
 *    + `assets/producer-gazetteer-v1/wines.sqlite`)
 * 3. `ProducerResolver` & `LinkedWineResolver` (FTS trigram + bounded edit-distance lookup)
 * 4. `TextLabFormPreview` + `TextLabSpatialPreview` (`EvidenceExtractor`, `EvidenceAnchorHybrid`,
 *    `ProducerRepair`, `PlaceGazetteer`, `SupplementedWineKnowledgeBase`)
 * 5. `ReferenceWineCatalog.origins`, `BundledOrigins.terms`, `BundledGrapes.terms`,
 *    `BundledWineStyles.terms`, and French AOC / Italian DOC-DOCG-IGT / US AVA hierarchies.
 */
class GraniteDatabaseRagRetriever : AutoCloseable {
    private val sessionMutex = Mutex()
    private var warmSession: TextLabWineExtraction.WarmSession? = null

    private data class CatalogOriginEntry(
        val country: String,
        val region: String,
        val subregion: String,
        val forms: List<String>,
        val source: String
    )

    private val catalogOrigins: List<CatalogOriginEntry> by lazy {
        val entries = mutableListOf<CatalogOriginEntry>()
        val saintRegex = Regex("(?i)\\bsaint\\b")

        // 1. ReferenceWineCatalog origins (French AOCs, Italian DOC/DOCGs, US AVAs, etc.)
        ReferenceWineCatalog.origins.forEach { origin ->
            if (!origin.contextRequired && origin.regions.size <= 1) {
                val parentRegion = origin.regions.singleOrNull().orEmpty()
                val baseForms = (listOf(origin.name) + origin.aliases).filter(String::isNotBlank)
                val expandedForms = if (origin.country == "France") {
                    (baseForms + baseForms.map { saintRegex.replace(it, "St") }).distinct()
                } else {
                    baseForms.distinct()
                }
                entries += CatalogOriginEntry(
                    country = origin.country,
                    region = parentRegion.ifBlank { origin.name },
                    subregion = if (parentRegion.isNotBlank() &&
                        ProducerResolver.normalize(parentRegion) != ProducerResolver.normalize(origin.name)
                    ) {
                        origin.name
                    } else {
                        ""
                    },
                    forms = expandedForms,
                    source = "ReferenceWineCatalog:${origin.source}"
                )
            }
        }

        // 2. BundledOrigins terms
        BundledOrigins.terms.forEach { term ->
            val forms = (listOf(term.name) + term.aliases).filter(String::isNotBlank).distinct()
            val sub = if (term.region.isNotBlank() &&
                ProducerResolver.normalize(term.name) != ProducerResolver.normalize(term.region)
            ) {
                term.name
            } else {
                ""
            }
            entries += CatalogOriginEntry(
                country = term.country,
                region = term.region.ifBlank { term.name },
                subregion = sub,
                forms = forms,
                source = "BundledOrigins"
            )
        }

        // 3. Additional curated regional hierarchies (same as GlinerGeography)
        val extraOrigins = listOf(
            CatalogOriginEntry("Chile", "Central Valley", "Colchagua Valley", listOf("Colchagua Valley", "Valle de Colchagua"), "GeographyCatalog"),
            CatalogOriginEntry("Chile", "Central Valley", "Cachapoal Valley", listOf("Cachapoal Valley", "Valle de Cachapoal"), "GeographyCatalog"),
            CatalogOriginEntry("Chile", "Central Valley", "Maule Valley", listOf("Maule Valley", "Valle del Maule"), "GeographyCatalog"),
            CatalogOriginEntry("Chile", "Central Valley", "Maipo Valley", listOf("Maipo Valley", "Valle del Maipo"), "GeographyCatalog"),
            CatalogOriginEntry("Chile", "Central Valley", "Curicó Valley", listOf("Curicó Valley", "Valle de Curicó"), "GeographyCatalog"),
            CatalogOriginEntry("Australia", "South Australia", "Clare Valley", listOf("Clare Valley"), "GeographyCatalog"),
            CatalogOriginEntry("Australia", "South Australia", "Barossa Valley", listOf("Barossa Valley"), "GeographyCatalog"),
            CatalogOriginEntry("Australia", "South Australia", "McLaren Vale", listOf("McLaren Vale"), "GeographyCatalog"),
            CatalogOriginEntry("California", "Sonoma County", "Dry Creek Valley", listOf("Dry Creek Valley"), "GeographyCatalog"),
            CatalogOriginEntry("California", "Sonoma County", "Alexander Valley", listOf("Alexander Valley"), "GeographyCatalog"),
            CatalogOriginEntry("California", "Napa Valley", "Stags Leap District", listOf("Stags Leap District"), "GeographyCatalog"),
            CatalogOriginEntry("Oregon", "Willamette Valley", "Dundee Hills", listOf("Dundee Hills"), "GeographyCatalog"),
            CatalogOriginEntry("Washington", "Columbia Valley", "Horse Heaven Hills", listOf("Horse Heaven Hills"), "GeographyCatalog"),
            CatalogOriginEntry("Washington", "Columbia Valley", "Red Mountain", listOf("Red Mountain"), "GeographyCatalog")
        )
        entries.addAll(extraOrigins)
        entries
    }

    private val originsByNormalizedForm: Map<String, List<CatalogOriginEntry>> by lazy {
        val map = linkedMapOf<String, MutableList<CatalogOriginEntry>>()
        for (entry in catalogOrigins) {
            for (form in entry.forms) {
                val key = ProducerResolver.normalize(form)
                if (key.length >= 4) {
                    map.getOrPut(key) { mutableListOf() }.add(entry)
                }
            }
        }
        map
    }

    suspend fun retrieve(
        context: Context,
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode,
        enabled: Boolean = true
    ): GraniteDatabaseRagContext = withContext(Dispatchers.Default) {
        if (!enabled) {
            return@withContext GraniteDatabaseRagContext(enabled = false)
        }
        val started = SystemClock.elapsedRealtime()
        val notes = mutableListOf<String>()

        val records = buildTokenRecords(mlKitBlocks, ppOcrV6Blocks, ablationMode)
        if (records.isEmpty()) {
            return@withContext GraniteDatabaseRagContext(
                enabled = true,
                elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L),
                diagnosticNotes = listOf("No OCR blocks to query against databases")
            )
        }

        sessionMutex.withLock {
            val warm = warmSession ?: TextLabWineExtraction.WarmSession(context.applicationContext).also {
                warmSession = it
            }

            // 1. Run full Spatial + KnowledgeBase + ProducerGazetteer + LinkedWineResolver contender
            //    (identical to GlinerLabRunner.postProcess)
            val journal = TextLabJournal(setOf("candidate_3"), BuildConfig.BUILD_COMMIT, true)
            var gazetteer: ProducerGazetteer? = warm.cachedGazetteer(true)?.gazetteer
            var ownsGazetteer = gazetteer == null
            var unavailableMsg = "Lab expanded producer index required"

            if (gazetteer == null) {
                try {
                    val opened = ProducerGazetteer.openExpanded(context.applicationContext)
                    gazetteer = opened
                    warm.cacheGazetteer(true, opened, emptyMap())
                    ownsGazetteer = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    unavailableMsg = "Expanded producer gazetteer unavailable: ${e.javaClass.simpleName}: ${e.message}"
                    notes += unavailableMsg
                }
            }

            val spatialFields = linkedMapOf<String, String>()
            val spatialStates = linkedMapOf<String, String>()
            val producerCandidates = mutableListOf<GraniteProducerCandidate>()
            val linkedWineCandidates = mutableListOf<GraniteLinkedWineCandidate>()

            try {
                val year = Year.now().value
                val modules = LabPostOcrModules.none.withIntegrated()
                val kb = warm.knowledgeBase()
                val fill = warm.fillEngine(kb)
                TextLabFormPreview.append(journal, fill, year, recordsOverride = records)
                val evidenceEngine = warm.evidenceExtractor(kb, true, true, modules)
                val hybrid = warm.anchorHybrid(kb)
                val repair = warm.producerRepair(kb)

                var lookupDeadline = 0L
                val lookupBudget = {
                    if (lookupDeadline == 0L) {
                        lookupDeadline = SystemClock.elapsedRealtime() + TextLabBudgets.GAZETTEER_LOOKUP_MS
                    }
                    if (SystemClock.elapsedRealtime() >= lookupDeadline) {
                        throw ProducerResolver.BudgetExceeded()
                    }
                }

                TextLabSpatialPreview.append(
                    journal = journal,
                    engine = evidenceEngine,
                    hybrid = hybrid,
                    repair = repair,
                    year = year,
                    producerIndex = gazetteer,
                    producerUnavailable = unavailableMsg,
                    checkProducerBudget = lookupBudget,
                    candidate = "granite_rag",
                    stagePrefix = "granite_",
                    fillEngine = fill,
                    labModules = modules,
                    policy = ProducerResolver.Policy.singleReading,
                    recordsOverride = records
                )

                val summary = journal.rows.lastOrNull { it["stage"] == "granite_evidence_summary" }
                    ?: journal.rows.lastOrNull { it["stage"] == "form_summary" }
                for (field in listOf("producer", "wine_name", "country", "region", "subregion", "vintage")) {
                    val state = summary?.get("${field}_state").orEmpty()
                    spatialStates[field] = state
                    spatialFields[field] = if (state == "RED") "" else summary?.get(field).orEmpty()
                }

                // Collect producer_gazetteer_candidate rows recorded by TextLabSpatialPreview
                journal.rows.filter { it["stage"] == "producer_gazetteer_candidate" }.forEach { row ->
                    val name = row["producer_display_name"].orEmpty()
                    if (name.isNotBlank()) {
                        producerCandidates += GraniteProducerCandidate(
                            id = row["producer_entity_id"].orEmpty(),
                            name = name,
                            country = "",
                            distance = row["edit_distance"]?.toIntOrNull() ?: 0,
                            matchedSpan = row["raw_text"].orEmpty(),
                            sources = row["lexical_support_sources"].orEmpty().ifBlank { "lwin" }
                        )
                    }
                }

                // Collect linked_wine_candidate rows recorded by TextLabSpatialPreview
                journal.rows.filter { it["stage"] == "linked_wine_candidate" }.forEach { row ->
                    val name = row["wine_display_name"].orEmpty()
                    if (name.isNotBlank()) {
                        linkedWineCandidates += GraniteLinkedWineCandidate(
                            id = row["wine_name_id"].orEmpty(),
                            name = name,
                            producerId = row["producer_entity_id"].orEmpty(),
                            lwin7 = row["lwin7"].orEmpty(),
                            distance = row["edit_distance"]?.toIntOrNull() ?: 0,
                            matchedSpan = row["raw_text"].orEmpty()
                        )
                    }
                }

                // Also perform direct FTS / exact retrieval on ProducerGazetteer + LinkedWines
                // when TextLabSpatialPreview did not already populate producer candidates
                if (gazetteer != null && producerCandidates.isEmpty()) {
                    val directDeadline = SystemClock.elapsedRealtime() + 150L
                    val directBudget = {
                        if (SystemClock.elapsedRealtime() >= directDeadline) {
                            throw ProducerResolver.BudgetExceeded()
                        }
                    }
                    runCatching {
                        supplementDirectGazetteerCandidates(
                            gazetteer = gazetteer,
                            records = records,
                            producerCandidates = producerCandidates,
                            linkedWineCandidates = linkedWineCandidates,
                            checkBudget = directBudget
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                notes += "Spatial/KB retrieval note: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                if (ownsGazetteer) {
                    runCatching { gazetteer?.close() }
                }
            }

            // 2. Match Appellation / Region / Country candidates from ReferenceWineCatalog + BundledOrigins
            val appellationCandidates = matchAppellationCatalogCandidates(records, spatialFields)

            // 3. Match Grape Varietals (BundledGrapes) and Wine Styles (BundledWineStyles)
            val matchedGrapes = matchBundledGrapes(records)
            val matchedStyles = matchBundledStyles(records)

            val dedupedProducers = producerCandidates
                .distinctBy { ProducerResolver.normalize(it.name) }
                .sortedWith(compareBy<GraniteProducerCandidate> { it.distance }.thenByDescending { "lwin" in it.sources })
                .take(10)

            val dedupedWines = linkedWineCandidates
                .distinctBy { ProducerResolver.normalize(it.name) }
                .sortedBy { it.distance }
                .take(10)

            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            GraniteDatabaseRagContext(
                enabled = true,
                elapsedMs = elapsed,
                producerCandidates = dedupedProducers,
                linkedWineCandidates = dedupedWines,
                appellationCandidates = appellationCandidates,
                matchedGrapes = matchedGrapes,
                matchedStyles = matchedStyles,
                spatialContenderFields = spatialFields,
                spatialContenderStates = spatialStates,
                gazetteerVersion = gazetteer?.version ?: "bundled-kb",
                diagnosticNotes = notes
            )
        }
    }

    private fun buildTokenRecords(
        mlKitBlocks: List<OcrBlock>,
        ppOcrV6Blocks: List<OcrBlock>,
        ablationMode: OcrAblationMode
    ): List<TokenRecord> {
        val records = mutableListOf<TokenRecord>()
        if (ablationMode.runsMlKit) {
            mlKitBlocks.forEach { block ->
                val text = TextNorm.scrub(block.text)
                if (text.isNotBlank()) {
                    records += TokenRecord(
                        textRaw = text,
                        textNorm = TextNorm.normalize(text),
                        score = block.confidence.coerceIn(0.05f, 1f),
                        bboxOrig = block.normBbox,
                        bboxRect = block.normBbox,
                        polyOrig = emptyList(),
                        engineId = "mlkit",
                        variantId = "original",
                        scoreIsEstimated = false
                    )
                }
            }
        }
        if (ablationMode.runsPpOcrV6) {
            ppOcrV6Blocks.forEach { block ->
                val text = TextNorm.scrub(block.text)
                if (text.isNotBlank()) {
                    records += TokenRecord(
                        textRaw = text,
                        textNorm = TextNorm.normalize(text),
                        score = block.confidence.coerceIn(0.05f, 1f),
                        bboxOrig = block.normBbox,
                        bboxRect = block.normBbox,
                        polyOrig = emptyList(),
                        engineId = "paddle_v6_medium",
                        variantId = "original",
                        scoreIsEstimated = false
                    )
                }
            }
        }
        return records
    }

    private fun supplementDirectGazetteerCandidates(
        gazetteer: ProducerGazetteer,
        records: List<TokenRecord>,
        producerCandidates: MutableList<GraniteProducerCandidate>,
        linkedWineCandidates: MutableList<GraniteLinkedWineCandidate>,
        checkBudget: () -> Unit
    ) {
        val proposals = TextLabSpatialPhrases.proposeUnconstrained(records)
        val candidateSpans = (records.map { it.textRaw } + proposals.filter { it.sources.size in 2..3 }.map { it.phrase.mergedText })
            .map { TextNorm.scrub(it) }
            .filter { it.length in 5..80 && !it.any(Char::isDigit) && '%' !in it }
            .distinctBy { ProducerResolver.normalize(it) }
            .take(24)

        val matchedProducerIds = producerCandidates.map { it.id }.filter(String::isNotBlank).toMutableSet()

        for (span in candidateSpans) {
            checkBudget()
            val q = ProducerResolver.normalize(span)
            if (q.length < 5) continue
            val hits = gazetteer.lookup(q, 32, checkBudget)
            for (entity in hits) {
                val dist = ProducerResolver.producerDistance(q, entity.key)
                if (dist <= if (q.length >= 16) 2 else 1) {
                    matchedProducerIds += entity.id
                    producerCandidates += GraniteProducerCandidate(
                        id = entity.id,
                        name = entity.name,
                        country = entity.countryGroup,
                        distance = dist,
                        matchedSpan = span,
                        sources = entity.support.sorted().joinToString("|").ifBlank { "lwin" }
                    )
                }
            }
        }

        val topProducerIds = matchedProducerIds.take(8).toSet()
        if (topProducerIds.isNotEmpty()) {
            for (span in candidateSpans.take(16)) {
                checkBudget()
                if (!LinkedWineResolver.eligible(span)) continue
                val q = ProducerResolver.normalize(span)
                val wines = gazetteer.lookupWines(q, topProducerIds, 32, checkBudget)
                for (wine in wines) {
                    val dist = ProducerResolver.distance(q, wine.key)
                    if (dist <= if (q.length >= 16) 2 else 1) {
                        linkedWineCandidates += GraniteLinkedWineCandidate(
                            id = wine.id,
                            name = wine.name,
                            producerId = wine.producerId,
                            lwin7 = wine.lwin7,
                            distance = dist,
                            matchedSpan = span
                        )
                    }
                }
            }
        }
    }

    private fun matchAppellationCatalogCandidates(
        records: List<TokenRecord>,
        spatialFields: Map<String, String>
    ): List<GraniteAppellationCandidate> {
        val candidates = mutableListOf<GraniteAppellationCandidate>()

        // Include the spatial contender's geography if non-blank
        val spCountry = spatialFields["country"].orEmpty()
        val spRegion = spatialFields["region"].orEmpty()
        val spSubregion = spatialFields["subregion"].orEmpty()
        if (spCountry.isNotBlank() || spRegion.isNotBlank() || spSubregion.isNotBlank()) {
            candidates += GraniteAppellationCandidate(
                appellationOrSubregion = spSubregion.ifBlank { spRegion },
                region = spRegion,
                country = spCountry,
                matchedSpan = listOf(spSubregion, spRegion, spCountry).filter(String::isNotBlank).joinToString(" / "),
                distance = 0,
                source = "SqliteWineKnowledgeBase+PlaceGazetteer"
            )
        }

        val rawLines = records.map { TextNorm.scrub(it.textRaw) }.distinct()
        val allSubPhrases = mutableSetOf<String>()
        for (line in rawLines) {
            allSubPhrases += line
            val words = line.split(Regex("\\s+")).filter(String::isNotBlank)
            for (winSize in 1..minOf(5, words.size)) {
                for (start in 0..(words.size - winSize)) {
                    val phrase = words.subList(start, start + winSize).joinToString(" ")
                    if (phrase.length >= 4) {
                        allSubPhrases += phrase
                    }
                }
            }
        }

        for (phrase in allSubPhrases) {
            val norm = ProducerResolver.normalize(phrase)
            if (norm.length < 4) continue
            val exactEntries = originsByNormalizedForm[norm]
            if (!exactEntries.isNullOrEmpty()) {
                val unambiguous = exactEntries.distinctBy {
                    Triple(
                        ProducerResolver.normalize(it.country),
                        ProducerResolver.normalize(it.region),
                        ProducerResolver.normalize(it.subregion)
                    )
                }
                if (unambiguous.size == 1) {
                    val match = unambiguous.single()
                    candidates += GraniteAppellationCandidate(
                        appellationOrSubregion = match.subregion.ifBlank { match.region },
                        region = match.region,
                        country = match.country,
                        matchedSpan = phrase,
                        distance = 0,
                        source = match.source
                    )
                }
            }
        }

        return candidates
            .distinctBy {
                Triple(
                    ProducerResolver.normalize(it.country),
                    ProducerResolver.normalize(it.region),
                    ProducerResolver.normalize(it.appellationOrSubregion)
                )
            }
            .sortedBy { it.distance }
            .take(10)
    }

    private fun matchBundledGrapes(records: List<TokenRecord>): List<String> {
        val fullNorm = " " + records.joinToString(" ") { ProducerResolver.normalize(it.textRaw) } + " "
        val matched = linkedSetOf<String>()
        for (term in BundledGrapes.terms) {
            val forms = listOf(term.name) + term.aliases
            for (form in forms) {
                val normForm = ProducerResolver.normalize(form)
                if (normForm.length >= 4 && fullNorm.contains(" $normForm ")) {
                    matched += term.name
                    break
                }
            }
        }
        return matched.toList()
    }

    private fun matchBundledStyles(records: List<TokenRecord>): List<String> {
        val fullNorm = " " + records.joinToString(" ") { ProducerResolver.normalize(it.textRaw) } + " "
        val matched = linkedSetOf<String>()
        for (term in BundledWineStyles.terms) {
            val forms = listOf(term.name) + term.aliases
            for (form in forms) {
                val normForm = ProducerResolver.normalize(form)
                if (normForm.length >= 4 && fullNorm.contains(" $normForm ")) {
                    matched += term.name
                    break
                }
            }
        }
        return matched.toList()
    }

    override fun close() {
        runCatching { warmSession?.close() }
        warmSession = null
    }
}
