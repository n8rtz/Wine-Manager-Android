package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelOcrHypothesisSelectorTest {
    private val parser = WineLabelParser()
    private val dictionary = WineReferenceDictionary.builtIn()

    @Test
    fun keepsTheFullFrameWhenTheEnhancedCropOffersNoImprovement() {
        val fullFrame = LabelOcrHypothesis(
            text = "Bodegas Muga\nRioja\n2019",
            isFullFrame = true
        )
        val enhancedCrop = fullFrame.copy(isFullFrame = false)

        val selected = LabelOcrHypothesisSelector.choose(fullFrame, enhancedCrop, parser, dictionary)

        assertEquals(fullFrame, selected)
    }

    @Test
    fun choosesAClearerWineRelevantEnhancedCropOverWeakFullFrameText() {
        val fullFrame = LabelOcrHypothesis(
            text = "Bodegas Muqa\nRloja\n2O19",
            isFullFrame = true
        )
        val enhancedCrop = LabelOcrHypothesis(
            text = "BODEGAS MUGA\nRIOJA\nTEMPRANILLO\n2019",
            isFullFrame = false
        )

        val selected = LabelOcrHypothesisSelector.choose(fullFrame, enhancedCrop, parser, dictionary)

        assertEquals(enhancedCrop, selected)
    }

    @Test
    fun fallsBackToFullFrameWhenTheEnhancedCropIsBlank() {
        val fullFrame = LabelOcrHypothesis("Rioja 2019", true)
        val enhancedCrop = LabelOcrHypothesis("  ", false)

        val selected = LabelOcrHypothesisSelector.choose(fullFrame, enhancedCrop, parser, dictionary)

        assertEquals(fullFrame, selected)
    }
}
