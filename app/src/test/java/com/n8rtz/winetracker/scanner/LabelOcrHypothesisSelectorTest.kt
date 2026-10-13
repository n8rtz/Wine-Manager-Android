package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelOcrHypothesisSelectorTest {
    private val parser = WineLabelParser()
    private val dictionary = WineReferenceDictionary.builtIn()

    @Test
    fun keepsTheFullFrameWhenTheEnhancedCropOffersNoImprovement() {
        val fullFrame = LabelOcrHypothesis(text = "Bodegas Muga\nRioja\n2019")
        val enhancedCrop = fullFrame

        val selected = LabelOcrHypothesisSelector.choose(fullFrame, enhancedCrop, parser, dictionary)

        assertEquals(fullFrame, selected)
    }

    @Test
    fun choosesAClearerWineRelevantEnhancedCropOverWeakFullFrameText() {
        val fullFrame = LabelOcrHypothesis("Bodegas Muqa\nRloja\n2O19")
        val enhancedCrop = LabelOcrHypothesis("BODEGAS MUGA\nRIOJA\nTEMPRANILLO\n2019")

        val selected = LabelOcrHypothesisSelector.choose(fullFrame, enhancedCrop, parser, dictionary)

        assertEquals(enhancedCrop, selected)
    }

    @Test
    fun selectsEnhancedPassWhenItRecoversTheDecorativeMerlotWord() {
        val fullFrame = LabelOcrHypothesis("BLACKSTONE\nWINERY\nMERL0T\nCALIFORNIA")
        val enhancedCrop = LabelOcrHypothesis("BLACKSTONE\nWINERY\nMERLOT\nCALIFORNIA")

        val selected = LabelOcrHypothesisSelector.choose(fullFrame, enhancedCrop, parser, dictionary)

        assertEquals(enhancedCrop, selected)
    }

    @Test
    fun fallsBackToFullFrameWhenTheEnhancedCropIsBlank() {
        val fullFrame = LabelOcrHypothesis("Rioja 2019")
        val enhancedCrop = LabelOcrHypothesis("  ")

        val selected = LabelOcrHypothesisSelector.choose(fullFrame, enhancedCrop, parser, dictionary)

        assertEquals(fullFrame, selected)
    }
}
