package com.yourapp.wine.textlab

import java.nio.FloatBuffer
import kotlin.math.ceil

/** No vocabulary correction. These are raw CTC posteriors, not ML Kit confidence or calibrated certainty. */
internal object PaddleCtc {
    data class Reading(val text:String,val confidence:Float)
    fun decode(values:FloatBuffer,steps:Int,classes:Int,dictionary:List<String>,checkActive:()->Unit={}):Reading {
        require(steps in 1..512 && classes==dictionary.size+2 && values.remaining()==steps*classes)
        var previous=-1;var sum=0.0;var count=0
        val text=StringBuilder()
        repeat(steps) {
            checkActive()
            var best=-1;var score=-1f
            repeat(classes) { index ->
                val value=values.get()
                require(value.isFinite() && value in 0f..1.00001f) { "Expected CTC softmax probabilities" }
                if(value>score) { score=value;best=index }
            }
            if(best!=0 && best!=previous) {
                text.append(if(best==classes-1) " " else dictionary[best-1])
                sum+=score;count++
            }
            previous=best
        }
        return Reading(text.toString(),if(count==0) 0f else (sum/count).toFloat())
    }
    /** Widths in tensor pixels. A long paragraph is not a single-line handwriting input. */
    fun widths(width:Int,height:Int,fixedWidth:Long):Pair<Int,Int> {
        require(width>0 && height>0)
        val resized=ceil(48.0*width/height).toInt().coerceAtLeast(1)
        require(resized<=1024) { "Select a shorter text line" }
        val padded=if(fixedWidth>0) fixedWidth.toInt().also { require(it in 1..1024 && resized<=it) }
            else maxOf(320,resized)
        return resized to padded
    }
    /** Packed Android RGB pixel -> normalized model channel, in Paddle's BGR order. */
    fun channel(pixel:Int,channel:Int):Float = ((pixel ushr (channel*8)) and 255)/127.5f-1f
}
