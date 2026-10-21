package com.yourapp.wine.textlab

import android.graphics.PointF
import com.yourapp.wine.geom.Matrix3
import kotlin.math.*

/** Coordinates are explicit: detector map -> selected base crop -> EXIF-upright original. */
internal object PaddleDetectionGeometry {
    const val MAX_EDGE=960
    const val MAX_LINES=32
    fun inputSize(width:Int,height:Int):Pair<Int,Int> {
        require(width>0 && height>0)
        val scale=minOf(1.0,MAX_EDGE.toDouble()/maxOf(width,height))
        fun rounded(value:Int)=((value*scale/32).roundToInt()*32).coerceIn(32,MAX_EDGE)
        return rounded(width) to rounded(height)
    }
    private val mean=floatArrayOf(.485f,.456f,.406f)
    private val std=floatArrayOf(.229f,.224f,.225f)
    fun normalize(pixel:Int,channel:Int):Float {
        return (((pixel ushr (channel*8)) and 255)/255f-mean[channel])/std[channel]
    }
    fun lineSize(quad:List<PointF>):Pair<Int,Int> {
        require(quad.size==4 && quad.all { it.x.isFinite() && it.y.isFinite() })
        fun distance(a:PointF,b:PointF)=hypot((a.x-b.x).toDouble(),(a.y-b.y).toDouble())
        val width=maxOf(distance(quad[0],quad[1]),distance(quad[2],quad[3]))
        val height=maxOf(distance(quad[0],quad[3]),distance(quad[1],quad[2]))
        require(width>=2 && height>=2) { "Degenerate detected line" }
        return ceil(width).toInt() to ceil(height).toInt()
    }
    /** Keep long text axis horizontal. This does not infer upside-down orientation. */
    fun horizontal(ordered:List<PointF>):List<PointF> {
        val size=lineSize(ordered)
        return if(size.second>size.first) listOf(ordered[3],ordered[0],ordered[1],ordered[2]) else ordered
    }
    fun normalizedBox(quad:List<PointF>,width:Int,height:Int):String {
        require(width>0 && height>0)
        return listOf(quad.minOf { it.x }/width,quad.minOf { it.y }/height,
            quad.maxOf { it.x }/width,quad.maxOf { it.y }/height).joinToString(" ") {
            java.lang.String.format(java.util.Locale.ROOT,"%.6f",it.coerceIn(0f,1f))
        }
    }
    fun mapToBase(mapWidth:Int,mapHeight:Int,baseWidth:Int,baseHeight:Int):Matrix3 {
        require(minOf(mapWidth,mapHeight,baseWidth,baseHeight)>0)
        return Matrix3.scale(baseWidth.toDouble()/mapWidth,baseHeight.toDouble()/mapHeight)
    }
    /** Mean over the *entire* unexpanded rectangle, not only high-probability foreground. */
    fun boxScore(prob:FloatArray,width:Int,height:Int,quad:List<PointF>,checkActive:()->Unit):Float {
        require(prob.size==width*height && quad.size==4)
        val left=floor(quad.minOf { it.x }.toDouble()).toInt().coerceIn(0,width-1)
        val right=ceil(quad.maxOf { it.x }.toDouble()).toInt().coerceIn(0,width-1)
        val top=floor(quad.minOf { it.y }.toDouble()).toInt().coerceIn(0,height-1)
        val bottom=ceil(quad.maxOf { it.y }.toDouble()).toInt().coerceIn(0,height-1)
        var sum=0.0;var count=0
        for(y in top..bottom) {
            checkActive()
            for(x in left..right) {
                var positive=false;var negative=false
                for(i in 0..3) {
                    val a=quad[i];val b=quad[(i+1)%4]
                    val cross=(b.x-a.x)*(y+.5f-a.y)-(b.y-a.y)*(x+.5f-a.x)
                    positive=positive || cross>0;negative=negative || cross<0
                }
                if(!(positive && negative)) { sum+=prob[y*width+x];count++ }
            }
        }
        return if(count==0) 0f else (sum/count).toFloat()
    }
}
