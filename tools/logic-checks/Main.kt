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
        "reported footer crop declines / omitted aligned title preserved / full-frame inverse" to com.yourapp.wine.LocalizationChecks::footerCropDeclinesInsteadOfLosingTheTitle,
        "rescue schedules from usable confidence not raw counts" to com.yourapp.wine.LocalizationChecks::rescueUsesUsableEvidenceRatherThanRawLineCount,
        "upscaled-crop inverse coordinate mapping" to com.yourapp.wine.LocalizationChecks::enlargedCropPreservesOriginalCoordinateRoundtrip).forEach { (name, test) ->
        test(); println("PASS: $name")
    }
    listOf("Kirkland logged producer/variety/geography assignments" to com.yourapp.wine.ReportedWineChecks::kirklandReportDoesNotSwapProducerAndVariety,
        "known word breaks only, never spelling guesses" to com.yourapp.wine.ReportedWineChecks::wordBreakRepairDoesNotGuessLettersOrUnknownNames,
        "cached read-only geography on existing snapshots" to com.yourapp.wine.ReportedWineChecks::geographicSupplementIsCachedReadOnlyAndWorksOnExistingSnapshots,
        "imported hierarchy and explicit conflicts preserved" to com.yourapp.wine.ReportedWineChecks::importedHierarchyWinsAndConflictsStayReviewable,
        "spacing equivalence versus genuine disagreement" to com.yourapp.wine.ReportedWineChecks::spacingIsNotDisagreementButDifferentLettersStillAre,
        "Blackstone cannot be fabricated from weak scores" to com.yourapp.wine.ReportedWineChecks::blackstoneWeakTextDoesNotBecomeAFieldJustBecauseANameIsKnown,
        "aspect inverse handles odd pixel dimensions" to com.yourapp.wine.ReportedWineChecks::aspectInverseHandlesOddDimensionsAndOriginalCoordinates,
        "ensemble callback projects variant into base label" to com.yourapp.wine.ReportedWineChecks::ensemblePublishesBaseLabelCoordinatesNotVariantPixels,
        "explicit printed style/appellation wine name" to com.yourapp.wine.ReportedWineChecks::printedAppellationStyleCanBeTheOnlyWineName).forEach { (name,test) ->
        test(); println("PASS: $name")
    }
    com.yourapp.wine.LabelNameChecks.awardDoesNotDisplacePrintedNameAndStyle()
    println("PASS: award badge excluded / adjacent name-style / producer-scoped correction")
    com.yourapp.wine.LabelNameChecks.fragmentsNeverInventMerlotAndPresentationUsesState()
    println("PASS: letter fragments rejected / no invented Merlot / state-as-country / manual edits preserved")
    com.yourapp.wine.DiagnosticChecks.passTreatmentsAreVisibleWithoutInventingAttempts()
    println("PASS: actual pass treatment descriptions / grayscale adapter / absent and reused passes")
    com.yourapp.wine.benchmark.BenchmarkChecks.recipesAreBoundedAndGroundTruthDoesNotChooseThem()
    com.yourapp.wine.benchmark.BenchmarkChecks.shearRetainsCornersAndMapsBack()
    com.yourapp.wine.benchmark.BenchmarkChecks.scoringDoesNotRewardConfidenceOrInventMissingText()
    println("PASS: 30 benchmark recipes / shear inverse and corner retention / ground-truth scoring isolation")
    com.yourapp.wine.textlab.TextCropChecks.geometry()
    println("PASS: manual text crops / reverse drag / bounds / pixel budget / original-coordinate inverse")
    com.yourapp.wine.benchmark.CellarWineChoiceChecks.searchAndExpectedFields()
    println("PASS: cellar search / distinct vintages / six-field truth snapshot / blank and NV remain unscored")
    com.yourapp.wine.benchmark.BenchmarkCsvChecks.csvCarriesPerImageTruthAndEscapesSpreadsheets()
    com.yourapp.wine.benchmark.BenchmarkCsvChecks.historySurvivesNewInstancesAndExportsAllBatches()
    println("PASS: per-image CSV truth / quoting and formula safety / durable multi-batch history")
    com.yourapp.wine.AdministrativeLabelChecks.rhoneAppellationIsNotAMakerAndLegalDebrisIsNotAName()
    com.yourapp.wine.AdministrativeLabelChecks.narrowAdministrativeRulesPreserveRealIdentities()
    com.yourapp.wine.AdministrativeLabelChecks.reserveHeadingIsNotAnUnsupportedProducer()
    println("PASS: CSV-derived appellation/admin roles / protected real brands / reserve name without invented maker")
    com.yourapp.wine.AppellationContextChecks.designationsFollowNamesWithoutBecomingProducers()
    com.yourapp.wine.AppellationContextChecks.namedOriginsAreStrongButGenericDesignationsAreNotCountries()
    com.yourapp.wine.AppellationContextChecks.spellingNeedsIndependentCompatibleEvidenceAndStaysYellow()
    println("PASS: suffix composition / appellation origins / no country from generic terms / conservative contextual repair")
    com.yourapp.wine.ReferenceCatalogChecks.catalogAndProvenance()
    com.yourapp.wine.ReferenceCatalogChecks.ambiguousAndMultiregionOrigins()
    com.yourapp.wine.ReferenceCatalogChecks.labelTermsNeverInventFacts()
    println("PASS: 1,000+ reference origins / provenance / multi-state and ambiguous names / non-inferential label terms")
    GeneratedFixtureChecks.run()
    com.yourapp.wine.EnsemblePolicyChecks.optionalEnginesNeverReadAnUnrectifiedScene()
    println("PASS: optional engines never receive localization/full-scene fallback pixels")
    deadlineChecks()
    bitmapOwnershipChecks()
    csvImportChecks()
    boundedImportChecks()

}
