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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Actual ONNX inference on a MANUALLY selected text line. Not the whole-label optional Paddle bridge. */
internal object PaddleTextRecognizer {
    private const val MODEL_HASH="243a0f06d826761323e9045e9b113ab2c191c3aa50565585e628300b8eda0224"
    private const val DICT_HASH="d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b"
    private val mutex=Mutex()
    private var session:OrtSession?=null
    private var dictionary:List<String> = emptyList()
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(32768)
            while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun open(context:Context):OrtSession {
        session?.let { return it }
        val dir=File(context.noBackupFilesDir,"text-lab-paddle-v1").apply { check(isDirectory || mkdirs()) { "Cannot create private model directory" } }
        fun verified(name:String,expected:String,limit:Long):File {
            val target=File(dir,name)
            if(target.isFile && target.length()==limit && hash(target)==expected) return target
            val temp=File.createTempFile("model-",".tmp",dir)
            try {
                context.assets.open("text-lab-paddle-v1/$name").use { input -> temp.outputStream().use { output ->
                    val buffer=ByteArray(32768);var size=0L
                    while(true) { val n=input.read(buffer);if(n<0) break;size+=n;check(size<=limit) { "Asset $name exceeds pinned size $limit" };output.write(buffer,0,n) }
                } }
                check(temp.length()==limit && hash(temp)==expected) { "Bundled $name checksum/size mismatch: ${temp.length()} bytes; expected $limit" }
                check(temp.renameTo(target)) { "Cannot install verified asset $name" }
            } finally { temp.delete() }
            return target
        }
        val model=verified("inference.onnx",MODEL_HASH,16562373)
        val dict=verified("ppocrv5_dict.utf8",DICT_HASH,74012).readLines(Charsets.UTF_8)
        check(dict.size==18383 && dict.none(String::isEmpty)) { "Dictionary has ${dict.size} entries; expected 18383 nonempty entries" }
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
            check(output.type==OnnxJavaType.FLOAT && output.shape.size==3 && (output.shape[2]<0 || output.shape[2]==18385L)) { "Expected FLOAT [1,time,18385] output; got ${output.type} ${output.shape.contentToString()}" }
            dictionary=dict;session=created
            return created
        } catch(error:Throwable) { created.close();throw error }
    }
    suspend fun recognize(context:Context,crop:Bitmap,workspace:BitmapWorkspace,deadline:ScanDeadline,gate:NativeEngineGate):String = mutex.withLock {
        val lease=gate.acquire()
        var stage="asset verification / model initialization"
        try {
            currentCoroutineContext().ensureActive()
            val loadStart=SystemClock.elapsedRealtime()
            val active=open(context)
            val loadMs=SystemClock.elapsedRealtime()-loadStart
            currentCoroutineContext().ensureActive()
            if(deadline.expired) return@withLock "PP-OCRv5: budget ended during model initialization; retry after warm-up."
            stage="input preprocessing"
            val inputInfo=active.inputInfo.values.single().info as TensorInfo
            val (resizedWidth,paddedWidth)=PaddleCtc.widths(crop.width,crop.height,inputInfo.shape[3])
            val preprocessStart=SystemClock.elapsedRealtime()
            val buffer=ByteBuffer.allocateDirect(3*48*paddedWidth*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val resized=workspace.own(Bitmap.createScaledBitmap(crop,resizedWidth,48,true))
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
            if(deadline.expired) return@withLock "PP-OCRv5: budget ended before inference."
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
                                check(shape.size==3 && shape[0]==1L && shape[1] in 1L..512L && shape[2]==18385L) { "Actual output shape ${shape.contentToString()}; expected [1,time<=512,18385]" }
                                val owner=currentCoroutineContext()
                                val decoded=PaddleCtc.decode(result.floatBuffer,shape[1].toInt(),shape[2].toInt(),dictionary) { owner.ensureActive() }
                                "PP-OCRv5 mobile (ONNX Runtime 1.21.1 CPU)\n"+
                                    "Model SHA-256: $MODEL_HASH\n"+
                                    "Declared input: ${inputInfo.shape.contentToString()}; actual output: ${shape.contentToString()}\n"+
                                    "Model setup: ${loadMs} ms; preprocessing: ${preprocessMs} ms; inference: ${inferenceMs} ms\n"+
                                    "Input: BGR NCHW 1×3×48×$paddedWidth; content width $resizedWidth; normalized zero padding\n"+
                                    "Text: ${decoded.text.ifEmpty { "[no text recognized]" }}\n"+
                                    "Mean retained CTC posterior: ${java.lang.String.format(java.util.Locale.ROOT,"%.3f",decoded.confidence)}\n"+
                                    "Not calibrated and NOT comparable to ML Kit confidence. Recognition-only; no word boxes or wine corrections."
                            }
                        } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
                    }
                }
            }
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("$stage: ${error.javaClass.simpleName}: ${error.message ?: "no detail"}",error)
        } finally { lease.close() }
    }
}
