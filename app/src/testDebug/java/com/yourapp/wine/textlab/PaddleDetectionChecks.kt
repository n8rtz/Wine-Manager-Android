package com.yourapp.wine.textlab

import android.graphics.PointF
import kotlin.math.abs

object PaddleDetectionChecks {
    fun run() {
        check(PaddleDetectionGeometry.inputSize(1001,1920)==(512 to 960))
        check(PaddleDetectionGeometry.inputSize(1920,1001)==(960 to 512))
        check(PaddleDetectionGeometry.inputSize(8,8)==(32 to 32))
        for(w in listOf(8,100,777,1001,1920)) for(h in listOf(8,137,1490,1920)) {
            val size=PaddleDetectionGeometry.inputSize(w,h)
            check(size.first in 32..960 && size.second in 32..960 && size.first%32==0 && size.second%32==0)
        }
        check(abs(PaddleDetectionGeometry.normalize(0xffff0000.toInt(),0)-(-.485f/.229f))<1e-5f)
        check(abs(PaddleDetectionGeometry.normalize(0xffff0000.toInt(),2)-((1-.406f)/.225f))<1e-5f)
        val transform=PaddleDetectionGeometry.mapToBase(512,960,1001,1920)
        val point=PointF(200f,400f);val roundTrip=transform.inverse().map(transform.map(point))
        check(abs(point.x-roundTrip.x)<.001 && abs(point.y-roundTrip.y)<.001)
        val quad=listOf(PointF(0f,0f),PointF(4f,0f),PointF(4f,4f),PointF(0f,4f))
        check(abs(PaddleDetectionGeometry.boxScore(FloatArray(16){.8f},4,4,quad){}-.8f)<1e-6)
        check(PaddleDetectionGeometry.boxScore(FloatArray(16){if(it==0)1f else 0f},4,4,quad){}==1f/16)
        check(PaddleDetectionGeometry.normalizedBox(quad,8,8)=="0.000000 0.000000 0.500000 0.500000")
        val vertical=listOf(PointF(0f,0f),PointF(4f,0f),PointF(4f,20f),PointF(0f,20f))
        check(PaddleDetectionGeometry.lineSize(PaddleDetectionGeometry.horizontal(vertical))==(20 to 4))
        try { PaddleDetectionGeometry.boxScore(FloatArray(16),4,4,quad) { throw InterruptedException() };error("Must cancel") }
        catch(_:InterruptedException) { }
    }
}
