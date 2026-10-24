package com.yourapp.wine.benchmark

import org.junit.Test

class CellarWineChoiceTest {
    @Test fun cellarSearchAndTruthMapping() = CellarWineChoiceChecks.searchAndExpectedFields()
}
