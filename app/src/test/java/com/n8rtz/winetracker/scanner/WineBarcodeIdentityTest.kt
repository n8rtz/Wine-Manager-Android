package com.n8rtz.winetracker.scanner

import com.n8rtz.winetracker.network.OpenFoodFactsProductDetail
import com.n8rtz.winetracker.network.OpenFoodFactsProductResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WineBarcodeIdentityTest {
    private fun response(
        name: String? = null,
        brands: String? = null,
        countriesTags: List<String>? = null,
        status: Int? = 1
    ) = OpenFoodFactsProductResponse(
        status = status,
        product = OpenFoodFactsProductDetail(
            code = "0036000291452",
            product_name = name,
            brands = brands,
            countries_tags = countriesTags
        )
    )

    // --- mapping -------------------------------------------------------------------------------

    @Test
    fun stripsTheBrandVolumeAndAlcoholFromTheProductName() {
        val identity = OffWineProductMapper.map(
            "0036000291452",
            response(
                name = "Blackstone Winery Merlot 750ml 13.5% vol",
                brands = "Blackstone Winery",
                countriesTags = listOf("en:united-states")
            )
        )

        assertEquals("Blackstone Winery", identity?.maker)
        assertEquals("Merlot", identity?.name)
        assertEquals("United States", identity?.country)
    }

    @Test
    fun keepsAVintageFoundInTheProductName() {
        val identity = OffWineProductMapper.map(
            "0036000291452",
            response(name = "Kirkland Signature Malbec 2021 750 ml", brands = "Kirkland Signature")
        )

        assertEquals("Kirkland Signature", identity?.maker)
        assertEquals("Malbec", identity?.name)
        assertEquals("2021", identity?.vintage)
    }

    @Test
    fun usesTheFirstBrandWhenAProductListsSeveral() {
        val identity = OffWineProductMapper.map(
            "0036000291452",
            response(name = "Côtes du Rhône Villages", brands = "Kirkland Signature,Costco")
        )

        assertEquals("Kirkland Signature", identity?.maker)
        assertEquals("Côtes du Rhône Villages", identity?.name)
    }

    @Test
    fun fallsBackToTheGenericNameWhenNoProductNameExists() {
        val found = OpenFoodFactsProductResponse(
            status = 1,
            product = OpenFoodFactsProductDetail(generic_name = "Red wine", brands = "Some Winery")
        )

        assertEquals("Red wine", OffWineProductMapper.map("4006381333931", found)?.name)
    }

    @Test
    fun ignoresAMissingProductOrAnUnsuccessfulStatus() {
        assertNull(OffWineProductMapper.map("4006381333931", response(name = "Wine", brands = "X", status = 0)))
        assertNull(OffWineProductMapper.map("4006381333931", OpenFoodFactsProductResponse(status = 1, product = null)))
        assertNull(OffWineProductMapper.map("4006381333931", null))
        assertNull(
            "a product with no usable fields must not create an identity",
            OffWineProductMapper.map("4006381333931", response(name = null, brands = null))
        )
    }

    // --- merging -------------------------------------------------------------------------------

    @Test
    fun barcodeIdentityNamesTheWineAndTheLabelSuppliesTheBottleDetails() {
        val ocr = ScannedWine(
            maker = "Blackstome",
            name = "Merlot",
            vintage = "2019",
            country = "United States",
            region = "California",
            type = "Red",
            rawText = "BLACKSTONE\nMERLOT\nCALIFORNIA\n2019"
        )
        val identity = WineBarcodeIdentity(
            barcode = "0036000291452",
            maker = "Blackstone Winery",
            name = "Merlot",
            country = "United States"
        )

        val merged = WineIdentityMerge.merge(ocr, identity, "0036000291452")

        assertEquals("Blackstone Winery", merged.wine.maker)
        assertEquals("Merlot", merged.wine.name)
        assertEquals("2019", merged.wine.vintage)
        assertEquals("California", merged.wine.region)
        assertEquals("0036000291452", merged.wine.barcode)
        assertEquals(emptyList<String>(), merged.contradictions)
    }

    @Test
    fun reportsWhenTheBarcodeDisagreesWithTheLabel() {
        val ocr = ScannedWine(maker = "Penfolds", vintage = "2019", rawText = "PENFOLDS\n2019")
        val identity = WineBarcodeIdentity(
            barcode = "0036000291452",
            maker = "Blackstone Winery",
            name = "Merlot",
            vintage = "2021"
        )

        val merged = WineIdentityMerge.merge(ocr, identity, "0036000291452")

        // The registry identity wins, the bottle's own label keeps the vintage, and both clashes
        // are surfaced rather than hidden.
        assertEquals("Blackstone Winery", merged.wine.maker)
        assertEquals("Merlot", merged.wine.name)
        assertEquals("2019", merged.wine.vintage)
        assertEquals(listOf("maker=Penfolds / Blackstone Winery", "vintage=2019 / 2021"), merged.contradictions)
    }

    @Test
    fun aGenericProducerWordAloneDoesNotCountAsAgreement() {
        val ocr = ScannedWine(maker = "Domaine North", rawText = "DOMAINE NORTH")
        val identity = WineBarcodeIdentity(barcode = "4006381333931", maker = "Domaine South", name = "Rouge")

        val merged = WineIdentityMerge.merge(ocr, identity, "4006381333931")

        // "Domaine" is shared by thousands of producers, so the two makers still contradict.
        assertEquals(listOf("maker=Domaine North / Domaine South"), merged.contradictions)
    }

    @Test
    fun aShortenedOrMisspelledBrandIsNotAContradiction() {
        val shortened = WineIdentityMerge.merge(
            ScannedWine(maker = "Kirkland", rawText = "KIRKLAND"),
            WineBarcodeIdentity(barcode = "4006381333931", maker = "Kirkland Signature", name = "Malbec"),
            "4006381333931"
        )
        val typo = WineIdentityMerge.merge(
            ScannedWine(maker = "Blackstome", rawText = "BLACKSTOME"),
            WineBarcodeIdentity(barcode = "4006381333931", maker = "Blackstone Winery", name = "Merlot"),
            "4006381333931"
        )

        assertTrue(shortened.contradictions.isEmpty())
        assertTrue(typo.contradictions.isEmpty())
    }

    @Test
    fun keepsTheOcrResultWhenNoIdentityWasFound() {
        val ocr = ScannedWine(maker = "Penfolds", vintage = "2019", rawText = "PENFOLDS\n2019")

        val merged = WineIdentityMerge.merge(ocr, null, "0036000291452")

        assertEquals("Penfolds", merged.wine.maker)
        assertEquals("0036000291452", merged.wine.barcode)
        assertTrue(merged.contradictions.isEmpty())
    }
}
