package com.yourapp.wine.textlab

import android.graphics.RectF
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.merge.SpatialPhraseMerger
import com.yourapp.wine.ocr.TokenRecord

/** Step 1 adapter, enabled only by the benchmark Lab selection; no metadata subtraction.
 * Input must be mapped tokens for ONE image. Rectified normalized boxes are used exclusively.
 * Engine/pass grouping prevents alternative readings from being concatenated or counted as votes.
 */
internal object TextLabSpatialPhrases {
    data class Proposal(val engineId:String,val variantId:String,val phrase:SpatialPhraseMerger.Phrase,val sources:List<TokenRecord>)
    /** Replace each group with one reading, never append a synthetic extra vote to its sources. */
    fun readings(proposals:List<Proposal>):List<TokenRecord> = proposals.map { proposal ->
        val sources=proposal.sources
        if(sources.size==1) sources.single() else {
            fun envelope(boxes:List<RectF>)=RectF(boxes.minOf { it.left },boxes.minOf { it.top },boxes.maxOf { it.right },boxes.maxOf { it.bottom })
            sources.first().copy(textRaw=proposal.phrase.mergedText,textNorm=TextNorm.normalize(proposal.phrase.mergedText),
                score=sources.minOf { it.score },scoreIsEstimated=true,
                bboxRect=envelope(sources.map { it.bboxRect }),bboxOrig=envelope(sources.map { it.bboxOrig }),polyOrig=emptyList())
        }
    }
    fun propose(records:List<TokenRecord>):List<Proposal> = records.groupBy { it.engineId to it.variantId }
        .flatMap { (key,group) ->
            val detections=group.map { token ->
                val box=token.bboxRect
                SpatialPhraseMerger.Detection(token.textRaw,SpatialPhraseMerger.Box(box.left.toDouble(),box.top.toDouble(),box.right.toDouble(),box.bottom.toDouble()))
            }
            SpatialPhraseMerger.merge(detections).map { phrase -> Proposal(key.first,key.second,phrase,phrase.sourceIndices.map { group[it] }) }
        }
}
