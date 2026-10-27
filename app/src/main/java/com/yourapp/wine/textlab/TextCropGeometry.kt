package com.yourapp.wine.textlab

import com.yourapp.wine.geom.Matrix3
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Fractions of the EXIF-upright displayed image, not screen pixels or letterboxed container pixels. */
data class TextCrop(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    fun pixels(width:Int,height:Int):CropPixels? {
        if(width<=0 || height<=0 || listOf(left,top,right,bottom).any { !it.isFinite() || it !in 0f..1f }) return null
        val x=floor(left*width).toInt();val y=floor(top*height).toInt()
        val w=ceil(right*width).toInt()-x;val h=ceil(bottom*height).toInt()-y
        return if(w>=8 && h>=8) CropPixels(x,y,w,h) else null
    }
    companion object {
        fun between(x1:Float,y1:Float,x2:Float,y2:Float)=TextCrop(
            minOf(x1,x2).coerceIn(0f,1f),minOf(y1,y2).coerceIn(0f,1f),
            maxOf(x1,x2).coerceIn(0f,1f),maxOf(y1,y2).coerceIn(0f,1f))
    }
}
/** Integer pixels in the bounded upright source bitmap. Outward rounding retains edge pixels. */
data class CropPixels(val x:Int,val y:Int,val width:Int,val height:Int) {
    fun outputSize(enlarge:Boolean):Pair<Int,Int> {
        val scale=minOf(if(enlarge) 2.0 else 1.0,1280.0/width,1920.0/maxOf(width,height))
        return (width*scale).roundToInt().coerceAtLeast(1) to (height*scale).roundToInt().coerceAtLeast(1)
    }
    /** OCR crop pixels -> EXIF-upright ORIGINAL pixels, including source downsampling and crop resize. */
    fun toOriginal(sourceW:Int,sourceH:Int,originalW:Int,originalH:Int,outW:Int,outH:Int):Matrix3 =
        Matrix3.scale(originalW.toDouble()/sourceW,originalH.toDouble()/sourceH) *
            Matrix3(listOf(width.toDouble()/outW,0.0,x.toDouble(),0.0,height.toDouble()/outH,y.toDouble(),0.0,0.0,1.0))
}
