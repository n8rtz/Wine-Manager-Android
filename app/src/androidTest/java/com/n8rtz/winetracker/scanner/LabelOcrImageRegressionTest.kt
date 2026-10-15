package com.n8rtz.winetracker.scanner

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real ML Kit + crop + wine-parser pipeline on the checked-in reference photos.
 *
 * Every fixture must produce OCR text; the reviewed fixtures then have to agree on the fields a
 * human can read on the label. When one of those assertions fails, the `LabelOcrImageTest` Logcat
 * line for that fixture has the raw text and parsed suggestions, so the failure can be traced to
 * either ML Kit's reading or this app's parsing. Group photos hold several bottles, so they are only
 * required to produce text: their suggestions depend on which bottle ML Kit reads first.
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
        val subregion: String? = null,
        val type: String? = null
    )

    @Test
    fun scansReferencePhotosAndChecksTheReviewedLabels() = runBlocking {
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

        // Front labels carry no retail barcode, so the barcode path is exercised (decode attempt +
        // no lookup) but never asserted; the values are logged for inspection.
        val reviewedNames = REVIEWED_LABELS.map(LabelExpectation::fixture).toSet()
        val scanner = ScannerManager(appContext)
        val results = mutableMapOf<String, ScannedWine>()
        val repeatResults = mutableMapOf<String, ScannedWine>()
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

                    val result = scanner.scanImage(fixtureFile)
                    assertFalse(
                        "$fixtureName produced no OCR text",
                        result.rawText.isNullOrBlank()
                    )
                    results[fixtureName] = result
                    // Scanning the same photo twice must give the same suggestions: unstable output
                    // is what users notice as "it works sometimes".
                    if (fixtureName in reviewedNames) {
                        repeatResults[fixtureName] = scanner.scanImage(fixtureFile)
                    }
                    Log.i(
                        TAG,
                        "$fixtureName: maker=${result.maker}, name=${result.name}, " +
                            "vintage=${result.vintage}, country=${result.country}, " +
                            "region=${result.region}, subregion=${result.subregion}, " +
                            "type=${result.type}, barcode=${result.barcode}, " +
                            "agreement=${result.agreement}, needsReview=${result.needsReview}, " +
                            "captureQuality=${result.captureQuality}; " +
                            "raw=${result.rawText?.replace('\n', '|')}"
                    )
                } finally {
                    fixtureFile.delete()
                }
            }
        } finally {
            scanner.close()
            tempDirectory.delete()
        }

        reviewedNames.forEach { fixtureName ->
            val first = results.getValue(fixtureName)
            val second = repeatResults.getValue(fixtureName)
            assertEquals(
                "Repeated scan of $fixtureName changed its suggestions " +
                    "(first raw=${first.rawText?.replace('\n', '|')}, " +
                    "second raw=${second.rawText?.replace('\n', '|')})",
                first.suggestionFields(),
                second.suggestionFields()
            )
        }

        REVIEWED_LABELS.forEach { expectation ->
            val result = results.getValue(expectation.fixture)
            fun assertField(field: String, expected: String?, actual: String?) {
                if (expected != null) {
                    assertEquals(
                        "${expectation.fixture} $field (raw=${result.rawText?.replace('\n', '|')})",
                        expected,
                        actual
                    )
                }
            }
            // The reference photos are sharp and properly exposed: flagging one would mean the
            // capture-quality gate is asking users for retakes they do not need.
            assertNull(
                "${expectation.fixture} was reported as a bad capture: ${result.captureQuality}",
                result.captureQuality
            )
            assertField("maker", expectation.maker, result.maker)
            assertField("name", expectation.name, result.name)
            assertField("vintage", expectation.vintage, result.vintage)
            assertField("country", expectation.country, result.country)
            assertField("region", expectation.region, result.region)
            assertField("subregion", expectation.subregion, result.subregion)
            assertField("type", expectation.type, result.type)
        }
    }

    private fun ScannedWine.suggestionFields(): List<String?> =
        listOf(maker, name, vintage, country, region, subregion, type)

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
                region = "California",
                type = "Red"
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
                country = "Chile",
                type = "White"
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
