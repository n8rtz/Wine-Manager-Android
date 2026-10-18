package com.yourapp.wine.textlab

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.ocr.NativeEngineGate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independent PP-OCRv5 mobile detector. No ML Kit boxes, expected words or label-position heuristics. */
internal object PaddleTextDetector {
    const val MODEL_HASH="1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039"
    private val mutex=Mutex()
    private var session:OrtSession?=null
    data class Detection(val regions:PaddleRegions,val description:String)
    private fun open(context:Context):OrtSession {
        session?.let { return it }
        val file=PaddleAssets.verified(context,"detection.onnx",MODEL_HASH,4826518)
        val created=OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            OrtEnvironment.getEnvironment().createSession(file.path,options)
        }
        try {
            check(created.inputNames.size==1 && created.outputNames.size==1) { "Detector must have one input/output" }
            val info=created.inputInfo.values.single().info as? TensorInfo ?: error("Detector input is not a tensor")
            val shape=info.shape
            check(info.type==OnnxJavaType.FLOAT && shape.size==4 && (shape[0]<0 || shape[0]==1L) &&
                (shape[1]<0 || shape[1]==3L) && shape[2]<0 && shape[3]<0) {
                "Expected dynamic detector FLOAT [1,3,H,W], got ${info.type} ${shape.contentToString()}"
            }
            session=created;return created
        } catch(error:Throwable) { created.close();throw error }
    }
    suspend fun detect(context:Context,base:Bitmap,workspace:BitmapWorkspace,gate:NativeEngineGate):Detection = mutex.withLock {
        val lease=gate.acquire()
        var stage="detector asset verification / initialization"
        try {
            currentCoroutineContext().ensureActive()
            val started=SystemClock.elapsedRealtime();val active=open(context)
            val setupMs=SystemClock.elapsedRealtime()-started
            currentCoroutineContext().ensureActive()
            stage="detector preprocessing"
            val prepStart=SystemClock.elapsedRealtime()
            val (width,height)=PaddleDetectionGeometry.inputSize(base.width,base.height)
            val input=ByteBuffer.allocateDirect(3*width*height*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val small=workspace.own(Bitmap.createScaledBitmap(base,width,height,true))
            try {
                val row=IntArray(width)
                repeat(height) { y ->
                    currentCoroutineContext().ensureActive()
                    small.getPixels(row,0,width,0,y,width,1)
                    repeat(3) { channel -> repeat(width) { x ->
                        input.put(channel*width*height+y*width+x,PaddleDetectionGeometry.normalize(row[x],channel))
                    } }
                }
            } finally { if(small!==base) workspace.release(small) }
            val prepMs=SystemClock.elapsedRealtime()-prepStart
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),input,longArrayOf(1,3,height.toLong(),width.toLong())).use { tensor ->
                OrtSession.RunOptions().use { options ->
                    coroutineScope {
                        val stop=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { options.setTerminate(true) }
                        }
                        try {
                            stage="detector inference"
                            val inferStart=SystemClock.elapsedRealtime()
                            active.run(mapOf(active.inputNames.single() to tensor),options).use { result ->
                                val inferMs=SystemClock.elapsedRealtime()-inferStart
                                currentCoroutineContext().ensureActive()
                                stage="detector output / DB decoding"
                                val postStart=SystemClock.elapsedRealtime()
                                val output=result[0] as? OnnxTensor ?: error("Detector output is not a tensor")
                                val shape=output.info.shape
                                check(output.info.type==OnnxJavaType.FLOAT && shape.size==4 && shape[0]==1L && shape[1]==1L &&
                                    shape[2] in 1L..960L && shape[3] in 1L..960L) {
                                    "Expected detector FLOAT [1,1,H<=960,W<=960], got ${output.info.type} ${shape.contentToString()}"
                                }
                                val values=FloatArray((shape[2]*shape[3]).toInt());output.floatBuffer.get(values)
                                val owner=currentCoroutineContext()
                                val regions=PaddleDbPostprocess.decode(values,shape[3].toInt(),shape[2].toInt(),base.width,base.height) { owner.ensureActive() }
                                val postMs=SystemClock.elapsedRealtime()-postStart
                                val mapToBase=PaddleDetectionGeometry.mapToBase(shape[3].toInt(),shape[2].toInt(),base.width,base.height)
                                Detection(regions,"PP-OCRv5 mobile detection → line rectification → recognition\n"+
                                    "Detector SHA-256: $MODEL_HASH\n"+
                                    "Detector setup: $setupMs ms; preprocessing: $prepMs ms; inference: $inferMs ms; DB decoding: $postMs ms\n"+
                                    "Detector input: BGR/ImageNet normalization [1,3,$height,$width]; output: ${shape.contentToString()}\n"+
                                    "Map→base crop matrix: ${mapToBase.values.joinToString(" ")}\n"+
                                    "Base crop→map matrix: ${mapToBase.inverse().values.joinToString(" ")}\n"+
                                    "DB threshold .3; box score .6; rectangular unclip 1.5. ${regions.lines.size} lines; ${regions.candidates} candidates; capped=${regions.truncated}.\n"+
                                    "Detector scores and CTC posteriors are distinct, uncalibrated measures; neither is comparable to ML Kit confidence.")
                            }
                        } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
                    }
                }
            }
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("$stage: ${error.javaClass.simpleName}: ${error.message}",error)
        } finally { lease.close() }
    }
}
