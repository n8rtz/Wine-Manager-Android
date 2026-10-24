package com.yourapp.wine

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ScanDiagnosticsTest {
    @Test fun passImageTreatmentsAreExplicit() = DiagnosticChecks.passTreatmentsAreVisibleWithoutInventingAttempts()
    @Test fun reportExplainsBuildDeviceAndFilteredEvidence() = DiagnosticChecks.reportExplainsPartialEvidenceAndBuild()
    @Test fun recorderIsBoundedAndOmitsExceptionMessages() = DiagnosticChecks.traceHasBoundedSamplesAndNoExceptionMessages()
    @Test fun exportIsBoundedAndUsesStableDecimalSeparators() = DiagnosticChecks.reportsAreBoundedAndLocaleIndependent()
    @Test fun cameraAndSelectedPhotoFailuresKeepTheirSource() = DiagnosticChecks.inputStatesAndFailuresRetainProvenance()
    @Test fun photoByteCopyIsBoundedAndChecksCancellation() = DiagnosticChecks.photoBytesAreStreamedBoundedAndCancellable()
}
