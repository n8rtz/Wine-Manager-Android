package com.yourapp.wine.textlab.evidence

/**
 * Mix-and-match post-OCR processing modules for Lab v5 benchmark isolation.
 *
 * When all flags are `false` ([none]), Lab reproduces the pre-improvement baseline post-OCR behavior
 * (the state of `main` at `ce9225f`) - that is the clean control, not the best measured build. Five
 * of the seven flags describe behavior the last best measured Lab v5 build (`636dda970fa4`) already
 * shipped with, so [bestBuild] switches exactly those five on and leaves the two genuinely new
 * modules (blank-subregion raw-OCR fallback, stacked two-line appellations) off.
 *
 * Each flag independently enables one post-OCR improvement so its isolated and combined effects can
 * be measured scientifically against Cached OCR and frozen Prod baselines.
 */
internal data class LabPostOcrModules(
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
        ModuleId.BLANK_SUBREGION_FUZZY -> blankSubregionFuzzy
        ModuleId.VINTAGE_RECOVERY -> vintageRecovery
        ModuleId.STACKED_APPELLATIONS -> stackedAppellations
        ModuleId.FINAL_PRODUCER_NEAR_MATCH -> finalProducerNearMatch
        ModuleId.PRODUCER_ROLE_AND_PARTICLE -> producerRoleAndParticle
        ModuleId.SPATIAL_SEMANTIC_GUARDS -> spatialSemanticGuards
        ModuleId.VERTICAL_WINE_TITLE_PAIRS -> verticalWineTitlePairs
    }

    fun withModule(id: ModuleId, enabled: Boolean): LabPostOcrModules = when (id) {
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
         * The module set the last best measured Lab v5 run shipped with (`636dda970fa4`): producer
         * anchor/evidence spans and spatial join guards, `de`-particle/role rules with the 256-name
         * producer shortlist, the final producer near-match pass, OCR vintage recovery (including
         * split crest halves and the hybrid fill), and bounded vertical wine-title pairs. The two
         * flags left off here postdate that build and have never been part of a best run.
         */
        val bestBuild = LabPostOcrModules(
            blankSubregionFuzzy = false,
            vintageRecovery = true,
            stackedAppellations = false,
            finalProducerNearMatch = true,
            producerRoleAndParticle = true,
            spatialSemanticGuards = true,
            verticalWineTitlePairs = true,
        )
        val all = LabPostOcrModules(
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
