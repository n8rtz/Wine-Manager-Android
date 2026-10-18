package com.yourapp.wine

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Geometry/scheduling regressions from the supplied device log, not an assertion of native ML Kit accuracy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LabelLocalizationTest {
    @Test fun weakBoxesMayLocateButNeverBecomeAcceptedFields() = LocalizationChecks.weakBoxesLocalizeWithoutBecomingAcceptedText()
    @Test fun sparseLabelBodyIsOnePaddedProposal() = LocalizationChecks.sparseBodyKeepsTheBrandAndLowerLabelInOneProposal()
    @Test fun weakOrInvalidBoxesCannotCreateAnUnanchoredLabel() = LocalizationChecks.geometryNeedsAnAnchorAndRejectsInvalidReadings()
    @Test fun separateBottleColumnsRemainSeparate() = LocalizationChecks.separateBottleColumnsDoNotJoin()
    @Test fun labelCropsUseBoundedUpscaling() = LocalizationChecks.rectifiedCropsUseThePixelBudgetWithoutExceedingIt()
    @Test fun paddingDoesNotLeaveTheFrameOrPromoteTinyFragments() = LocalizationChecks.paddedProposalsStayBoundedAndTinyFragmentsRemainFallbacks()
    @Test fun lowConfidenceReadingsTriggerTheBoundedRescuePass() = LocalizationChecks.rescueUsesUsableEvidenceRatherThanRawLineCount()
    @Test fun enlargedCropStillMapsBackToTheOriginalImage() = LocalizationChecks.enlargedCropPreservesOriginalCoordinateRoundtrip()
}
