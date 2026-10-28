package com.yourapp.wine.textlab.evidence

/**
 * Mix-and-match post-OCR processing modules for Lab v5 benchmark isolation.
 *
 * When all flags are `false` ([none]), Lab reproduces the pre-improvement baseline post-OCR behavior
 * (the state of `main` at `ce9225f`) - that is the clean control, not the best measured build. Four
 * of the seven flags are measured net wins on the 124-label corpus; [bestBuild] switches exactly
 * those four on and leaves vertical wine-title pairs plus the two modules that postdate every
 * measured build (blank-subregion raw-OCR fallback, stacked two-line appellations) off.
 *
 * Each flag independently enables one post-OCR improvement so its isolated and combined effects can
 * be measured scientifically against Cached OCR and frozen Prod baselines.
 */
internal data class LabPostOcrModules(
    val candidateIsolation: Boolean = false,
    val exactProducerProtection: Boolean = false,
    val administrativeFiltering: Boolean = false,
    val roleAwareJoins: Boolean = false,
    val broaderDiscovery: Boolean = false,
    val geographySpans: Boolean = false,
    val producerSpans: Boolean = false,
    val grapeSpans: Boolean = false,
    val classificationSpans: Boolean = false,
    val vintageSpans: Boolean = false,
    val wineNameSpans: Boolean = false,
    val blankSubregionFuzzy: Boolean = false,
    val vintageRecovery: Boolean = false,
    val stackedAppellations: Boolean = false,
    val finalProducerNearMatch: Boolean = false,
    val producerRoleAndParticle: Boolean = false,
    val spatialSemanticGuards: Boolean = false,
    val verticalWineTitlePairs: Boolean = false,
) {
    enum class ModuleId(
        val key: String,
        val shortName: String,
        val uiTitle: String,
        val uiDetail: String,
    ) {
        CANDIDATE_ISOLATION("candidate_isolation", "candidate-isolation", "Candidate isolation", "Physical-observation layout; field-scoped rescues and context"),
        EXACT_PRODUCER_PROTECTION("exact_producer_protection", "exact-producer-protection", "Protect exact producer evidence", "Do not replace exact observed identities; abstain on fuzzy ties"),
        ADMINISTRATIVE_FILTERING("administrative_filtering", "administrative-filtering", "Administrative role filtering", "Reject legal/operator text as identity, including extracted spans"),
        ROLE_AWARE_JOINS("role_aware_joins", "role-aware-joins", "Role-aware joins", "Require compatible title, grape, producer or catalog roles"),
        BROADER_DISCOVERY("broader_discovery", "broader-discovery", "Bounded compound discovery", "Catalog-backed compound appellations and numeric title candidates"),
        GEOGRAPHY_SPANS("geography_spans", "geography-spans", "Geography span discovery", "Exact and bounded fuzzy geography spans before field selection"),
        PRODUCER_SPANS("producer_spans", "producer-spans", "Producer span discovery", "Catalog producer spans with existing role and ambiguity guards"),
        GRAPE_SPANS("grape_spans", "grape-spans", "Grape span discovery", "Exact vocabulary spans, preserving compound grape names"),
        CLASSIFICATION_SPANS("classification_spans", "classification-spans", "Classification span discovery", "Printed designation and classification spans"),
        VINTAGE_SPANS("vintage_spans", "vintage-spans", "Vintage span discovery", "Isolate plausible year spans before vintage resolution"),
        WINE_NAME_SPANS("wine_name_spans", "wine-name-spans", "Wine / cuvée span discovery", "Bounded open-ended title spans; experimental, may over-segment"),
        BLANK_SUBREGION_FUZZY(
            key = "blank_subregion_fuzzy",
            shortName = "subregion-fuzzy",
            uiTitle = "Blank subregion fuzzy search",
            uiDetail = "Raw-OCR multi-word/line appellation fallback when subregion is blank",
        ),
        VINTAGE_RECOVERY(
            key = "vintage_recovery",
            shortName = "vintage-recovery",
            uiTitle = "Vintage recovery (OCR year)",
            uiDetail = "I9xx/2Oxx year repair, .45f year rescue, split crest year & hybrid fill",
        ),
        STACKED_APPELLATIONS(
            key = "stacked_appellations",
            shortName = "stacked-appellations",
            uiTitle = "Stacked 2-line appellations",
            uiDetail = "Combine vertically stacked appellation + classification lines",
        ),
        FINAL_PRODUCER_NEAR_MATCH(
            key = "final_producer_near_match",
            shortName = "final-near-match",
            uiTitle = "Final producer near-match (d<=1)",
            uiDetail = "Canonicalize final producer to a unique 1-edit catalog entry",
        ),
        PRODUCER_ROLE_AND_PARTICLE(
            key = "producer_role_particle",
            shortName = "producer-role-de",
            uiTitle = "Producer role & 'de' rules",
            uiDetail = "'de' particle omission, role/grape penalties & 256-candidate shortlist",
        ),
        SPATIAL_SEMANTIC_GUARDS(
            key = "spatial_semantic_guards",
            shortName = "spatial-guards",
            uiTitle = "Spatial join & anchor guards",
            uiDetail = "Semantic join guards, producer anchor/evidence spans & pass checks",
        ),
        VERTICAL_WINE_TITLE_PAIRS(
            key = "vertical_wine_title_pairs",
            shortName = "vertical-title-pairs",
            uiTitle = "Vertical wine-title pairs",
            uiDetail = "Propose bounded 2-line vertical wine-title phrases",
        ),
    }

    fun isEnabled(id: ModuleId): Boolean = when (id) {
        ModuleId.CANDIDATE_ISOLATION -> candidateIsolation
        ModuleId.EXACT_PRODUCER_PROTECTION -> exactProducerProtection
        ModuleId.ADMINISTRATIVE_FILTERING -> administrativeFiltering
        ModuleId.ROLE_AWARE_JOINS -> roleAwareJoins
        ModuleId.BROADER_DISCOVERY -> broaderDiscovery
        ModuleId.GEOGRAPHY_SPANS -> geographySpans
        ModuleId.PRODUCER_SPANS -> producerSpans
        ModuleId.GRAPE_SPANS -> grapeSpans
        ModuleId.CLASSIFICATION_SPANS -> classificationSpans
        ModuleId.VINTAGE_SPANS -> vintageSpans
        ModuleId.WINE_NAME_SPANS -> wineNameSpans
        ModuleId.BLANK_SUBREGION_FUZZY -> blankSubregionFuzzy
        ModuleId.VINTAGE_RECOVERY -> vintageRecovery
        ModuleId.STACKED_APPELLATIONS -> stackedAppellations
        ModuleId.FINAL_PRODUCER_NEAR_MATCH -> finalProducerNearMatch
        ModuleId.PRODUCER_ROLE_AND_PARTICLE -> producerRoleAndParticle
        ModuleId.SPATIAL_SEMANTIC_GUARDS -> spatialSemanticGuards
        ModuleId.VERTICAL_WINE_TITLE_PAIRS -> verticalWineTitlePairs
    }

    fun withModule(id: ModuleId, enabled: Boolean): LabPostOcrModules = when (id) {
        ModuleId.CANDIDATE_ISOLATION -> copy(candidateIsolation = enabled)
        ModuleId.EXACT_PRODUCER_PROTECTION -> copy(exactProducerProtection = enabled)
        ModuleId.ADMINISTRATIVE_FILTERING -> copy(administrativeFiltering = enabled)
        ModuleId.ROLE_AWARE_JOINS -> copy(roleAwareJoins = enabled)
        ModuleId.BROADER_DISCOVERY -> copy(broaderDiscovery = enabled)
        ModuleId.GEOGRAPHY_SPANS -> copy(geographySpans = enabled)
        ModuleId.PRODUCER_SPANS -> copy(producerSpans = enabled)
        ModuleId.GRAPE_SPANS -> copy(grapeSpans = enabled)
        ModuleId.CLASSIFICATION_SPANS -> copy(classificationSpans = enabled)
        ModuleId.VINTAGE_SPANS -> copy(vintageSpans = enabled)
        ModuleId.WINE_NAME_SPANS -> copy(wineNameSpans = enabled)
        ModuleId.BLANK_SUBREGION_FUZZY -> copy(blankSubregionFuzzy = enabled)
        ModuleId.VINTAGE_RECOVERY -> copy(vintageRecovery = enabled)
        ModuleId.STACKED_APPELLATIONS -> copy(stackedAppellations = enabled)
        ModuleId.FINAL_PRODUCER_NEAR_MATCH -> copy(finalProducerNearMatch = enabled)
        ModuleId.PRODUCER_ROLE_AND_PARTICLE -> copy(producerRoleAndParticle = enabled)
        ModuleId.SPATIAL_SEMANTIC_GUARDS -> copy(spatialSemanticGuards = enabled)
        ModuleId.VERTICAL_WINE_TITLE_PAIRS -> copy(verticalWineTitlePairs = enabled)
    }

    fun enabledModules(): List<ModuleId> = ModuleId.entries.filter(::isEnabled)

    fun enabledKeys(): List<String> = enabledModules().map { it.key }

    fun activeKeys(): List<String> = enabledKeys()

    fun signatureKey(): String {
        val keys = enabledKeys()
        return if (keys.isEmpty()) "best-v5" else keys.joinToString("+")
    }

    fun summaryLabel(): String {
        val active = enabledModules()
        return when {
            active.isEmpty() -> "Baseline (pre-improvement)"
            this == bestBuild -> "Best build (${active.size} modules)"
            else -> active.joinToString(" + ") { it.shortName }
        }
    }

    companion object {
        val none = LabPostOcrModules()
        /**
         * The best combination measured on the 124-label corpus, module by module:
         *
         * | run | modules | producer | wine name | vintage | complete |
         * | --- | --- | ---: | ---: | ---: | ---: |
         * | `636dda970fa4` | five (below + wine-title pairs) | 92 | 63 | 122 | 46/122 |
         * | `a24a9683968b` | none | 91 | 74 | 119 | 48/122 |
         * | `187a01ae7732` | five | 97 | 63 | 123 | 46/122 |
         *
         * Producer anchor/evidence spans and spatial join guards, the `de`-particle/role rules with
         * the 256-name producer shortlist, the final producer near-match pass and OCR vintage
         * recovery are net wins (`+6` producer, `+4` vintage). Vertical wine-title pairs are the
         * opposite: they won one label (`#142`) and lost eleven, because an unrecognised first line
         * (an OCR'd estate name such as `Trancette`, `BOUSQUET`, `VANGELYS`) is paired with the
         * varietal below it and offered as the wine name at a rank nothing else can beat
         * (`Trancette Cabernet Sauvignon` instead of `Cabernet Sauvignon`). They stay off here and
         * remain selectable on their own for a future isolated run. The two modules that postdate
         * every measured build (blank-subregion raw-OCR fallback, stacked appellations) stay off.
         */
        val bestBuild = LabPostOcrModules(
            blankSubregionFuzzy = false,
            vintageRecovery = true,
            stackedAppellations = false,
            finalProducerNearMatch = true,
            producerRoleAndParticle = true,
            spatialSemanticGuards = true,
            verticalWineTitlePairs = false,
        )
        val all = LabPostOcrModules(
            candidateIsolation = true,
            exactProducerProtection = true,
            administrativeFiltering = true,
            roleAwareJoins = true,
            broaderDiscovery = true,

            geographySpans = true,
            producerSpans = true,
            grapeSpans = true,
            classificationSpans = true,
            vintageSpans = true,
            wineNameSpans = true,

            blankSubregionFuzzy = true,
            vintageRecovery = true,
            stackedAppellations = true,
            finalProducerNearMatch = true,
            producerRoleAndParticle = true,
            spatialSemanticGuards = true,
            verticalWineTitlePairs = true,
        )
        /** Defaults used by direct standalone unit/device tests when not running a benchmark ablation pass. */
        val unitTestDefault = LabPostOcrModules(
            blankSubregionFuzzy = true,
            vintageRecovery = true,
            stackedAppellations = true,
            finalProducerNearMatch = true,
            producerRoleAndParticle = true,
            spatialSemanticGuards = true,
            verticalWineTitlePairs = false,
        )

        fun fromKeys(keys: Iterable<String>): LabPostOcrModules {
            val set = keys.map(String::trim).filter(String::isNotEmpty).toSet()
            if (set.isEmpty() || "best-v5" in set || "baseline_best_v5" in set || "none" in set) return none
            var out = none
            for (id in ModuleId.entries) {
                if (id.key in set) out = out.withModule(id, true)
            }
            return out
        }
    }
}
