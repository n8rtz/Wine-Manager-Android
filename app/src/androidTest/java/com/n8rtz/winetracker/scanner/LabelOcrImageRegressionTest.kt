package com.n8rtz.winetracker.scanner

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.merge.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real local scanner on existing photographed labels, not a promise of world-KB coverage.
 * A confident GREEN value must agree with reviewed label truth. Unsupported boutique identities
 * remain explicitly reviewable YELLOW; rejected/partial captures never silently fill RED fields.
 * The three printed fixtures separately test known KB GREEN behavior and 20-capture ownership.
 */
@RunWith(AndroidJUnit4::class)
class LabelOcrImageRegressionTest {
    private data class LabelExpectation(
        val fixture: String,
        val maker: String? = null,
        val name: String? = null,
        val vintage: String? = null,
        val country: String? = null,
        val region: String? = null,
        val subregion: String? = null
    )

    @Test
    fun scansReferencePhotosAndChecksTheReviewedLabels() = runBlocking(Dispatchers.Default) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtureContext = instrumentation.context
        val appContext = instrumentation.targetContext
        val fixtureNames = fixtureContext.assets.list(FIXTURE_ASSET_DIRECTORY)
            .orEmpty()
            .filter { it.endsWith(".jpg", ignoreCase = true) }
            .sorted()

        assertTrue(
            "No OCR reference JPEGs found under $FIXTURE_ASSET_DIRECTORY",
            fixtureNames.size >= MIN_EXPECTED_FIXTURE_COUNT
        )
        REVIEWED_LABELS.forEach { expectation ->
            assertTrue("Missing reviewed fixture ${expectation.fixture}", expectation.fixture in fixtureNames)
        }

        // This entry point intentionally has no barcode/network wine resolver.
        val reviewedNames = REVIEWED_LABELS.map(LabelExpectation::fixture).toSet()
        val scanner = ScannerManager(appContext)
        val results = mutableMapOf<String, ScanReport>()
        val repeatResults = mutableMapOf<String, ScanReport>()
        val tempDirectory = File(appContext.cacheDir, "wine-label-ocr-fixtures").apply {
            check(mkdirs() || isDirectory) { "Could not create $absolutePath" }
        }

        try {
            for (fixtureName in fixtureNames) {
                val fixtureFile = File(tempDirectory, fixtureName)
                try {
                    fixtureContext.assets.open("$FIXTURE_ASSET_DIRECTORY/$fixtureName").use { input ->
                        fixtureFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    val result = scanner.scanLabel(fixtureFile)
                    assertEquals(FieldId.entries.toSet(), result.fill.fields.keys)
                    assertEquals(0, result.workingBitmapsAfter)
                    assertTrue(result.peakWorkingBitmaps <= 2)
                    val legacy = result.toLegacyWine()
                    if (result.tokens.isEmpty()) {
                        assertTrue("$fixtureName rejected without a retake/manual hint", !result.fill.overallHint.isNullOrBlank())
                        assertFalse("RED capture must not fill legacy fields", legacy.hasSuggestions())
                    }
                    result.tokens.forEach { token ->
                        assertEquals(4, token.polyOrig.size)
                        for (box in listOf(token.bboxOrig, token.bboxRect)) {
                            assertTrue(box.left >= 0 && box.top >= 0 && box.right <= 1 && box.bottom <= 1)
                        }
                    }
                    results[fixtureName] = result
                    // Scanning the same photo twice must give the same suggestions: unstable output
                    // is what users notice as "it works sometimes".
                    if (fixtureName in reviewedNames) {
                        repeatResults[fixtureName] = scanner.scanLabel(fixtureFile)
                    }
                    Log.i(TAG, "$fixtureName: ${result.fill.fields}; elapsed=${result.elapsedMs} " +
                        "deadline=${result.deadlineCancelled} engines=${result.timings}; raw=${legacy.rawText}")
                } finally {
                    fixtureFile.delete()
                }
            }
        } finally {
            scanner.close()
            tempDirectory.delete()
        }

        assertTrue("Quality gate/scanner rejected every reference photo", results.values.count { it.tokens.isNotEmpty() } >= 3)
        reviewedNames.forEach { fixtureName ->
            val first = results.getValue(fixtureName)
            val second = repeatResults.getValue(fixtureName)
            FieldId.entries.forEach { field ->
                val a = first.fill.fields.getValue(field); val b = second.fill.fields.getValue(field)
                // Cold/warm deadlines may change review state; two validated GREEN answers may not contradict.
                if (a.state == UiState.GREEN && b.state == UiState.GREEN) {
                    assertEquals("Repeated GREEN $fixtureName $field", TextNorm.normalize(a.value), TextNorm.normalize(b.value))
                }
            }
        }

        REVIEWED_LABELS.forEach { expectation ->
            val result = results.getValue(expectation.fixture)
            fun assertField(field: FieldId, expected: String?) {
                val actual = result.fill.fields.getValue(field)
                if (expected != null && actual.state == UiState.GREEN) {
                    assertEquals("${expectation.fixture} confidently wrong $field: ${actual.reason}",
                        TextNorm.normalize(expected), TextNorm.normalize(actual.value))
                }
            }
            assertField(FieldId.PRODUCER, expectation.maker)
            assertField(FieldId.WINE_NAME, expectation.name)
            assertField(FieldId.VINTAGE, expectation.vintage)
            assertField(FieldId.COUNTRY, expectation.country)
            assertField(FieldId.REGION, expectation.region)
            assertField(FieldId.SUBREGION, expectation.subregion)
        }
    }

