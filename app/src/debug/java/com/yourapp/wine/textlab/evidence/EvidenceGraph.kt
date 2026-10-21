package com.yourapp.wine.textlab.evidence

import android.graphics.RectF
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.extract.WineDesignations

internal data class Observation(val id:Int,val record:TokenRecord)
internal data class Phrase(val id:Int,val text:String,val observations:Set<Int>,val box:RectF,val construction:String)
internal data class EvidenceGraph(val observations:List<Observation>,val phrases:List<Phrase>,val truncated:Boolean)

/** Alternative segmentations refer to unchanged observations. No canonical winner or score fusion. */
internal object EvidenceGraphBuilder {
    const val MAX_OBSERVATIONS=128
    const val MAX_PHRASES=384
    fun build(records:List<TokenRecord>,checkBudget:()->Unit = {}):EvidenceGraph {
        val observations=records.take(MAX_OBSERVATIONS).mapIndexedNotNull { i,r ->
            val b=r.bboxRect
            if(r.textRaw.isBlank() || r.textRaw.length>256 || !r.score.isFinite() || r.score !in 0f..1f ||
                b.width()<=0 || b.height()<=0 || listOf(b.left,b.top,b.right,b.bottom).any { !it.isFinite() || it !in 0f..1f }) null
            else Observation(i,r.copy(bboxRect=RectF(b),bboxOrig=RectF(r.bboxOrig)))
        }
        val phrases=mutableListOf<Phrase>()
        var truncated=records.size>MAX_OBSERVATIONS
        fun add(text:String,ids:Set<Int>,box:RectF,why:String) {
            if(phrases.size>=MAX_PHRASES) { truncated=true;return }
            if(text.length<=256 && phrases.none { it.observations==ids && TextNorm.normalize(it.text)==TextNorm.normalize(text) })
                phrases+=Phrase(phrases.size,text,ids,RectF(box),why)
        }
        observations.forEach { add(it.record.textRaw,setOf(it.id),it.record.bboxRect,"observed") }
        // Camel-case boundary is a proposed segmentation, never a rewrite of the original observation.
        observations.forEach { o ->
            val separated=o.record.textRaw.replace(Regex("(?<=[A-ZÀ-Ý])(?=(?:de|du|di)\\b)")," ")
            if(separated!=o.record.textRaw) add(separated,setOf(o.id),o.record.bboxRect,"case-boundary segmentation")
        }
        val singles=phrases.toList()
        for(a in singles) for(b in singles) {
            checkBudget()
            if(a.observations.intersect(b.observations).isNotEmpty()) continue
            val ak=TextNorm.normalize(a.text).filter(Char::isLetterOrDigit);val bk=TextNorm.normalize(b.text).filter(Char::isLetterOrDigit)
            if(ak==bk || (BoxMath.iou(a.box,b.box)>.25f && TextNorm.editDistance(ak,bk,2)!=null)) continue
            val horizontal=a.box.centerX()<b.box.centerX() && BoxMath.verticalOverlap(a.box,b.box)>=.55f &&
                b.box.left>=a.box.left && b.box.left-a.box.right<=.06f &&
                (minOf(a.box.right,b.box.right)-maxOf(a.box.left,b.box.left))<.6f*minOf(a.box.width(),b.box.width()) &&
                kotlin.math.abs(a.box.centerY()-b.box.centerY())<=minOf(a.box.height(),b.box.height())*.5f
            val vertical=a.box.centerY()<b.box.centerY() &&
                kotlin.math.abs(a.box.centerX()-b.box.centerX())<=maxOf(a.box.width(),b.box.width())*.25f &&
                b.box.top-a.box.bottom<=maxOf(a.box.height(),b.box.height())*.65f &&
                b.box.centerY()-a.box.centerY()<=maxOf(a.box.height(),b.box.height())*1.8f
            if(!horizontal && !vertical) continue
            val an=TextNorm.normalize(a.text);val bn=TextNorm.normalize(b.text)
            // Contained overlapping readings remain alternatives, not concatenations (LA / LA RESERVE).
            if(BoxMath.iou(a.box,b.box)>.10f && (" $an ".contains(" $bn ") || " $bn ".contains(" $an "))) continue
            val designation=WineDesignations.isDesignation(b.text) && !WineDesignations.isDesignation(a.text)
            val leadingConnector=Regex("^(de|du|di|des)\\b").containsMatchIn(bn)
            if(horizontal || designation || leadingConnector || Regex("\\b(de|du|di|des)$").containsMatchIn(an) || bn in setOf("de","du","di","des"))
                add(a.text+" "+b.text,a.observations+b.observations,BoxMath.union(listOf(a.box,b.box)),if(horizontal) "aligned horizontal phrase" else if(designation) "designation-linked phrase" else "connector-linked phrase")
        }
        // Horizontal heading fragments (e.g. article + title) can precede an isolated connector.
        for(a in phrases.toList().filter { it.construction=="aligned horizontal phrase" }) for(b in singles) {
            checkBudget()
            if(TextNorm.normalize(b.text) !in setOf("de","du","di","des") || a.observations.intersect(b.observations).isNotEmpty()) continue
            if(b.box.centerY()>a.box.centerY() && b.box.top-a.box.bottom<=.08f &&
                kotlin.math.abs(a.box.centerX()-b.box.centerX())<=a.box.width()*.25f)
                add(a.text+" "+b.text,a.observations+b.observations,BoxMath.union(listOf(a.box,b.box)),"connector-linked phrase")
        }
        // Third title line, still bounded; do not recursively expand arbitrary paragraphs.
        for(a in phrases.toList().filter { it.construction=="connector-linked phrase" }) for(b in singles) {
            checkBudget()
            if(a.observations.intersect(b.observations).isNotEmpty() || !Regex("\\b(de|du|di|des)$").containsMatchIn(TextNorm.normalize(a.text))) continue
            if(TextNorm.normalize(b.text) in setOf("de","du","di","des") || b.text.count(Char::isLetter)<4) continue
            if(a.observations.any { id -> singles.any { s -> id in s.observations && TextNorm.normalize(s.text).filter(Char::isLetterOrDigit)==TextNorm.normalize(b.text).filter(Char::isLetterOrDigit) } }) continue
            val connectorY=observations.filter { it.id in a.observations && TextNorm.normalize(it.record.textRaw) in setOf("de","du","di","des") }.maxOfOrNull { it.record.bboxRect.centerY() } ?: a.box.centerY()
            if(b.box.centerY()>connectorY && b.box.top-a.box.bottom<=.08f &&
                kotlin.math.abs(a.box.centerX()-b.box.centerX())<=maxOf(a.box.width(),b.box.width())*.25f)
                add(a.text+" "+b.text,a.observations+b.observations,BoxMath.union(listOf(a.box,b.box)),"three-line connector phrase")
        }
        return EvidenceGraph(observations,phrases,truncated)
    }
}
