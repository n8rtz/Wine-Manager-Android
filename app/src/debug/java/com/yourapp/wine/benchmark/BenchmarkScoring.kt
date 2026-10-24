package com.yourapp.wine.benchmark

import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.FillResult
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

/** Evaluation only. Expected text is never passed to preprocessing, engines, merger or extractor. */
object BenchmarkScoring {
    data class Score(val exactFields: Int, val checkedFields: Int, val rawNameSimilarity: Double?)
    private fun normalized(s: String) = TextNorm.normalize(s).replace(Regex("[^\\p{L}\\p{N}]+"), "")
    fun score(expected: Map<FieldId,String>, fill: FillResult, tokens: List<TokenRecord>): Score {
        val known=expected.filterValues { it.isNotBlank() }
        val exact=known.count { (field,value) -> normalized(fill.fields.getValue(field).value)==normalized(value) }
        val name=known[FieldId.WINE_NAME]?.let(::normalized)
        // Best single/adjacent (up to three) raw OCR lines, separately per pass and engine.
        // This is explicitly a best-span metric, not full-label CER or independent corroboration.
        val spans=tokens.groupBy { it.engineId to it.variantId }.values.flatMap { reads ->
            val lines=reads.sortedWith(compareBy<TokenRecord> { it.bboxRect.centerY() }.thenBy { it.bboxRect.left })
            lines.indices.flatMap { start -> (1..3).mapNotNull { n ->
                if (start+n>lines.size) null else normalized(lines.subList(start,start+n).joinToString(" ") { it.textRaw })
            } }
        }
        val similarity=name?.let { target -> spans.maxOfOrNull { text ->
            1.0-distance(target,text).toDouble()/maxOf(target.length,text.length,1)
        } ?: 0.0 }
        return Score(exact,known.size,similarity)
    }
    internal fun distance(a:String,b:String):Int {
        var previous=IntArray(b.length+1) { it }
        a.forEachIndexed { i,c ->
            val next=IntArray(b.length+1);next[0]=i+1
            b.forEachIndexed { j,d -> next[j+1]=minOf(next[j]+1,previous[j+1]+1,previous[j]+if(c==d) 0 else 1) }
            previous=next
        }
        return previous[b.length]
    }
}
