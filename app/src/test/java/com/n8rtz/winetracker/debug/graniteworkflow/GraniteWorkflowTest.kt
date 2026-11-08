package com.n8rtz.winetracker.debug.graniteworkflow

import android.app.Application
import android.graphics.RectF
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GraniteWorkflowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<GraniteWorkflowActivity>()

    @Test
    fun screenOpensWithoutModelFilesAndShowsFriendlyImportPrompt() {
        composeRule.onNodeWithText("Dual OCR + Granite 4.2 4B Workflow").assertIsDisplayed()
        composeRule.onNodeWithTag("import_models_prompt_card").assertIsDisplayed()
        composeRule.onNodeWithText("Import Model Files to Run", substring = true).assertIsDisplayed()
    }

    @Test
    fun preprocessorPreservesAspectRatioAndNeverDownscalesBelow768OrUnder1280() {
        // Image already <= 1280px longest edge: untouched
        assertEquals(800 to 600, GraniteWorkflowImagePreprocessor.computeTargetDimensions(800, 600))
        assertEquals(768 to 1024, GraniteWorkflowImagePreprocessor.computeTargetDimensions(768, 1024))
        // Image > 1280px longest edge: scaled proportionally so longest edge == 1280px
        assertEquals(960 to 1280, GraniteWorkflowImagePreprocessor.computeTargetDimensions(3000, 4000))
        assertEquals(1280 to 720, GraniteWorkflowImagePreprocessor.computeTargetDimensions(3840, 2160))
    }

    @Test
    fun ocrBlockNormalizesCenterWhAndSortsTopToBottomThenLeftToRight() {
        val bottomRight = OcrBlock.fromPixelBounds("750 ML", 0.92f, 500f, 900f, 700f, 950f, 1000, 1000, OcrEngineSource.ML_KIT)
        val topCenter = OcrBlock.fromPixelBounds("Château Margaux", 0.96f, 200f, 100f, 800f, 180f, 1000, 1000, OcrEngineSource.ML_KIT)
        val bottomLeft = OcrBlock.fromPixelBounds("13.5% ALC./VOL.", 0.90f, 100f, 900f, 350f, 950f, 1000, 1000, OcrEngineSource.PP_OCRV6)

        assertEquals(0.50f, topCenter.xCenter, 0.001f)
        assertEquals(0.14f, topCenter.yCenter, 0.001f)
        assertEquals(0.60f, topCenter.width, 0.001f)
        assertEquals(0.08f, topCenter.height, 0.001f)

        val sorted = DualOcrEngine.sortBlocksTopToBottomLeftToRight(listOf(bottomRight, topCenter, bottomLeft))
        assertEquals(listOf("Château Margaux", "13.5% ALC./VOL.", "750 ML"), sorted.map { it.text })
    }

    @Test
    fun graniteExtractorEnforcesSchemaDiacriticsAndNonVintageSparklingRules() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val extractor = GraniteLabelExtractor()

        val mlKitBlocks = listOf(
            OcrBlock("Chateau Margaux", 0.85f, RectF(0.2f, 0.15f, 0.8f, 0.22f), OcrEngineSource.ML_KIT),
            OcrBlock("Cotes du Rhone", 0.88f, RectF(0.25f, 0.45f, 0.75f, 0.52f), OcrEngineSource.ML_KIT),
            OcrBlock("Champagne Brut NV", 0.91f, RectF(0.25f, 0.60f, 0.75f, 0.66f), OcrEngineSource.ML_KIT),
            OcrBlock("12.5% ALC./VOL. 750 ML", 0.93f, RectF(0.15f, 0.88f, 0.85f, 0.93f), OcrEngineSource.ML_KIT)
        )
        val ppOcrBlocks = listOf(
            OcrBlock("Château Margaux", 0.94f, RectF(0.2f, 0.15f, 0.8f, 0.22f), OcrEngineSource.PP_OCRV6),
            OcrBlock("Côtes du Rhône", 0.95f, RectF(0.25f, 0.45f, 0.75f, 0.52f), OcrEngineSource.PP_OCRV6)
        )

        val outcome = extractor.extract(
            context = context,
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = ppOcrBlocks,
            ablationMode = OcrAblationMode.DUAL,
            thinkModeEnabled = true
        )

        assertEquals("Château Margaux", outcome.structuredJson.producerOrWinery)
        assertNull(outcome.structuredJson.vintage)
        assertTrue(outcome.structuredJson.isNonVintage)
        assertEquals(12.5, outcome.structuredJson.abvPercentage ?: 0.0, 0.01)
        assertEquals(750, outcome.structuredJson.bottleVolumeMl)
        assertFalse(outcome.structuredJson.ocrDiscrepanciesResolved.isEmpty())
        assertNotNull(outcome.rawThinkTrace)

        val parsedBack = GraniteWineLabelJson.fromJsonString(outcome.prettyJson)
        assertEquals(outcome.structuredJson.producerOrWinery, parsedBack.producerOrWinery)
        assertEquals(outcome.structuredJson.isNonVintage, parsedBack.isNonVintage)
    }
}
