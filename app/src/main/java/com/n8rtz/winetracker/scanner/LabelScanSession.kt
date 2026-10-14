package com.n8rtz.winetracker.scanner

/**
 * One scan's decision state: the OCR passes collected so far, which view to try next, and what the
 * scan finally suggests.
 *
 * All of the judgment lives here, free of Android and ML Kit, so the whole policy — when to escalate,
 * how the passes are merged, when a scan is safe to trust — is unit tested. `ScannerManager` only
 * feeds it text and bitmaps and returns the outcome.
 */
internal class LabelScanSession(private val dictionary: WineReferenceDictionary) {
    data class Reading(
        val consensus: LabelOcrConsensus.Result?,
        val wine: ScannedWine,
        val assessment: LabelScanAssessment.Report
    )

    data class Outcome(
        val wine: ScannedWine,
        val passesRun: Int,
        val agreement: Double?,
        val supportedLines: Int,
        val totalLines: Int,
        val contradictions: List<String>,
        val labelled: Boolean
    ) {
        /** One Logcat line that says whether this scan can be trusted and why not. */
        fun summary(barcode: String?, identity: WineBarcodeIdentity?): String =
            "Label scan passes=$passesRun barcode=${barcode ?: "none"} " +
                "identity=${identity?.let { "${it.maker} / ${it.name}" } ?: "none"} " +
                "agreement=${agreement?.let { "%.2f".format(it) } ?: "n/a"} " +
                "supported=$supportedLines/$totalLines " +
                "needsReview=${wine.needsReview} contradictions=$contradictions"
    }

    private val parser = WineLabelParser()
    private val passes = mutableListOf<LabelOcrHypothesis>()
    private var reading: Reading? = null

    val passesRun: Int get() = passes.size

    fun addPass(hypothesis: LabelOcrHypothesis): Reading {
        passes += hypothesis
        // A new pass changes the merged reading, so the cached one must not survive it.
        reading = null
        return current()
    }

    fun current(): Reading = reading ?: computeReading().also { reading = it }

    /** The crop recipe for the next pass, or null when this scan is done escalating. */
    fun nextRecipe(identityResolved: Boolean): LabelCropRecipe? =
        if (LabelScanLadder.shouldEscalate(passesRun, current().assessment, identityResolved)) {
            LabelScanLadder.recipeFor(passesRun)
        } else {
            null
        }

    fun finish(barcode: String?, identity: WineBarcodeIdentity?): Outcome {
        val merged = WineIdentityMerge.merge(current().wine, identity, barcode)
        val needsReview = when {
            merged.contradictions.isNotEmpty() -> true
            // The registry named the bottle; missing optional fields do not need a second look.
            identity != null -> false
            // A barcode was visible but could not be resolved, so the identity is still a guess.
            barcode != null -> true
            else -> current().assessment.needsReview
        }
        val wine = merged.wine.copy(
            agreement = current().consensus?.agreement,
            needsReview = needsReview
        )
        return Outcome(
            wine = wine,
            passesRun = passesRun,
            agreement = current().consensus?.agreement,
            supportedLines = current().consensus?.supportedLines ?: 0,
            totalLines = current().consensus?.totalLines ?: 0,
            contradictions = current().assessment.contradictions + merged.contradictions,
            labelled = current().consensus != null
        )
    }

    private fun computeReading(): Reading {
        val consensus = LabelOcrConsensus.merge(passes, parser, dictionary)
        val wine = consensus?.let { merged -> parser.parse(merged.text, dictionary) } ?: ScannedWine()
        val assessment = LabelScanAssessment.assess(
            consensus = consensus ?: LabelOcrConsensus.Result("", 0.0, 0, 0),
            perPassResults = passes.filter { it.text.isNotBlank() }
                .map { pass -> parser.parse(pass.text, dictionary) },
            mergedResult = wine
        )
        return Reading(consensus, wine, assessment)
    }
}