    private companion object {
        const val TAG = "LabelOcrImageTest"
        const val FIXTURE_ASSET_DIRECTORY = "scanner/labels"
        const val MIN_EXPECTED_FIXTURE_COUNT = 13

        /**
         * Fields transcribed by looking at each photo. Only the bottles where a single label fills
         * the frame are listed; the Olarra name stays off the list because the label's "RECIENTE"
         * cuvée is not part of the shipped vocabulary yet.
         */
        val REVIEWED_LABELS = listOf(
            // Reciente Gran Reserva, Bodegas Olarra, Rioja, 2019.
            LabelExpectation(
                fixture = "IMG20261013001626.jpg",
                maker = "Bodegas Olarra",
                vintage = "2019",
                country = "Spain",
                region = "Rioja"
            ),
            // Blackstone Winery Merlot, California; "Merlot" is calligraphic.
            LabelExpectation(
                fixture = "IMG20261013001611.jpg",
                maker = "Blackstone Winery",
                name = "Merlot",
                country = "United States",
                region = "California"
            ),
            // OZV Old Vine Zinfandel, estate grown, Lodi; "Zinfandel" is calligraphic.
            LabelExpectation(
                fixture = "547348d6-2386-420d-9e01-40083844a424.jpg",
                vintage = "2022",
                country = "United States",
                region = "California",
                subregion = "Lodi"
            ),
            // Penfolds Koonunga Hill Shiraz Cabernet, South Australia, 2019. The photo also shows
            // "FIRST VINTAGE 1996" and "ESTABLISHED 1844"; neither is the vintage.
            LabelExpectation(
                fixture = "98c91789-1fe5-427c-9daf-8df4eda28eda.jpg",
                maker = "Penfolds",
                vintage = "2019",
                country = "Australia",
                region = "South Australia"
            ),
            // Conde de Basalu Crianza, Rioja, 2021.
            LabelExpectation(
                fixture = "IMG20260609104335.jpg",
                maker = "Conde de Basalu",
                vintage = "2021",
                country = "Spain",
                region = "Rioja"
            ),
            // Juan Gil, Jumilla (Denominación de Origen Protegida), 2020.
            LabelExpectation(
                fixture = "0ff8abd6-2410-4787-b8e2-096072e180f0.jpg",
                vintage = "2020",
                country = "Spain",
                region = "Murcia",
                subregion = "Jumilla"
            ),
            // La Réserve de l'Enclave, Côtes du Rhône Villages, 2022.
            LabelExpectation(
                fixture = "1e51ac9e-8c20-418e-9ef7-0dd488a52995.jpg",
                vintage = "2022",
                country = "France",
                region = "Rhone",
                subregion = "Côtes du Rhône Villages"
            ),
            // Kirkland Signature Pinot Noir, Russian River Valley, Sonoma County, 2024.
            LabelExpectation(
                fixture = "216503e7-7a55-4d88-835c-e2efbd2092c1.jpg",
                maker = "Kirkland Signature",
                vintage = "2024",
                country = "United States",
                region = "California",
                subregion = "Russian River Valley"
            ),
            // Los Haroldos Reserva Malbec, Valle de Uco, Mendoza, 2020.
            LabelExpectation(
                fixture = "IMG20250509232919.jpg",
                maker = "Los Haroldos",
                vintage = "2020",
                country = "Argentina",
                region = "Mendoza",
                subregion = "Uco Valley"
            ),
            // Los Riscos Chardonnay, Chile.
            LabelExpectation(
                fixture = "IMG20250810211557.jpg",
                maker = "Los Riscos",
                name = "Chardonnay",
                country = "Chile"
            ),
            // Three Kirkland Signature Malbec bottles (2022/2023/2024), Mendoza, Argentina. The
            // vintage is whichever bottle ML Kit reads first, so it is logged rather than asserted.
            LabelExpectation(
                fixture = "IMG20260309213552.jpg",
                maker = "Kirkland Signature",
                country = "Argentina",
                region = "Mendoza"
            )
        )
    }
}
