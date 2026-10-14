package com.yourapp.wine.merge

import com.yourapp.wine.ocr.MergedToken
import com.yourapp.wine.ocr.TokenRecord
import java.text.Normalizer

class TokenMerger {
    /** Union-find over normalized rectified boxes; original spellings and capture boxes are retained. */
    fun merge(records: List<TokenRecord>): List<MergedToken> {
        val tokens = records.filter { it.textNorm.isNotBlank() && it.score.isFinite() &&
            it.bboxRect.width() > 0f && it.bboxRect.height() > 0f }
        val parent = IntArray(tokens.size) { it }
        val rank = IntArray(tokens.size)
        fun root(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }
        fun union(a: Int, b: Int) {
            var ra = root(a); var rb = root(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) { val swap = ra; ra = rb; rb = swap }
            parent[rb] = ra
            if (rank[ra] == rank[rb]) rank[ra]++
        }
        for (i in tokens.indices) for (j in i + 1 until tokens.size) {
            val a = tokens[i]; val b = tokens[j]
            val spatial = BoxMath.iou(a.bboxRect, b.bboxRect) >= 0.25f ||
                BoxMath.centerDistance(a.bboxRect, b.bboxRect) <= 0.06f
            if (!spatial) continue
            val contains = minOf(a.textNorm.length, b.textNorm.length) >= 3 &&
                (a.textNorm.contains(b.textNorm) || b.textNorm.contains(a.textNorm))
            if (contains || TextNorm.jaroWinkler(a.textNorm, b.textNorm) >= 0.88) union(i, j)
        }
        return tokens.indices.groupBy { root(it) }.values.mapNotNull { indexes ->
            val members = indexes.map { tokens[it] }
            val engineMax = members.groupBy { it.engineId }
                .mapValues { (_, reads) -> reads.maxOf { it.score.coerceIn(0f, 1f) } }
            // A repeated read by one engine is still a singleton, not independent corroboration.
            if (engineMax.size == 1) {
                val threshold = if (engineMax.keys.single().startsWith("paddle")) 0.75f else 0.60f
                if (engineMax.values.single() < threshold) return@mapNotNull null
            }
            val best = members.sortedWith(compareByDescending<TokenRecord> { it.score }
                .thenByDescending { diacriticCount(it.textRaw) }.thenByDescending { it.textRaw.length }).first()
            val vote = 1f - engineMax.values.fold(1f) { product, score -> product * (1f - score) }
            if (vote < .60f) return@mapNotNull null // weak multi-engine clusters are not acceptable candidates either
            MergedToken(best.textRaw, members, vote, BoxMath.union(members.map { it.bboxRect }))
        }.sortedWith(compareBy<MergedToken> { it.bboxRect.centerY() }.thenBy { it.bboxRect.left })
    }

    private fun diacriticCount(text: String): Int =
        Normalizer.normalize(text, Normalizer.Form.NFD).count { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
}
