package com.n8rtz.winetracker.scanner

import com.n8rtz.winetracker.network.OpenFoodFactsProductResponse
import java.util.Locale

/**
 * Identity a barcode lookup produced for one bottle. A barcode is a registry hit rather than a
 * guess, so it outranks OCR for the maker and the wine name; the label photo still supplies what a
 * barcode cannot know (this bottle's vintage, the regional detail the user sees on the label).
 */
internal data class WineBarcodeIdentity(
    val barcode: String,
    val maker: String? = null,
    val name: String? = null,
    val vintage: String? = null,
    val country: String? = null,
    val region: String? = null,
    val type: String? = null,
    val imageUrl: String? = null
)

/** Turns an Open Food Facts product into wine fields. Pure so the string surgery is unit tested. */
internal object OffWineProductMapper {
    private val vintageRegex = Regex("\\b(19|20)\\d{2}\\b")
    private val volumeRegex = Regex(
        "(?i)\\b\\d+(?:[.,]\\d+)?\\s?(?:ml|cl|l|liter|litre|oz|fl\\.?\\s?oz)\\b"
    )
    private val alcoholRegex = Regex("(?i)\\b\\d+(?:[.,]\\d+)?\\s?%\\s?(?:vol|alc)?\\.?")
    private val noiseRegex = Regex("\\s+")

    fun map(barcode: String, response: OpenFoodFactsProductResponse?): WineBarcodeIdentity? {
        if (response == null || response.status?.let { it != 1 } == true) return null
        val product = response.product ?: return null
        val brands = product.brands?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val rawName = product.product_name?.takeIf(String::isNotBlank)
            ?: product.product_name_en?.takeIf(String::isNotBlank)
        val name = rawName?.let { stripBrandAndPackaging(it, brands) }
            ?.takeIf(String::isNotBlank)
            ?: product.generic_name?.takeIf(String::isNotBlank)
        val maker = brands.firstOrNull()
        val vintage = rawName?.let { vintageRegex.find(it)?.value }
        if (maker == null && name == null) return null
        return WineBarcodeIdentity(
            barcode = barcode,
            maker = maker,
            name = name,
            vintage = vintage,
            country = countryFrom(product.countries_tags, product.countries),
            imageUrl = product.image_front_url?.takeIf(String::isNotBlank)
        )
    }

    /** "Blackstone Winery Merlot 2019 750ml" with brand "Blackstone Winery" becomes "Merlot". */
    private fun stripBrandAndPackaging(productName: String, brands: List<String>): String {
        var remaining = productName
        brands.forEach { brand ->
            remaining = remaining.replace(Regex("(?i)^\\Q$brand\\E\\b"), " ")
        }
        remaining = volumeRegex.replace(remaining, " ")
        remaining = alcoholRegex.replace(remaining, " ")
        // The vintage becomes its own field, so it is not part of the wine name.
        remaining = vintageRegex.replace(remaining, " ")
        return remaining
            .replace(Regex("[|,;]+"), " ")
            .replace(noiseRegex, " ")
            .trim()
            .trim('-', '–', '—', '.', ':')
            .trim()
    }

    /** Open Food Facts tags look like "en:italy"; the plain `countries` field is a fallback. */
    private fun countryFrom(tags: List<String>?, countries: String?): String? {
        val fromTag = tags.orEmpty()
            .mapNotNull { tag -> tag.substringAfter(':', missingDelimiterValue = "").takeIf(String::isNotBlank) }
            .firstOrNull()
        val value = fromTag ?: countries?.split(',')?.firstOrNull()?.trim()?.takeIf(String::isNotBlank)
        return value?.replace('-', ' ')?.replace(noiseRegex, " ")?.split(' ')?.joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { first ->
                if (first.isLowerCase()) first.titlecase() else first.toString()
            }
        }
    }
}

