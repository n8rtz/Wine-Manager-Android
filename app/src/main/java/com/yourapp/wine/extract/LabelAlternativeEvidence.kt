package com.yourapp.wine.extract

import com.yourapp.wine.merge.*
import com.yourapp.wine.ocr.*
import com.yourapp.wine.fill.*

/** Retains weak evidence only for a supported title. Never changes the ordinary merger's admission gate. */
object LabelAlternativeEvidence {
    private val producer=ProducerLineSelector()
    fun connectors(lines:List<LabelLine>,records:List<TokenRecord>):List<LabelLine> {
        val retained=lines.flatMap { it.tokens }.flatMap { it.members }.toSet()
        val weak=records.filter { it !in retained && it.score.isFinite() && it.score>=.40f && it.score<.60f &&
            it.engineId=="mlkit" && TextNorm.normalize(it.textRaw) in setOf("de","du","des","di","della") &&
            it.bboxRect.width()>0 && it.bboxRect.height()>0 &&
            listOf(it.bboxRect.left,it.bboxRect.top,it.bboxRect.right,it.bboxRect.bottom).all { v -> v.isFinite() && v in 0f..1f }
        }.distinctBy { TextNorm.normalize(it.textRaw) to listOf(it.bboxRect.left,it.bboxRect.top,it.bboxRect.right,it.bboxRect.bottom) }.take(8)
        // Evaluate together so competing connectors cannot each appear uniquely supported.
        val extra=weak.map { LabelLine(it.textRaw,listOf(MergedToken(it.textRaw,listOf(it),it.score,it.bboxRect)),it.bboxRect) }
        val titles=LabelLayoutContext.titles(lines+extra)
        return extra.filter { line ->
            titles.any { title -> title.sources.any { it in line.tokens } &&
                title.sources.filterNot { it in line.tokens }.all { it.vote>=.75f } } &&
                extra.none { other -> other!==line && BoxMath.centerDistance(other.bboxRect,line.bboxRect)<.06f }
        }
    }

    data class Repair(val lines:List<LabelLine>,val changes:List<Pair<LabelLine,LabelLine>>)
    /** A producer fragment can disambiguate alternative readings at a separate title location.
     * Two engines at that title location are required; repetitions of one engine do not qualify.
     * We choose an actually observed complete tail, not a dictionary completion.
     */
    fun repeatedTails(input:List<LabelLine>):Repair {
        val changes=mutableListOf<Pair<LabelLine,LabelLine>>()
        val result=input.toMutableList()
        val makers=input.filter { producer.hasProducerCue(it.text) }
        for(title in input.filterNot { producer.hasProducerCue(it.text) || MetadataRules.isBoilerplate(it.text) }) {
            // Only a standalone distinctive tail, not a paragraph, appellation or arbitrary word in a sentence.
            if(!title.text.matches(Regex("(?iu)(?:l['’])?[\\p{L}]{6,16}"))) continue
            val reads=title.tokens.flatMap { it.members }.filter { it.score.isFinite() && it.score>=.40f && it.score<=1f }
            if(reads.map { it.engineId }.distinct().size<2) continue
            val forms=reads.map { it.textRaw }.filter { it.matches(Regex("(?iu)(?:l['’])?[\\p{L}]{6,16}")) }
                .distinctBy(TextNorm::normalize)
            if(forms.size<2) continue
            fun word(text:String)=TextNorm.normalize(Regex("[\\p{L}]{5,}").findAll(text).lastOrNull()?.value.orEmpty())
            // An exact reading at a producer location protects it from contextual correction.
            if(makers.any { maker -> forms.any { word(it)==word(maker.text) } }) continue
            val matches=makers.filter { maker ->
                BoxMath.iou(maker.bboxRect,title.bboxRect)<.05f &&
                    kotlin.math.abs(maker.bboxRect.centerY()-title.bboxRect.centerY())>maxOf(maker.bboxRect.height(),title.bboxRect.height())
            }.mapNotNull { maker ->
                val observed=word(maker.text)
                // Do not correct away from any exact title reading.
                if(forms.any { word(it)==observed }) return@mapNotNull null
                val candidates=forms.filter { TextNorm.editDistance(word(it),observed,1)!=null }
                if(candidates.size==1) maker to candidates.single() else null
            }
            if(matches.isEmpty() || matches.map { TextNorm.normalize(it.second) }.distinct().size!=1) continue
            val chosen=matches.first().second
            val fixedTitle=title.copy(text=chosen)
            if(title.text!=chosen) { result[input.indexOf(title)]=fixedTitle;changes+=title to fixedTitle }
            for((maker,_) in matches) {
                // Replace the full observed tail only, leaving role/connector evidence intact.
                val tail=Regex("(?iu)(?:l['’]|['’])?[\\p{L}]{5,16}$").find(maker.text) ?: continue
                val fixed=maker.copy(text=maker.text.replaceRange(tail.range,chosen))
                result[input.indexOf(maker)]=fixed;changes+=maker to fixed
            }
        }
        // Multiple title locations must not offer contradictory replacements for the same producer.
        if(changes.groupBy { it.first }.any { (_,proposals) -> proposals.map { TextNorm.normalize(it.second.text) }.distinct().size>1 })
            return Repair(input,emptyList())
        return Repair(result,changes)
    }
    fun suggestion(before:LabelLine,after:LabelLine)=SpellingSuggestion("label-local-alternatives",before.text,after.text,after.text,
        TextNorm.editDistance(TextNorm.normalize(before.text),TextNorm.normalize(after.text),8) ?: 8,
        "cross_label_evidence","near_match","unique observed two-engine title alternative supported by a separate producer fragment; no confidence increase")
}
