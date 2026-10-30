package com.yourapp.wine.gliner

import android.graphics.PointF
import android.graphics.RectF
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord

/** One observation handed to the existing Lab v7 post-processing, tagged with the lane it came from. */
internal data class GlinerLaneObservation(
    val lane:String,
    val text:String,
    val score:Float,
    val box:RectF,
    val lines:Int,
)

/**
 * Bridge between the GLiNER stage and the existing post-processing.
 *
 * A label is not re-OCR'd: each accepted lane fragment becomes one observation whose text is the
 * grouped fragment and whose box is the union of the OCR lines it came from. Words that no lane
 * claimed stay as one observation per OCR line, so the vintage normalizer and the geography
 * hierarchy still see the raw residual evidence (the three lanes intentionally do not cover
 * vintage, country, region or subregion).
 *
 * Every record uses the same engine id, `paddle-gliner-v1`. Its family prefix keeps the existing
 * independent-engine rules honest: lane text and residual text are two views of one OCR pass and
 * must never count as two supporting engines.
 */
internal object GlinerLaneRecords {
    const val ENGINE="paddle-gliner-v1"

    fun observations(result:GlinerLabelResult):List<GlinerLaneObservation> {
        val byLine=result.document.lines.withIndex().associate { (index,line) -> index to line }
        val laneRows=result.entities.map { entity ->
            val posteriors=entity.lineIndices.mapNotNull { byLine[it]?.posterior }.ifEmpty { listOf(entity.score) }
            GlinerLaneObservation(entity.lane.key,entity.text,
                posteriors.average().toFloat().coerceIn(0.01f,0.99f),entity.box,entity.lineIndices.size)
        }
        val residualRows=result.residualLines.map { residual ->
            val line=byLine[residual.lineIndex]
            GlinerLaneObservation("residual",residual.text,(line?.posterior ?: 0.5f).coerceIn(0.01f,0.99f),
                residual.box,1)
        }
        return (laneRows+residualRows).sortedWith(compareBy({ it.box.top },{ it.box.left }))
    }

    fun records(result:GlinerLabelResult):List<TokenRecord> = observations(result).map { observation ->
        val text=TextNorm.scrub(observation.text).trim()
        TokenRecord(
            textRaw=text,
            textNorm=TextNorm.normalize(text),
            score=observation.score,
            bboxOrig=RectF(observation.box),
            bboxRect=RectF(observation.box),
            polyOrig=emptyList<PointF>(),
            engineId=ENGINE,
            variantId=if(observation.lane=="residual") "residual" else "lane:${observation.lane}",
            // CTC posteriors are uncalibrated, so no lane observation may establish GREEN.
            scoreIsEstimated=true,
        )
    }.filter { it.textRaw.isNotBlank() }
}
