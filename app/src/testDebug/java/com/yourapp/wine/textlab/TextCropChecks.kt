package com.yourapp.wine.textlab

import android.graphics.PointF
import kotlin.math.abs

object TextCropChecks {
    fun geometry() {
        val crop=TextCrop.between(.8f,.9f,.2f,.3f)
        check(crop==TextCrop(.2f,.3f,.8f,.9f))
        check(TextCrop.between(-1f,-1f,2f,2f)==TextCrop(0f,0f,1f,1f))
        check(TextCrop(.4f,.4f,.4f,.4f).pixels(1920,1280)==null)
        check(TextCrop(Float.NaN,0f,1f,1f).pixels(100,100)==null)
        check(TextCrop(.9f,.1f,.1f,.9f).pixels(100,100)==null)
        val box=requireNotNull(TextCrop(.201f,.301f,.799f,.899f).pixels(1000,1500))
        check(box.x<=201 && box.y<=452 && box.x+box.width>=799 && box.y+box.height>=1348)
        val known=CropPixels(200,300,600,900)
        for(enlarge in listOf(false,true)) {
            val (w,h)=known.outputSize(enlarge)
            check(w<=1280 && h<=1920)
            val matrix=known.toOriginal(1000,1500,3000,4500,w,h)
            val origin=matrix.map(PointF(0f,0f));check(abs(origin.x-600)<.001f && abs(origin.y-900)<.001f)
            val corner=matrix.map(PointF(w.toFloat(),h.toFloat()))
            check(abs(corner.x-2400)<.001f && abs(corner.y-3600)<.001f)
            val roundtrip=matrix.inverse().map(matrix.map(PointF(w*.33f,h*.7f)))
            check(abs(roundtrip.x-w*.33f)<.002f && abs(roundtrip.y-h*.7f)<.002f)
        }
        val full=TextCrop(0f,0f,1f,1f).pixels(1918,1439)!!
        check(full.width==1918 && full.height==1439)
        check(full.outputSize(true).first<=1280)
    }
}
