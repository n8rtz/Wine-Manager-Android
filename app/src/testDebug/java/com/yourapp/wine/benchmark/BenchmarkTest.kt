package com.yourapp.wine.benchmark

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26],manifest=Config.NONE)
class BenchmarkTest {
    @Test fun csvTruthAndEscaping()=BenchmarkCsvChecks.csvCarriesPerImageTruthAndEscapesSpreadsheets()
    @Test fun durableHistory()=BenchmarkCsvChecks.historySurvivesNewInstancesAndExportsAllBatches()
    @Test fun boundedRecipes()=BenchmarkChecks.recipesAreBoundedAndGroundTruthDoesNotChooseThem()
    @Test fun explicitShearInverse()=BenchmarkChecks.shearRetainsCornersAndMapsBack()
    @Test fun truthIsOnlyEvaluation()=BenchmarkChecks.scoringDoesNotRewardConfidenceOrInventMissingText()
}
