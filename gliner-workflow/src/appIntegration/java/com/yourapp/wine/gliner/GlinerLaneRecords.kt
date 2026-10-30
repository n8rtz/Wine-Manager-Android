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
    /** Engine id to that engine's measured score for the underlying OCR lines. */
    val engines:Map<String,Float> = emptyMap(),
)

/**
 * Adapter for OCR evidence and diagnostic lane observations.
 *
 * Production of this experimental workflow uses [originalRecords]: it retains each independent
 * engine reading as-is for the base extraction. [observations] and the old [records] bridge are
 * retained only for diagnostics and historical host replays; the old bridge re-emits fused text
 * under several engine IDs and can remove lane words from the residual. It must not be used as
 * the post-processing input or interpreted as independent corroboration.
 */
internal object GlinerLaneRecords {
    /** Used only if a line somehow carries no engine attribution at all. */
    const val FALLBACK_ENGINE=GlinerEngines.PADDLE

    fun observations(result:GlinerLabelResult):List<GlinerLaneObservation> {
        val byLine=result.document.lines.withIndex().associate { (index,line) -> index to line }
        val laneRows=result.entities.map { entity ->
            val contributing=entity.lineIndices.mapNotNull { byLine[it] }
            val posteriors=contributing.map { it.posterior }.ifEmpty { listOf(entity.score) }
            GlinerLaneObservation(entity.lane.key,entity.text,
                posteriors.average().toFloat().coerceIn(0.01f,0.99f),entity.box,entity.lineIndices.size,
                enginesOf(contributing))
        }
        val residualRows=result.residualLines.map { residual ->
            val line=byLine[residual.lineIndex]
            GlinerLaneObservation("residual",residual.text,(line?.posterior ?: 0.5f).coerceIn(0.01f,0.99f),
                residual.box,1,line?.let { enginesOf(listOf(it)) }.orEmpty())
        }
        return (laneRows+residualRows).sortedWith(compareBy({ it.box.top },{ it.box.left }))
    }

    /** Weakest measured read per engine: a lane is never more certain than its worst line. */
    private fun enginesOf(lines:List<GlinerOcrLine>):Map<String,Float> {
        val scores=linkedMapOf<String,Float>()
        lines.forEach { line ->
            line.engines.forEach { (engine,score) ->
                if(!score.isFinite() || score<=0f) return@forEach
                val current=scores[engine]
                scores[engine]=if(current==null) score else minOf(current,score)
            }
        }
        return scores.toList().sortedBy { it.first }.toMap(LinkedHashMap())
    }

    /** Preserve each real OCR reading and its provenance; do not duplicate a fused winner per engine. */
    fun originalRecords(readings:List<GlinerFusion.Reading>):List<TokenRecord> = readings.mapNotNull { reading ->
        val text=TextNorm.scrub(reading.text).trim()
        if(text.isBlank() || !reading.score.isFinite() || reading.score !in 0f..1f ||
            reading.box.width()<=0f || reading.box.height()<=0f) return@mapNotNull null
        TokenRecord(textRaw=text,textNorm=TextNorm.normalize(text),score=reading.score,
            bboxOrig=RectF(reading.box),bboxRect=RectF(reading.box),polyOrig=reading.quad,
            engineId=reading.engineId,variantId=reading.variantId,scoreIsEstimated=reading.scoreIsEstimated)
    }

    /** Historical destructive bridge for reproducing pre-refactor host experiments only. */
    fun records(result:GlinerLabelResult):List<TokenRecord> = observations(result).flatMap { observation ->
        val text=TextNorm.scrub(observation.text).trim()
        if(text.isBlank()) return@flatMap emptyList()
        val variant=if(observation.lane=="residual") "residual" else "lane:${observation.lane}"
        val engines=observation.engines.ifEmpty { mapOf(FALLBACK_ENGINE to observation.score) }
        engines.map { (engine,score) ->
            TokenRecord(
                textRaw=text,
                textNorm=TextNorm.normalize(text),
                score=score.coerceIn(0.01f,0.99f),
                bboxOrig=RectF(observation.box),
                bboxRect=RectF(observation.box),
                polyOrig=emptyList<PointF>(),
                engineId=engine,
                variantId=variant,
                // Measured per-engine posterior of the underlying lines, not an invented score.
                scoreIsEstimated=false,
            )
        }
    }
}
