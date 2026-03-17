6. Open Food Facts API Service

// network/OpenFoodFactsApi.kt

import retrofit2.http.GET
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
        @Query("fields") fields: String = "product_name,image_front_url,image_url,images,completeness,creator"
    ): OpenFoodFactsResponse
}

data class OpenFoodFactsResponse(
val products: List<OpenFoodFactsProduct>?
)

data class OpenFoodFactsProduct(
val product_name: String?,
val image_front_url: String?,
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
val height: Int?
)


// network/RetrofitInstance.kt

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitInstance {

    val openFoodFactsApi: OpenFoodFactsApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://world.openfoodfacts.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenFoodFactsApi::class.java)
    }
}

7. The Key Insight: Minimal Downloads, Maximum Intelligence
   The pipeline downloads at most 2 images per wine (one from each source),
   and often only 1 (if the OFF image passes whitespace on the first try).
   All other scoring is done on metadata alone.

WRONG approach:
├── Import 200 wines
├── Download 1000+ candidate images ← SLOW
├── Decode 1000+ bitmaps ← MEMORY BOMB
├── Score 1000+ bitmaps ← CPU HEAVY

RIGHT approach:
├── Import 200 wines
├── Metadata-score 1000 candidates ← Instant, no downloads
├── Download 200-400 images (1-2 per wine, only top picks)
├── Whitespace-analyze on downsampled 200x300 thumbnails ← Fast
├── Cache winning URLs in Room ← Instant
├── Full-res images load lazily via Coil ← On-demand as user scrolls

Coil handles the display layer:
├── Loads on demand as user scrolls
├── Caches in memory and disk
├── Handles failures gracefully
├── Shows placeholder during loading
├── Never loads off-screen images

