package com.yourapp.wine.textlab

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.ocr.NativeEngineGate
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Actual ONNX inference on a manual line or a detector-supplied, perspective-rectified line. Not the whole-label optional Paddle bridge. */
internal object PaddleTextRecognizer {
    private val mutex=Mutex()
    private var session:OrtSession?=null
    private var loaded:String?=null
    suspend fun release(gate:NativeEngineGate)=mutex.withLock {
        val lease=gate.acquire()
        try { val old=session;session=null;loaded=null;dictionary=emptyList();old?.close() } finally { lease.close() }
    }
    private var dictionary:List<String> = emptyList()
    private fun open(context:Context,spec:PaddleCandidate):OrtSession {
        session?.let { check(loaded==spec.recognizer.sha) { "Previous candidate session was not released" };return it }
        val model=PaddleAssets.verified(context,spec.recognizer.asset,spec.recognizer.sha,spec.recognizer.bytes)
        val dict=PaddleAssets.verified(context,spec.dictionary.asset,spec.dictionary.sha,spec.dictionary.bytes).readLines(Charsets.UTF_8)
        check(dict.size==spec.dictionary.entries && dict.none(String::isEmpty)) { "Dictionary has ${dict.size} entries; expected ${spec.dictionary.entries} nonempty entries" }
        val created=OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            OrtEnvironment.getEnvironment().createSession(model.path,options)
        }
        try {
            check(created.inputNames.size==1 && created.outputNames.size==1) { "Expected one input/output, got ${created.inputNames} / ${created.outputNames}" }
            val input=created.inputInfo.values.single().info as? TensorInfo ?: error("Model input is not a tensor")
            check(input.type==OnnxJavaType.FLOAT) { "Expected FLOAT input, got ${input.type}" }
            PaddleCtc.validateInputShape(input.shape)
            val output=created.outputInfo.values.single().info as? TensorInfo ?: error("Model output is not a tensor")
            check(output.type==OnnxJavaType.FLOAT && output.shape.size==3 && (output.shape[2]<0 || output.shape[2]==(spec.dictionary.entries+2).toLong())) { "Expected FLOAT [1,time,${spec.dictionary.entries+2}] output; got ${output.type} ${output.shape.contentToString()}" }
            dictionary=dict;loaded=spec.recognizer.sha;session=created
            return created
        } catch(error:Throwable) { created.close();throw error }
    }
    suspend fun recognize(context:Context,crop:Bitmap,workspace:BitmapWorkspace,deadline:ScanDeadline,gate:NativeEngineGate,lineQuad:List<android.graphics.PointF>?=null,spec:PaddleCandidate=PaddleCandidates.all[0],emit:(Map<String,String>)->Unit={}):String = mutex.withLock {
        val lease=gate.acquire()
        var stage="asset verification / model initialization"
        try {
            currentCoroutineContext().ensureActive()
            val loadStart=SystemClock.elapsedRealtime()
            val active=open(context,spec)
            val loadMs=SystemClock.elapsedRealtime()-loadStart
            currentCoroutineContext().ensureActive()
            if(deadline.expired) throw CandidateBudgetExpired()
            stage="input preprocessing"
            val inputInfo=active.inputInfo.values.single().info as TensorInfo
            val size=lineQuad?.let(PaddleDetectionGeometry::lineSize) ?: (crop.width to crop.height)
            val (resizedWidth,paddedWidth)=PaddleCtc.widths(size.first,size.second,inputInfo.shape[3])
            val preprocessStart=SystemClock.elapsedRealtime()
            val buffer=ByteBuffer.allocateDirect(3*48*paddedWidth*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val warp=lineQuad?.let { PaddleLineWarp.create(crop,it,resizedWidth,48,workspace) }
            val resized=warp?.bitmap ?: workspace.own(Bitmap.createScaledBitmap(crop,resizedWidth,48,true))
            try {
                val row=IntArray(resizedWidth)
                repeat(48) { y ->
                    currentCoroutineContext().ensureActive()
                    resized.getPixels(row,0,resizedWidth,0,y,resizedWidth,1)
                    repeat(3) { channel -> repeat(resizedWidth) { x ->
                        buffer.put(channel*48*paddedWidth+y*paddedWidth+x,PaddleCtc.channel(row[x],channel))
                    } }
                }
            } finally { if(resized!==crop) workspace.release(resized) }
            val preprocessMs=SystemClock.elapsedRealtime()-preprocessStart
            currentCoroutineContext().ensureActive()
            if(deadline.expired) throw CandidateBudgetExpired()
            stage="input tensor creation"
            val env=OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env,buffer,longArrayOf(1,3,48,paddedWidth.toLong())).use { tensor ->
                OrtSession.RunOptions().use { options ->
                    coroutineScope {
                        // ONNX run is synchronous. Cancel asks native code to terminate, but keep its gate,
                        // tensors and RunOptions alive until run actually returns. Never free borrowed data.
                        val stop=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { options.setTerminate(true) }
                        }
                        try {
                            stage="ONNX inference"
                            val start=SystemClock.elapsedRealtime()
                            active.run(mapOf(active.inputNames.single() to tensor),options).use { output ->
                                val inferenceMs=SystemClock.elapsedRealtime()-start
                                currentCoroutineContext().ensureActive()
                                stage="output validation / CTC decoding"
                                val result=output[0] as? OnnxTensor ?: error("Inference output is not a tensor")
                                val shape=result.info.shape
                                check(shape.size==3 && shape[0]==1L && shape[1] in 1L..512L && shape[2]==(spec.dictionary.entries+2).toLong()) { "Actual output shape ${shape.contentToString()}; expected [1,time<=512,${spec.dictionary.entries+2}]" }
                                val owner=currentCoroutineContext()
                                val decodeStart=SystemClock.elapsedRealtime()
                                val decoded=PaddleCtc.decode(result.floatBuffer,shape[1].toInt(),shape[2].toInt(),dictionary) { owner.ensureActive() }
                                val decodeMs=SystemClock.elapsedRealtime()-decodeStart
                                emit(mapOf("postprocess_ms" to decodeMs.toString(),"raw_text" to decoded.text,"ctc_posterior" to decoded.confidence.toString(),
                                    "setup_ms" to loadMs.toString(),"preprocess_ms" to preprocessMs.toString(),"inference_ms" to inferenceMs.toString(),
                                    "input_shape" to "1 3 48 $paddedWidth","content_width" to resizedWidth.toString(),"output_shape" to shape.joinToString(" "),
                                    "line_to_base" to (warp?.lineToBase?.values?.joinToString(" ") ?: ""),
                                    "base_to_line" to (warp?.lineToBase?.inverse()?.values?.joinToString(" ") ?: ""))+TextLabMemory.sample())
                                "${spec.recognizer.name} (ONNX Runtime 1.21.1 CPU)\n"+
                                    "Model SHA-256: ${spec.recognizer.sha}\n"+
                                    "Declared input: ${inputInfo.shape.contentToString()}; actual output: ${shape.contentToString()}\n"+
                                    "Model setup: ${loadMs} ms; preprocessing: ${preprocessMs} ms; inference: ${inferenceMs} ms\n"+
                                    "Input: BGR NCHW 1×3×48×$paddedWidth; content width $resizedWidth; normalized zero padding\n"+
                                    (warp?.let { "Line pixels→base crop matrix: ${it.lineToBase.values.joinToString(" ")}\nBase crop→line pixels matrix: ${it.lineToBase.inverse().values.joinToString(" ")}\n" } ?: "")+
                                    "Text: ${decoded.text.ifEmpty { "[no text recognized]" }}\n"+
                                    "Mean retained CTC posterior: ${java.lang.String.format(java.util.Locale.ROOT,"%.3f",decoded.confidence)}\n"+
                                    "Not calibrated and NOT comparable to ML Kit confidence. Recognition-only; no word boxes or wine corrections."
                            }
                        } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
                    }
                }
            }
        } catch(cancel:CancellationException) { throw cancel }
        catch(expired:CandidateBudgetExpired) { throw expired }
        catch(error:Exception) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("$stage: ${error.javaClass.simpleName}: ${error.message ?: "no detail"}",error)
        } finally { lease.close() }
    }
}
