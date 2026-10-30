package com.yourapp.wine.gliner

import android.graphics.RectF
import java.util.Locale

/**
 * One fused OCR line: the text both engines are pointing at, its rectified-label box and the
 * combined posterior. [engines] keeps every engine that read this line with its own score, so the
 * downstream records can still count two independent engines; [posterior] is only the display and
 * threshold value (1 - product of the engine misses).
 */
internal data class GlinerOcrLine(
    val text:String,
    val box:RectF,
    val quad:List<android.graphics.PointF>,
    val posterior:Float,
    val engines:Map<String,Float> = emptyMap(),
)

/** One word of the flattened label document, with the OCR line it came from and its own box. */
internal data class GlinerWord(
    val text:String,
    val normalized:String,
    val lineIndex:Int,
    val charStart:Int,
    val charEnd:Int,
    val box:RectF,
    val engines:Map<String,Float> = emptyMap(),
)

/**
 * Reading-order view of a wine label.
 *
 * The fused ML Kit + PP-OCRv6 lines arrive in detector order, which is not reading order, and a
 * wine label mixes
 * architecture: an estate name above a cuvée, an appellation in a crest, a vintage in a corner.
 * This document re-sorts lines into horizontal bands (top to bottom, left to right inside a band)
 * and flattens them into one word sequence. GLiNER sees that sequence; every word still knows the
 * OCR line and box it came from, so a lane fragment recovers real geometry for post-processing.
 *
 * The model text is lowercase-normalized word by word (DeBERTa-v3 is an uncased vocabulary); the
 * original casing is retained for display and for the post-processing text.
 */
internal class GlinerDocument(
    val lines:List<GlinerOcrLine>,
    val bandOrder:List<Int>,
    val words:List<GlinerWord>,
    val text:String,
) {
    val lineCount:Int get() = lines.size

    fun line(index:Int):GlinerOcrLine? = lines.getOrNull(index)

    /** Survivors of the word budget: the first [limit] words, so spans never index past the tensor. */
    fun limited(limit:Int):GlinerDocument {
        if(words.size<=limit) return this
        val kept=words.take(limit)
        val keptLines=kept.map { it.lineIndex }.toSet()
        val keptText=kept.joinToString(" ") { it.text }
        return GlinerDocument(lines,bandOrder.filter { it in keptLines },kept,keptText)
    }

    companion object {
        private const val TRAILING=" \t,.;:!?()[]{}\"'«»“”‘’|/-–—"
        private const val LEADING=" \t,.;:!?()[]{}\"'«»“”‘’|"

        /**
         * Builds the document. Lines with no usable text are dropped, lines are banded when their
         * vertical overlap is at least half the shorter box, and words are split on whitespace
         * with leading/trailing punctuation removed (GLiNER's spaCy tokenizer behaves the same way
         * for a label, where punctuation is never part of a producer or appellation).
         */
        fun build(rawLines:List<GlinerOcrLine>):GlinerDocument {
            val actionable=rawLines.filter { it.text.isNotBlank() && it.box.width()>0f && it.box.height()>0f }
            val ordered=actionable.sortedWith(compareBy<GlinerOcrLine>({ it.box.top },{ it.box.left }))
            val bands=mutableListOf<MutableList<GlinerOcrLine>>()
            for(line in ordered) {
                val band=bands.lastOrNull()
                if(band!=null && overlapsVertically(band.last(),line)) band.add(line) else bands.add(mutableListOf(line))
            }
            val readingOrder=bands.flatMap { band -> band.sortedBy { it.box.left } }
            val indexOf=readingOrder.withIndex().associate { (index,line) -> line to index }
            val words=mutableListOf<GlinerWord>()
            val display=mutableListOf<String>()
            var offset=0
            for(line in readingOrder) {
                val lineIndex=indexOf.getValue(line)
                display += line.text
                val pieces=split(line.text)
                for(piece in pieces) {
                    val normalized=normalize(piece.text)
                    if(normalized.isEmpty()) continue
                    words += GlinerWord(piece.text,normalized,lineIndex,offset+piece.start,offset+piece.end,
                        wordBox(line.box,line.text.length,piece.start,piece.end),line.engines)
                }
                offset+=line.text.length+1
            }
            return GlinerDocument(readingOrder,readingOrder.indices.toList(),words,display.joinToString("\n"))
        }

        private fun overlapsVertically(left:GlinerOcrLine,right:GlinerOcrLine):Boolean {
            val top=maxOf(left.box.top,right.box.top)
            val bottom=minOf(left.box.bottom,right.box.bottom)
            if(bottom<=top) return false
            val shorter=minOf(left.box.height(),right.box.height())
            return (bottom-top)>=shorter*0.5f
        }

        private data class Piece(val text:String,val start:Int,val end:Int)

        /** Whitespace split with punctuation trimmed, keeping original character offsets. */
        private fun split(line:String):List<Piece> {
            val pieces=mutableListOf<Piece>()
            var index=0
            while(index<line.length) {
                while(index<line.length && line[index].isWhitespace()) index++
                if(index>=line.length) break
                val start=index
                while(index<line.length && !line[index].isWhitespace()) index++
                var end=index
                while(end>start && line[end-1] in TRAILING) end--
                var begin=start
                while(begin<end && line[begin] in LEADING) begin++
                if(end>begin) pieces += Piece(line.substring(begin,end),begin,end)
            }
            return pieces
        }

        /** Lowercase + fold accents the same way the OCR text is normalized for the catalogs. */
        fun normalize(word:String):String {
            val folded=java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"),"")
            return folded.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
                .ifEmpty { folded.lowercase(Locale.ROOT) }
        }

        /** Linear interpolation of the word box inside its line box: enough geometry for a join. */
        private fun wordBox(line:RectF,lineLength:Int,start:Int,end:Int):RectF {
            if(lineLength<=0) return RectF(line)
            val width=line.width()
            val left=line.left+width*(start.toFloat()/lineLength)
            val right=line.left+width*(end.toFloat()/lineLength)
            return RectF(left,line.top,right,line.bottom)
        }

        fun union(boxes:List<RectF>):RectF {
            if(boxes.isEmpty()) return RectF(0f,0f,0f,0f)
            val union=RectF(boxes.first())
            boxes.drop(1).forEach { box -> union.union(box) }
            return union
        }
    }
}
