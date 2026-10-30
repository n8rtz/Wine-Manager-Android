package com.yourapp.wine.gliner

import android.graphics.PointF
import android.graphics.RectF
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.TextNorm

/**
 * Stage 1b: one text sequence out of two engines.
 *
 * ML Kit Latin and PP-OCRv6 read the same decoded label. GLiNER needs a single word sequence, so
 * the readings are fused conservatively: two readings become one line only when they describe the
 * same piece of the label (near-identical text at the same place, or a strong box overlap with a
 * close spelling). Everything else is kept, because duplicated or garbled fragments are exactly
 * what the contextual stage is supposed to resolve — pre-filtering them here would remove the
 * evidence GLiNER needs.
 *
 * A fused line keeps each engine's own score in [GlinerOcrLine.engines]; that map is what lets the
 * downstream records still count two independent engines, the same way the production ensemble does.
 */
internal object GlinerFusion {
    /** One engine's reading of one line, in normalized rectified-label coordinates. */
    data class Reading(
        val engineId:String,
        val text:String,
        val box:RectF,
        val quad:List<PointF>,
        val score:Float,
    )

    private const val SAME_TEXT_IOU=0.20f
    private const val SAME_TEXT_DISTANCE=0.08f
    private const val DIFFERENT_TEXT_IOU=0.50f
    private const val DIFFERENT_TEXT_SIMILARITY=0.85

    fun fuse(readings:List<Reading>):List<GlinerOcrLine> {
        val usable=readings.mapNotNull { reading ->
            val text=TextNorm.scrub(reading.text).trim()
            if(text.isBlank() || reading.box.width()<=0f || reading.box.height()<=0f) null
            else reading.copy(text=text)
        }
        if(usable.isEmpty()) return emptyList()
        val parent=IntArray(usable.size) { it }
        fun root(index:Int):Int {
            var current=index
            while(parent[current]!=current) { parent[current]=parent[parent[current]];current=parent[current] }
            return current
        }
        for(left in usable.indices) for(right in left+1 until usable.size) {
            if(!sameLine(usable[left],usable[right])) continue
            val a=root(left);val b=root(right)
            if(a!=b) parent[b]=a
        }
        return usable.indices.groupBy { root(it) }.values.map { indexes ->
            val members=indexes.map { usable[it] }
            // An engine's score for a fused line is its best read of that line, not the sum.
            val engines=members.groupBy { it.engineId }
                .mapValues { (_,reads) -> reads.maxOf { it.score }.coerceIn(0.01f,0.99f) }
            // Prefer the read the engine was most confident about; a longer read breaks ties so
            // "Chateau Margaux" wins over a truncated "Chateau".
            val best=members.sortedWith(compareByDescending<Reading> { it.score }
                .thenByDescending { it.text.length }).first()
            val combined=1f-engines.values.fold(1f) { product,score -> product*(1f-score) }
            GlinerOcrLine(best.text,GlinerDocument.union(members.map { it.box }),best.quad,
                combined.coerceIn(0.01f,0.99f),engines)
        }.sortedWith(compareBy<GlinerOcrLine>({ it.box.top },{ it.box.left }))
    }

    /**
     * Same line? Equal normalized text at (almost) the same place, or a strong overlap plus a
     * near-identical spelling. Two engines that disagree on the words keep both readings.
     */
    private fun sameLine(left:Reading,right:Reading):Boolean {
        val iou=BoxMath.iou(left.box,right.box)
        val distance=BoxMath.centerDistance(left.box,right.box)
        val leftText=TextNorm.normalize(left.text)
        val rightText=TextNorm.normalize(right.text)
        if(leftText==rightText) return iou>=SAME_TEXT_IOU || distance<=SAME_TEXT_DISTANCE
        if(iou<DIFFERENT_TEXT_IOU) return false
        return TextNorm.jaroWinkler(leftText,rightText)>=DIFFERENT_TEXT_SIMILARITY
    }
}
