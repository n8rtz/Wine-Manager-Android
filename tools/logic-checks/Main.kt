import com.yourapp.wine.LogicChecks

fun main() {
    val matrix = com.yourapp.wine.geom.Matrix3(listOf(1.1, .1, -10.0, -.05, 1.2, 12.0, .0001, .0002, 1.0))
    val point = android.graphics.PointF(240f, 160f)
    val roundtrip = matrix.inverse().map(matrix.map(point))
    check(kotlin.math.abs(point.x - roundtrip.x) < .001f && kotlin.math.abs(point.y - roundtrip.y) < .001f)
    println("PASS: projective inverse mapping")
    listOf("NFKD" to LogicChecks::normalization, "union-find" to LogicChecks::merger,
        "rectified lines" to LogicChecks::lineGrouping, "vintage" to LogicChecks::vintage,
        "hierarchy conflict" to LogicChecks::hierarchy, "nested geography specificity" to LogicChecks::nestedGeographyUsesSpecificPlaceWithoutFalseConflict, "fill states" to LogicChecks::fillStates, "form policy" to LogicChecks::formPolicy, "rejected retake clears stale auto-fill" to LogicChecks::rejectedRetakeClearsOnlyAutomaticSuggestions, "extraction edges" to LogicChecks::extractionEdges, "index failure preserves partial" to LogicChecks::indexFailurePreservesPartial,
        "estimated vs measured confidence" to LogicChecks::estimatedScoresNeverPromoteWeakMeasurements, "zero/invalid confidence cannot create support" to LogicChecks::zeroAndInvalidConfidenceNeverManufactureSupport, "capture rejection enters six-field RED review" to LogicChecks::rejectedCapturesEnterRedReviewWithShutterTiming).forEach { (name, test) ->
        test(); println("PASS: $name")
    }
    listOf("producer cue beats prominent varietal/cuvee" to LogicChecks::producerCuesBeatProminentVarietalAndCuveeText,
        "wrapped producer marker keeps its nearby brand" to LogicChecks::wrappedProducerMarkersKeepTheirNearbyBrand,
        "FTS shortlist never renames unknown winery" to LogicChecks::ftsShortlistDoesNotRenameAnUnknownWinery,
        "wine descriptions and bare markers are not producers" to LogicChecks::wineDescriptionsAndBareMarkersAreNotProducers,
        "prominent brand is not replaced by production footer" to LogicChecks::prominentBrandIsNotReplacedWithItsProductionFooter,
        "location-only reading never invents a wine identity" to LogicChecks::locationOnlyReadingDoesNotInventWineIdentity,
        "producer marker requires nearby aligned text" to LogicChecks::producerMarkersDoNotBorrowDistantOrUnrelatedText).forEach { (name, test) ->
        test(); println("PASS: $name")
    }
    listOf("copyable diagnostics explain build/device/filtered evidence" to com.yourapp.wine.DiagnosticChecks::reportExplainsPartialEvidenceAndBuild,
        "bounded trace samples without exception messages" to com.yourapp.wine.DiagnosticChecks::traceHasBoundedSamplesAndNoExceptionMessages,
        "bounded locale-independent diagnostics" to com.yourapp.wine.DiagnosticChecks::reportsAreBoundedAndLocaleIndependent,
        "input provenance and busy/cancel states" to com.yourapp.wine.DiagnosticChecks::inputStatesAndFailuresRetainProvenance,
        "streamed photo bytes / byte cap / cancellation" to com.yourapp.wine.DiagnosticChecks::photoBytesAreStreamedBoundedAndCancellable).forEach { (name, test) ->
        test(); println("PASS: $name")
    }
    listOf("weak probe geometry never lowers field confidence" to com.yourapp.wine.LocalizationChecks::weakBoxesLocalizeWithoutBecomingAcceptedText,
        "logged label body stays in one padded proposal" to com.yourapp.wine.LocalizationChecks::sparseBodyKeepsTheBrandAndLowerLabelInOneProposal,
        "localization requires a strong anchor and valid geometry" to com.yourapp.wine.LocalizationChecks::geometryNeedsAnAnchorAndRejectsInvalidReadings,
        "separate text columns remain separate" to com.yourapp.wine.LocalizationChecks::separateBottleColumnsDoNotJoin,
        "bounded rectified-label upscaling" to com.yourapp.wine.LocalizationChecks::rectifiedCropsUseThePixelBudgetWithoutExceedingIt,
        "padding stays in frame and declines tiny fragments" to com.yourapp.wine.LocalizationChecks::paddedProposalsStayBoundedAndTinyFragmentsRemainFallbacks,
        "rescue schedules from usable confidence not raw counts" to com.yourapp.wine.LocalizationChecks::rescueUsesUsableEvidenceRatherThanRawLineCount,
        "upscaled-crop inverse coordinate mapping" to com.yourapp.wine.LocalizationChecks::enlargedCropPreservesOriginalCoordinateRoundtrip).forEach { (name, test) ->
        test(); println("PASS: $name")
    }
    GeneratedFixtureChecks.run()
    com.yourapp.wine.EnsemblePolicyChecks.optionalEnginesNeverReadAnUnrectifiedScene()
    println("PASS: optional engines never receive localization/full-scene fallback pixels")
    deadlineChecks()
    bitmapOwnershipChecks()
    csvImportChecks()
    boundedImportChecks()

}
