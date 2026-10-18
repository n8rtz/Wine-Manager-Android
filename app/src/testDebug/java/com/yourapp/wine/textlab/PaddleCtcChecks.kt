package com.yourapp.wine.textlab

import java.nio.FloatBuffer
import kotlin.math.abs

object PaddleCtcChecks {
    fun run() {
        // Blank, repeated symbol, blank, same symbol, different symbol, appended space.
        fun buffer(indices:List<Int>,classes:Int):FloatBuffer = FloatBuffer.wrap(indices.flatMap { best ->
            List(classes) { if(it==best) .9f else .1f/(classes-1) }
        }.toFloatArray())
        val dictionary=listOf("M","e","r","l","o","t")
        val reading=PaddleCtc.decode(buffer(listOf(0,1,1,0,1,2,7),8),7,8,dictionary)
        check(reading.text=="MMe " && abs(reading.confidence-.9f)<.0001f)
        check(PaddleCtc.decode(buffer(listOf(0,0),8),2,8,dictionary).text.isEmpty())
        check(PaddleCtc.decode(buffer(listOf(1,2,3,4,5,6),8),6,8,dictionary).text=="Merlot")
        val emoji=PaddleCtc.decode(buffer(listOf(1),3),1,3,listOf("🕧"))
        check(emoji.text=="🕧")
        check(PaddleCtc.widths(200,50,-1)==(192 to 320))
        check(PaddleCtc.widths(1000,50,-1)==(960 to 960))
        check(PaddleCtc.widths(200,50,320)==(192 to 320))
        check(PaddleCtc.channel(0xffff0000.toInt(),0)==-1f)
        check(PaddleCtc.channel(0xffff0000.toInt(),2)==1f)
        for(action in listOf<()->Unit>(
            { PaddleCtc.widths(2000,10,-1) },
            { PaddleCtc.decode(buffer(listOf(1),3),1,3,dictionary) },
            { PaddleCtc.decode(FloatBuffer.wrap(floatArrayOf(Float.NaN,0f,1f)),1,3,listOf("a")) }
        )) {
            try { action();error("Invalid model input/output must fail") } catch(_:IllegalArgumentException) { }
        }
        var stopped=false
        try { PaddleCtc.decode(buffer(listOf(1),3),1,3,listOf("a")) { throw InterruptedException() } }
        catch(_:InterruptedException) { stopped=true }
        check(stopped)
    }
}
