package com.yourapp.wine

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ScannerLogicTest {
    @Test fun nfkdAndDiacritics() = LogicChecks.normalization()
    @Test fun unionFindIndependentVotesAndSingletons() = LogicChecks.merger()
    @Test fun rectifiedLines() = LogicChecks.lineGrouping()
    @Test fun vintageRegexAndFoundationYear() = LogicChecks.vintage()
    @Test fun hierarchyConflictIsYellowNotCorrected() = LogicChecks.hierarchy()
    @Test fun inlineWrappedFuzzyAndMetadataFields() = LogicChecks.extractionEdges()
    @Test fun producerCueBeatsLargeVarietalAndCuvee() = LogicChecks.producerCuesBeatProminentVarietalAndCuveeText()
    @Test fun wrappedProducerMarkerUsesNearbyBrand() = LogicChecks.wrappedProducerMarkersKeepTheirNearbyBrand()
    @Test fun ftsShortlistDoesNotInventProducerIdentity() = LogicChecks.ftsShortlistDoesNotRenameAnUnknownWinery()
    @Test fun varietiesAndGenericMarkersDoNotBecomeProducer() = LogicChecks.wineDescriptionsAndBareMarkersAreNotProducers()
    @Test fun producerFooterDoesNotReplaceProminentBrand() = LogicChecks.prominentBrandIsNotReplacedWithItsProductionFooter()
    @Test fun wrappedProducerNeedsNearbyAlignedEvidence() = LogicChecks.producerMarkersDoNotBorrowDistantOrUnrelatedText()
    @Test fun locationOnlyReadingKeepsWineIdentityBlank() = LogicChecks.locationOnlyReadingDoesNotInventWineIdentity()
    @Test fun indexFailureStillReviewsPartial() = LogicChecks.indexFailurePreservesPartial()
    @Test fun editedGreenAndOneTapYellow() = LogicChecks.formPolicy()
    @Test fun nestedGeographyAndDifferentBranchConflicts() = LogicChecks.nestedGeographyUsesSpecificPlaceWithoutFalseConflict()
    @Test fun rejectedRetakeDoesNotSaveStaleAutomaticFields() = LogicChecks.rejectedRetakeClearsOnlyAutomaticSuggestions()
    @Test fun invalidAndZeroScoresNeverBecomeIndependentSupport() = LogicChecks.zeroAndInvalidConfidenceNeverManufactureSupport()
    @Test fun failedCaptureHasSixRedStatesAndShutterTiming() = LogicChecks.rejectedCapturesEnterRedReviewWithShutterTiming()
    @Test fun estimatedScoresCannotCreateGreen() = LogicChecks.estimatedScoresNeverPromoteWeakMeasurements()
    @Test fun allFillStatesAndUnknownBoutique() = LogicChecks.fillStates()
}
