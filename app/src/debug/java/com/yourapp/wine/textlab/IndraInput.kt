package com.yourapp.wine.textlab

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Apache-2.0 adaptation of Indra page_to_input/crop_line; no word hints or language correction.
 * Only Mats/tensors are allocated here; the caller retains its one base Bitmap.
 */
internal object IndraInput {
    data class Tensor(val values:FloatArray,val width:Int,val height:Int,val contentWidth:Int,val scale:Double=1.0,
        val dark:Boolean=false,val sourceX:Int=0,val sourceY:Int=0,val sourceWidth:Int=0,val sourceHeight:Int=0)
    private fun ink(mat:Mat,paddedWidth:Int,invert:Boolean,checkActive:()->Unit):FloatArray {
        val bytes=ByteArray(mat.rows()*mat.cols());mat.get(0,0,bytes)
        val values=FloatArray(mat.rows()*paddedWidth)
        for(y in 0 until mat.rows()) {
            checkActive()
            for(x in 0 until mat.cols()) {
                val gray=(bytes[y*mat.cols()+x].toInt() and 255)/255f
                values[y*paddedWidth+x]=if(invert) gray else 1f-gray
            }
        }
        return values
    }
    fun detector(gray:Mat,checkActive:()->Unit):Tensor {
        val scale=1024.0/max(gray.cols(),gray.rows())
        val w=max(1,round(gray.cols()*scale).toInt());val h=max(1,round(gray.rows()*scale).toInt())
        val pw=(w+31)/32*32;val ph=(h+31)/32*32
        val small=Mat()
        try {
            Imgproc.resize(gray,small,Size(w.toDouble(),h.toDouble()),0.0,0.0,if(scale<1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            val output=FloatArray(pw*ph);ink(small,pw,false,checkActive).copyInto(output)
            return Tensor(output,pw,ph,w,scale)
        } finally { small.release() }
    }
    fun line(gray:Mat,box:IndraMath.Box,checkActive:()->Unit):Tensor {
        val x0=max(0,box.left.toInt());val y0=max(0,box.top.toInt())
        val x1=min(gray.cols(),box.right.toInt()+1);val y1=min(gray.rows(),box.bottom.toInt()+1)
        val histogram=IntArray(256);var count=0
        if(x1>x0 && y1>y0) {
            val roi=gray.submat(y0,y1,x0,x1)
            try { val bytes=ByteArray(roi.rows()*roi.cols());roi.get(0,0,bytes)
                bytes.forEachIndexed { i,b -> if(i%4096==0) checkActive();histogram[b.toInt() and 255]++;count++ }
            } finally { roi.release() }
        }
        val dark=IndraMath.dark(histogram,count)
        val height=max(1f,box.bottom-box.top)
        // rec_window=3 expands pad_y=(3*(1+2*.2)-1)/2=1.6; center band is still 32px.
        val left=floor(box.left-.3*height).toInt();val right=ceil(box.right+.3*height).toInt()
        val top=floor(box.top-1.6*height).toInt();val bottom=ceil(box.bottom+1.6*height).toInt()
        val cw=max(1,right-left);val ch=max(1,bottom-top)
        require(cw<=4096 && ch<=8192) { "Indra padded crop exceeds bounded input" }
        val contentWidth=round(cw*96.0/ch).toInt().coerceIn(8,1024)
        val pw=(contentWidth+7)/8*8
        val crop=Mat(ch,cw,CvType.CV_8UC1,Scalar(if(dark) 0.0 else 255.0));val resized=Mat()
        try {
            val sx0=max(0,left);val sx1=min(gray.cols(),right);val sy0=max(0,top);val sy1=min(gray.rows(),bottom)
            if(sx1>sx0 && sy1>sy0) {
                val src=gray.submat(sy0,sy1,sx0,sx1);val dst=crop.submat(sy0-top,sy1-top,sx0-left,sx1-left)
                try { src.copyTo(dst) } finally { src.release();dst.release() }
            }
            checkActive()
            Imgproc.resize(crop,resized,Size(contentWidth.toDouble(),96.0),0.0,0.0,if(ch>96) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            return Tensor(ink(resized,pw,dark,checkActive),pw,96,contentWidth,dark=dark,
                sourceX=left,sourceY=top,sourceWidth=cw,sourceHeight=ch)
        } finally { crop.release();resized.release() }
    }
}
