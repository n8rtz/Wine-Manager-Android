package com.n8rtz.winetracker.scanner

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real ML Kit + crop + wine-parser pipeline on the checked-in reference photos. */
@RunWith(AndroidJUnit4::class)
class LabelOcrImageRegressionTest {
    @Test
    fun scansReferencePhotosAndChecksTheKnownCalligraphicAndRiojaLabels() = runBlocking {
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
        assertTrue("Missing Olarra fixture", OLARRA_FIXTURE in fixtureNames)
        assertTrue("Missing Blackstone fixture", BLACKSTONE_FIXTURE in fixtureNames)

        val scanner = ScannerManager(appContext)
        val results = mutableMapOf<String, ScannedWine>()
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
                    Log.i(
                        TAG,
                        "$fixtureName: maker=${result.maker}, name=${result.name}, " +
                            "vintage=${result.vintage}, country=${result.country}, " +
                            "region=${result.region}; raw=${result.rawText?.replace('\n', '|')}"
                    )
                } finally {
                    fixtureFile.delete()
                }
            }
        } finally {
            scanner.close()
            tempDirectory.delete()
        }

        val rioja = results.getValue(OLARRA_FIXTURE)
        assertEquals("Bodegas Olarra", rioja.maker)
        assertEquals("2019", rioja.vintage)
        assertEquals("Spain", rioja.country)
        assertEquals("Rioja", rioja.region)

        val blackstone = results.getValue(BLACKSTONE_FIXTURE)
        assertEquals("Blackstone Winery", blackstone.maker)
        assertEquals("Merlot", blackstone.name)
        assertEquals("United States", blackstone.country)
        assertEquals("California", blackstone.region)
        assertEquals("Red", blackstone.type)
    }

    private companion object {
        const val TAG = "LabelOcrImageTest"
        const val FIXTURE_ASSET_DIRECTORY = "scanner/labels"
        const val MIN_EXPECTED_FIXTURE_COUNT = 13
        const val OLARRA_FIXTURE = "IMG20261013001626.jpg"
        const val BLACKSTONE_FIXTURE = "IMG20261013001611.jpg"
    }
}