/**
 * Combines the barcode identity with what the label photo read.
 *
 * The barcode decides the identity; the label decides the bottle-specific fields. Nothing is
 * silently overwritten: when both sources state a maker or a vintage, the disagreement is reported
 * so the scan can be marked for review instead of quietly picking one.
 */
internal object WineIdentityMerge {
    data class Result(
        val wine: ScannedWine,
        val contradictions: List<String>
    )

    fun merge(ocr: ScannedWine, identity: WineBarcodeIdentity?, barcode: String?): Result {
        if (identity == null) {
            return Result(ocr.copy(barcode = barcode), emptyList())
        }
        val contradictions = buildList {
            addAll(compare("maker", ocr.maker, identity.maker))
            addAll(compare("vintage", ocr.vintage, identity.vintage))
        }
        return Result(
            wine = ocr.copy(
                // The registry spelling of the producer and the cuvée wins; the label still owns
                // the vintage, country, region and type.
                maker = identity.maker ?: ocr.maker,
                name = identity.name ?: ocr.name,
                vintage = ocr.vintage ?: identity.vintage,
                country = ocr.country ?: identity.country,
                region = ocr.region ?: identity.region,
                type = ocr.type ?: identity.type,
                barcode = identity.barcode
            ),
            contradictions = contradictions
        )
    }

    private fun compare(field: String, labelValue: String?, barcodeValue: String?): List<String> {
        val label = labelValue?.trim()?.takeIf(String::isNotEmpty) ?: return emptyList()
        val barcode = barcodeValue?.trim()?.takeIf(String::isNotEmpty) ?: return emptyList()
        return if (isSameClaim(label, barcode)) {
            emptyList()
        } else {
            listOf("$field=$label / $barcode")
        }
    }

    /**
     * Two readings of one field count as the same claim when they share a distinctive word: an OCR
     * slip ("Blackstome" vs "Blackstone Winery") or a shortened brand ("Blackstone" vs "Blackstone
     * Winery") is a spelling difference the registry settles, while "Penfolds" against
     * "Blackstone Winery" is a real disagreement worth interrupting the user for. Producer-generic
     * words ("domaine", "winery", ...) prove nothing, so they never count as a shared word.
     */
    private fun isSameClaim(first: String, second: String): Boolean {
        val left = comparableForm(first)
        val right = comparableForm(second)
        if (left.isEmpty() || right.isEmpty()) return left == right
        if (left == right) return true
        if (left.contains(right) || right.contains(left)) return true
        val leftTokens = left.split(' ').filterNot { it in GENERIC_PRODUCER_WORDS }
        val rightTokens = right.split(' ').filterNot { it in GENERIC_PRODUCER_WORDS }
        return leftTokens.any { leftToken ->
            rightTokens.any { rightToken -> tokensMatch(leftToken, rightToken) }
        }
    }

    private fun tokensMatch(first: String, second: String): Boolean {
        if (first == second) return true
        val longest = maxOf(first.length, second.length)
        if (longest == 0) return true
        val allowedEdits = (longest * (1.0 - MIN_SAME_CLAIM_SIMILARITY)).toInt()
        if (kotlin.math.abs(first.length - second.length) > allowedEdits) return false
        return editDistance(first, second, allowedEdits) <= allowedEdits
    }

    private fun comparableForm(value: String): String = value
        .lowercase(Locale.ROOT)
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun editDistance(left: String, right: String, cutoff: Int): Int {
        if (kotlin.math.abs(left.length - right.length) > cutoff) return cutoff + 1
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private const val MIN_SAME_CLAIM_SIMILARITY = 0.8

    /** Words that say "this is a wine producer" but not which one. */
    private val GENERIC_PRODUCER_WORDS = setOf(
        "domaine", "chateau", "bodega", "bodegas", "winery", "wineries", "cellar", "cellars",
        "estate", "estates", "vineyard", "vineyards", "wine", "wines", "maison", "casa", "weingut",
        "tenuta", "cantina", "vina", "family", "societe", "company", "co"
    )
}
