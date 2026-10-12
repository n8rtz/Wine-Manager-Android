package com.n8rtz.winetracker.image

import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import com.n8rtz.winetracker.network.OpenFoodFactsProduct
import java.net.URI

/** A reviewable image result. Finding one never writes to Room or downloads the original. */
data class WineImageCandidate(
    val id: String,
    val productTitle: String,
    val productCode: String?,
    val previewUrl: String,
    val sourceUrl: String,
    val sourcePageUrl: String?,
    val sourceName: String,
    val attribution: String?,
    val width: Int?,
    val height: Int?
)

enum class WineImageKind(val databaseValue: String, val displayName: String) {
    BOTTLE("BOTTLE", "Whole bottle"),
    LABEL("LABEL", "Label only")
}

interface WineImageProvider {
    suspend fun findCandidates(wine: WineDefinition): List<WineImageCandidate>
}

class OpenFoodFactsWineImageProvider(
    private val api: OpenFoodFactsApi
) : WineImageProvider {
    override suspend fun findCandidates(wine: WineDefinition): List<WineImageCandidate> {
        val query = listOfNotNull(
            wine.maker.trim().takeIf(String::isNotEmpty),
            wine.name.trim().takeIf(String::isNotEmpty),
            (wine.subregion ?: wine.region)?.trim()?.takeIf(String::isNotEmpty)
        ).joinToString(" ")
        if (query.isBlank()) return emptyList()

        val response = api.search(query = query, pageSize = MAX_RESULTS)
        return response.products.orEmpty()
            .mapNotNull(::toCandidate)
            .distinctBy { it.sourceUrl }
            .sortedWith(
                compareByDescending<WineImageCandidate> { candidate ->
                    val width = candidate.width ?: 0
                    val height = candidate.height ?: 0
                    width.toLong() * height.toLong()
                }.thenBy { it.productTitle.lowercase() }
            )
            .take(MAX_RESULTS)
    }

    private fun toCandidate(product: OpenFoodFactsProduct): WineImageCandidate? {
        val rawImageUrl = listOfNotNull(product.image_front_url, product.image_url)
            .firstNotNullOfOrNull(::allowedOpenFoodFactsHttpsUrl)
            ?: return null
        val fullImageUrl = allowedOpenFoodFactsHttpsUrl(makeFullImageUrl(rawImageUrl)) ?: rawImageUrl
        val previewUrl = product.image_front_small_url
            ?.let(::allowedOpenFoodFactsHttpsUrl)
            ?: makeSmallPreviewUrl(fullImageUrl)
        val code = product.code?.trim()?.takeIf { it.matches(PRODUCT_CODE_PATTERN) }
        val title = product.product_name?.trim()?.takeIf(String::isNotEmpty)
            ?: code?.let { "Open Food Facts product $it" }
            ?: "Open Food Facts wine"

        return WineImageCandidate(
            id = code?.let { "$it:${fullImageUrl.hashCode()}" } ?: fullImageUrl,
            productTitle = title,
            productCode = code,
            previewUrl = previewUrl,
            sourceUrl = fullImageUrl,
            sourcePageUrl = code?.let { "https://world.openfoodfacts.org/product/$it" },
            sourceName = SOURCE_NAME,
            // `creator` is a product contributor, not necessarily the image uploader. Do not mislabel it.
            attribution = product.images?.front?.uploader?.trim()?.takeIf(String::isNotEmpty),
            width = product.images?.front?.width,
            height = product.images?.front?.height
        )
    }

    private fun allowedOpenFoodFactsHttpsUrl(raw: String): String? {
        val value = raw.trim()
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
        if (host != "openfoodfacts.org" && !host.endsWith(".openfoodfacts.org")) return null
        return uri.toASCIIString()
    }

    private fun makeFullImageUrl(url: String): String = when {
        ".400." in url -> url.replace(".400.", ".full.")
        ".200." in url -> url.replace(".200.", ".full.")
        else -> url
    }

    private fun makeSmallPreviewUrl(fullUrl: String): String = when {
        ".400." in fullUrl -> fullUrl.replace(".400.", ".200.")
        ".full." in fullUrl -> fullUrl.replace(".full.", ".200.")
        else -> fullUrl
    }

    private companion object {
        const val SOURCE_NAME = "Open Food Facts"
        const val MAX_RESULTS = 5
        val PRODUCT_CODE_PATTERN = Regex("[0-9A-Za-z-]{4,64}")
    }
}
