package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WineLabelParserTest {
    private val parser = WineLabelParser()

    @Test
    fun parsesLabelTextIntoEditableSuggestions() {
        val result = parser.parse("Domaine Example\nCuvée Rouge\n2019")

        assertEquals("Domaine Example", result.maker)
        assertEquals("Cuvée Rouge", result.name)
        assertEquals("2019", result.vintage)
        assertEquals("Domaine Example\nCuvée Rouge\n2019", result.rawText)
    }

    @Test
    fun blankOcrTextProducesNoSuggestions() {
        val result = parser.parse(" \n  \n")

        assertFalse(result.hasSuggestions)
        assertNull(result.maker)
        assertNull(result.name)
        assertNull(result.vintage)
        assertNull(result.rawText)
    }

    @Test
    fun textWithoutAVintageDoesNotInventOne() {
        val result = parser.parse("Winery Example\nReserve Cabernet")

        assertEquals("Winery Example", result.maker)
        assertEquals("Reserve Cabernet", result.name)
        assertNull(result.vintage)
    }
}
