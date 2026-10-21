package com.yourapp.wine.extract

import com.yourapp.wine.fill.*
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm

/** Bounded label-local cues, not a bottle catalog. Token text, scores and coordinates remain untouched. */
object LabelLayoutContext {
    private val producer=ProducerLineSelector()
    fun repair(line:LabelLine):LabelLine {
        var text=line.text
        val words=Regex("[\\p{L}]+").findAll(text).toList()
        val first=words.firstOrNull()
        // Only a leading long role word with an actual name remainder; never arbitrary identity words.
        if(first!=null && words.size>=3 && first.value.length>=8 &&
            TextNorm.normalize(first.value)!="vignerons" && TextNorm.editDistance(TextNorm.normalize(first.value),"vignerons",1)!=null &&
            TextNorm.normalize(words[1].value) in setOf("de","du","des"))
            text=text.replaceRange(first.range,"Vignerons")
        val normalized=TextNorm.normalize(text)
        val core="cotes du rhone"
        if(normalized.startsWith("$core ")) {
            val tail=normalized.removePrefix("$core ")
            if(tail.length in 5..8 && tail.startsWith("vil") && !tail.contains(' ') &&
                TextNorm.editDistance(tail,"villages",3)!=null && tail!="villages")
                text="Côtes du Rhône Villages"
        }
        return line.copy(text=text)
    }

    fun suggestion(original:LabelLine,repaired:LabelLine)=SpellingSuggestion(
        if(repaired.text.startsWith("Vignerons")) "producer-marker" else "appellation-suffix",
        original.text,repaired.text,repaired.text,
        TextNorm.editDistance(TextNorm.normalize(original.text),TextNorm.normalize(repaired.text),3) ?: 3,
        "bounded_context", "near_match", "leading role with name remainder or exact appellation core; confirmation required")

    /** Paragraph membership is only a ranking penalty, never deletion of small-print identities. */
    fun paragraphLines(lines:List<LabelLine>):Set<LabelLine> = lines.filter { line ->
        if(producer.hasProducerCue(line.text) || MetadataRules.isPackaging(line.text)) return@filter false
        val a=line.bboxRect
        if(a.width()<=0 || a.height()<=0 || line.text.split(' ').size<2) return@filter false
        val neighbors=lines.filter { other ->
            if(other===line || producer.hasProducerCue(other.text)) return@filter false
            val b=other.bboxRect
            val ratio=b.height()/a.height()
            ratio in .55f..1.8f && kotlin.math.abs(a.left-b.left)<=minOf(a.width(),b.width())*.3f &&
                minOf(a.right,b.right)-maxOf(a.left,b.left)>=.65f*minOf(a.width(),b.width()) &&
                kotlin.math.abs(a.centerY()-b.centerY())<=maxOf(a.height(),b.height())*2.7f
        }
        neighbors.size>=2 && lines.any { it.bboxRect.height()>a.height()*1.8f && it.bboxRect.width()>a.width()*1.8f }
    }.toSet()

    /** Join only a three-line title around an isolated connector; do not assume a fixed label zone. */
    fun titles(lines:List<LabelLine>):List<FieldCandidate> {
        val paragraphs=paragraphLines(lines)
        val usable=lines.filterNot { it in paragraphs || MetadataRules.isBoilerplate(it.text) || MetadataRules.isPackaging(it.text) || producer.hasProducerCue(it.text) }
        return usable.filter { TextNorm.normalize(it.text) in setOf("de","du","des","di","della") }.take(8).mapNotNull { connector ->
            val b=connector.bboxRect
            fun aligned(line:LabelLine):Boolean {
                val a=line.bboxRect
                return kotlin.math.abs(a.centerX()-b.centerX())<=maxOf(a.width(),b.width())*.25f &&
                    minOf(a.right,b.right)>maxOf(a.left,b.left) &&
                    kotlin.math.abs(a.centerY()-b.centerY())<=maxOf(a.height(),b.height())*1.8f
            }
            val above=usable.filter { it!==connector && it.bboxRect.centerY()<b.centerY() && aligned(it) && it.text.count(Char::isLetter)>=5 }
            val below=usable.filter { it!==connector && it.bboxRect.centerY()>b.centerY() && aligned(it) && it.text.count(Char::isLetter)>=5 }
            if(above.size!=1 || below.size!=1) return@mapNotNull null
            val first=above.single();val last=below.single()
            if(producer.isWineDescription(last.text) || WineDesignations.isAppellationName(first.text) || WineDesignations.isAppellationName(last.text)) return@mapNotNull null
            var suffix=last.text
            var support=""
            var spelling:SpellingSuggestion?=null
            // Repeated distinctive tail in a separate producer line; a unique exact word supports one edit.
            val observed=Regex("[\\p{L}]{5,}").findAll(suffix).lastOrNull()
            if(observed!=null) {
                val alternatives=lines.filter { producer.hasProducerCue(it.text) }.flatMap {
                    listOfNotNull(Regex("[\\p{L}]{5,}").findAll(it.text).lastOrNull()?.value)
                }.filter { TextNorm.normalize(it)!="vignerons" }.distinctBy(TextNorm::normalize)
                val exact=alternatives.any { TextNorm.normalize(it)==TextNorm.normalize(observed.value) }
                val matches=alternatives.filter { TextNorm.editDistance(TextNorm.normalize(it),TextNorm.normalize(observed.value),1)!=null }
                if(!exact && matches.size==1) {
                    suffix=suffix.replaceRange(observed.range,matches.single())
                    spelling=SpellingSuggestion("label-local-title",last.text,suffix,suffix,1,"substitution","near_match",
                        "unique repeated tail in separate producer line: "+matches.single())
                    support="; repeated producer-tail evidence '${matches.single()}' supports one-edit title repair '${observed.value}'; raw OCR unchanged"
                }
            }
            FieldCandidate(FieldId.WINE_NAME,"${first.text} ${connector.text.lowercase(java.util.Locale.ROOT)} $suffix",UiState.YELLOW,
                "Aligned stacked title with isolated connector; contextual wine-name suggestion; confirm$support",
                (first.tokens+connector.tokens+last.tokens).distinct(),spelling)
        }.distinctBy { TextNorm.normalize(it.value) }.take(8)
    }
}
