package com.yourapp.wine.textlab

import com.yourapp.wine.merge.SpatialPhraseMerger
import com.yourapp.wine.ocr.TokenRecord

/** Opt-in Step 1 adapter; NOT wired into production or metadata subtraction.
 * Input must be mapped tokens for ONE image. Rectified normalized boxes are used exclusively.
 * Engine/pass grouping prevents alternative readings from being concatenated or counted as votes.
 */
internal object TextLabSpatialPhrases {
    data class Proposal(val engineId:String,val variantId:String,val phrase:SpatialPhraseMerger.Phrase,val sources:List<TokenRecord>)
    fun propose(records:List<TokenRecord>):List<Proposal> = records.groupBy { it.engineId to it.variantId }
        .flatMap { (key,group) ->
            val detections=group.map { token ->
                val box=token.bboxRect
                SpatialPhraseMerger.Detection(token.textRaw,SpatialPhraseMerger.Box(box.left.toDouble(),box.top.toDouble(),box.right.toDouble(),box.bottom.toDouble()))
            }
            SpatialPhraseMerger.merge(detections).map { phrase -> Proposal(key.first,key.second,phrase,phrase.sourceIndices.map { group[it] }) }
        }
}
