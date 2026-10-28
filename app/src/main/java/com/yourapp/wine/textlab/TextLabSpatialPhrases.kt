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
            val merged=TextNorm.scrub(proposal.phrase.mergedText)
            sources.first().copy(textRaw=merged,textNorm=TextNorm.normalize(merged),
                score=sources.minOf { it.score },scoreIsEstimated=true,
                bboxRect=envelope(sources.map { it.bboxRect }),bboxOrig=envelope(sources.map { it.bboxOrig }),polyOrig=emptyList())
        }
    }
    /**
     * Joins are disabled unless the Lab caller supplies a role-aware producer policy. Even when
     * permitted, vertical spans are at most two lines; wine title/appellation pairs belong to the
     * evidence graph, not to a paragraph-sized producer lookup span.
     */
    fun propose(
        records:List<TokenRecord>,
        allowHorizontalJoin:(leftText:String,rightText:String)->Boolean={ _,_ -> false },
        allowVerticalJoin:(upperText:String,lowerText:String)->Boolean={ _,_ -> false },
    ):List<Proposal> = records.groupBy { it.engineId to it.variantId }
        .flatMap { (key,group) ->
            val horizontalDecisions=mutableMapOf<Pair<String,String>,Boolean>()
            val verticalDecisions=mutableMapOf<Pair<String,String>,Boolean>()
            fun horizontalAllowed(left:String,right:String)=horizontalDecisions.getOrPut(left to right) { allowHorizontalJoin(left,right) }
            fun verticalAllowed(upper:String,lower:String)=verticalDecisions.getOrPut(upper to lower) { allowVerticalJoin(upper,lower) }
            val detections=group.map { token ->
                val box=token.bboxRect
                SpatialPhraseMerger.Detection(token.textRaw,SpatialPhraseMerger.Box(box.left.toDouble(),box.top.toDouble(),box.right.toDouble(),box.bottom.toDouble()))
            }
            SpatialPhraseMerger.merge(
                detections,
                maxVerticalLines=2,
                maxVerticalHeightRatio=1.45,
                allowHorizontalJoin={ left,right -> horizontalAllowed(left,right) },
                allowVerticalJoin={ upper,lower -> verticalAllowed(upper,lower) },
            ).map { phrase -> Proposal(key.first,key.second,phrase,phrase.sourceIndices.map { group[it] }) }
        }

    /** Best-V5 baseline spatial merger without vertical-line count, height-ratio, or role gates. */
    fun proposeUnconstrained(records:List<TokenRecord>):List<Proposal> = records.groupBy { it.engineId to it.variantId }
        .flatMap { (key,group) ->
            val detections=group.map { token ->
                val box=token.bboxRect
                SpatialPhraseMerger.Detection(token.textRaw,SpatialPhraseMerger.Box(box.left.toDouble(),box.top.toDouble(),box.right.toDouble(),box.bottom.toDouble()))
            }
            SpatialPhraseMerger.merge(
                detections,
                maxVerticalLines=Int.MAX_VALUE,
                maxVerticalHeightRatio=Double.POSITIVE_INFINITY,
                allowHorizontalJoin={ _,_ -> true },
                allowVerticalJoin={ _,_ -> true },
            ).map { phrase -> Proposal(key.first,key.second,phrase,phrase.sourceIndices.map { group[it] }) }
        }
}
