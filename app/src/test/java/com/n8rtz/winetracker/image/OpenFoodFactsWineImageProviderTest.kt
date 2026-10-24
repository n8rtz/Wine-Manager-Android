package com.n8rtz.winetracker.image

import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.network.ImageMeta
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import com.n8rtz.winetracker.network.OpenFoodFactsProduct
import com.n8rtz.winetracker.network.OpenFoodFactsResponse
import com.n8rtz.winetracker.network.OpenFoodFactsProductResponse
import com.n8rtz.winetracker.network.ProductImages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFoodFactsWineImageProviderTest {
    @Test
    fun `returns only HTTPS Open Food Facts candidates with attribution metadata`() = runBlocking {
        val api = FakeOpenFoodFactsApi(
            OpenFoodFactsResponse(
                products = listOf(
                    product(
                        code = "12345678",
                        name = "Estate Cabernet",
                        image = "https://images.openfoodfacts.org/images/products/12/34/front.400.jpg",
                        preview = "https://images.openfoodfacts.org/images/products/12/34/front.200.jpg",
                        width = 800,
                        height = 1200,
                        uploader = "wine-contributor"
                    ),
                    // Duplicate image URLs are not shown twice.
                    product(
                        code = "12345678",
                        name = "Duplicate listing",
                        image = "https://images.openfoodfacts.org/images/products/12/34/front.400.jpg"
                    ),
                    // Non-HTTPS and non-Open-Food-Facts URLs are rejected before preview/display.
                    product(code = "87654321", name = "Insecure", image = "http://images.openfoodfacts.org/front.jpg"),
                    product(code = "87654322", name = "Untrusted", image = "https://example.com/front.jpg")
                )
            )
        )

        val candidates = OpenFoodFactsWineImageProvider(api).findCandidates(wine())

        assertEquals(1, candidates.size)
        assertEquals("Estate Cabernet", candidates.single().productTitle)
        assertEquals("https://images.openfoodfacts.org/images/products/12/34/front.200.jpg", candidates.single().previewUrl)
        assertEquals("wine-contributor", candidates.single().attribution)
        assertEquals("https://world.openfoodfacts.org/product/12345678", candidates.single().sourcePageUrl)
        assertEquals("Example Estate Estate Cabernet Napa Valley", api.lastQuery)
        assertTrue(candidates.single().sourceUrl.startsWith("https://images.openfoodfacts.org/"))
    }

    @Test
    fun `uses a bounded preview rendition when the small URL is absent`() = runBlocking {
        val api = FakeOpenFoodFactsApi(
            OpenFoodFactsResponse(
                products = listOf(
                    product(
                        code = "12345678",
                        name = "Estate Cabernet",
                        image = "https://images.openfoodfacts.org/images/products/12/34/front.400.jpg"
                    )
                )
            )
        )

        val candidate = OpenFoodFactsWineImageProvider(api).findCandidates(wine()).single()

        assertEquals("https://images.openfoodfacts.org/images/products/12/34/front.200.jpg", candidate.previewUrl)
        assertNull(candidate.attribution)
    }

    private fun wine() = WineDefinition(
        definitionId = 42L,
        maker = "Example Estate",
        name = "Estate Cabernet",
        type = "Red",
        country = "United States",
        region = "Napa Valley"
    )

    private fun product(
        code: String,
        name: String,
        image: String,
        preview: String? = null,
        width: Int? = null,
        height: Int? = null,
        uploader: String? = null
    ) = OpenFoodFactsProduct(
        code = code,
        product_name = name,
        image_front_url = image,
        image_front_small_url = preview,
        image_url = null,
        images = ProductImages(ImageMeta(width, height, uploader)),
        completeness = null,
        creator = null
    )

    private class FakeOpenFoodFactsApi(
        private val response: OpenFoodFactsResponse
    ) : OpenFoodFactsApi {
        var lastQuery: String? = null

        override suspend fun product(barcode: String, fields: String): OpenFoodFactsProductResponse =
            error("Image searches must not invoke barcode product lookup")

        override suspend fun search(
            query: String,
            simple: Int,
            action: String,
            json: Int,
            tagType: String,
            tagContains: String,
            tag: String,
            pageSize: Int,
            fields: String
        ): OpenFoodFactsResponse {
            lastQuery = query
            return response
        }
    }
}
