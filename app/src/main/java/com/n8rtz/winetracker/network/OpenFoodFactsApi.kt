package com.n8rtz.winetracker.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface OpenFoodFactsApi {

    @GET("cgi/search.pl")
    suspend fun search(
        @Query("search_terms") query: String,
        @Query("search_simple") simple: Int = 1,
        @Query("action") action: String = "process",
        @Query("json") json: Int = 1,
        @Query("tagtype_0") tagType: String = "categories",
        @Query("tag_contains_0") tagContains: String = "contains",
        @Query("tag_0") tag: String = "wines",
        @Query("page_size") pageSize: Int = 5,
        @Query("fields") fields: String = "code,product_name,image_front_url,image_front_small_url,image_url,images,completeness,creator"
    ): OpenFoodFactsResponse

    /** Direct product lookup by the barcode scanned off a bottle. */
    @GET("api/v2/product/{barcode}.json")
    suspend fun product(
        @Path("barcode") barcode: String,
        @Query("fields") fields: String = "code,product_name,product_name_en,generic_name,brands," +
            "quantity,countries,countries_tags,categories_tags,image_front_url"
    ): OpenFoodFactsProductResponse
}

data class OpenFoodFactsResponse(
    val products: List<OpenFoodFactsProduct>?
)

data class OpenFoodFactsProduct(
    val code: String?,
    val product_name: String?,
    val image_front_url: String?,
    val image_front_small_url: String?,
    val image_url: String?,
    val images: ProductImages?,
    val completeness: Int?,
    val creator: String?
)

data class ProductImages(
    val front: ImageMeta?
)

data class ImageMeta(
    val width: Int?,
    val height: Int?,
    val uploader: String?
)