4. The Metadata Scoring Engine (No Downloads)
   Scores candidate images using only API response metadata, URL pattern
   heuristics, and source domain reputation. No bitmaps are downloaded or decoded.
   This narrows the field BEFORE any image is downloaded for whitespace analysis.

// image/MetadataScorer.kt

object MetadataScorer {

    data class Candidate(
        val imageUrl: String,
        val width: Int?,
        val height: Int?,
        val productCompleteness: Int? = null,
        val source: String? = null,
        val title: String? = null
    )

    fun score(candidate: Candidate): Double {
        var score = 0.0

        // --- Dimension scoring ---
        if (candidate.width != null && candidate.height != null
            && candidate.width > 0 && candidate.height > 0
        ) {
            // Aspect ratio: wine bottles are portrait, ideal width/height ~0.4-0.55
            val ratio = candidate.width.toDouble() / candidate.height
            score += when {
                ratio in 0.35..0.60 -> 30.0   // Perfect bottle shape
                ratio in 0.25..0.70 -> 15.0   // Acceptable
                ratio in 0.70..1.00 -> 5.0    // Squarish, probably cropped
                else -> 0.0                    // Landscape = wrong
            }

            // Resolution: more pixels = better quality
            val pixels = candidate.width * candidate.height
            score += when {
                pixels > 500_000 -> 20.0
                pixels > 100_000 -> 10.0
                pixels > 10_000  -> 5.0
                else -> 0.0                    // Tiny thumbnail
            }
        }

        // --- URL heuristics ---
        score += urlScore(candidate.imageUrl)

        // --- Source domain heuristics (DDG gives us the source site) ---
        score += sourceScore(candidate.source)

        // --- OFF-specific fields ---
        if (candidate.source == "producer") score += 15.0
        candidate.productCompleteness?.let {
            score += (it.toDouble() / 100.0) * 10.0
        }

        return score
    }

    private fun urlScore(url: String): Double {
        val lower = url.lowercase()
        var s = 0.0

        // Positive signals (stock-like)
        if ("front" in lower)        s += 10.0
        if ("full" in lower)         s += 5.0
        if ("large" in lower)        s += 5.0
        if ("product" in lower)      s += 5.0
        if ("bottle" in lower)       s += 5.0
        if ("400" in lower)          s += 3.0

        // Negative signals (user photos, thumbnails, wrong image type)
        if ("thumb" in lower)        s -= 15.0
        if ("small" in lower)        s -= 10.0
        if ("user" in lower)         s -= 5.0
        if ("nutrition" in lower)    s -= 20.0
        if ("ingredients" in lower)  s -= 20.0
        if ("icon" in lower)         s -= 15.0
        if ("logo" in lower)         s -= 15.0
        if ("avatar" in lower)       s -= 15.0
        if (".100." in lower)        s -= 10.0   // 100px thumbnail

        return s
    }

    /**
     * Score based on which website the image comes from.
     * Wine retailer sites almost always have clean product shots.
     * Social media and blogs almost always have user photos.
     */
    private fun sourceScore(source: String?): Double {
        if (source == null) return 0.0
        val lower = source.lowercase()

        return when {
            // Wine retailers = clean stock product shots
            "vivino" in lower        -> 25.0
            "wine.com" in lower      -> 25.0
            "wine-searcher" in lower -> 25.0
            "totalwine" in lower     -> 20.0
            "cellartracker" in lower -> 20.0
            "majestic" in lower      -> 20.0
            "decanter" in lower      -> 15.0
            "winefolly" in lower     -> 15.0
            "dan murphy" in lower    -> 20.0
            "laithwaites" in lower   -> 20.0
            "naked wines" in lower   -> 18.0

            // General retailers = decent product shots
            "amazon" in lower        -> 10.0
            "ebay" in lower          -> 5.0

            // User content = bad
            "reddit" in lower        -> -10.0
            "instagram" in lower     -> -10.0
            "facebook" in lower      -> -10.0
            "twitter" in lower       -> -10.0
            "pinterest" in lower     -> -5.0
            "flickr" in lower        -> -5.0
            "blog" in lower          -> -5.0

            else -> 0.0
        }
    }
}