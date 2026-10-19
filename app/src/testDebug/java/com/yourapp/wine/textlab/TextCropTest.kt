package com.yourapp.wine.textlab

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26],manifest=Config.NONE)
class TextCropTest {
    @Test fun detectorProbabilityBoundaries()=PaddleProbabilityChecks.run()
    @Test fun candidateSelectionAndCsv()=TextLabJournalChecks.run()
    @Test fun paddleCtcAndInputContract()=PaddleCtcChecks.run()
    @Test fun cropCoordinatesAndBudget()=TextCropChecks.geometry()
}
