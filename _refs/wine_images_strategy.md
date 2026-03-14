# Feature: Metadata-First Image Fetching (Multi-Source Strategy)

## 1. Redesigned Pipeline: Multi-Source Fallback Strategy
**New approach per wine:**
├── 1. Check Room cache → already have URL? Done.
├── 2. Open Food Facts (no key, unlimited)
│      Query: "Penfolds Koonunga Hill"
│      Returns: structured product data with images
│      Score using metadata → cache best URL
│      ✅ Found? Done.
├── 3. DuckDuckGo Images (no key, be polite)
│      Query: "Penfolds Koonunga Hill 2014 wine bottle"
│      Returns: image results from across the web
│      Score using metadata + source domain heuristics
│      Wine retailer source (vivino, wine.com)? Big bonus.
│      Portrait aspect ratio? Big bonus.
│      ✅ Found? Done.
├── 4. Nothing found → color-coded fallback background
│      Red wine    → #FFF5F5
│      White wine  → #FFFFF0
│      Rosé        → #FFF0F5
│      Sparkling   → #F5F5F0

---

## 2. DuckDuckGo Image Search API (No Key Needed)

```kotlin
// network/DuckDuckGoImageSearch.kt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DuckDuckGoImageSearch {

    /**
     * Searches DuckDuckGo for images. No API key needed.
     * Returns a list of image URLs sorted by relevance.
     *
     * This uses DDG's vqd token system:
     * 1. Hit the search page to get a vqd token
     * 2. Use the token to query their image API endpoint
     */
    suspend fun search(query: String, maxResults: Int = 5): List<DdgImage> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")

                // Step 1: Get the vqd token
                val token = fetchVqdToken(encoded) ?: return@withContext emptyList()

                // Step 2: Query the image API
                val url = "[https://duckduckgo.com/i.js](https://duckduckgo.com/i.js)" +
                    "?l=us-en" +
                    "&o=json" +
                    "&q=$encoded" +
                    "&vqd=$token" +
                    "&f=size:Medium" +
                    "&p=1"

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"
                )
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                // Step 3: Parse results
                val json = JSONObject(body)
                val results = json.getJSONArray("results")

                val images = mutableListOf<DdgImage>()
                for (i in 0 until minOf(results.length(), maxResults)) {
                    val item = results.getJSONObject(i)
                    images.add(
                        DdgImage(
                            imageUrl = item.getString("image"),
                            width = item.optInt("width", 0),
                            height = item.optInt("height", 0),
                            source = item.optString("source", ""),
                            title = item.optString("title", "")
                        )
                    )
                }

                images
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * DDG requires a "vqd" token for image search.
     * We get it by hitting the main search page first.
     */
    private fun fetchVqdToken(encodedQuery: String): String? {
        return try {
            val url = "[https://duckduckgo.com/?q=$encodedQuery&iax=images&ia=images](https://duckduckgo.com/?q=$encodedQuery&iax=images&ia=images)"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
            )
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Extract vqd token from the page
            val regex = Regex("""vqd=["']?([\d-]+)""")
            regex.find(body)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}

data class DdgImage(
    val imageUrl: String,
    val width: Int,
    val height: Int,
    val source: String,
    val title: String
)
```

---

## 3. The Fast Scoring Engine (Updated for Dual-Source)

```kotlin
// MetadataScorer.kt
// Scores candidates WITHOUT downloading the image
object MetadataScorer {
    data class Candidate(
        val imageUrl: String,
        val width: Int?,
        val height: Int?,
        val productCompleteness: Double? = null,  // OFF provides this
        val source: String? = null                // URL source domain from DDG
    )

    fun score(candidate: Candidate): Double {
        var score = 0.0

        // 1. Aspect ratio from metadata dimensions
        if (candidate.width != null && candidate.height != null && candidate.height > 0) {
            val ratio = candidate.width.toDouble() / candidate.height
            score += when {
                ratio in 0.30..0.55 -> 35.0  // Ideal Portrait wine bottle
                ratio in 0.25..0.70 -> 15.0  // Acceptable Portrait
                else -> -20.0                // Landscape = bad
            }

            // 2. Resolution from metadata
            val pixels = candidate.width * candidate.height
            score += when {
                pixels > 500_000 -> 20.0
                pixels > 100_000 -> 10.0
                pixels > 10_000  -> 5.0
                else -> -10.0                 // Thumbnail
            }
        }

        // 3. URL heuristics (surprisingly effective)
        val url = candidate.imageUrl.lowercase()
        score += urlScore(url)

        // 4. Source domain heuristics (DuckDuckGo heavily relies on this)
        candidate.source?.lowercase()?.let { src ->
            if ("vivino" in src) score += 40.0
            if ("wine.com" in src) score += 35.0
            if ("totalwine" in src) score += 30.0
            if ("cellartracker" in src) score += 20.0
            if ("producer" in src) score += 15.0 // For Open Food Facts
        }

        // 5. Product completeness (Open Food Facts specific)
        candidate.productCompleteness?.let {
            score += (it / 100.0) * 10.0
        }
        return score
    }

    private fun urlScore(url: String): Double {
        var s = 0.0
        // Stock photo indicators
        if ("front" in url) s += 10.0
        if ("bottle" in url) s += 5.0
        if ("full" in url) s += 5.0
        if ("large" in url || "400" in url) s += 5.0
        
        // Bad indicators
        if ("thumb" in url) s -= 15.0
        if ("small" in url) s -= 10.0
        if ("user" in url) s -= 5.0
        if ("nutrition" in url) s -= 20.0
        if ("ingredients" in url) s -= 20.0
        if (".100." in url) s -= 10.0  // 100px thumbnail
        return s
    }
}
```

