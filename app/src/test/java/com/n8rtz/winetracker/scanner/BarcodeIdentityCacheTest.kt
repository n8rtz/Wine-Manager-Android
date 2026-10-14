package com.n8rtz.winetracker.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BarcodeIdentityCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun cacheFile(): File = File(temporaryFolder.root, "barcode-identities.txt")

    private fun identity(barcode: String, maker: String? = "Blackstone Winery", name: String? = "Merlot") =
        WineBarcodeIdentity(
            barcode = barcode,
            maker = maker,
            name = name,
            vintage = "2019",
            country = "United States",
            imageUrl = "https://images.example/front.jpg"
        )

    @Test
    fun roundTripsAnIdentityThroughTheFile() {
        val file = cacheFile()
        BarcodeIdentityCache(file).put(identity("0036000291452"))

        val reloaded = BarcodeIdentityCache(file).get("0036000291452")

        assertEquals(identity("0036000291452"), reloaded)
        assertNull("other barcodes stay unknown", BarcodeIdentityCache(file).get("4006381333931"))
    }

    @Test
    fun overwritesAnEntryForTheSameBarcode() {
        val cache = BarcodeIdentityCache(cacheFile())
        cache.put(identity("0036000291452", maker = "Old Spelling"))
        cache.put(identity("0036000291452", maker = "Blackstone Winery"))

        assertEquals(1, cache.size())
        assertEquals("Blackstone Winery", cache.get("0036000291452")?.maker)
    }

    @Test
    fun skipsMalformedLinesInsteadOfFailing() {
        val file = cacheFile()
        file.writeText(
            """
            |not-a-barcode|Bogus|Wine
            |0036000291452|Blackstone Winery|Merlot|2019|United States|||https://images.example/front.jpg
            |short
            |
            """.trimMargin()
        )

        val cache = BarcodeIdentityCache(file)

        assertEquals(1, cache.size())
        assertEquals("Blackstone Winery", cache.get("0036000291452")?.maker)
    }

    @Test
    fun dropsTheOldestEntriesWhenTheCacheIsFull() {
        val cache = BarcodeIdentityCache(cacheFile(), maxEntries = 2)
        cache.put(identity("4006381333931"))
        cache.put(identity("036000291452"))
        cache.put(identity("96385074"))

        assertEquals(2, cache.size())
        assertNull(cache.get("4006381333931"))
        assertEquals("Blackstone Winery", cache.get("96385074")?.maker)
    }

    @Test
    fun survivesAReadOnlyDirectoryWithoutLosingTheInMemoryEntry() {
        val cache = BarcodeIdentityCache(File(temporaryFolder.root, "missing-dir").resolve("nested/cache.txt"))
        cache.put(identity("4006381333931"))

        // Either the file was written or not; the in-memory entry must still answer this session.
        assertEquals("Blackstone Winery", cache.get("4006381333931")?.maker)
    }
}
