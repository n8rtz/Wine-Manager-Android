package com.n8rtz.winetracker.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the [SensoryData] JSON TypeConverter, focused on the aroma-wheel
 * descriptor list added alongside the existing flaw list. These tests only
 * exercise the Gson path (well-formed current-model JSON); the org.json legacy
 * fallback needs Android framework stubs and is covered by device checks.
 */
class SensoryDataAromasConverterTest {

    private val converters = Converters()

    @Test
    fun roundTripsAromaSelectionThroughJson() {
        val original = SensoryData(
            sweetness = 3,
            aroma = 4,
            acidity = 2,
            tannins = 1,
            body = 3,
            balance = 4,
            intensity = 2,
            complexity = 3,
            finish = 4,
            quality = 3,
            overallScore = 91,
            flaws = listOf("Cork taint (TCA)"),
            aromas = listOf(
                "red_cherry",
                "oak_vanilla",
                "spi_black_pepper"
            )
        )
        val restored = converters.toSensoryData(converters.fromSensoryData(original))
        assertEquals(original, restored)
    }

    @Test
    fun legacyJsonWithoutAromasParsesWithEmptyLists() {
        // A row written before the aromas (or flaws) fields existed: no NPEs, empty lists.
        val legacyJson = """
            {"sweetness":1,"aroma":2,"acidity":3,"tannins":2,"body":2,"balance":4,
             "intensity":2,"complexity":1,"finish":2,"quality":3,"overallScore":88}
        """.trimIndent()
        val parsed = converters.toSensoryData(legacyJson)
        assertNotNull(parsed)
        assertEquals(88, parsed!!.overallScore)
        assertEquals(3, parsed.acidity)
        assertTrue(parsed.aromas.isEmpty())
        assertTrue(parsed.flaws.isEmpty())
    }

    @Test
    fun jsonWithFlawsButNoAromasPreservesFlaws() {
        val json = """
            {"sweetness":1,"aroma":2,"acidity":2,"tannins":2,"body":2,"balance":2,
             "intensity":2,"complexity":1,"finish":2,"quality":2,"overallScore":75,
             "flaws":["Oxidation","Volatile acidity"]}
        """.trimIndent()
        val parsed = converters.toSensoryData(json)
        assertNotNull(parsed)
        assertEquals(listOf("Oxidation", "Volatile acidity"), parsed!!.flaws)
        assertTrue(parsed.aromas.isEmpty())
    }

    @Test
    fun unknownExtraKeysAreIgnored() {
        val json = """
            {"sweetness":2,"aroma":3,"acidity":2,"tannins":2,"body":2,"balance":2,
             "intensity":2,"complexity":2,"finish":2,"quality":2,"overallScore":85,
             "noseIntensity":3,"aromas":["cit_lemon"],"retiredField":true}
        """.trimIndent()
        val parsed = converters.toSensoryData(json)
        assertNotNull(parsed)
        assertEquals(listOf("cit_lemon"), parsed!!.aromas)
        assertEquals(3, parsed.aroma)
    }

    @Test
    fun blankAndNullStorageYieldNullSensoryData() {
        assertNull(converters.toSensoryData(null))
        assertNull(converters.toSensoryData(""))
        assertNull(converters.toSensoryData("   "))
        assertNull(converters.fromSensoryData(null))
    }

    @Test
    fun defaultSensoryDataHasEmptyAromaSelection() {
        val defaults = SensoryData()
        assertTrue(defaults.aromas.isEmpty())
        assertTrue(defaults.flaws.isEmpty())
    }
}
