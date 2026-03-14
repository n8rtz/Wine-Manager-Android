package com.n8rtz.winetracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class WineImageBatchProcessor(
    private val openFoodFactsApi: OpenFoodFactsApi,
    private val wineDao: WineDao,
    private val context: Context
) {
    // Control concurrency — don't hammer the API
    private val semaphore = Semaphore(5)  // 5 parallel requests max

    suspend fun processImport(
        wines: List<WineDefinition>,
        onProgress: (completed: Int, total: Int) -> Unit
    ) = coroutineScope {
        val total = wines.size
        val completed = AtomicInteger(0)
        
        // Process in parallel with controlled concurrency
        wines.map { wine ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        fetchSingleWineImage(wine)
                    } catch (e: Exception) {
                        Log.e("WINE_SYNC", "Failed to process ${wine.name}", e)
                    } finally {
                        val done = completed.incrementAndGet()
                        onProgress(done, total)
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchSingleWineImage(wine: WineDefinition) {
        Log.d("WINE_SYNC", "Processing: ${wine.maker} ${wine.name}")

        val query = "${wine.maker} ${wine.name}"
        
        // Step 1: One API call, get candidates with metadata
        val response = openFoodFactsApi.search(
            query = query,
            pageSize = 5
        )
        
        val candidates = response.products
            ?.mapNotNull { product ->
                val url = product.image_front_url
                    ?: product.image_url
                    ?: return@mapNotNull null
                
                // Use the large version URL if available
                val largeUrl = url.replace(".400.", ".full.")
                    .replace(".200.", ".full.")
                
                // Safely extract dimensions from the metadata
                val frontImage = product.images?.front
                
                MetadataScorer.Candidate(
                    imageUrl = largeUrl,
                    width = frontImage?.width,
                    height = frontImage?.height,
                    productCompleteness = product.completeness,
                    source = product.creator
                )
            } ?: emptyList()

        Log.d("WINE_SYNC", "OFF found ${candidates.size} candidates")
            
        if (candidates.isEmpty()) return
            
        // Step 2: Score using metadata ONLY (instant, no downloads)
        Log.d("WINE_SYNC", "Falling back to DuckDuckGo for ${wine.name}")
        // Log.d("WINE_SYNC", "Comparing OFF score (${offResult?.whitespaceScore}) vs DDG score (${ddgResult?.whitespaceScore})")

        val best = candidates
            .map { it to MetadataScorer.score(it) }
            .maxByOrNull { it.second }
            ?.first
            ?: return
            
        // Step 3: Quick validation — just check headers, don't 
        // download the whole image
        val isValid = validateImageHeaders(best.imageUrl)
        if (!isValid) return
        
        // Step 4: Cache the URL
        wineDao.updateImageUrl(wine.definitionId, best.imageUrl)
        Log.d("WINE_SYNC", "Successfully saved image for ${wine.name}")
    }

    private suspend fun validateImageHeaders(url: String): Boolean {
        // HEAD request only — no body downloaded
        // Just verify it's a real image that still exists
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val contentType = connection.contentType ?: ""
            val contentLength = connection.contentLength
            connection.disconnect()
            
            val valid = contentType.startsWith("image/") &&
                contentLength > 10_000  // At least 10KB (not a tiny icon)
            
            if (!valid) {
                Log.e("WINE_SYNC", "Header validation failed for $url")
            }
            
            valid
        } catch (e: Exception) {
            Log.e("WINE_SYNC", "Header validation failed for $url", e)
            false
        }
    }
}