---

## 4. The Batch Import Engine (Multi-Step Pipeline)

```kotlin
// WineImageBatchProcessor.kt
class WineImageBatchProcessor(
    private val openFoodFactsApi: OpenFoodFactsApi,
    private val wineDao: WineDao,
    private val context: Context
) {
    // Control concurrency — don't hammer the APIs
    private val semaphore = Semaphore(5)

    suspend fun processImport(
        wines: List<WineDefinition>,
        onProgress: (completed: Int, total: Int) -> Unit
    ) = coroutineScope {
        val total = wines.size
        val completed = AtomicInteger(0)
        
        // Skip wines that already have images
        val needImages = wines.filter { it.imageUrl == null }
        
        // Process in parallel with controlled concurrency
        needImages.map { wine ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        fetchSingleWineImage(wine)
                    } catch (e: Exception) {
                        // Log and continue
                    } finally {
                        val done = completed.incrementAndGet()
                        onProgress(done, total)
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchSingleWineImage(wine: WineDefinition) {
        // Step 1: Open Food Facts (Best for barcodes, OK for text)
        var success = tryOpenFoodFacts(wine)
        
        // Step 2: DuckDuckGo Fallback (Incredible for text search, hits Vivino/Wine.com)
        if (!success) {
            success = tryDuckDuckGo(wine)
        }
    }

    private suspend fun tryOpenFoodFacts(wine: WineDefinition): Boolean {
        val query = "${wine.maker} ${wine.name}"
        val response = try {
            openFoodFactsApi.searchWine(searchTerms = query, pageSize = 3)
        } catch (e: Exception) { return false }
        
        val candidates = response.products.mapNotNull { product ->
            val url = product.image_front_url ?: product.image_url ?: return@mapNotNull null
            val largeUrl = url.replace(".400.", ".full.").replace(".200.", ".full.")
            
            val firstSize = product.images?.get("front")?.sizes?.values?.firstOrNull()
            MetadataScorer.Candidate(
                imageUrl = largeUrl,
                width = firstSize?.w,
                height = firstSize?.h,
                productCompleteness = product.completeness,
                source = "producer"
            )
        }
            
        return scoreAndSave(wine.definitionId, candidates)
    }

    private suspend fun tryDuckDuckGo(wine: WineDefinition): Boolean {
        // DDG is smart, adding "wine bottle" prevents it from finding pictures of literal vineyards
        val query = "${wine.maker} ${wine.name} wine bottle"
        val results = DuckDuckGoImageSearch.search(query, maxResults = 8)
        
        val candidates = results.map { img ->
            MetadataScorer.Candidate(
                imageUrl = img.imageUrl,
                width = img.width,
                height = img.height,
                source = img.source // Crucial: contains domains like vivino.com
            )
        }
        
        return scoreAndSave(wine.definitionId, candidates)
    }

    private suspend fun scoreAndSave(definitionId: Long, candidates: List<MetadataScorer.Candidate>): Boolean {
        if (candidates.isEmpty()) return false
            
        val best = candidates
            .map { it to MetadataScorer.score(it) }
            .filter { it.second > 0 } // Must pass minimum threshold
            .maxByOrNull { it.second }
            ?.first
            ?: return false
            
        val isValid = validateImageHeaders(best.imageUrl)
        if (!isValid) return false
        
        wineDao.updateImageUrl(definitionId, best.imageUrl)
        return true
    }

    private suspend fun validateImageHeaders(url: String): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val contentType = connection.contentType ?: ""
            val contentLength = connection.contentLength
            connection.disconnect()
            
            contentType.startsWith("image/") && contentLength > 10_000
        } catch (e: Exception) {
            false
        }
    }
}
```

---

## 5. The Key Insight: Don't Download Images During Import
**RIGHT approach:**
├── Fetch IMAGE URLS (metadata only) ← Fast, ~2 min
├── Cache URLs in Room database          ← Instant
├── Images load lazily when cards scroll ← Coil handles this
│
**Coil already:**
├── Loads on demand as user scrolls
├── Caches in memory and disk
├── Shows fallback color during loading or failure
├── Never loads off-screen images

---

## 6. The UI Integration

```kotlin
// MainActivity.kt (Inside WineCard composable)
// Handles Step 4 of Pipeline (Color fallback)
val fallbackColor = when (type.lowercase()) {
    "red" -> Color(0xFFFFF5F5)
    "white" -> Color(0xFFFFFFF0)
    "rosé", "rose" -> Color(0xFFFFF0F5)
    "sparkling" -> Color(0xFFF5F5F0)
    else -> Color(0xFFF8F9FA)
}

Box(
    modifier = Modifier
        .size(60.dp, 80.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(fallbackColor),
    contentAlignment = Alignment.Center
) {
    if (imageUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Icon(
            imageVector = Icons.Default.WineBar,
            contentDescription = null,
            tint = Color.Gray.copy(alpha = 0.5f)
        )
    }
}
```