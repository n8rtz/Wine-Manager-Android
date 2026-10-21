package com.yourapp.wine.textlab

import com.yourapp.wine.geom.Matrix3
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Upstream projection-profile skew search. Final deskew expands the canvas to retain label corners. */
internal object IndraOrientation {
    data class Page(val gray:Mat,val pageToBase:Matrix3,val turns:Int,val skew:Double)
    private fun thumb(gray:Mat,side:Int):Mat {
        val s=side.toDouble()/max(gray.cols(),gray.rows());val out=Mat()
        try { Imgproc.resize(gray,out,Size(max(1.0,round(gray.cols()*s)),max(1.0,round(gray.rows()*s))),0.0,0.0,Imgproc.INTER_AREA);return out }
        catch(e:Throwable) { out.release();throw e }
    }
    fun skew(gray:Mat,checkActive:()->Unit):Double {
        val fine=thumb(gray,800)
        try {
            Core.bitwise_not(fine,fine)
            if(Core.mean(fine).`val`[0]<.003*255) return 0.0
            val coarse=thumb(gray,400)
            try {
                Core.bitwise_not(coarse,coarse)
                fun score(ink:Mat,angle:Double):Double {
                    checkActive();val rotation=Imgproc.getRotationMatrix2D(Point(ink.cols()/2.0,ink.rows()/2.0),angle,1.0);val out=Mat()
                    try {
                        Imgproc.warpAffine(ink,out,rotation,ink.size(),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,Scalar(0.0))
                        val bytes=ByteArray(out.rows()*out.cols());out.get(0,0,bytes)
                        var previous=0.0;var sum=0.0
                        for(y in 0 until out.rows()) {
                            if(y%32==0) checkActive()
                            var row=0.0;for(x in 0 until out.cols()) row+=(bytes[y*out.cols()+x].toInt() and 255)
                            if(y>0) sum+=(row-previous).pow(2);previous=row
                        }
                        return sum
                    } finally { out.release();rotation.release() }
                }
                val starts=linkedSetOf(0.0)
                starts.addAll((0..45).map { -45.0+2*it }.map { it to score(coarse,it) }.sortedByDescending { it.second }.take(2).map { it.first })
                val near=starts.flatMap { a -> (0..8).map { a-2+.5*it } }.distinct()
                val first=near.maxBy { score(fine,it) }
                return (0..10).map { first-.5+.1*it }.maxBy { score(fine,it) }
            } finally { coarse.release() }
        } finally { fine.release() }
    }
    /** Orientation network sees the same-size, deskewed preview as upstream, not the expanded final canvas. */
    fun orientationInput(gray:Mat,angle:Double,checkActive:()->Unit):IndraInput.Tensor {
        checkActive();val preview=thumb(gray,min(1600,max(gray.rows(),gray.cols())))
        try {
            if(abs(angle)>=.3) {
                val rotated=Mat();val m=Imgproc.getRotationMatrix2D(Point(preview.cols()/2.0,preview.rows()/2.0),angle,1.0)
                try { Imgproc.warpAffine(preview,rotated,m,preview.size(),Imgproc.INTER_LINEAR,Core.BORDER_REPLICATE);return square(rotated,checkActive) }
                finally { rotated.release();m.release() }
            }
            return square(preview,checkActive)
        } finally { preview.release() }
    }
    private fun square(gray:Mat,checkActive:()->Unit):IndraInput.Tensor {
        val small=thumb(gray,768)
        try {
            val bytes=ByteArray(small.rows()*small.cols());small.get(0,0,bytes);val out=FloatArray(768*768)
            for(y in 0 until small.rows()) { checkActive();for(x in 0 until small.cols()) out[y*768+x]=1f-(bytes[y*small.cols()+x].toInt() and 255)/255f }
            return IndraInput.Tensor(out,768,768,small.cols())
        } finally { small.release() }
    }
    fun prepare(gray:Mat,k:Int,initialSkew:Double,checkActive:()->Unit):Page {
        require(k in 0..3);val turned=Mat()
        try {
            when(k) { 0 -> gray.copyTo(turned);1 -> Core.rotate(gray,turned,Core.ROTATE_90_CLOCKWISE)
                2 -> Core.rotate(gray,turned,Core.ROTATE_180);3 -> Core.rotate(gray,turned,Core.ROTATE_90_COUNTERCLOCKWISE) }
            val raw=if(k==1 || k==3) skew(turned,checkActive) else initialSkew
            val angle=if(abs(raw)>=.3) raw else 0.0
            val turn=IndraMath.turnMatrix(k,gray.cols(),gray.rows())
            if(angle==0.0) return Page(turned,turn.inverse(),k,0.0)
            val m=Imgproc.getRotationMatrix2D(Point(turned.cols()/2.0,turned.rows()/2.0),angle,1.0)
            try {
                val a=DoubleArray(6);m.get(0,0,a)
                val original=Matrix3(listOf(a[0],a[1],a[2],a[3],a[4],a[5],0.0,0.0,1.0))
                val corners=listOf(android.graphics.PointF(0f,0f),android.graphics.PointF(turned.cols().toFloat(),0f),
                    android.graphics.PointF(turned.cols().toFloat(),turned.rows().toFloat()),android.graphics.PointF(0f,turned.rows().toFloat())).map(original::map)
                val minX=floor(corners.minOf { it.x }.toDouble());val minY=floor(corners.minOf { it.y }.toDouble())
                val w=ceil(corners.maxOf { it.x }-minX).toInt();val h=ceil(corners.maxOf { it.y }-minY).toInt()
                require(w in 1..4096 && h in 1..4096)
                a[2]-=minX;a[5]-=minY;m.put(0,0,*a)
                val affine=Matrix3(listOf(a[0],a[1],a[2],a[3],a[4],a[5],0.0,0.0,1.0))
                val out=Mat()
                try {
                    checkActive();Imgproc.warpAffine(turned,out,m,Size(w.toDouble(),h.toDouble()),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,Scalar(255.0))
                    turned.release();return Page(out,(affine*turn).inverse(),k,angle)
                } catch(e:Throwable) { out.release();throw e }
            } finally { m.release() }
        } catch(e:Throwable) { turned.release();throw e }
    }
}
