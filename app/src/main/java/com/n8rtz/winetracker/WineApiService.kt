package com.n8rtz.winetracker

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface WineApiService {
    @GET("customsearch/v1")
    suspend fun searchImage(
        @Query("q") query: String,
        @Query("searchType") searchType: String = "image",
        @Query("num") num: Int = 1,
        @Query("key") apiKey: String,
        @Query("cx") cx: String
    ): GoogleSearchResponse

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/"

        fun create(): WineApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(WineApiService::class.java)
        }
    }
}

data class GoogleSearchResponse(
    val items: List<SearchItem>?
)

data class SearchItem(
    val link: String
)

class WineImageFetcher {
    private val apiService = WineApiService.create()
    
    // Placeholder values - user will plug in their own later
    private val API_KEY = "YOUR_GOOGLE_API_KEY"
    private val SEARCH_ENGINE_ID = "YOUR_CUSTOM_SEARCH_ENGINE_ID"

    suspend fun fetchImageUrl(maker: String, name: String, vintage: String): String? {
        // Mocking the API call for now as requested
        // return null 
        
        // Example of how the real call would look:
        /*
        return try {
            val query = "$maker $name $vintage wine bottle"
            val response = apiService.searchImage(query, apiKey = API_KEY, cx = SEARCH_ENGINE_ID)
            response.items?.firstOrNull()?.link
        } catch (e: Exception) {
            null
        }
        */
        
        // Returning null for now to simulate "not found" or "no key"
        return null
    }
}
