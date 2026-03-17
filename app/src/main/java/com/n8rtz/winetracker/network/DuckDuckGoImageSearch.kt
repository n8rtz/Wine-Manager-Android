package com.n8rtz.winetracker.network

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
                val token = fetchVqdToken(encoded)
                    ?: return@withContext emptyList()

                // Step 2: Query the image API
                val url = "https://duckduckgo.com/i.js" +
                    "?l=us-en" +
                    "&o=json" +
                    "&q=$encoded" +
                    "&vqd=$token" +
                    "&f=size:Medium" +
                    "&p=1"

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty(
                    "User-Agent",
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
            val url = "https://duckduckgo.com/?q=$encodedQuery&iax=images&ia=images"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty(
                "User-Agent",
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