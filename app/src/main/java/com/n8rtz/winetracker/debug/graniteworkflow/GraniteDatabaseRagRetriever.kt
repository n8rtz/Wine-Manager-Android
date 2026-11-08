package com.n8rtz.winetracker.debug.graniteworkflow

import android.content.Context
import android.os.SystemClock
import androidx.sqlite.SQLiteConnection
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
    val sources: String,
    val corroboratedWines: List<String> = emptyList(),
    val countryCorroborated: Boolean = false
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
    val matchedSpan: String,
    val producerName: String = ""
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
            return "(RAG off)"
        }
        return buildString {
            appendLine("Producers:")
            if (producerCandidates.isEmpty()) {
                appendLine("- (none)")
            } else {
                producerCandidates.take(5).forEach { p ->
                    val countryPart = if (p.country.isNotBlank()) ", ${p.country}" else ""
                    val countryTag = if (p.countryCorroborated) " [COUNTRY_MATCHES_LABEL]" else ""
                    val wineTag = if (p.corroboratedWines.isNotEmpty()) {
                        " [CORROBORATED_BY_LINKED_WINE: ${p.corroboratedWines.take(2).joinToString(", ") { "\"$it\"" }}]"
                    } else {
                        ""
                    }
                    appendLine("- \"${p.name}\" (d=${p.distance}$countryPart)$countryTag$wineTag")
                }
            }

            appendLine("Linked Wines:")
            if (linkedWineCandidates.isEmpty()) {
                appendLine("- (none)")
            } else {
                linkedWineCandidates.take(5).forEach { w ->
                    val prodPart = if (w.producerName.isNotBlank()) ", producer=\"${w.producerName}\"" else ""
                    appendLine("- \"${w.name}\" (d=${w.distance}$prodPart)")
                }
            }

            appendLine("Appellations:")
            if (appellationCandidates.isEmpty()) {
                appendLine("- (none)")
            } else {
                appellationCandidates.take(4).forEach { a ->
                    val sub = a.appellationOrSubregion.ifBlank { "(none)" }
                    val reg = a.region.ifBlank { "(none)" }
                    val ctry = a.country.ifBlank { "(none)" }
                    appendLine("- Subregion/Appellation=\"$sub\" -> Region=\"$reg\", Country=\"$ctry\" (d=${a.distance})")
                }
            }

            if (matchedGrapes.isNotEmpty()) {
                appendLine("Grapes: ${matchedGrapes.take(5).joinToString(", ")}")
            }
            if (matchedStyles.isNotEmpty()) {
                appendLine("Styles: ${matchedStyles.take(4).joinToString(", ")}")
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

    /**
     * Pre-warms SQLite knowledge bases, Aho-Corasick catalog tries, and the LWIN Producer/Wine
     * gazetteers in the background so per-image [retrieve] calls run in ~30–120 ms instead of
     * paying a ~20s cold index-initialization cost on the first scan.
     */
    suspend fun warmUp(context: Context) = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            runCatching {
                val app = context.applicationContext
                val warm = warmSession ?: TextLabWineExtraction.WarmSession(app).also {
                    warmSession = it
                }
                val modules = LabPostOcrModules.none.withIntegrated()
                val kb = warm.knowledgeBase()
                warm.fillEngine(kb)
                warm.evidenceExtractor(kb, true, true, modules)
                warm.anchorHybrid(kb)
                warm.producerRepair(kb)
                if (warm.cachedGazetteer(true) == null) {
                    val opened = ProducerGazetteer.openExpanded(app)
                    warm.cacheGazetteer(true, opened, emptyMap())
                }
                // Touch lazy origin catalog map once
                originsByNormalizedForm.size
            }
        }
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

                // Perform general FTS5 trigram + contiguous word-subspan retrieval on ProducerGazetteer + LinkedWines
                // so partial, clipped, or noisy OCR spans surface candidate hypotheses for Granite to reason over.
                if (gazetteer != null) {
                    val directDeadline = SystemClock.elapsedRealtime() + 180L
                    val directBudget = {
                        if (SystemClock.elapsedRealtime() >= directDeadline) {
                            throw ProducerResolver.BudgetExceeded()
                        }
                    }
                    runCatching {
                        supplementDirectGazetteerCandidates(
                            gazetteer = gazetteer,
                            records = records,
                            spatialFields = spatialFields,
                            spatialStates = spatialStates,
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

            // 4. Cross-reference relational corroboration between Producer Candidates, Linked Wines, and Geography
            val labelCountries = buildSet {
                appellationCandidates.mapNotNullTo(this) { it.country.takeIf(String::isNotBlank)?.let(ProducerResolver::normalize) }
                spatialFields["country"]?.takeIf(String::isNotBlank)?.let { add(ProducerResolver.normalize(it)) }
            }

            val producerNameById = producerCandidates
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .associate { it.id to it.name }

            val enrichedWines = linkedWineCandidates
                .map { w ->
                    if (w.producerName.isNotBlank()) w
                    else w.copy(producerName = producerNameById[w.producerId].orEmpty())
                }
                .distinctBy { "${ProducerResolver.normalize(it.producerName)}::${ProducerResolver.normalize(it.name)}" }
                .sortedBy { it.distance }
                .take(10)

            val winesByProducerId = enrichedWines
                .filter { it.producerId.isNotBlank() }
                .groupBy({ it.producerId }, { it.name })

            val dedupedProducers = producerCandidates
                .distinctBy { ProducerResolver.normalize(it.name) }
                .map { p ->
                    val corrWines = winesByProducerId[p.id].orEmpty().distinct()
                    val ctryNorm = ProducerResolver.normalize(p.country)
                    val ctryMatch = ctryNorm.isNotBlank() && labelCountries.any { lc ->
                        lc == ctryNorm ||
                            (lc == "usa" && ctryNorm == "united states") ||
                            (lc == "united states" && ctryNorm == "usa")
                    }
                    p.copy(
                        corroboratedWines = corrWines,
                        countryCorroborated = ctryMatch
                    )
                }
                .sortedWith(
                    compareBy<GraniteProducerCandidate> { it.distance }
                        .thenByDescending { it.corroboratedWines.isNotEmpty() }
                        .thenByDescending { it.countryCorroborated }
                        .thenBy { ProducerResolver.producerDistance(ProducerResolver.normalize(it.matchedSpan), ProducerResolver.normalize(it.name)) }
                        .thenByDescending { "lwin" in it.sources }
                )
                .take(10)

            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            GraniteDatabaseRagContext(
                enabled = true,
                elapsedMs = elapsed,
                producerCandidates = dedupedProducers,
                linkedWineCandidates = enrichedWines,
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

    /**
     * General contiguous word-window edit distance between an OCR query span [queryKey] and a
     * candidate entity key [entityKey].
     *
     * When OCR misses leading or trailing words on a curved bottle or stylized typography (in any
     * language), the captured span [queryKey] aligns with a contiguous word sub-window of [entityKey].
     * Checking contiguous word windows of length `>= max(6, queryKey.length - 1)` measures how closely
     * the captured text matches the corresponding sub-phrase of the candidate without relying on any
     * language-specific word or article lists.
     */
    internal fun minSubspanEditDistance(queryKey: String, entityKey: String): Int {
        var best = ProducerResolver.producerDistance(queryKey, entityKey)
        if (best == 0) return 0
        val entityWords = entityKey.split(' ').filter(String::isNotEmpty)
        if (entityWords.size > 1 && queryKey.length >= 6) {
            val minSubLen = maxOf(6, queryKey.length - 1)
            for (winSize in 1 until entityWords.size) {
                for (start in 0..(entityWords.size - winSize)) {
                    val sub = entityWords.subList(start, start + winSize).joinToString(" ")
                    if (sub.length >= minSubLen) {
                        val subDist = ProducerResolver.producerDistance(queryKey, sub)
                        if (subDist < best) {
                            best = subDist
                        }
                    }
                }
            }
        }
        return best
    }

    private val pureAbvVolumeOrYearRegex = Regex(
        "(?i)^\\s*(\\d{4}|\\d+(?:[.,]\\d+)?\\s*(?:%|ml|cl|l|fl\\.?\\s*oz|alc|vol)\\b.*)\\s*$"
    )

    private fun supplementDirectGazetteerCandidates(
        gazetteer: ProducerGazetteer,
        records: List<TokenRecord>,
        spatialFields: Map<String, String>,
        spatialStates: Map<String, String>,
        producerCandidates: MutableList<GraniteProducerCandidate>,
        linkedWineCandidates: MutableList<GraniteLinkedWineCandidate>,
        checkBudget: () -> Unit
    ) {
        val proposals = TextLabSpatialPhrases.proposeUnconstrained(records)
        val spProducerRaw = spatialFields["producer"].orEmpty().takeIf {
            it.isNotBlank() && spatialStates["producer"] != "GREEN"
        }
        val rawBase = buildList {
            if (spProducerRaw != null) add(spProducerRaw)
            addAll(records.map { it.textRaw })
            addAll(proposals.filter { it.sources.size in 2..3 }.map { it.phrase.mergedText })
        }
        val rawPool = buildList {
            for (item in rawBase) {
                add(item)
                val yearStripped = item.replace(Regex("\\b(19[4-9]\\d|20[0-2]\\d)\\b"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (yearStripped.isNotEmpty() && yearStripped != item) {
                    add(yearStripped)
                }
            }
        }
        // Allow 3+ character spans and alphanumeric producer/cuvée names (e.g. "Krug", "Gaja", "19 Crimes", "689 Cellars"),
        // excluding only pure vintage years or ABV/volume technical strings.
        val candidateSpans = rawPool
            .map { TextNorm.scrub(it) }
            .filter { it.length in 3..80 && '%' !in it && !pureAbvVolumeOrYearRegex.matches(it) }
            .distinctBy { ProducerResolver.normalize(it) }
            .take(28)

        val matchedProducerIds = producerCandidates.map { it.id }.filter(String::isNotBlank).toMutableSet()
        val producerNameById = producerCandidates
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .associate { it.id to it.name }
            .toMutableMap()

        for (span in candidateSpans) {
            checkBudget()
            val q = ProducerResolver.normalize(span)
            if (q.length < 3) continue
            val hits = gazetteer.lookup(q, 48, checkBudget)
            val maxAllowedDist = when {
                q.length >= 12 -> 3
                q.length >= 7 -> 2
                q.length >= 5 -> 1
                else -> 0
            }
            hits.forEachIndexed { rank, entity ->
                val dist = minSubspanEditDistance(q, entity.key)
                // Also retain top-3 FTS5 trigram hits up to distance 3 for spans >= 6 chars so Granite
                // can evaluate stylized/script OCR readings with 2-3 character errors.
                val trigramTopHypothesis = rank < 3 && q.length >= 6 && dist <= 3
                if (dist <= maxAllowedDist || trigramTopHypothesis) {
                    matchedProducerIds += entity.id
                    producerNameById[entity.id] = entity.name
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

        // Forward Producer -> Linked Wine lookup
        val topProducerIds = matchedProducerIds.take(8).toSet()
        if (topProducerIds.isNotEmpty()) {
            for (span in candidateSpans.take(18)) {
                checkBudget()
                if (!LinkedWineResolver.eligible(span)) continue
                val q = ProducerResolver.normalize(span)
                val wines = gazetteer.lookupWines(q, topProducerIds, 32, checkBudget)
                for (wine in wines) {
                    val dist = minSubspanEditDistance(q, wine.key)
                    if (dist <= if (q.length >= 10) 2 else 1) {
                        linkedWineCandidates += GraniteLinkedWineCandidate(
                            id = wine.id,
                            name = wine.name,
                            producerId = wine.producerId,
                            lwin7 = wine.lwin7,
                            distance = dist,
                            matchedSpan = span,
                            producerName = producerNameById[wine.producerId].orEmpty()
                        )
                    }
                }
            }
        }

        // Reverse Wine/Cuvée -> Producer lookup: when a distinctive cuvée/monopole/estate name is
        // readable on the label even if the producer text was heavily damaged or omitted, query
        // wines.sqlite directly and surface the linked parent producer into producerCandidates.
        runCatching {
            reverseLookupWinesToProducers(
                gazetteer = gazetteer,
                candidateSpans = candidateSpans,
                matchedProducerIds = matchedProducerIds,
                producerNameById = producerNameById,
                producerCandidates = producerCandidates,
                linkedWineCandidates = linkedWineCandidates,
                checkBudget = checkBudget
            )
        }
    }

    private fun reverseLookupWinesToProducers(
        gazetteer: ProducerGazetteer,
        candidateSpans: List<String>,
        matchedProducerIds: MutableSet<String>,
        producerNameById: MutableMap<String, String>,
        producerCandidates: MutableList<GraniteProducerCandidate>,
        linkedWineCandidates: MutableList<GraniteLinkedWineCandidate>,
        checkBudget: () -> Unit
    ) {
        val winesField = ProducerGazetteer::class.java.getDeclaredField("wines").apply { isAccessible = true }
        val dbField = ProducerGazetteer::class.java.getDeclaredField("db").apply { isAccessible = true }
        val winesConn = winesField.get(gazetteer) as? SQLiteConnection ?: return
        val prodConn = dbField.get(gazetteer) as? SQLiteConnection ?: return

        val grapeNorms = BundledGrapes.terms
            .flatMap { listOf(it.name) + it.aliases }
            .map { ProducerResolver.normalize(it) }
            .toSet()

        for (span in candidateSpans.take(12)) {
            checkBudget()
            if (!LinkedWineResolver.eligible(span)) continue
            val q = ProducerResolver.normalize(span)
            // Only reverse-lookup distinctive cuvée/wine spans (skip bare grape names or short generic words)
            if (q.length < 6 || q in grapeNorms || q in originsByNormalizedForm) continue

            val matchedWines = mutableListOf<Pair<LinkedWineResolver.Wine, Int>>()
            val exactSql = "SELECT n.id, n.name, n.key, p.producer_id, l.lwin7 " +
                "FROM wine_names n " +
                "JOIN wine_links l ON l.wine_id = n.id " +
                "JOIN wine_producers p ON p.id = l.producer_id " +
                "WHERE n.key = ? ORDER BY n.id LIMIT 6"
            winesConn.prepare(exactSql).use { stmt ->
                stmt.bindText(1, q)
                while (stmt.step()) {
                    checkBudget()
                    val wine = LinkedWineResolver.Wine(
                        stmt.getLong(0).toString(),
                        TextNorm.scrub(stmt.getText(1)),
                        ProducerResolver.normalize(stmt.getText(2)),
                        stmt.getText(3),
                        stmt.getText(4)
                    )
                    matchedWines += wine to 0
                }
            }

            if (q.length >= 7) {
                val trigrams = q.windowed(3).filter { it.none(Char::isWhitespace) }.distinct().take(12)
                if (trigrams.size >= 3) {
                    val ftsQuery = trigrams.joinToString(" AND ") { "\"$it\"" }
                    val ftsSql = "SELECT n.id, n.name, n.key, p.producer_id, l.lwin7 " +
                        "FROM wine_fts JOIN wine_names n ON wine_fts.rowid = n.id " +
                        "JOIN wine_links l ON l.wine_id = n.id " +
                        "JOIN wine_producers p ON p.id = l.producer_id " +
                        "WHERE wine_fts MATCH ? ORDER BY bm25(wine_fts), n.id LIMIT 8"
                    winesConn.prepare(ftsSql).use { stmt ->
                        stmt.bindText(1, ftsQuery)
                        while (stmt.step()) {
                            checkBudget()
                            val wineKey = ProducerResolver.normalize(stmt.getText(2))
                            val dist = minSubspanEditDistance(q, wineKey)
                            if (dist <= 1) {
                                val wine = LinkedWineResolver.Wine(
                                    stmt.getLong(0).toString(),
                                    TextNorm.scrub(stmt.getText(1)),
                                    wineKey,
                                    stmt.getText(3),
                                    stmt.getText(4)
                                )
                                if (matchedWines.none { it.first.id == wine.id && it.first.producerId == wine.producerId }) {
                                    matchedWines += wine to dist
                                }
                            }
                        }
                    }
                }
            }

            // Only use reverse lookup when the cuvée name maps to a small, specific set of producers (<= 4)
            val distinctProdIds = matchedWines.map { it.first.producerId }.distinct()
            if (distinctProdIds.size in 1..4) {
                for ((wine, dist) in matchedWines) {
                    var prodName = producerNameById[wine.producerId].orEmpty()
                    if (prodName.isBlank()) {
                        prodConn.prepare("SELECT id, name, COALESCE(country_group, '') FROM producers WHERE id = ? LIMIT 1").use { pStmt ->
                            pStmt.bindText(1, wine.producerId)
                            if (pStmt.step()) {
                                val pId = pStmt.getText(0)
                                prodName = TextNorm.scrub(pStmt.getText(1))
                                val pCountry = TextNorm.scrub(pStmt.getText(2))
                                producerNameById[pId] = prodName
                                if (pId !in matchedProducerIds && prodName.isNotBlank()) {
                                    matchedProducerIds += pId
                                    producerCandidates += GraniteProducerCandidate(
                                        id = pId,
                                        name = prodName,
                                        country = pCountry,
                                        distance = dist,
                                        matchedSpan = "$span (linked cuvée)",
                                        sources = "lwin_linked_wine"
                                    )
                                }
                            }
                        }
                    }
                    linkedWineCandidates += GraniteLinkedWineCandidate(
                        id = wine.id,
                        name = wine.name,
                        producerId = wine.producerId,
                        lwin7 = wine.lwin7,
                        distance = dist,
                        matchedSpan = span,
                        producerName = prodName
                    )
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
