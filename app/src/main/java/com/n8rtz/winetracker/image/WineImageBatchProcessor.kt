package com.n8rtz.winetracker.image

import android.content.Context
import com.n8rtz.winetracker.MetadataScorer
import com.n8rtz.winetracker.WineDao
import com.n8rtz.winetracker.WineDefinition
import com.n8rtz.winetracker.network.DuckDuckGoImageSearch
import com.n8rtz.winetracker.network.OpenFoodFactsApi
import kotlinx.coroutines.*
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
    private val whitespaceAnalyzer = WhitespaceAnalyzer(context)

    // Be respectful: max 3 parallel for DDG, 5 for OFF
    private val ddgSemaphore = Semaphore(3)
    private val offSemaphore = Semaphore(5)

    // Small delay between DDG requests to avoid rate limiting
    private val ddgDelayMs = 500L

    suspend fun processImport(
        wines: List<WineDefinition>,
        onProgress: (completed: Int, total: Int) -> Unit
    ) = coroutineScope {

        val needImages = wines.filter { it.imageUrl == null }
        val total = needImages.size
        val completed = AtomicInteger(0)

        needImages.map { wine ->
            async(Dispatchers.IO) {
                try {
                    fetchBestWineImage(wine)
                } catch (e: Exception) {
                    // Never kill the batch for one failure
                } finally {
                    val done = completed.incrementAndGet()
                    onProgress(done, total)
                }
            }
        }.awaitAll()
    }

    /**
     * Master logic for a single wine. Tries sources in order,
     * uses whitespace analysis to pick the most stock-like image.
     */
    private suspend fun fetchBestWineImage(wine: WineDefinition) {
        val query = "${wine.maker} ${wine.name}"

        // ----------------------------------------------------------
        // Step 1: Try Open Food Facts
        // ----------------------------------------------------------
        var offResult: WhitespaceResult? = null

        offSemaphore.withPermit {
            val offBestUrl = metadataPickBest(
                tryOpenFoodFactsCandidates(query)
            )

            if (offBestUrl != null) {
                offResult = whitespaceAnalyzer.analyze(offBestUrl, context)

                // FAST PATH: If OFF image has great whitespace, use it immediately.
                // No need to query DDG at all — saves time and network.
                if (offResult?.passesThreshold == true) {
                    wineDao.updateImageUrl(wine.definitionId, offBestUrl)
                    return
                }
            }
        }

        // ----------------------------------------------------------
        // Step 2: OFF either failed or had poor whitespace.
        //         Try DuckDuckGo.
        // ----------------------------------------------------------
        var ddgResult: WhitespaceResult? = null

        ddgSemaphore.withPermit {
            delay(ddgDelayMs)  // Rate limit ourselves

            val ddgBestUrl = metadataPickBest(
                tryDuckDuckGoCandidates("$query wine bottle")
            )

            if (ddgBestUrl != null) {
                ddgResult = whitespaceAnalyzer.analyze(ddgBestUrl, context)
            }
        }

        // ----------------------------------------------------------
        // Step 3: Compare results from both sources.
        //         Pick whichever has more white background.
        // ----------------------------------------------------------
        val winner = pickWinner(offResult, ddgResult)

        if (winner != null) {
            wineDao.updateImageUrl(wine.definitionId, winner.url)
        }

        // If winner is null, imageUrl stays null → color fallback in UI
    }

    /**
     * Given whitespace results from two sources (either may be null),
     * picks the best one. Returns null if neither passes the absolute
     * minimum threshold.
     */
    private fun pickWinner(
        offResult: WhitespaceResult?,
        ddgResult: WhitespaceResult?
    ): WhitespaceResult? {
        val candidates = listOfNotNull(offResult, ddgResult)

        if (candidates.isEmpty()) return null

        // Pick the one with the highest whitespace score
        val best = candidates.maxByOrNull { it.whitespaceScore }!!

        // Even the best must pass the absolute minimum
        // (avoids saving images that are clearly not stock photos)
        return if (best.whitespaceScore >= WhitespaceResult.MINIMUM_THRESHOLD) {
            best
        } else {
            null
        }
    }

    // ---------------------------------------------------------------
    // Open Food Facts: get candidates with metadata
    // ---------------------------------------------------------------

    private suspend fun tryOpenFoodFactsCandidates(
        query: String
    ): List<MetadataScorer.Candidate> {
        val response = try {
            openFoodFactsApi.search(query = query, pageSize = 5)
        } catch (e: Exception) {
            return emptyList()
        }

        return response.products
            ?.mapNotNull { product ->
                val url = product.image_front_url
                    ?: product.image_url
                    ?: return@mapNotNull null

                val largeUrl = url
                    .replace(".400.", ".full.")
                    .replace(".200.", ".full.")

                MetadataScorer.Candidate(
                    imageUrl = largeUrl,
                    width = product.images?.front?.width,
                    height = product.images?.front?.height,
                    productCompleteness = product.completeness,
                    source = "producer"
                )
            }
            ?: emptyList()
    }

    // ---------------------------------------------------------------
    // DuckDuckGo: get candidates with metadata
    // ---------------------------------------------------------------

    private suspend fun tryDuckDuckGoCandidates(
        query: String
    ): List<MetadataScorer.Candidate> {
        val results = DuckDuckGoImageSearch.search(query, maxResults = 5)

        return results.map { ddg ->
            MetadataScorer.Candidate(
                imageUrl = ddg.imageUrl,
                width = ddg.width,
                height = ddg.height,
                source = ddg.source,
                title = ddg.title
            )
        }
    }

    // ---------------------------------------------------------------
    // Shared: metadata-score candidates, pick the single best URL
    // ---------------------------------------------------------------

    /**
     * Scores candidates using metadata ONLY (instant, no downloads).
     * Returns the URL of the single best candidate, or null.
     * Also validates with a HEAD request to ensure the URL is live.
     */
    private suspend fun metadataPickBest(
        candidates: List<MetadataScorer.Candidate>
    ): String? {
        if (candidates.isEmpty()) return null

        val best = candidates
            .map { it to MetadataScorer.score(it) }
            .filter { it.second > 10.0 }      // Minimum metadata quality
            .maxByOrNull { it.second }
            ?.first
            ?: return null

        // HEAD request only — verify it's a real, reasonably-sized image
        val valid = validateImageHeaders(best.imageUrl)
        return if (valid) best.imageUrl else null
    }

    /**
     * Validates an image URL by sending a HEAD request.
     * Checks that the URL returns an actual image of reasonable size.
     * Does NOT download the image body.
     */
    private suspend fun validateImageHeaders(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url)
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = true

                val contentType = connection.contentType ?: ""
                val contentLength = connection.contentLength

                connection.disconnect()

                contentType.startsWith("image/") && contentLength > 10_000
            } catch (e: Exception) {
                false
            }
        }
    }
}