package com.yourapp.wine.textlab

import android.graphics.PointF
import com.yourapp.wine.geom.OpenCvRuntime
import com.yourapp.wine.geom.QuadOrder
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

internal data class PaddleRegion(val quadBase:List<PointF>,val detectionScore:Float)
internal data class PaddleRegions(val lines:List<PaddleRegion>,val candidates:Int,val truncated:Boolean)

/** Bounded DB rectangle decoder: family-specific thresholds/unclip from the candidate profile.
 * Components cap noisy native contour allocations BEFORE findContours, not after allocating thousands.
 * All returned quads are in the selected base crop, not detector-resized pixels.
 */
internal object PaddleDbPostprocess {
    fun decode(prob:FloatArray,width:Int,height:Int,baseWidth:Int,baseHeight:Int,spec:PaddleCandidate,checkActive:()->Unit):PaddleRegions {
        require(width in 1..960 && height in 1..960 && prob.size==width*height)
        val bytes=ByteArray(prob.size) { index ->
            if(index%4096==0) checkActive()
            val value=prob[index]
            require(value.isFinite() && value in 0f..1.00001f) { "Detector output is not a probability map" }
            if(value>spec.threshold) 255.toByte() else 0
        }
        OpenCvRuntime.ensureLoaded()
        val binary=Mat(height,width,CvType.CV_8UC1);val labels=Mat();val stats=Mat();val centers=Mat()
        try {
            binary.put(0,0,bytes)
            val count=Imgproc.connectedComponentsWithStats(binary,labels,stats,centers,8,CvType.CV_32S)
            checkActive()
            check(count<=4096) { "Detector map too noisy: $count components; refused unbounded contour decoding" }
            val table=IntArray(count*5);stats.get(0,0,table)
            val ids=(1 until count).filter { table[it*5+4]>=6 && table[it*5+2]>=3 && table[it*5+3]>=3 }
                .sortedByDescending { table[it*5+4] }
            val labelValues=IntArray(width*height);labels.get(0,0,labelValues)
            val mapToBase=PaddleDetectionGeometry.mapToBase(width,height,baseWidth,baseHeight)
            val found=mutableListOf<PaddleRegion>()
            var clipped=ids.size>128
            for(id in ids.take(128)) {
                checkActive()
                val left=table[id*5];val top=table[id*5+1];val w=table[id*5+2];val h=table[id*5+3]
                val component=ByteArray(w*h) { offset -> if(labelValues[(top+offset/w)*width+left+offset%w]==id) 255.toByte() else 0 }
                val mask=Mat(h,w,CvType.CV_8UC1);val hierarchy=Mat();val contours=mutableListOf<MatOfPoint>()
                try {
                    mask.put(0,0,component)
                    Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE,
                        Point(left.toDouble(),top.toDouble()))
                    for(contour in contours) {
                        checkActive()
                        if(contour.rows()<3) continue
                        if(contour.rows()>16384) { clipped=true;continue }
                        val points=MatOfPoint2f(*contour.toArray())
                        try {
                            val rect=Imgproc.minAreaRect(points)
                            if(minOf(rect.size.width,rect.size.height)<3) continue
                            fun quad(r:RotatedRect):List<PointF> {
                                val corners=Array(4) { Point() };r.points(corners)
                                return QuadOrder.clockwise(corners.map { PointF(it.x.toFloat(),it.y.toFloat()) })
                            }
                            val score=PaddleDetectionGeometry.boxScore(prob,width,height,quad(rect),checkActive)
                            if(score<spec.boxThreshold) continue
                            // For a rectangle, the bounding rectangle of a round-join offset expands both axes by 2d.
                            val distance=rect.size.width*rect.size.height*spec.unclip/(2*(rect.size.width+rect.size.height))
                            val expanded=RotatedRect(rect.center,Size(rect.size.width+2*distance,rect.size.height+2*distance),rect.angle)
                            val base=quad(expanded).map(mapToBase::map).map {
                                PointF(it.x.coerceIn(0f,baseWidth.toFloat()),it.y.coerceIn(0f,baseHeight.toFloat()))
                            }
                            if(runCatching { PaddleDetectionGeometry.lineSize(base) }.isFailure) continue
                            found += PaddleRegion(PaddleDetectionGeometry.horizontal(base),score)
                        } finally { points.release() }
                    }
                } finally { contours.forEach { it.release() };mask.release();hierarchy.release() }
            }
            val ordered=found.sortedWith(compareBy<PaddleRegion> { it.quadBase.minOf { p -> p.y } }.thenBy { it.quadBase.minOf { p -> p.x } })
            return PaddleRegions(ordered.take(PaddleDetectionGeometry.MAX_LINES),ids.size,clipped || ordered.size>PaddleDetectionGeometry.MAX_LINES)
        } finally { binary.release();labels.release();stats.release();centers.release() }
    }
}
