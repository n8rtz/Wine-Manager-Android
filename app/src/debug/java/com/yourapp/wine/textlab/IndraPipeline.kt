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
        mapping:Matrix3,originalW:Int,originalH:Int,journal:TextLabJournal,enhanced:Boolean=false,pass:IndraPass?=null):PaddleLabelPipeline.Outcome {
        val candidate=if(pass!=null) IndraSpec.MULTIPASS_ID else if(enhanced) IndraSpec.ENHANCED_ID else IndraSpec.ID
        val lease=gate.acquire()
        var ownedGray:Mat?=null;var ownedRgba:Mat?=null;var ownedPage:Mat?=null
        val owner=currentCoroutineContext();val checkActive={ owner.ensureActive();if(deadline.expired) throw CandidateBudgetExpired() }
        try {
            checkActive();OpenCvRuntime.ensureLoaded()
            var gray=Mat().also { ownedGray=it };val rgba=Mat().also { ownedRgba=it }
            Utils.bitmapToMat(base,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);rgba.release()
            val metaFile=PaddleAssets.verified(context,"meta.json",IndraSpec.META_SHA,211869,"indra-v1")
            val meta=JSONObject(metaFile.readText(Charsets.UTF_8))
            require(meta.getInt("det_long_side")==1024 && meta.getInt("rec_height")==32 && meta.getInt("rec_window")==3)
            val vocabulary=JSONObject(meta.getString("vocab"));require(vocabulary.getInt("blank")==0)
            val chars=vocabulary.getJSONArray("chars");require(chars.length()==1697)
            val vocab=List(chars.length()) { chars.getString(it) }
            if(pass?.contrast==true) {
                val clahe=Imgproc.createCLAHE(2.0,org.opencv.core.Size(8.0,8.0))
                val adjusted=Mat()
                try { checkActive();clahe.apply(gray,adjusted);adjusted.copyTo(gray) }
                finally { adjusted.release();clahe.collectGarbage();clahe.clear() }
            }
            var pageToBase=Matrix3.scale(1.0,1.0)
            if(enhanced && pass?.deskew!=false) {
                val started=SystemClock.elapsedRealtime()
                val skew=IndraOrientation.skew(gray,checkActive)
                var chosen=0;var probability=0f;var predicted=0
                checkActive()
                session(context,"orient.onnx",IndraSpec.ORIENT_SHA,371802).use { orient ->
                    require(orient.inputNames==setOf("x") && orient.outputNames.size==1)
                    val input=IndraOrientation.orientationInput(gray,skew,checkActive)
                    OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(input.values),longArrayOf(1,1,768,768)).use { tensor ->
                        inference(orient,mapOf("x" to tensor),orient.outputNames) { result ->
                            val output=result[0] as OnnxTensor
                            require(output.info.type==OnnxJavaType.FLOAT && output.info.shape.contentEquals(longArrayOf(1,5)))
                            val p=output.floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also { buffer.get(it) } }
                            require(p.size==5 && p.all { it.isFinite() && it in 0f..1f })
                            predicted=p.indices.maxBy { p[it] };probability=p[predicted]
                            // Vertical-script classification needs a different recognizer, not a fake base routing.
                            chosen=if(probability>.95f && predicted in 0..3) predicted else 0
                        }
                    }
                }
                val page=IndraOrientation.prepare(gray,chosen,skew,checkActive)
                ownedPage=page.gray;gray=page.gray;pageToBase=page.pageToBase
                journal.add(candidate,"orientation",values=mapOf("elapsed_ms" to "${SystemClock.elapsedRealtime()-started}",
                    "indra_orientation_class" to "$predicted","indra_orientation_probability" to "$probability",
                    "indra_quarter_turns" to "$chosen","indra_skew_degrees" to "${page.skew}",
                    "indra_vertical_unsupported" to "${predicted==4 && probability>.95f}",
                    "indra_page_to_base" to pageToBase.values.joinToString(" "),"indra_base_to_page" to pageToBase.inverse().values.joinToString(" ")))
            }
            val regions=if(journal.detectLines) {
                val setupStart=SystemClock.elapsedRealtime()
                checkActive()
                session(context,"det.onnx",IndraSpec.DET_SHA,389948).use { det ->
                    require(det.inputNames==setOf("x") && det.outputNames.size==1) { "Unexpected Indra detector contract" }
                    val setupMs=SystemClock.elapsedRealtime()-setupStart
                    suspend fun detect(side:Int,pass:Int):Pair<IndraMath.Regions,Double> {
                        val preStart=SystemClock.elapsedRealtime();val input=IndraInput.detector(gray,checkActive,side)
                        val preMs=SystemClock.elapsedRealtime()-preStart
                        return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(input.values),longArrayOf(1,1,input.height.toLong(),input.width.toLong())).use { tensor ->
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
                                val decoded=IndraMath.regions(values,shape[3].toInt(),shape[2].toInt(),input.scale,gray.cols(),gray.rows(),if(enhanced) 256 else 32,checkActive)
                                val mapToBase=pageToBase*Matrix3.scale(2/input.scale,2/input.scale)
                                journal.add(candidate,"detection",values=mapOf("setup_ms" to if(pass==1) "$setupMs" else "0","preprocess_ms" to "$preMs",
                                    "inference_ms" to "$inferMs","postprocess_ms" to "${SystemClock.elapsedRealtime()-postStart}",
                                    "input_shape" to "1 1 ${input.height} ${input.width}","output_shape" to shape.joinToString(" "),
                                    "region_count" to "${decoded.boxes.size}","candidate_count" to "${decoded.components}","truncated" to "${decoded.truncated}",
                                    "indra_detection_pass" to "$pass","indra_detector_long_side" to "$side",
                                    "map_to_base" to mapToBase.values.joinToString(" "),"base_to_map" to mapToBase.inverse().values.joinToString(" ")))
                                decoded to input.scale
                            }
                        }
                    }
                    val first=detect(pass?.edge ?: 1024,1)
                    val heights=first.first.boxes.map { (it.bottom-it.top)*first.second }.sorted()
                    val median=if(heights.isEmpty()) 0.0 else (heights[(heights.size-1)/2]+heights[heights.size/2])/2
                    val nextSide=if(pass==null && enhanced && heights.size>=8 && median<9) (minOf(2048.0,1024*12/maxOf(median,1.0)).toInt()/32)*32 else 1024
                    if(nextSide>1024) detect(nextSide,2).first else first.first
                }
            } else IndraMath.Regions(listOf(IndraMath.Box(0f,0f,gray.cols().toFloat(),gray.rows().toFloat(),1f)),1,false)
            fun coordinates(box:IndraMath.Box,index:Int):Map<String,String> {
                val quad=listOf(PointF(box.left,box.top),PointF(box.right,box.top),PointF(box.right,box.bottom),PointF(box.left,box.bottom))
                val baseQuad=quad.map(pageToBase::map)
                val original=baseQuad.map(mapping::map)
                return mapOf("region_id" to "${index+1}","detector_score" to if(journal.detectLines) "${box.score}" else "",
                    "base_polygon" to baseQuad.joinToString(";") { "${it.x} ${it.y}" },"original_polygon" to original.joinToString(";") { "${it.x} ${it.y}" },
                    "base_box_normalized" to PaddleDetectionGeometry.normalizedBox(baseQuad,base.width,base.height),
                    "original_box_normalized" to PaddleDetectionGeometry.normalizedBox(original,originalW,originalH))
            }
            regions.boxes.forEachIndexed { i,box -> journal.add(candidate,"region",values=coordinates(box,i)) }
            if(regions.boxes.isEmpty()) return PaddleLabelPipeline.Outcome("no_regions",0,0,0,0)
            var read=0;var failed=0;var attempted=0;var degraded=0
            val lm=if(enhanced) try {
                val started=SystemClock.elapsedRealtime()
                val file=PaddleAssets.verified(context,"lm.npz",IndraSpec.LM_SHA,15695957,"indra-v1")
                IndraLanguageModel.load(file,checkActive).also {
                    journal.add(candidate,"language_model_setup",values=mapOf("elapsed_ms" to "${SystemClock.elapsedRealtime()-started}","indra_lm_order" to "${it.order}"))
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(expired:CandidateBudgetExpired) { throw expired }
            catch(error:Exception) {
                degraded++
                journal.add(candidate,"lm_fallback","unavailable",details="language_model_setup: ${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1500))
                null
            } else null
            val recStart=SystemClock.elapsedRealtime()
            checkActive()
            session(context,"rec.onnx",IndraSpec.REC_SHA,5042161).use { rec ->
                require(rec.inputNames==setOf("x","widths") && rec.outputNames.containsAll(setOf("ids","conf","lengths"))) { "Unexpected Indra recognizer contract" }
                val activeLm=if(lm!=null && !rec.outputNames.containsAll(setOf("blank","topv","topi"))) {
                    degraded++;journal.add(candidate,"lm_fallback","unavailable",details="lm_output_names: actual=${rec.outputNames}; greedy retained");null
                } else lm
                val requested=if(activeLm!=null) setOf("ids","conf","lengths","blank","topv","topi") else setOf("ids","conf","lengths")
                val setupMs=SystemClock.elapsedRealtime()-recStart
                journal.add(candidate,"recognizer_setup",values=mapOf("setup_ms" to "$setupMs"))
                for((index,box) in regions.boxes.withIndex()) {
                    checkActive();attempted++
                    val location=coordinates(box,index);journal.add(candidate,"line_start","running",location)
                    var lineStage="line_preprocessing"
                    try {
                        val preStart=SystemClock.elapsedRealtime();val input=IndraInput.line(gray,box,checkActive,if(enhanced) 4096 else 1024)
                        val preMs=SystemClock.elapsedRealtime()-preStart
                        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(input.values),longArrayOf(1,1,96,input.width.toLong())).use { tensor ->
                            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),LongBuffer.wrap(longArrayOf(input.contentWidth.toLong())),longArrayOf(1)).use { widths ->
                                lineStage="recognizer_inference"
                                val start=SystemClock.elapsedRealtime()
                                inference(rec,mapOf("x" to tensor,"widths" to widths),requested) { result ->
                                    val inferMs=SystemClock.elapsedRealtime()-start
                                    lineStage="greedy_output_validation"
                                    val ids=result.get("ids").get() as OnnxTensor;val conf=result.get("conf").get() as OnnxTensor
                                    require(ids.info.shape.size==2 && ids.info.shape[0]==1L && ids.info.shape[1] in 1L..4096L &&
                                        conf.info.type==OnnxJavaType.FLOAT && conf.info.shape.contentEquals(ids.info.shape)) { "Greedy tensor contract: ids=${ids.info.type} ${ids.info.shape.contentToString()} conf=${conf.info.type} ${conf.info.shape.contentToString()}" }
                                    val lengths=longs(result.get("lengths").get() as OnnxTensor);require(lengths.size==1 && lengths[0] in 0L..ids.info.shape[1]) { "Invalid recognizer lengths ${lengths.contentToString()} for ${ids.info.shape.contentToString()}" }
                                    val ps=conf.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                                    val reading=IndraMath.decode(longs(ids),ps,lengths[0].toInt(),vocab)
                                    val postStart=SystemClock.elapsedRealtime()
                                    val rescored=activeLm!=null && reading.confidence>=.5f && reading.confidence.toDouble()<.9
                                    var decoded=reading.raw
                                    var lmApplied=false
                                    var lmError=""
                                    // Publish greedy evidence BEFORE optional LM validation/decoding, including cancellation.
                                    journal.add(candidate,"greedy_recognition",if(reading.accepted && reading.raw.isNotBlank()) "complete" else "no_text",
                                        location+mapOf("raw_text" to reading.raw,"ctc_posterior" to "${reading.confidence}","evidence_role" to "diagnostic; not an additional vote"))
                                    if(rescored) try {
                                        lineStage="lm_output_validation"
                                        fun floats(name:String):Pair<LongArray,FloatArray> {
                                            val t=result.get(name).get() as OnnxTensor;require(t.info.type==OnnxJavaType.FLOAT) { "LM $name type=${t.info.type}, expected FLOAT" }
                                            return t.info.shape to t.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                                        }
                                        val(blankShape,blank)=floats("blank");val(topShape,top)=floats("topv")
                                        val topIds=result.get("topi").get() as OnnxTensor
                                        require(blankShape.contentEquals(ids.info.shape) && topShape.size==3 && topShape[0]==1L && topShape[1]==ids.info.shape[1] &&
                                            topShape[2] in 1L..256L && topIds.info.shape.contentEquals(topShape)) { "LM tensor shapes: ids=${ids.info.shape.contentToString()} blank=${blankShape.contentToString()} topv=${topShape.contentToString()} topi=${topIds.info.shape.contentToString()}" }
                                        lineStage="lm_beam_decode"
                                        val beam=IndraBeam.decode(blank,top,longs(topIds),lengths[0].toInt(),topShape[2].toInt(),requireNotNull(activeLm),checkActive,vocab.size)
                                        // The beam already collapses CTC repeats; do not collapse its repeated characters again.
                                        decoded=IndraMath.tokensText(beam.map { token -> require(token in 1..vocab.size) { "Beam token $token outside recognizer vocabulary ${vocab.size}" };vocab[token-1] })
                                        lmApplied=true
                                    } catch(cancel:CancellationException) { throw cancel }
                                    catch(expired:CandidateBudgetExpired) { throw expired }
                                    catch(error:Exception) {
                                        degraded++
                                        lmError="$lineStage: ${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1500)
                                        decoded=reading.raw
                                        journal.add(candidate,"lm_fallback","greedy_retained",location,lmError)
                                    }
                                    lineStage="coordinate_export"
                                    val text=if(reading.accepted) decoded else ""
                                    if(text.isNotBlank()) read++
                                    val lineToBase=pageToBase*Matrix3(listOf(input.sourceWidth.toDouble()/input.contentWidth,0.0,input.sourceX.toDouble(),
                                        0.0,input.sourceHeight/96.0,input.sourceY.toDouble(),0.0,0.0,1.0))
                                    journal.add(candidate,"recognition",if(text.isBlank()) "no_text" else if(lmError.isNotEmpty()) "greedy_fallback" else "complete",location+mapOf(
                                        "raw_text" to text,"indra_unfiltered_text" to decoded,"indra_greedy_text" to reading.raw,
                                        "indra_lm_requested" to "$rescored","indra_lm_applied" to "$lmApplied","indra_lm_error" to lmError,"postprocess_ms" to "${SystemClock.elapsedRealtime()-postStart}","ctc_posterior" to "${reading.confidence}",
                                        "preprocess_ms" to "$preMs","inference_ms" to "$inferMs","input_shape" to "1 1 96 ${input.width}",
                                        "output_shape" to ids.info.shape.joinToString(" "),"content_width" to "${input.contentWidth}","indra_dark_line" to "${input.dark}",
                                        "line_to_base" to lineToBase.values.joinToString(" "),"base_to_line" to lineToBase.inverse().values.joinToString(" "))+TextLabMemory.sample())
                                }
                            }
                        }
                    } catch(cancel:CancellationException) { throw cancel }
                    catch(expired:CandidateBudgetExpired) { throw expired }
                    catch(error:Exception) { failed++;journal.add(candidate,"recognition","failed",location,"$lineStage: ${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1500)) }
                }
            }
            return PaddleLabelPipeline.Outcome(if(failed>0) "partial_failed" else if(degraded>0) "complete_with_lm_fallback" else if(regions.truncated) "truncated" else if(read==0) "no_text" else "complete",regions.boxes.size,attempted,read,failed)
        } finally { try { ownedPage?.release();ownedGray?.release() } finally { try { ownedRgba?.release() } finally { lease.close() } } }
    }
}
