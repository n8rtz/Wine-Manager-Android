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
    fun bodegasPrefixIdentifiesTheProducerInsteadOfAnUnrelatedCellarProducer() {
        val frenchCellarWine = com.n8rtz.winetracker.data.local.WineDefinition(
            maker = "Domaine des Cèdres",
            name = "Les Cèdres du Nord",
            type = "Red",
            country = "France",
            region = "Bordeaux"
        )
        val dictionary = WineReferenceDictionary.forCellar(listOf(frenchCellarWine), emptyMap())
        val result = parser.parse(
            "BODEGAS SIERRA CANTABRIA, S.A.\nRIOJA\nTEMPRANILLO\n2019",
            dictionary
        )

        assertEquals("Bodegas Sierra Cantabria", result.maker)
        assertEquals("Spain", result.country)
        assertEquals("Rioja", result.region)
    }

    @Test
    fun recognizesBodegasWhenTheProducerNameIsOnTheNextOcrLine() {
        val result = parser.parse("BODEGAS\nMUGA\nRIOJA\n2018")

        assertEquals("Bodegas Muga", result.maker)
        assertEquals("Spain", result.country)
        assertEquals("Rioja", result.region)
    }

    @Test
    fun explicitRiojaContextRejectsAnIncompatibleFrenchCellarWineGuess() {
        val frenchCellarWine = com.n8rtz.winetracker.data.local.WineDefinition(
            maker = "Domaine des Cèdres",
            name = "Les Cèdres du Nord",
            type = "Red",
            country = "France",
            region = "Bordeaux"
        )
        val dictionary = WineReferenceDictionary.forCellar(listOf(frenchCellarWine), emptyMap())
        val result = parser.parse("RIOJA\nLES CEDRES DU NORD\n2019", dictionary)

        assertNull(result.maker)
        assertEquals("Spain", result.country)
        assertEquals("Rioja", result.region)
    }

    @Test
    fun extractsProducerAndRiojaMetadataFromOlarraGranReservaLabelText() {
        val result = parser.parse(
            "RECIENTE\nGRAN RESERVA\nBODEGAS OLARRA\nRIOJA\n" +
                "DENOMINACION DE ORIGEN CALIFICADA\n2019"
        )

        assertEquals("Bodegas Olarra", result.maker)
        assertEquals("2019", result.vintage)
        assertEquals("Spain", result.country)
        assertEquals("Rioja", result.region)
    }

    @Test
    fun readsMerlotAndCaliforniaFromBlackstoneLabelText() {
        val result = parser.parse("BLACKSTONE\nWINERY\nMERLOT\nCALIFORNIA")

        assertEquals("Blackstone Winery", result.maker)
        assertEquals("Merlot", result.name)
        assertEquals("United States", result.country)
        assertEquals("California", result.region)
        assertEquals("Red", result.type)
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
    fun completesTheBrunelloPrefixAndRejectsAnIncompatibleGrape() {
        val result = parser.parse("BRUNELLO DI RIESLING")

        assertEquals("Brunello di Montalcino", result.name)
        assertEquals("Italy", result.country)
        assertEquals("Tuscany", result.region)
        assertEquals("Red", result.type)
        assertEquals("Brunello di Montalcino Riesling", result.correctedText)
    }

    @Test
    fun regionalContextFavorsTheGrapeTypicalOfThatRegion() {
        val result = parser.parse("MOSEL RIESLING")

        assertEquals("Riesling", result.name)
        assertEquals("Germany", result.country)
        assertEquals("Mosel", result.region)
        assertEquals("White", result.type)
    }

    @Test
    fun recognizesCaliforniaAsAUnitedStatesRegionNotACountry() {
        val result = parser.parse("CALIFORNIA")

        assertEquals("United States", result.country)
        assertEquals("California", result.region)
    }

    @Test
    fun completesNapaToNapaValleyWithItsCountryAndRegion() {
        val result = parser.parse("NAPA VALLY")

        assertEquals("Napa Valley", result.name)
        assertEquals("Napa Valley", result.correctedText)
        assertEquals("United States", result.country)
        assertEquals("California", result.region)
        assertEquals("Napa Valley", result.subregion)
        assertNull(result.type)
    }

    @Test
    fun prefersFullCanonicalWineAndProducerOverShortCellarAliases() {
        val definitions = listOf(
            com.n8rtz.winetracker.data.local.WineDefinition(
                maker = "Kirkland",
                name = "Brunello",
                type = "Red",
                country = "Italy",
                region = "Tuscana",
                subregion = "Brunello"
            ),
            com.n8rtz.winetracker.data.local.WineDefinition(
                maker = "Kirkland Signature",
                name = "Brunello di Montalcino",
                type = "Red",
                country = "Italy",
                region = "Tuscany",
                subregion = "Brunello di Montalcino"
            )
        )
        val locations = mapOf("Italy" to mapOf("Tuscany" to listOf("Brunello di Montalcino")))
        val dictionary = WineReferenceDictionary.forCellar(definitions, locations)
        val rawText = "KIRKLAND\nBRUNELLO\nbMONTALCINO\nORGNE CONTROLLAA\n2021"
        val result = parser.parse(rawText, dictionary)

        assertEquals("Kirkland Signature", result.maker)
        assertEquals("Brunello di Montalcino", result.name)
        assertEquals("Italy", result.country)
        assertEquals("Tuscany", result.region)
        assertEquals("Brunello di Montalcino", result.subregion)
        assertEquals("Red", result.type)
        assertEquals(
            "Kirkland Signature\nBrunello di Montalcino\nOrigine Controllata\n2021",
            result.correctedText
        )
        assertEquals("Tuscany", parser.parse("TUSCANA", dictionary).region)
        val shortLabel = parser.parse("KIRKLAND BRUNELLO", dictionary)
        assertEquals("Kirkland Signature", shortLabel.maker)
        assertEquals("Brunello", shortLabel.name)
    }

    @Test
    fun prefersAnAppellationOverACompetingCellarWineName() {
        val definition = com.n8rtz.winetracker.data.local.WineDefinition(
            maker = "Kirkland Signature",
            name = "E Parantita",
            type = "Red",
            country = "Italy",
            region = "Tuscany",
            subregion = "Brunello di Montalcino"
        )
        val dictionary = WineReferenceDictionary.forCellar(
            listOf(definition),
            mapOf("Italy" to mapOf("Tuscany" to listOf("Brunello di Montalcino")))
        )
        val result = parser.parse(
            "KIRKLAND\nE PARANTITA\nBRUNELLO DI MONTALCINO\n2021",
            dictionary
        )

        assertEquals("Kirkland Signature", result.maker)
        assertEquals("Brunello di Montalcino", result.name)
    }

    @Test
    fun keepsDetectedAppellationAheadOfUnrecognizedOcrNoise() {
        val definitions = listOf(
            com.n8rtz.winetracker.data.local.WineDefinition(
                maker = "Kirkland Signature",
                name = "Pinot Noir",
                type = "Red",
                country = "California",
                region = "North Coast",
                subregion = "Napa Valley"
            ),
            com.n8rtz.winetracker.data.local.WineDefinition(
                maker = "Other Producer",
                name = "Brunello di Montalcino",
                type = "Red",
                country = "Italy",
                region = "Tuscana",
                subregion = "Brunello"
            )
        )
        val locations = mapOf("Italy" to mapOf("Tuscany" to listOf("Brunello di Montalcino")))
        val dictionary = WineReferenceDictionary.forCellar(definitions, locations)
        val result = parser.parse(
            "KIRKLAND\nBRUNELLO DI MONTALCINO\nORGUE CONUOLATA\n2021",
            dictionary
        )

        assertEquals("Kirkland Signature", result.maker)
        assertEquals("Brunello di Montalcino", result.name)
        assertEquals("ORGUE CONUOLATA", result.correctedText?.lineSequence()?.drop(2)?.first())
    }

    @Test
    fun defaultsCellarProducerKirklandToKirklandSignature() {
        val definition = com.n8rtz.winetracker.data.local.WineDefinition(
            maker = "Kirkland",
            name = "Pinot Noir",
            type = "Red",
            country = "California",
            region = "North Coast",
            subregion = "Napa Valley"
        )
        val dictionary = WineReferenceDictionary.forCellar(listOf(definition), emptyMap())
        val result = parser.parse("KIRKLAND", dictionary)

        assertEquals("Kirkland Signature", result.maker)
        assertEquals("Kirkland Signature", result.correctedText)
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
    fun repairsACellarRegionSpellingToTheCanonicalLocationRegion() {
        val definition = com.n8rtz.winetracker.data.local.WineDefinition(
            maker = "Kirkland",
            name = "Brunello",
            type = "Red",
            country = "Italy",
            region = "Tuscana",
            subregion = "Brunello"
        )
        val dictionary = WineReferenceDictionary.forCellar(
            listOf(definition),
            mapOf("Italy" to mapOf("Tuscany" to listOf("Brunello di Montalcino")))
        )
        val result = parser.parse("TUSCANA", dictionary)

        assertEquals("Italy", result.country)
        assertEquals("Tuscany", result.region)
        // The location-data spelling wins the correction, and the cellar typing stays an alias.
        assertEquals("Tuscany", result.correctedText)
        assertEquals("Tuscany", parser.parse("TOSCANA", dictionary).region)
    }

    @Test
    fun recognizesNativeLanguageRegionSpellingsFromLocationData() {
        val definition = com.n8rtz.winetracker.data.local.WineDefinition(
            maker = "Cantina Example",
            name = "Barolo",
            type = "Red",
            country = "Italy",
            region = "Piemonte",
            subregion = "Barolo"
        )
        val dictionary = WineReferenceDictionary.forCellar(
            listOf(definition),
            mapOf("Italy" to mapOf("Piedmont" to listOf("Barolo")))
        )
        val result = parser.parse("PIEMONTE", dictionary)

        assertEquals("Italy", result.country)
        assertEquals("Piedmont", result.region)
    }

    @Test
    fun keepsCuratedMetadataWhenCellarHistoryDescribesTheSameRegion() {
        val cellarWine = com.n8rtz.winetracker.data.local.WineDefinition(
            maker = "Kirkland Signature",
            name = "Brunello di Montalcino",
            type = "Red",
            country = "Italy",
            region = "Tuscana",
            subregion = "Brunello di Montalcino"
        )
        val dictionary = WineReferenceDictionary.forCellar(
            listOf(cellarWine),
            mapOf("Italy" to mapOf("Tuscany" to listOf("Brunello di Montalcino")))
        )
        val result = parser.parse("KIRKLAND\nBRUNELLO DI MONTALCINO\n2019", dictionary)

        // Curated region spelling, subregion and varietal context survive the cellar duplicate.
        assertEquals("Kirkland Signature", result.maker)
        assertEquals("Brunello di Montalcino", result.name)
        assertEquals("Italy", result.country)
        assertEquals("Tuscany", result.region)
        assertEquals("Brunello di Montalcino", result.subregion)
        assertEquals("Red", result.type)
    }

    @Test
    fun readsADecorativeVarietyAndItsAppellationFromAnOldVineLabel() {
        val result = parser.parse("OZV\nOLD VINE\nZINFANDEL\nESTATE GROWN\nLODI\nV. 2022")

        // The label's own spelling is the grape's canonical American name; Primitivo stays a synonym.
        assertEquals("Zinfandel", result.name)
        assertEquals("2022", result.vintage)
        assertEquals("United States", result.country)
        assertEquals("California", result.region)
        assertEquals("Lodi", result.subregion)
        assertEquals("Red", result.type)
    }

    @Test
    fun ignoresFoundingYearsWhenChoosingTheVintage() {
        val result = parser.parse(
            "Penfolds\nKOONUNGA HILL\nSHIRAZ CABERNET\n" +
                "FIRST VINTAGE 1996\nTWENTY WINES SOUTH AUSTRALIA\nESTABLISHED 1844\n2019"
        )

        // 1996 and 1844 are cellar trivia; the bottle is the 2019 vintage.
        assertEquals("2019", result.vintage)
        assertEquals("Penfolds", result.maker)
        assertEquals("Shiraz Cabernet", result.name)
        assertEquals("Australia", result.country)
        assertEquals("South Australia", result.region)
        assertEquals("Red", result.type)
        // A year introduced by "vintage" is still the vintage when the label offers no other year.
        assertEquals("2019", parser.parse("ESTATE WINE\nVINTAGE 2019").vintage)
    }

    @Test
    fun readsASpanishDenominacionDeOrigenAndItsProvince() {
        val result = parser.parse(
            "Juan Gil\nJUMILLA\nGIL\nDENOMINACION DE ORIGEN PROTEGIDA\n2020"
        )

        assertEquals("Juan Gil", result.maker)
        assertEquals("2020", result.vintage)
        assertEquals("Spain", result.country)
        assertEquals("Murcia", result.region)
        assertEquals("Jumilla", result.subregion)
        assertEquals("Red", result.type)
    }

    @Test
    fun keepsAMultiWordProducerNameFromTheLabel() {
        val result = parser.parse(
            "CONDE\nDE BASALU\nCrianza\n2021\n10 MESES EN BARRICA\nRIOJA\n" +
                "DENOMINACION DE ORIGEN CALIFICADA"
        )

        assertEquals("Conde de Basalu", result.maker)
        assertEquals("2021", result.vintage)
        assertEquals("Spain", result.country)
        assertEquals("Rioja", result.region)
    }

    @Test
    fun prefersTheVillagesAppellationOverItsParentRegion() {
        val result = parser.parse(
            "LA RESERVE\nDE\nL'ENCLAVE\nFONDEE EN 1317\nCOTES DU RHONE VILLAGES\n" +
                "APPELLATION D'ORIGINE PROTEGEE\n2022\nVIGNERONS DE L'ENCLAVE"
        )

        assertEquals("Côtes du Rhône Villages", result.name)
        assertEquals("France", result.country)
        assertEquals("Rhone", result.region)
        assertEquals("Côtes du Rhône Villages", result.subregion)
        assertEquals("2022", result.vintage)
    }

    @Test
    fun resolvesTheSpanishNameOfAnArgentineSubregion() {
        val result = parser.parse(
            "LOS HAROLDOS\nRESERVA\n2020\nMALBEC\nVALLE DE UCO\nMENDOZA ARGENTINA"
        )

        assertEquals("Los Haroldos", result.maker)
        assertEquals("2020", result.vintage)
        assertEquals("Argentina", result.country)
        assertEquals("Mendoza", result.region)
        assertEquals("Uco Valley", result.subregion)
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
        assertEquals("United States", result.country)
        assertEquals("North Coast", result.region)
        assertEquals("Napa Valley", result.subregion)
    }
}
