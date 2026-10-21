package com.yourapp.wine.textlab

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import com.yourapp.wine.geom.Matrix3
import com.yourapp.wine.geom.OpenCvRuntime
import com.yourapp.wine.ocr.NativeEngineGate
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.*
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Fresh sessions per test, detector closes before recognizer opens; no shared Paddle detections. */
internal object IndraPipeline {
    private fun session(context:Context,name:String,sha:String,bytes:Long):OrtSession {
        val file=PaddleAssets.verified(context,name,sha,bytes,"indra-v1")
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(false);options.setMemoryPatternOptimization(false)
            OrtEnvironment.getEnvironment().createSession(file.path,options)
        }
    }
    private suspend fun <T> inference(session:OrtSession,inputs:Map<String,OnnxTensor>,outputs:Set<String>,
        consume:(OrtSession.Result)->T):T = OrtSession.RunOptions().use { options ->
        coroutineScope {
            val stop=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { options.setTerminate(true) }
            }
            try {
                session.run(inputs,outputs,options).use { result ->
                    currentCoroutineContext().ensureActive();consume(result)
                }
            } finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
        }
    }
    private fun longs(tensor:OnnxTensor):LongArray=when(tensor.info.type) {
        OnnxJavaType.INT64 -> tensor.longBuffer.let { b -> LongArray(b.remaining()).also { b.get(it) } }
        OnnxJavaType.INT32 -> tensor.intBuffer.let { b -> LongArray(b.remaining()) { b.get().toLong() } }
        else -> error("Expected Indra integer output, got ${tensor.info.type}")
    }
    suspend fun recognize(context:Context,base:Bitmap,deadline:ScanDeadline,gate:NativeEngineGate,
        mapping:Matrix3,originalW:Int,originalH:Int,journal:TextLabJournal):PaddleLabelPipeline.Outcome {
        val lease=gate.acquire()
        var ownedGray:Mat?=null;var ownedRgba:Mat?=null
        val owner=currentCoroutineContext();val checkActive={ owner.ensureActive();if(deadline.expired) throw CandidateBudgetExpired() }
        try {
            checkActive();OpenCvRuntime.ensureLoaded()
            val gray=Mat().also { ownedGray=it };val rgba=Mat().also { ownedRgba=it }
            Utils.bitmapToMat(base,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);rgba.release()
            val metaFile=PaddleAssets.verified(context,"meta.json",IndraSpec.META_SHA,211869,"indra-v1")
            val meta=JSONObject(metaFile.readText(Charsets.UTF_8))
            require(meta.getInt("det_long_side")==1024 && meta.getInt("rec_height")==32 && meta.getInt("rec_window")==3)
            val vocabulary=JSONObject(meta.getString("vocab"));require(vocabulary.getInt("blank")==0)
            val chars=vocabulary.getJSONArray("chars");require(chars.length()==1697)
            val vocab=List(chars.length()) { chars.getString(it) }
            val regions=if(journal.detectLines) {
                val setupStart=SystemClock.elapsedRealtime()
                session(context,"det.onnx",IndraSpec.DET_SHA,389948).use { det ->
                    require(det.inputNames==setOf("x") && det.outputNames.size==1) { "Unexpected Indra detector contract" }
                    val setupMs=SystemClock.elapsedRealtime()-setupStart
                    val preStart=SystemClock.elapsedRealtime();val input=IndraInput.detector(gray,checkActive)
                    val preMs=SystemClock.elapsedRealtime()-preStart
                    OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(input.values),longArrayOf(1,1,input.height.toLong(),input.width.toLong())).use { tensor ->
                        val start=SystemClock.elapsedRealtime()
                        inference(det,mapOf("x" to tensor),det.outputNames) { result ->
                            val inferMs=SystemClock.elapsedRealtime()-start
                            val output=result[0] as? OnnxTensor ?: error("Indra detector output is not a tensor")
                            val shape=output.info.shape
                            require(output.info.type==OnnxJavaType.FLOAT && shape.size==4 && shape[0]==1L && shape[1] in 3L..4L &&
                                shape[2]==input.height/2L && shape[3]==input.width/2L) { "Unexpected Indra detector output ${shape.contentToString()}" }
                            val values=output.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                            require(values.size==shape.fold(1L,Long::times).toInt())
                            val postStart=SystemClock.elapsedRealtime()
                            val decoded=IndraMath.regions(values,shape[3].toInt(),shape[2].toInt(),input.scale,base.width,base.height,checkActive)
                            journal.add(IndraSpec.ID,"detection",values=mapOf("setup_ms" to "$setupMs","preprocess_ms" to "$preMs",
                                "inference_ms" to "$inferMs","postprocess_ms" to "${SystemClock.elapsedRealtime()-postStart}",
                                "input_shape" to "1 1 ${input.height} ${input.width}","output_shape" to shape.joinToString(" "),
                                "region_count" to "${decoded.boxes.size}","candidate_count" to "${decoded.components}","truncated" to "${decoded.truncated}",
                                "map_to_base" to Matrix3.scale(2/input.scale,2/input.scale).values.joinToString(" "),
                                "base_to_map" to Matrix3.scale(input.scale/2,input.scale/2).values.joinToString(" ")))
                            decoded
                        }
                    }
                }
            } else IndraMath.Regions(listOf(IndraMath.Box(0f,0f,base.width.toFloat(),base.height.toFloat(),1f)),1,false)
            fun coordinates(box:IndraMath.Box,index:Int):Map<String,String> {
                val quad=listOf(PointF(box.left,box.top),PointF(box.right,box.top),PointF(box.right,box.bottom),PointF(box.left,box.bottom))
                val original=quad.map(mapping::map)
                return mapOf("region_id" to "${index+1}","detector_score" to if(journal.detectLines) "${box.score}" else "",
                    "base_polygon" to quad.joinToString(";") { "${it.x} ${it.y}" },"original_polygon" to original.joinToString(";") { "${it.x} ${it.y}" },
                    "base_box_normalized" to PaddleDetectionGeometry.normalizedBox(quad,base.width,base.height),
                    "original_box_normalized" to PaddleDetectionGeometry.normalizedBox(original,originalW,originalH))
            }
            regions.boxes.forEachIndexed { i,box -> journal.add(IndraSpec.ID,"region",values=coordinates(box,i)) }
            if(regions.boxes.isEmpty()) return PaddleLabelPipeline.Outcome("no_regions",0,0,0,0)
            var read=0;var failed=0;var attempted=0
            val recStart=SystemClock.elapsedRealtime()
            session(context,"rec.onnx",IndraSpec.REC_SHA,5042161).use { rec ->
                require(rec.inputNames==setOf("x","widths") && rec.outputNames.containsAll(setOf("ids","conf","lengths"))) { "Unexpected Indra recognizer contract" }
                val setupMs=SystemClock.elapsedRealtime()-recStart
                journal.add(IndraSpec.ID,"recognizer_setup",values=mapOf("setup_ms" to "$setupMs"))
                for((index,box) in regions.boxes.withIndex()) {
                    checkActive();attempted++
                    val location=coordinates(box,index);journal.add(IndraSpec.ID,"line_start","running",location)
                    try {
                        val preStart=SystemClock.elapsedRealtime();val input=IndraInput.line(gray,box,checkActive)
                        val preMs=SystemClock.elapsedRealtime()-preStart
                        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(input.values),longArrayOf(1,1,96,input.width.toLong())).use { tensor ->
                            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),LongBuffer.wrap(longArrayOf(input.contentWidth.toLong())),longArrayOf(1)).use { widths ->
                                val start=SystemClock.elapsedRealtime()
                                inference(rec,mapOf("x" to tensor,"widths" to widths),setOf("ids","conf","lengths")) { result ->
                                    val inferMs=SystemClock.elapsedRealtime()-start
                                    val ids=result.get("ids").get() as OnnxTensor;val conf=result.get("conf").get() as OnnxTensor
                                    require(ids.info.shape.size==2 && ids.info.shape[0]==1L && ids.info.shape[1] in 1L..1024L &&
                                        conf.info.type==OnnxJavaType.FLOAT && conf.info.shape.contentEquals(ids.info.shape))
                                    val lengths=longs(result.get("lengths").get() as OnnxTensor);require(lengths.size==1 && lengths[0] in 0L..ids.info.shape[1])
                                    val ps=conf.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                                    val reading=IndraMath.decode(longs(ids),ps,lengths[0].toInt(),vocab)
                                    val text=if(reading.accepted) reading.raw else ""
                                    if(text.isNotBlank()) read++
                                    val lineToBase=Matrix3(listOf(input.sourceWidth.toDouble()/input.contentWidth,0.0,input.sourceX.toDouble(),
                                        0.0,input.sourceHeight/96.0,input.sourceY.toDouble(),0.0,0.0,1.0))
                                    journal.add(IndraSpec.ID,"recognition",if(text.isBlank()) "no_text" else "complete",location+mapOf(
                                        "raw_text" to text,"indra_unfiltered_text" to reading.raw,"ctc_posterior" to "${reading.confidence}",
                                        "preprocess_ms" to "$preMs","inference_ms" to "$inferMs","input_shape" to "1 1 96 ${input.width}",
                                        "output_shape" to ids.info.shape.joinToString(" "),"content_width" to "${input.contentWidth}","indra_dark_line" to "${input.dark}",
                                        "line_to_base" to lineToBase.values.joinToString(" "),"base_to_line" to lineToBase.inverse().values.joinToString(" "))+TextLabMemory.sample())
                                }
                            }
                        }
                    } catch(cancel:CancellationException) { throw cancel }
                    catch(expired:CandidateBudgetExpired) { throw expired }
                    catch(error:Exception) { failed++;journal.add(IndraSpec.ID,"recognition","failed",location,error.message.orEmpty().take(1500)) }
                }
            }
            return PaddleLabelPipeline.Outcome(if(failed>0) "partial_failed" else if(regions.truncated) "truncated" else if(read==0) "no_text" else "complete",regions.boxes.size,attempted,read,failed)
        } finally { try { ownedGray?.release() } finally { try { ownedRgba?.release() } finally { lease.close() } } }
    }
}
