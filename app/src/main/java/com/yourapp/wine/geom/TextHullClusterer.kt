package com.yourapp.wine.geom

import android.graphics.RectF
import com.yourapp.wine.ocr.RawToken
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Spatial localization only. Selecting a weak box never accepts its text as field evidence. */
class TextHullClusterer {
    private data class Sample(val token: RawToken, val box: RectF)

    /** WORKING-pixel probe polygons in/out; requires a strong anchor but keeps aligned positive-score weak boxes. */
    fun select(tokens: List<RawToken>, workW: Int, workH: Int): List<RawToken> {
        require(workW > 0 && workH > 0)
        val samples = tokens.take(300).mapNotNull { token ->
            if (!token.score.isFinite() || token.score <= 0f || token.score > 1f || token.text.isBlank() ||
                token.polygon.size < 3 || token.polygon.any { !it.x.isFinite() || !it.y.isFinite() }) return@mapNotNull null
            val box = RectF(token.polygon.minOf { it.x }.coerceIn(0f, workW.toFloat()),
                token.polygon.minOf { it.y }.coerceIn(0f, workH.toFloat()),
                token.polygon.maxOf { it.x }.coerceIn(0f, workW.toFloat()),
                token.polygon.maxOf { it.y }.coerceIn(0f, workH.toFloat()))
            if (box.width() <= 2 || box.height() <= 2) null else Sample(token, box)
        }
        if (samples.size < 2) return emptyList()
        val parent = IntArray(samples.size) { it }
        fun root(index: Int): Int {
            var value = index
            while (parent[value] != value) { parent[value] = parent[parent[value]]; value = parent[value] }
            return value
        }
        val medianHeight = samples.map { it.box.height() }.sorted()[samples.size / 2]
        val maxVerticalGap = max(.18f * max(workW, workH), 5f * medianHeight)
        for (i in samples.indices) for (j in i + 1 until samples.size) {
            val a = samples[i].box; val b = samples[j].box
            val horizontalOverlap = max(0f, min(a.right, b.right) - max(a.left, b.left)) /
                min(a.width(), b.width())
            val verticalGap = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
            val verticalOverlap = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top)) /
                min(a.height(), b.height())
            val horizontalGap = max(0f, max(a.left, b.left) - min(a.right, b.right))
            // Use edge gaps, not center distances: a tall brand must connect to shorter lower label lines.
            val sameColumn = horizontalOverlap >= .4f && abs(a.centerX() - b.centerX()) <= .25f * workW &&
                verticalGap <= maxVerticalGap
            val sameRow = verticalOverlap >= .5f && horizontalGap <= min(.10f * workW, 3f * medianHeight)
            if (sameColumn || sameRow) parent[root(i)] = root(j)
        }
        val chosen = samples.indices.groupBy(::root).values.filter { group ->
            group.size >= 2 && group.any { samples[it].token.score >= .6f } &&
                group.sumOf { samples[it].token.text.count(Char::isLetterOrDigit) } >= 8
        }.maxByOrNull { group ->
            val ink = group.sumOf { (samples[it].box.width() * samples[it].box.height()).toDouble() }
            val centerX = (group.minOf { samples[it].box.left } + group.maxOf { samples[it].box.right }) / (2 * workW)
            val centerY = (group.minOf { samples[it].box.top } + group.maxOf { samples[it].box.bottom }) / (2 * workH)
            val offset = kotlin.math.hypot((centerX - .5f).toDouble(), (centerY - .5f).toDouble())
            ink * sqrt(group.size.toDouble()) * (1.0 - .5 * offset)
        } ?: return emptyList()
        val selected = chosen.map { samples[it] }
        val left = selected.minOf { it.box.left }; val right = selected.maxOf { it.box.right }
        val top = selected.minOf { it.box.top }; val bottom = selected.maxOf { it.box.bottom }
        // A crest/illustration can separate the title and footer by more than the clustering gap.
        // Do not silently discard a substantial aligned text box, even if its OCR score is weak.
        val omittedAlignedText = samples.indices.filter { it !in chosen }.any { index ->
            val other = samples[index]
            val overlap = max(0f, min(right, other.box.right) - max(left, other.box.left)) /
                min(right-left, other.box.width())
            other.token.text.count(Char::isLetter) >= 3 && other.box.width() >= .15f * workW &&
                other.box.height() >= .015f * workH && overlap >= .6f &&
                (other.box.bottom < top || other.box.top > bottom)
        }
        return if (omittedAlignedText) emptyList() else selected.map { it.token }
    }
}
