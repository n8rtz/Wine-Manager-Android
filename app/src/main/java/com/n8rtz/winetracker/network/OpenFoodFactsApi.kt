package com.n8rtz.winetracker.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface OpenFoodFactsApi {

    /** Direct product lookup by the barcode scanned off a bottle. */
    @GET("api/v2/product/{barcode}.json")
    suspend fun product(
        @Path("barcode") barcode: String,
        @Query("fields") fields: String = "code,product_name,product_name_en,generic_name,brands," +
            "quantity,countries,countries_tags,categories_tags,image_front_url"
    ): OpenFoodFactsProductResponse
}
