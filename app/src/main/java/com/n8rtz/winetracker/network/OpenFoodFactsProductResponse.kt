package com.n8rtz.winetracker.network

/**
 * Open Food Facts product payload for `api/v2/product/{barcode}.json`.
 *
 * Plain data classes without Retrofit/Android imports so the mapping into wine fields can be unit
 * tested on the JVM. Only the fields the scanner uses are modelled.
 */
data class OpenFoodFactsProductResponse(
    val status: Int? = null,
    val product: OpenFoodFactsProductDetail? = null
)

data class OpenFoodFactsProductDetail(
    val code: String? = null,
    val product_name: String? = null,
    val product_name_en: String? = null,
    val generic_name: String? = null,
    val brands: String? = null,
    val quantity: String? = null,
    val countries: String? = null,
    val countries_tags: List<String>? = null,
    val categories_tags: List<String>? = null,
    val image_front_url: String? = null
)
