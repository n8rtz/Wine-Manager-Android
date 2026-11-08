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
    fun screenOpensWithoutModelFilesAndShowsFriendlyImportPromptAndGlinerDatasetControls() {
        composeRule.onNodeWithText("Dual OCR + Granite 4.2 4B Workflow").assertIsDisplayed()
        composeRule.onNodeWithTag("import_models_prompt_card").assertIsDisplayed()
        composeRule.onNodeWithText("Import Model Files to Run", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("run_gliner_dataset_button").assertIsDisplayed()
    }

    @Test
    fun graniteLabCorpusLoadsSame124WineLabelsAsGlinerLab() {
        val context = RuntimeEnvironment.getApplication()
        val samples = GraniteLabCorpus.load(context)
        assertEquals(124, samples.size)
        assertTrue(samples.all { it.included })
        assertEquals("#2 · 158.jpg", samples.first().displayName)
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
        val bottomLeft = OcrBlock.fromPixelBounds("13.5% ALC./VOL.", 0.90f, 100f, 900f, 350f, 950f, 1000, 1000, OcrEngineSource.ML_KIT)

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

    @Test
    fun graniteDatabaseRagContextInjectsCandidatesIntoPromptAndGroundsExtraction() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val extractor = GraniteLabelExtractor()

        val ragContext = GraniteDatabaseRagContext(
            enabled = true,
            elapsedMs = 14L,
            producerCandidates = listOf(
                GraniteProducerCandidate(
                    id = "P1001",
                    name = "Château Margaux",
                    country = "France",
                    distance = 1,
                    matchedSpan = "CHATEAU MARGAUX",
                    sources = "lwin"
                )
            ),
            linkedWineCandidates = listOf(
                GraniteLinkedWineCandidate(
                    id = "W2001",
                    name = "Pavillon Rouge du Château Margaux",
                    producerId = "P1001",
                    lwin7 = "1012345",
                    distance = 1,
                    matchedSpan = "PAVILLON ROUGE"
                )
            ),
            appellationCandidates = listOf(
                GraniteAppellationCandidate(
                    appellationOrSubregion = "Margaux",
                    region = "Bordeaux",
                    country = "France",
                    matchedSpan = "MARGAUX",
                    distance = 0,
                    source = "french_aoc"
                )
            ),
            matchedGrapes = listOf("Cabernet Sauvignon"),
            matchedStyles = listOf("Premier Grand Cru Classé"),
            spatialContenderFields = mapOf(
                "producer" to "Château Margaux",
                "wine_name" to "Pavillon Rouge du Château Margaux",
                "country" to "France",
                "region" to "Bordeaux",
                "subregion" to "Margaux",
                "vintage" to "2018"
            )
        )

        val mlKitBlocks = listOf(
            OcrBlock("CHATEAU MARGAUX", 0.94f, RectF(0.18f, 0.20f, 0.82f, 0.29f), OcrEngineSource.ML_KIT),
            OcrBlock("PAVILLON ROUGE 2018", 0.92f, RectF(0.20f, 0.36f, 0.80f, 0.44f), OcrEngineSource.ML_KIT)
        )

        val prompt = extractor.buildGranitePrompt(
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = emptyList(),
            ablationMode = OcrAblationMode.DUAL,
            thinkModeEnabled = false,
            ragContext = ragContext
        )
        assertTrue(prompt.contains("Retrieved Offline Wine Database Candidates (RAG Context"))
        assertTrue(prompt.contains("Château Margaux"))
        assertTrue(prompt.contains("Pavillon Rouge du Château Margaux"))
        assertTrue(prompt.contains("Subregion/Appellation=\"Margaux\" -> Region=\"Bordeaux\", Country=\"France\""))

        val outcome = extractor.extract(
            context = context,
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = emptyList(),
            ablationMode = OcrAblationMode.ML_KIT_ONLY,
            thinkModeEnabled = true,
            ragContext = ragContext
        )
        assertEquals("Château Margaux", outcome.structuredJson.producerOrWinery)
        assertEquals("Pavillon Rouge du Château Margaux", outcome.structuredJson.wineNameOrCuvee)
        assertEquals("France", outcome.structuredJson.country)
        assertEquals("Bordeaux", outcome.structuredJson.region)
        assertEquals("Margaux", outcome.structuredJson.subregion)
    }

    @Test
    fun graniteDatabaseRagSurfacesSubspanCandidatesForGraniteDynamicReasoning() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val retriever = GraniteDatabaseRagRetriever()
        val extractor = GraniteLabelExtractor()

        val mlKitBlocks = listOf(
            OcrBlock("trancette", 0.84f, RectF(0.22f, 0.24f, 0.78f, 0.34f), OcrEngineSource.ML_KIT),
            OcrBlock("bouchard pere", 0.86f, RectF(0.20f, 0.36f, 0.80f, 0.44f), OcrEngineSource.ML_KIT),
            OcrBlock("CABERNET SAUVIGNON", 0.93f, RectF(0.18f, 0.48f, 0.82f, 0.56f), OcrEngineSource.ML_KIT),
            OcrBlock("PRODUIT DE FRANCE", 0.91f, RectF(0.25f, 0.78f, 0.75f, 0.84f), OcrEngineSource.ML_KIT),
            OcrBlock("13% vol 750 ml", 0.90f, RectF(0.20f, 0.88f, 0.80f, 0.94f), OcrEngineSource.ML_KIT)
        )

        val ragContext = retriever.retrieve(
            context = context,
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = emptyList(),
            ablationMode = OcrAblationMode.ML_KIT_ONLY,
            enabled = true
        )

        assertTrue(
            "Expected subspan RAG to surface La Francette in producerCandidates, got ${ragContext.producerCandidates}",
            ragContext.producerCandidates.any { it.name == "La Francette" && it.distance <= 1 }
        )
        assertTrue(
            "Expected subspan RAG to surface Bouchard Père & Fils in producerCandidates, got ${ragContext.producerCandidates}",
            ragContext.producerCandidates.any { it.name == "Bouchard Père & Fils" && it.distance <= 1 }
        )

        val outcome = extractor.extract(
            context = context,
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = emptyList(),
            ablationMode = OcrAblationMode.ML_KIT_ONLY,
            thinkModeEnabled = false,
            ragContext = ragContext
        )

        assertEquals("La Francette", outcome.structuredJson.producerOrWinery)
        assertTrue(outcome.structuredJson.varietals.contains("Cabernet Sauvignon"))
        assertEquals("France", outcome.structuredJson.country)
        retriever.close()
    }

    @Test
    fun granitePromptUsesReasonThenExtractOrderingAlignedLinesAndBidirectionalRagWithoutAnchoring() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val retriever = GraniteDatabaseRagRetriever()
        val extractor = GraniteLabelExtractor()

        val gbnf = GraniteLabelExtractor.WINE_LABEL_JSON_GBNF
        val discrepanciesIdx = gbnf.indexOf("\"ocr_discrepancies_resolved\"")
        val regionIdx = gbnf.indexOf("\"region\"")
        val subregionIdx = gbnf.indexOf("\"subregion\"")
        val producerIdx = gbnf.indexOf("\"producer_or_winery\"")
        assertTrue(discrepanciesIdx in 1 until regionIdx)
        assertTrue(regionIdx < subregionIdx && subregionIdx < producerIdx)

        // Reverse wine -> producer lookup ("PAVILLON ROUGE" without "Margaux" printed cleanly on header)
        val mlKitBlocks = listOf(
            OcrBlock("trancette", 0.81f, RectF(0.24f, 0.16f, 0.52f, 0.28f), OcrEngineSource.ML_KIT),
            OcrBlock("PAVILLON ROUGE 2019", 0.93f, RectF(0.18f, 0.38f, 0.82f, 0.45f), OcrEngineSource.ML_KIT),
            OcrBlock("750 ml 13.5% vol", 0.90f, RectF(0.22f, 0.88f, 0.78f, 0.92f), OcrEngineSource.ML_KIT)
        )
        val ppOcrBlocks = listOf(
            OcrBlock("La Francette", 0.91f, RectF(0.14f, 0.16f, 0.62f, 0.28f), OcrEngineSource.PP_OCRV6),
            OcrBlock("PAVILLON ROUGE 2019", 0.94f, RectF(0.18f, 0.38f, 0.82f, 0.45f), OcrEngineSource.PP_OCRV6)
        )

        val ragContext = retriever.retrieve(
            context = context,
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = ppOcrBlocks,
            ablationMode = OcrAblationMode.DUAL,
            enabled = true
        )

        val prompt = extractor.buildGranitePrompt(
            mlKitBlocks = mlKitBlocks,
            ppOcrV6Blocks = ppOcrBlocks,
            ablationMode = OcrAblationMode.DUAL,
            thinkModeEnabled = false,
            ragContext = ragContext
        )

        assertTrue(prompt.contains("=== Spatially Aligned Cross-Engine Reading Lines"))
        assertTrue(prompt.contains("CONFLICT: MLKit=\"trancette\""))
        assertTrue(prompt.contains("LOWERCASE_INITIAL_ON_HEADER"))
        assertTrue(prompt.contains("WIDTH_MISMATCH"))
        assertFalse(prompt.contains("Spatial + KnowledgeBase Contender Hints"))
        assertTrue(
            "Expected reverse wine->producer lookup to surface a corroborated producer for Pavillon Rouge, got ${ragContext.producerCandidates}",
            ragContext.producerCandidates.any { it.corroboratedWines.isNotEmpty() }
        )
        assertTrue(
            "Expected linked wine candidates to include explicit producer name, got ${ragContext.linkedWineCandidates}",
            ragContext.linkedWineCandidates.any { !it.producerName.isNullOrBlank() }
        )
        retriever.close()
    }
}
