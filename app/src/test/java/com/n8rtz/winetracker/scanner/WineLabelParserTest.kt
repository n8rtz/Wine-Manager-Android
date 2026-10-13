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

    @Test
    fun normalizesInconsistentOcrCaseAndRemovesCertificationFromName() {
        val rawText = "dOmAiNe dE lA rOmAnÉe-cOnTi\nrEsErVe cAbErNeT aOc\n2019"
        val result = parser.parse(rawText)

        assertEquals("Domaine de la Romanée-Conti", result.maker)
        assertEquals("Reserve Cabernet", result.name)
        assertEquals(rawText, result.rawText)
    }

    @Test
    fun correctsKirklandBrunelloLabelUsingWineVocabulary() {
        val rawText = "KIRKLAND BRUNELLO DIMONTALCINU ORGNE CONTROLLAA 2021"
        val result = parser.parse(rawText)

        assertEquals("Kirkland Signature", result.maker)
        assertEquals("Brunello di Montalcino", result.name)
        assertEquals("2021", result.vintage)
        assertEquals("Italy", result.country)
        assertEquals("Tuscany", result.region)
        assertEquals("Brunello di Montalcino", result.subregion)
        assertEquals("Red", result.type)
        assertEquals(rawText, result.rawText)
        assertEquals(
            "Kirkland Signature Brunello di Montalcino Origine Controllata 2021",
            result.correctedText
        )
    }

    @Test
    fun usesAnUnambiguousCellarProducerPrefixAsAnAlias() {
        val definition = com.n8rtz.winetracker.data.local.WineDefinition(
            maker = "Silver Crane Estate",
            name = "Pinot Noir",
            type = "Red",
            country = "California",
            region = "North Coast",
            subregion = "Napa Valley"
        )
        val dictionary = WineReferenceDictionary.forCellar(listOf(definition), emptyMap())

        assertEquals("Silver Crane Estate", parser.parse("SILVER", dictionary).maker)
    }

    @Test
    fun doesNotChooseAnArbitraryMakerForADuplicateCellarWineName() {
        val definitions = listOf(
            com.n8rtz.winetracker.data.local.WineDefinition(
                maker = "Domaine North",
                name = "Cuvée No. 1",
                type = "Red",
                country = "France",
                region = "Burgundy"
            ),
            com.n8rtz.winetracker.data.local.WineDefinition(
                maker = "Domaine South",
                name = "Cuvee No. 1",
                type = "White",
                country = "Italy",
                region = "Tuscany"
            )
        )
        val dictionary = WineReferenceDictionary.forCellar(definitions, emptyMap())
        val result = parser.parse("CUVEE NO 1", dictionary)

        assertEquals("Cuvée No. 1", result.name)
        assertNull(result.maker)
        assertNull(result.country)
        assertNull(result.region)
        assertNull(result.type)
    }

    @Test
    fun learnsHistoricalProducersAndWinesFromAllDefinitions() {
        val cellarDefinition = com.n8rtz.winetracker.data.local.WineDefinition(
            definitionId = 42,
            maker = "Silver Crane Estate",
            name = "Cabernet Franc",
            type = "Red",
            country = "California",
            region = "North Coast",
            subregion = "Napa Valley"
        )
        val dictionary = WineReferenceDictionary.forCellar(listOf(cellarDefinition), emptyMap())
        val result = parser.parse("SILVER CRANE ESTATE\nCABERNET FRANQ 2019", dictionary)

        assertEquals("Silver Crane Estate", result.maker)
        assertEquals("Cabernet Franc", result.name)
        assertEquals("California", result.country)
        assertEquals("North Coast", result.region)
        assertEquals("Napa Valley", result.subregion)
    }
}
