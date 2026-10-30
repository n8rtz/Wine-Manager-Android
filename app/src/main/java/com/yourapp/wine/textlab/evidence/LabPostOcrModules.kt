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
 * Selectable flags independently enable post-OCR experiments against Cached OCR and frozen Prod
 * baselines. Retired flags remain representable only for historical signatures/regression coverage.
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
    val appellationWineFallback: Boolean = false,
    val appellationClassificationPrecedence: Boolean = false,
    val boundedWineTitlePairs: Boolean = false,
    val splitProducerSpans: Boolean = false,
    val stackedWineTitles: Boolean = false,
    val vintageRecovery: Boolean = false,
    val stackedAppellations: Boolean = false,
    val appellationTitleArbitration: Boolean = false,
    val producerHeadedWineGuard: Boolean = false,
    val identityTextQualityGate: Boolean = false,
    val classificationWineName: Boolean = false,
    val appellationSubregionDemotion: Boolean = false,
    val horizontalPhrasePairs: Boolean = false,
    val deepestAppellationFallback: Boolean = false,
    val styleAppellationWine: Boolean = false,
    val producerCompletionMatch: Boolean = false,
    val producerAnchorFallback: Boolean = false,
    val subEstateWineLinking: Boolean = false,
    val stackedAppellationWineBoost: Boolean = false,
    val grapeTitlePreference: Boolean = false,
    val designationPairPreference: Boolean = false,
    val guardedTitleTriples: Boolean = false,
    val appellationTitleArbitrationRelaxed: Boolean = false,
    val geographyAppellationWine: Boolean = false,
    val decisiveFieldSelection: Boolean = false,
    val producerNearMatchUnprotected: Boolean = false,
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
        APPELLATION_TITLE_ARBITRATION("appellation_title_arbitration", "appellation-title-arbitration", "Appellation vs title arbitration", "A clean non-producer title line above the appellation outranks appellation-as-wine"),
        PRODUCER_HEADED_WINE_GUARD("producer_headed_wine_guard", "producer-headed-wine-guard", "Producer-headed wine guard", "Suppress wine stacks whose head line is itself a producer candidate"),
        IDENTITY_TEXT_QUALITY_GATE("identity_text_quality_gate", "identity-text-quality", "Identity text-quality gate", "Reject long legal/back-label text as producer or wine identity"),
        CLASSIFICATION_WINE_NAME("classification_wine_name", "classification-wine-name", "Classification as wine name", "A standalone Premier Grand Cru Classe phrase may be the wine name"),
        APPELLATION_SUBREGION_DEMOTION("appellation_subregion_demotion", "appellation-subregion-demotion", "Appellation subregion demotion", "Do not fill subregion with the appellation already used as the wine name"),
        HORIZONTAL_PHRASE_PAIRS("horizontal_phrase_pairs", "horizontal-phrase-pairs", "Horizontal-phrase title pairs", "Bounded wine-title pairs whose upper or lower line is a horizontal phrase"),
        DEEPEST_APPELLATION_FALLBACK("deepest_appellation_fallback", "deepest-appellation-fallback", "Deepest printed appellation fallback", "Among competing printed appellations, fill wine with the deepest compatible one"),
        STYLE_APPELLATION_WINE("style_appellation_wine", "style-appellation-wine", "Style appellation wine names", "Two-line style appellations (Rosso di Montalcino) may fill a blank wine name"),
        PRODUCER_COMPLETION_MATCH("producer_completion_match", "producer-completion-match", "Producer completion match", "Complete a truncated producer from a unique longer observed producer span"),
        PRODUCER_ANCHOR_FALLBACK("producer_anchor_fallback", "producer-anchor-fallback", "Producer anchor fallback (3b)", "When the baseline anchor producer is blank, fall back to the evidence-selected producer"),
        SUB_ESTATE_WINE_LINKING("sub_estate_wine_linking", "sub-estate-wine-linking", "Sub-estate wine linking (3c)", "Link wines held under parenthetical LWIN sub-estate producers (Mouton Cadet)"),
        STACKED_APPELLATION_WINE_BOOST("stacked_appellation_wine_boost", "stacked-appellation-wine-boost", "Stacked appellation wine boost", "The composed two-line appellation outranks its single-line parts as wine name"),
        GRAPE_TITLE_PREFERENCE("grape_title_preference", "grape-title-preference", "Grape title preference", "An exact grape-variety title line outranks other plain title lines for the wine slot"),
        DESIGNATION_PAIR_PREFERENCE("designation_pair_preference", "designation-pair-preference", "Designation pair preference", "A pair forming a known designation (Blanc de Blancs Brut) outranks producer-headed pairs"),
        GUARDED_TITLE_TRIPLES("guarded_title_triples", "guarded-title-triples", "Guarded title triples", "A non-producer line above a bounded title pair completes a three-line wine name"),
        APPELLATION_TITLE_ARBITRATION_RELAXED("appellation_title_arbitration_relaxed", "appellation-title-arbitration-relaxed", "Title arbitration (relaxed)", "Title-over-appellation boost without the engine-count / vertical-order conditions"),
        GEOGRAPHY_APPELLATION_WINE("geography_appellation_wine", "geography-appellation-wine", "Geography appellation as wine", "Fill a blank wine name from the resolved appellation subregion"),
        DECISIVE_FIELD_SELECTION("decisive_field_selection", "decisive-field-selection", "Decisive field selection", "On solver abstention, keep the best finalist value instead of blanking the field"),
        PRODUCER_NEAR_MATCH_UNPROTECTED("producer_near_match_unprotected", "producer-near-match-unprotected", "Producer near-match without observation protection", "Retry the d<=1 catalog canonicalization when only exact-observation protection vetoed it"),
        STACKED_WINE_TITLES(
            key = "stacked_wine_titles",
            shortName = "stacked-wine-titles",
            uiTitle = "Stacked wine titles (4b)",
            uiDetail = "Tier-line + varietal pairs, vintage-skipping pairs and bounded three-line wine titles",
        ),
        SPLIT_PRODUCER_SPANS(
            key = "split_producer_spans",
            shortName = "split-producer-spans",
            uiTitle = "Split producer spans (4c)",
            uiDetail = "Vertically stacked estate/particle producer lines joined into one producer span",
        ),
        BOUNDED_WINE_TITLE_PAIRS(
            key = "bounded_wine_title_pairs",
            shortName = "bounded-wine-title-pairs",
            uiTitle = "Bounded wine title pairs (1a)",
            uiDetail = "Two-line cuvee/title + varietal-or-appellation hypotheses under hard semantic boundaries",
        ),
        APPELLATION_CLASSIFICATION_PRECEDENCE(
            key = "appellation_classification_precedence",
            shortName = "appellation-classification-precedence",
            uiTitle = "Appellation classification precedence",
            uiDetail = "Printed <appellation> <classification> outranks designation-linked title joins",
        ),
        APPELLATION_WINE_FALLBACK(
            key = "appellation_wine_fallback",
            shortName = "appellation-wine-fallback",
            uiTitle = "Appellation as wine name fallback",
            uiDetail = "Fill a blank wine name from the one printed appellation compatible with the resolved origin",
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
        ModuleId.APPELLATION_WINE_FALLBACK -> appellationWineFallback
        ModuleId.APPELLATION_CLASSIFICATION_PRECEDENCE -> appellationClassificationPrecedence
        ModuleId.BOUNDED_WINE_TITLE_PAIRS -> boundedWineTitlePairs
        ModuleId.APPELLATION_TITLE_ARBITRATION -> appellationTitleArbitration
        ModuleId.PRODUCER_HEADED_WINE_GUARD -> producerHeadedWineGuard
        ModuleId.IDENTITY_TEXT_QUALITY_GATE -> identityTextQualityGate
        ModuleId.CLASSIFICATION_WINE_NAME -> classificationWineName
        ModuleId.APPELLATION_SUBREGION_DEMOTION -> appellationSubregionDemotion
        ModuleId.HORIZONTAL_PHRASE_PAIRS -> horizontalPhrasePairs
        ModuleId.DEEPEST_APPELLATION_FALLBACK -> deepestAppellationFallback
        ModuleId.STYLE_APPELLATION_WINE -> styleAppellationWine
        ModuleId.PRODUCER_COMPLETION_MATCH -> producerCompletionMatch
        ModuleId.PRODUCER_ANCHOR_FALLBACK -> producerAnchorFallback
        ModuleId.SUB_ESTATE_WINE_LINKING -> subEstateWineLinking
        ModuleId.STACKED_APPELLATION_WINE_BOOST -> stackedAppellationWineBoost
        ModuleId.GRAPE_TITLE_PREFERENCE -> grapeTitlePreference
        ModuleId.DESIGNATION_PAIR_PREFERENCE -> designationPairPreference
        ModuleId.GUARDED_TITLE_TRIPLES -> guardedTitleTriples
        ModuleId.APPELLATION_TITLE_ARBITRATION_RELAXED -> appellationTitleArbitrationRelaxed
        ModuleId.GEOGRAPHY_APPELLATION_WINE -> geographyAppellationWine
        ModuleId.DECISIVE_FIELD_SELECTION -> decisiveFieldSelection
        ModuleId.PRODUCER_NEAR_MATCH_UNPROTECTED -> producerNearMatchUnprotected
        ModuleId.SPLIT_PRODUCER_SPANS -> splitProducerSpans
        ModuleId.STACKED_WINE_TITLES -> stackedWineTitles
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
        ModuleId.APPELLATION_WINE_FALLBACK -> copy(appellationWineFallback = enabled)
        ModuleId.APPELLATION_CLASSIFICATION_PRECEDENCE -> copy(appellationClassificationPrecedence = enabled)
        ModuleId.BOUNDED_WINE_TITLE_PAIRS -> copy(boundedWineTitlePairs = enabled)
        ModuleId.APPELLATION_TITLE_ARBITRATION -> copy(appellationTitleArbitration = enabled)
        ModuleId.PRODUCER_HEADED_WINE_GUARD -> copy(producerHeadedWineGuard = enabled)
        ModuleId.IDENTITY_TEXT_QUALITY_GATE -> copy(identityTextQualityGate = enabled)
        ModuleId.CLASSIFICATION_WINE_NAME -> copy(classificationWineName = enabled)
        ModuleId.APPELLATION_SUBREGION_DEMOTION -> copy(appellationSubregionDemotion = enabled)
        ModuleId.HORIZONTAL_PHRASE_PAIRS -> copy(horizontalPhrasePairs = enabled)
        ModuleId.DEEPEST_APPELLATION_FALLBACK -> copy(deepestAppellationFallback = enabled)
        ModuleId.STYLE_APPELLATION_WINE -> copy(styleAppellationWine = enabled)
        ModuleId.PRODUCER_COMPLETION_MATCH -> copy(producerCompletionMatch = enabled)
        ModuleId.PRODUCER_ANCHOR_FALLBACK -> copy(producerAnchorFallback = enabled)
        ModuleId.SUB_ESTATE_WINE_LINKING -> copy(subEstateWineLinking = enabled)
        ModuleId.STACKED_APPELLATION_WINE_BOOST -> copy(stackedAppellationWineBoost = enabled)
        ModuleId.GRAPE_TITLE_PREFERENCE -> copy(grapeTitlePreference = enabled)
        ModuleId.DESIGNATION_PAIR_PREFERENCE -> copy(designationPairPreference = enabled)
        ModuleId.GUARDED_TITLE_TRIPLES -> copy(guardedTitleTriples = enabled)
        ModuleId.APPELLATION_TITLE_ARBITRATION_RELAXED -> copy(appellationTitleArbitrationRelaxed = enabled)
        ModuleId.GEOGRAPHY_APPELLATION_WINE -> copy(geographyAppellationWine = enabled)
        ModuleId.DECISIVE_FIELD_SELECTION -> copy(decisiveFieldSelection = enabled)
        ModuleId.PRODUCER_NEAR_MATCH_UNPROTECTED -> copy(producerNearMatchUnprotected = enabled)
        ModuleId.SPLIT_PRODUCER_SPANS -> copy(splitProducerSpans = enabled)
        ModuleId.STACKED_WINE_TITLES -> copy(stackedWineTitles = enabled)
        ModuleId.VINTAGE_RECOVERY -> copy(vintageRecovery = enabled)
        ModuleId.STACKED_APPELLATIONS -> copy(stackedAppellations = enabled)
        ModuleId.FINAL_PRODUCER_NEAR_MATCH -> copy(finalProducerNearMatch = enabled)
        ModuleId.PRODUCER_ROLE_AND_PARTICLE -> copy(producerRoleAndParticle = enabled)
        ModuleId.SPATIAL_SEMANTIC_GUARDS -> copy(spatialSemanticGuards = enabled)
        ModuleId.VERTICAL_WINE_TITLE_PAIRS -> copy(verticalWineTitlePairs = enabled)
    }

    /** Retired flags remain decodable for historical results, never enabled by new UI/run selection. */
    fun withoutRetiredModules(): LabPostOcrModules = copy(wineNameSpans=false, verticalWineTitlePairs=false)

    /**
     * The Prod v5.0 checkpoint set is integrated into the Lab line (Lab v7): always on, no longer
     * user-toggleable. Applied at every Lab run boundary so a stored or restored
     * module selection can never switch these off.
     */
    fun withIntegrated(): LabPostOcrModules = copy(
        exactProducerProtection=true, roleAwareJoins=true, blankSubregionFuzzy=true,
        appellationWineFallback=true, appellationClassificationPrecedence=true,
        vintageRecovery=true, stackedAppellations=true, finalProducerNearMatch=true,
        producerRoleAndParticle=true, spatialSemanticGuards=true,
        boundedWineTitlePairs=true, stackedWineTitles=true, splitProducerSpans=true,
        appellationTitleArbitration=true, styleAppellationWine=true, producerCompletionMatch=true,
        stackedAppellationWineBoost=true, geographyAppellationWine=true)

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
        val retiredModuleIds = setOf(ModuleId.WINE_NAME_SPANS, ModuleId.VERTICAL_WINE_TITLE_PAIRS)
        /** Module ids integrated into the Lab line (always on, no checkbox); the Prod v6.0 checkpoint is this set frozen. */
        val integratedModuleIds = setOf(
            ModuleId.EXACT_PRODUCER_PROTECTION, ModuleId.ROLE_AWARE_JOINS,
            ModuleId.BLANK_SUBREGION_FUZZY, ModuleId.APPELLATION_WINE_FALLBACK,
            ModuleId.APPELLATION_CLASSIFICATION_PRECEDENCE, ModuleId.VINTAGE_RECOVERY,
            ModuleId.STACKED_APPELLATIONS, ModuleId.FINAL_PRODUCER_NEAR_MATCH,
            ModuleId.PRODUCER_ROLE_AND_PARTICLE, ModuleId.SPATIAL_SEMANTIC_GUARDS,
            // Item 1a (host-verified +4/-0 on the Lab v6 floor: #170, #177, #184, #186).
            ModuleId.BOUNDED_WINE_TITLE_PAIRS,
            // Items 4b/4c (host-verified +4/-0 each, +6 complete combined:
            // #50, #152, #154, #183 wine; #92, #136, #164 producer; #137 wine).
            ModuleId.STACKED_WINE_TITLES, ModuleId.SPLIT_PRODUCER_SPANS,
            // Gap A (host-verified +2/-0 on the 72/121 floor: #116, #171 wine).
            ModuleId.APPELLATION_TITLE_ARBITRATION,
            // Gap E style appellations (host-verified +1/-0: #190 wine).
            ModuleId.STYLE_APPELLATION_WINE,
            // Gap D producer completion (host-verified +1/-0: #54 producer).
            ModuleId.PRODUCER_COMPLETION_MATCH,
            // Lab v7 (host-verified +2/-0 each on the 77/121 floor):
            // stacked appellation wine boost (#103, #163) and geography
            // appellation as wine (#92, #163).
            ModuleId.STACKED_APPELLATION_WINE_BOOST, ModuleId.GEOGRAPHY_APPELLATION_WINE)
        val selectableModuleIds = ModuleId.entries.filterNot { it in retiredModuleIds || it in integratedModuleIds }
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
         * are now retired from new selections and experiment plans. The two modules that postdate
         * every measured build (blank-subregion raw-OCR fallback, stacked appellations) stay off.
         */
        /**
         * Prod v5.0 known-improvement checkpoint (2026-10-29): every module below was
         * measured net-positive on the 124-label corpus against the prior configuration
         * and verified on device (62/122 complete, 113/122 at most one error, zero
         * losses vs the Lab v5 phone baseline). Prod v5.0 runs exactly this set; it is
         * frozen and is not user-configurable. Lab v6 started from this checkpoint (historical).
         */
        // Frozen: exactly the ten checkpoint modules. Do NOT derive this from
        // withIntegrated() -- Lab integration grows over time (Item 1a added
        // BOUNDED_WINE_TITLE_PAIRS) and Prod v5.0 must never change.
        val prodV5Checkpoint = none.copy(
            exactProducerProtection=true, roleAwareJoins=true, blankSubregionFuzzy=true,
            appellationWineFallback=true, appellationClassificationPrecedence=true,
            vintageRecovery=true, stackedAppellations=true, finalProducerNearMatch=true,
            producerRoleAndParticle=true, spatialSemanticGuards=true)
        /**
         * Prod v6.0 known-improvement checkpoint (2026-10-29): the Lab v6 integrated
         * set, host-measured at 77/121 and phone-verified exactly on build
         * `75bfb53f0a93`. Frozen: exactly these sixteen modules; never derive this
         * from withIntegrated() -- Lab integration grows over time and Prod v6.0
         * must never change, like every past Prod version.
         */
        val prodV6Checkpoint = none.copy(
            exactProducerProtection=true, roleAwareJoins=true, blankSubregionFuzzy=true,
            appellationWineFallback=true, appellationClassificationPrecedence=true,
            vintageRecovery=true, stackedAppellations=true, finalProducerNearMatch=true,
            producerRoleAndParticle=true, spatialSemanticGuards=true,
            boundedWineTitlePairs=true, stackedWineTitles=true, splitProducerSpans=true,
            appellationTitleArbitration=true, styleAppellationWine=true, producerCompletionMatch=true,
        stackedAppellationWineBoost=true, geographyAppellationWine=true)
        val bestBuild = prodV5Checkpoint
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
            wineNameSpans = false,

            blankSubregionFuzzy = true,
            vintageRecovery = true,
            stackedAppellations = true,
            finalProducerNearMatch = true,
            producerRoleAndParticle = true,
            spatialSemanticGuards = true,
            verticalWineTitlePairs = false,
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
