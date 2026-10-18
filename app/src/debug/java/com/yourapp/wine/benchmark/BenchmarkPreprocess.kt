package com.yourapp.wine.benchmark

import android.graphics.Bitmap
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

internal class BenchmarkBudgetExceeded : Exception()
internal suspend fun checkpoint(deadline: ScanDeadline) {
    currentCoroutineContext().ensureActive()
    if (deadline.expired) throw BenchmarkBudgetExceeded()
}

data class PreparedTreatment(val bitmap: Bitmap, val variantToBase: Matrix3)

/** Debug-only filters. Input BASE rectified pixels -> variant pixels with an explicit inverse to BASE. */
object BenchmarkPreprocess {
    suspend fun prepare(base:Bitmap, recipe:Treatment, workspace:BitmapWorkspace, deadline:ScanDeadline):PreparedTreatment {
        val mats=mutableListOf<Mat>()
        fun mat()=Mat().also { mats+=it }
        try {
            checkpoint(deadline)
            val rgba=mat();Utils.bitmapToMat(base,rgba)
            val rgb=mat();Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);rgba.release()
            if(recipe.contrast=="clahe") {
                val lab=mat();Imgproc.cvtColor(rgb,lab,Imgproc.COLOR_RGB2Lab)
                val channels=mutableListOf<Mat>();Core.split(lab,channels);mats.addAll(channels)
                val lut=Mat(1,256,CvType.CV_8U).also { mats+=it }
                lut.put(0,0,ByteArray(256) { v -> (if(v>200) 200+(v-200)*.55 else v.toDouble()).toInt().toByte() })
                Core.LUT(channels[0],lut,channels[0])
                val clahe=Imgproc.createCLAHE(2.0,Size(8.0,8.0))
                try { clahe.apply(channels[0],channels[0]) } finally { clahe.collectGarbage();clahe.clear() }
                Core.merge(channels,lab);Imgproc.cvtColor(lab,rgb,Imgproc.COLOR_Lab2RGB)
            }
            checkpoint(deadline)
            // Preserve color for raw/CLAHE/sharpen controls; binary/morphological cases operate on gray.
            val needsGray=recipe.contrast=="illumination" || recipe.window>0 || recipe.thin || recipe.thicken
            val gray=if(needsGray) mat().also { Imgproc.cvtColor(rgb,it,Imgproc.COLOR_RGB2GRAY) } else rgb
            if(recipe.contrast=="illumination") {
                val background=mat();Imgproc.GaussianBlur(gray,background,Size(0.0,0.0),25.0)
                Core.max(background,Scalar(1.0),background)
                Core.divide(gray,background,gray,255.0)
            }
            if(recipe.sharpen) {
                val blur=mat();Imgproc.medianBlur(gray,gray,3)
                Imgproc.GaussianBlur(gray,blur,Size(0.0,0.0),.7)
                Core.addWeighted(gray,1.35,blur,-.35,0.0,gray)
            }
            checkpoint(deadline)
            if(recipe.window>0) {
                val f=mat();gray.convertTo(f,CvType.CV_32F)
                val mean=mat();val square=mat();val variance=mat()
                Imgproc.boxFilter(f,mean,-1,Size(recipe.window.toDouble(),recipe.window.toDouble()))
                Core.multiply(f,f,square)
                Imgproc.boxFilter(square,variance,-1,Size(recipe.window.toDouble(),recipe.window.toDouble()))
                Core.multiply(mean,mean,square);Core.subtract(variance,square,variance)
                Core.max(variance,Scalar(0.0),variance);Core.sqrt(variance,variance)
                val pixels=ByteArray(gray.cols());val means=FloatArray(gray.cols());val std=FloatArray(gray.cols())
                for(y in 0 until gray.rows()) {
                    if(y%32==0) checkpoint(deadline)
                    gray.get(y,0,pixels);mean.get(y,0,means);variance.get(y,0,std)
                    for(x in pixels.indices) {
                        val threshold=means[x]*(1.0+recipe.k*(std[x]/128.0-1.0))
                        pixels[x]=if((pixels[x].toInt() and 255)<threshold) 0 else 255.toByte()
                    }
                    gray.put(y,0,pixels)
                }
            }
            if(recipe.thin) thin(gray,deadline)
            if(recipe.thicken) {
                // Dark foreground: erosion of grayscale is dilation of the ink, not of the white background.
                val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(2.0,2.0)).also { mats+=it }
                Imgproc.erode(gray,gray,kernel)
            }
            checkpoint(deadline)
            var image=gray
            var baseToVariant=Matrix3.scale(1.0,1.0)
            if(recipe.shear!=0.0) {
                val w=base.width.toDouble();val h=base.height.toDouble();val s=recipe.shear
                baseToVariant=BenchmarkGeometry.shear(base.width,base.height,s)
                val affine=Mat(2,3,CvType.CV_64F).also { mats+=it }
                affine.put(0,0,*baseToVariant.values.take(6).toDoubleArray())
                val warped=mat()
                Imgproc.warpAffine(image,warped,affine,Size(w,h),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,Scalar.all(255.0))
                image=warped
            }
            if(recipe.aspect) {
                val resized=mat();val height=com.yourapp.wine.ocr.OcrVariantGeometry.aspectHeight(base.height)
                Imgproc.resize(image,resized,Size(base.width.toDouble(),height.toDouble()),0.0,0.0,Imgproc.INTER_AREA)
                baseToVariant=Matrix3.scale(1.0,height.toDouble()/base.height)*baseToVariant;image=resized
            }
            checkpoint(deadline)
            val bitmap=workspace.create(image.cols(),image.rows());Utils.matToBitmap(image,bitmap)
            return PreparedTreatment(bitmap,baseToVariant.inverse())
        } finally { mats.forEach { it.release() } }
    }

    /** Zhang-Suen binary thinning, capped and cancellable. Dark foreground; no guessed ligature cuts. */
    private suspend fun thin(image:Mat,deadline:ScanDeadline) {
        val w=image.cols();val h=image.rows();val pixels=ByteArray(w*h);image.get(0,0,pixels)
        val ink=ByteArray(pixels.size) { if((pixels[it].toInt() and 255)<128) 1 else 0 }
        val remove=IntArray(pixels.size)
        var converged=false
        for(iteration in 0 until 64) {
            var changed=false
            for(phase in 0..1) {
                var count=0
                for(y in 1 until h-1) {
                    if(y%16==0) checkpoint(deadline)
                    for(x in 1 until w-1) {
                        val i=y*w+x;if(ink[i].toInt()==0) continue
                        val p2=ink[i-w].toInt();val p3=ink[i-w+1].toInt();val p4=ink[i+1].toInt();val p5=ink[i+w+1].toInt()
                        val p6=ink[i+w].toInt();val p7=ink[i+w-1].toInt();val p8=ink[i-1].toInt();val p9=ink[i-w-1].toInt()
                        val b=p2+p3+p4+p5+p6+p7+p8+p9
                        val a=(if(p2==0&&p3==1) 1 else 0)+(if(p3==0&&p4==1) 1 else 0)+
                            (if(p4==0&&p5==1) 1 else 0)+(if(p5==0&&p6==1) 1 else 0)+
                            (if(p6==0&&p7==1) 1 else 0)+(if(p7==0&&p8==1) 1 else 0)+
                            (if(p8==0&&p9==1) 1 else 0)+(if(p9==0&&p2==1) 1 else 0)
                        val removable=if(phase==0) p2*p4*p6==0&&p4*p6*p8==0 else p2*p4*p8==0&&p2*p6*p8==0
                        if(b in 2..6 && a==1 && removable) remove[count++]=i
                    }
                }
                for(j in 0 until count) ink[remove[j]]=0
                changed=changed||count>0
            }
            if(!changed) { converged=true;break }
        }
        check(converged) { "Thinning did not converge within 64 iterations" }
        for(i in pixels.indices) pixels[i]=if(ink[i].toInt()==1) 0 else 255.toByte()
        image.put(0,0,pixels)
    }
}
