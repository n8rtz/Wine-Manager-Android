package com.yourapp.wine

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Device-report text/geometry regressions; no emulator or native OCR accuracy claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ReportedWineTest {
    @Test fun kirklandFieldsUseTheActualSupportedEvidence() = ReportedWineChecks.kirklandReportDoesNotSwapProducerAndVariety()
    @Test fun exactWineWordBreaksCanBeRepairedButMissingLettersCannot() = ReportedWineChecks.wordBreakRepairDoesNotGuessLettersOrUnknownNames()
    @Test fun installedGazetteersReceiveReadOnlySupplements() = ReportedWineChecks.geographicSupplementIsCachedReadOnlyAndWorksOnExistingSnapshots()
    @Test fun importedHierarchyAndExplicitCountryConflictsArePreserved() = ReportedWineChecks.importedHierarchyWinsAndConflictsStayReviewable()
    @Test fun wordBreaksAreNotFalseDisagreement() = ReportedWineChecks.spacingIsNotDisagreementButDifferentLettersStillAre()
    @Test fun knownNamesCannotPromoteBlackstonesWeakReads() = ReportedWineChecks.blackstoneWeakTextDoesNotBecomeAFieldJustBecauseANameIsKnown()
    @Test fun oddHeightAspectRescueHasAnExactInverse() = ReportedWineChecks.aspectInverseHandlesOddDimensionsAndOriginalCoordinates()
    @Test fun callbackPolygonsUseBaseLabelPixels() = ReportedWineChecks.ensemblePublishesBaseLabelCoordinatesNotVariantPixels()
    @Test fun anExplicitPrintedWineStyleCanNameTheWine() = ReportedWineChecks.printedAppellationStyleCanBeTheOnlyWineName()
}
