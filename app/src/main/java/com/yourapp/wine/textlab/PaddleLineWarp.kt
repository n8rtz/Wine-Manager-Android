package com.yourapp.wine.textlab

import android.graphics.*
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.geom.Matrix3

/** Direct base -> 48px-high rectification: base + line are the ONLY working bitmaps. */
internal object PaddleLineWarp {
    data class Warped(val bitmap:Bitmap,val lineToBase:Matrix3)
    fun create(base:Bitmap,quad:List<PointF>,width:Int,height:Int,workspace:BitmapWorkspace):Warped {
        require(quad.size==4 && width in 1..1024 && height==48)
        val matrix=Matrix()
        check(matrix.setPolyToPoly(quad.flatMap { listOf(it.x,it.y) }.toFloatArray(),0,
            floatArrayOf(0f,0f,width.toFloat(),0f,width.toFloat(),height.toFloat(),0f,height.toFloat()),0,4)) {
            "Singular detected-line warp"
        }
        val values=FloatArray(9);matrix.getValues(values)
        val lineToBase=Matrix3(values.map(Float::toDouble)).inverse()
        val line=workspace.create(width,height)
        try {
            Canvas(line).drawBitmap(base,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
            return Warped(line,lineToBase)
        } catch(error:Throwable) { workspace.release(line);throw error }
    }
}
