package com.n8rtz.winetracker.scanner

/**
 * One scan's decision state: the OCR passes collected so far, which view to try next, and what the
 * scan finally suggests.
 *
 * All of the judgment lives here, free of Android and ML Kit, so the whole policy — when to escalate,
 * which view to escalate to, how the passes are merged, when a scan is safe to trust — is unit tested.
 * `ScannerManager` only feeds it text and bitmaps and returns the outcome.
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
        val labelled: Boolean,
        val quality: CaptureQuality?,
        val views: List<String>
    ) {
        /** One Logcat line that says whether this scan can be trusted, and what it read and how. */
        fun summary(barcode: String?, identity: WineBarcodeIdentity?): String =
            "Label scan passes=$passesRun views=${views.joinToString(",")} " +
                "barcode=${barcode ?: "none"} " +
                "identity=${identity?.let { "${it.maker} / ${it.name}" } ?: "none"} " +
                "agreement=${agreement?.let { "%.2f".format(it) } ?: "n/a"} " +
                "supported=$supportedLines/$totalLines " +
                "needsReview=${wine.needsReview} " +
                "quality=${quality?.summary() ?: "not assessed"} " +
                "contradictions=$contradictions"
    }

    private class Pass(val hypothesis: LabelOcrHypothesis, val mapping: LabelCropMapping?)

    private val parser = WineLabelParser()
    private val passes = mutableListOf<Pass>()
    private val viewLabels = mutableListOf<String>()
    private var reading: Reading? = null

    val passesRun: Int get() = passes.size

    /** Which view each pass read, in order, for the scan log. */
    val views: List<String> get() = viewLabels.toList()

    /**
     * Adds one pass's reading. [mapping] is how that pass's bitmap maps back to the photo, which the
     * field zoom needs to point at a line; passes without one can still be merged, they just cannot
     * be zoomed into.
     */
    fun addPass(
        hypothesis: LabelOcrHypothesis,
        mapping: LabelCropMapping? = null,
        viewLabel: String? = null
    ): Reading {
        passes += Pass(hypothesis, mapping)
        viewLabels += viewLabel ?: "pass${passes.size}"
        // A new pass changes the merged reading, so the cached one must not survive it.
        reading = null
        return current()
    }

    fun current(): Reading = reading ?: computeReading().also { reading = it }

    /** The view the next pass should read, or null when this scan is done escalating. */
    fun nextPassPlan(identityResolved: Boolean, quality: CaptureQuality? = null): LabelPassPlan? =
        LabelScanLadder.planFor(
            passesRun = passesRun,
            assessment = current().assessment,
            identityResolved = identityResolved,
            quality = quality,
            zoomRegion = zoomTarget()
        )

    fun finish(
        barcode: String?,
        identity: WineBarcodeIdentity?,
        quality: CaptureQuality? = null
    ): Outcome {
        val merged = WineIdentityMerge.merge(current().wine, identity, barcode)
        val needsReview = when {
            merged.contradictions.isNotEmpty() -> true
            // A photo too soft, dark or washed out to read leaves every field a guess, whatever else
            // was recognised, so it is always worth a second look.
            quality?.needsRetake == true -> true
            // The registry named the bottle; missing optional fields do not need a second look.
            identity != null -> false
            // A barcode was visible but could not be resolved, so the identity is still a guess.
            barcode != null -> true
            else -> current().assessment.needsReview
        }
        val wine = merged.wine.copy(
            agreement = current().consensus?.agreement,
            needsReview = needsReview,
            captureQuality = quality?.takeIf { it.needsRetake }?.verdict?.token
        )
        return Outcome(
            wine = wine,
            passesRun = passesRun,
            agreement = current().consensus?.agreement,
            supportedLines = current().consensus?.supportedLines ?: 0,
            totalLines = current().consensus?.totalLines ?: 0,
            contradictions = current().assessment.contradictions + merged.contradictions,
            labelled = current().consensus != null,
            quality = quality,
            views = views
        )
    }

    /**
     * Where the field worth re-reading sits on the photo, or null when no pass reported boxes for it.
     * The name is the line the user sees first, so it is the one a zoom should settle.
     */
    private fun zoomTarget(): LabelBox? {
        val views = passes
            .filter { pass -> pass.mapping != null && pass.hypothesis.lines.any { it.box != null } }
            .map { pass -> LabelFieldGeometry.MappedLines(pass.hypothesis.lines, requireNotNull(pass.mapping)) }
        if (views.isEmpty()) return null
        val wine = current().wine
        return LabelFieldGeometry.locate(wine.name ?: wine.maker, views)
    }

    private fun computeReading(): Reading {
        val hypotheses = passes.map(Pass::hypothesis)
        val consensus = LabelOcrConsensus.merge(hypotheses, parser, dictionary)
        val wine = consensus?.let { merged -> parser.parse(merged.text, dictionary) } ?: ScannedWine()
        val assessment = LabelScanAssessment.assess(
            consensus = consensus ?: LabelOcrConsensus.Result("", 0.0, 0, 0),
            perPassResults = hypotheses.filter { it.text.isNotBlank() }
                .map { pass -> parser.parse(pass.text, dictionary) },
            mergedResult = wine
        )
        return Reading(consensus, wine, assessment)
    }
}
