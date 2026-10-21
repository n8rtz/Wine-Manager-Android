package com.yourapp.wine.extract

import com.yourapp.wine.merge.*
import com.yourapp.wine.fill.*

/** Geometry-backed assembly, not a producer dictionary. Raw tokens remain intact. */
object WrappedBrandContext {
    private val producer=ProducerLineSelector()
    /** Only split a glued connector when another engine actually observed both pieces. */
    fun reconcile(line:LabelLine):LabelLine {
        val tokens=line.tokens
        for(token in tokens) for(connector in listOf("de","du","di")) {
            val word=TextNorm.normalize(token.canonical)
            if(' ' in word || word.length<6 || !word.endsWith(connector)) continue
            val stem=word.removeSuffix(connector)
            val separate=tokens.filter { it!==token && TextNorm.normalize(it.canonical)==connector }
            if(separate.size!=1) continue
            val piece=separate.single()
            val supported=token.members.any { full -> TextNorm.normalize(full.textRaw)==word &&
                token.members.any { prefix -> TextNorm.normalize(prefix.textRaw)==stem && prefix.engineId!=full.engineId &&
                    piece.members.any { it.engineId==prefix.engineId } } }
            if(!supported || BoxMath.centerDistance(piece.bboxRect,token.bboxRect)>.25f ||
                BoxMath.verticalOverlap(piece.bboxRect,token.bboxRect)<.5f) continue
            val text=tokens.filterNot { it===piece }.joinToString(" ") {
                if(it===token) token.canonical.dropLast(connector.length)+" "+connector else it.canonical
            }
            return line.copy(text=text)
        }
        return line
    }
    data class Brand(val candidate:FieldCandidate,val designations:List<FieldCandidate>)
    fun candidates(lines:List<LabelLine>):List<Brand> {
        val paragraphs=LabelLayoutContext.paragraphLines(lines)
        fun safe(line:LabelLine)=line !in paragraphs && MetadataRules.isIdentity(line.text) &&
            !MetadataRules.isBoilerplate(line.text) && !MetadataRules.isPackaging(line.text) &&
            !producer.isWineDescription(line.text) && !LabelTextRoles.isDamagedRhoneAppellation(line.text)
        return lines.filter { safe(it) && TextNorm.normalize(it.text).matches(Regex("[\\p{L}]{4,}(?: [\\p{L}]+){0,2} (de|du|di)")) }
            .take(8).mapNotNull { first ->
                val prefix=first.text.substringBeforeLast(' ')
                if(producer.isWineDescription(prefix) || producer.isWineDescription(TextNorm.normalize(prefix).removePrefix("la ").removePrefix("le ")) || WineDesignations.isDesignation(prefix)) return@mapNotNull null
                val a=first.bboxRect
                val following=lines.filter { other ->
                    val b=other.bboxRect
                    other!==first && safe(other) && other.text.none(Char::isDigit) && other.text.count(Char::isLetter)>=4 &&
                        b.centerY()>a.centerY() && b.centerY()-a.centerY()<=maxOf(a.height(),b.height())*1.3f &&
                        kotlin.math.abs(a.centerX()-b.centerX())<=maxOf(a.width(),b.width())*.25f &&
                        minOf(a.right,b.right)-maxOf(a.left,b.left)>=.6f*minOf(a.width(),b.width())
                }
                if(following.size!=1) return@mapNotNull null
                val last=following.single();val b=last.bboxRect
                // A separate explicit winery makes this a possible cuvée, not a supported producer assembly.
                if(lines.any { it!==first && it!==last && producer.hasProducerCue(it.text) }) return@mapNotNull null
                val styles=lines.filter { WineDesignations.isDesignation(it.text) && it.bboxRect.centerY()>b.centerY() &&
                    maxOf(0f,it.bboxRect.top-b.bottom)<=maxOf(.06f,b.height()) &&
                    minOf(b.right,it.bboxRect.right)-maxOf(b.left,it.bboxRect.left)>=.5f*minOf(b.width(),it.bboxRect.width()) }
                if(styles.map { TextNorm.normalize(it.text) }.distinct().size!=1) return@mapNotNull null
                Brand(FieldCandidate(FieldId.PRODUCER,first.text+" "+last.text,UiState.YELLOW,
                    "Wrapped brand with trailing connector and separate designation; producer/brand convention — confirm"+
                        (if(first.text!=first.tokens.joinToString(" ") { it.canonical }) "; split/joined OCR reconciled using separate engine evidence; raw tokens unchanged" else ""),
                    (first.tokens+last.tokens).distinct()),styles.map { FieldCandidate(FieldId.WINE_NAME,
                    WineDesignations.display(it.text) ?: it.text,UiState.YELLOW,"Separate designation beneath wrapped brand — confirm",it.tokens) })
            }
    }
}
