package com.yourapp.wine.textlab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.capture.CaptureDecoder
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.ocr.ScanDeadline
import com.n8rtz.winetracker.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

/** Worker-only operations. Source + crop are the only working bitmaps; no field extraction or reference answers. */
internal object TextLabRunner {
    fun preview(input:File,output:File):TextPhoto {
        BitmapWorkspace().use { workspace ->
            val decoded=CaptureDecoder().decode(input,workspace)
            output.outputStream().use { check(decoded.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
            return TextPhoto(input,output,decoded.bitmap.width,decoded.bitmap.height,decoded.origW,decoded.origH)
        }
    }
    suspend fun recognize(context:Context,photo:TextPhoto,selection:TextCrop,enlarge:Boolean,output:File,comparePaddle:Boolean):String? {
        val started=SystemClock.elapsedRealtime()
        val deadline=ScanDeadline(started+ScanDeadline.BUDGET_MS)
        var partial=""
        val completed=deadline.run {
            val scanner=WineScannerServices.get(context)
            scanner.withBenchmarkEngines { engines,_ ->
                currentCoroutineContext().ensureActive()
                BitmapWorkspace().use { workspace ->
                    val decoded=CaptureDecoder().decode(photo.input,workspace)
                    val source=decoded.bitmap
                    val box=requireNotNull(selection.pixels(source.width,source.height)) { "Draw a larger crop" }
                    val (width,height)=box.outputSize(enlarge)
                    val crop=workspace.create(width,height)
                    Canvas(crop).drawBitmap(source,Rect(box.x,box.y,box.x+box.width,box.y+box.height),
                        RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
                    val mapping=box.toOriginal(source.width,source.height,decoded.origW,decoded.origH,width,height)
                    workspace.release(source)
                    currentCoroutineContext().ensureActive()
                    if(deadline.expired) return@withBenchmarkEngines "Preparation exceeded the 8-second budget. Try again."
                    output.outputStream().use { check(crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                    val mlStarted=SystemClock.elapsedRealtime()
                    var mlFailure:String?=null
                    val tokens=try {
                        val engine=engines.firstOrNull { it.id=="mlkit" && it.available } ?: error("ML Kit unavailable")
                        engine.recognize(crop,"manual-text-crop",deadline)
                    } catch(cancel:CancellationException) { throw cancel }
                    catch(error:Exception) { mlFailure="ML Kit failed: ${error.javaClass.simpleName}";emptyList() }
                    val mlElapsed=SystemClock.elapsedRealtime()-mlStarted
                    partial=buildString {
                        appendLine("OCR Text Lab · build ${BuildConfig.BUILD_COMMIT}")
                        appendLine("ML Kit Latin · raw text only · ${mlElapsed} ms recognition; ${SystemClock.elapsedRealtime()-started} ms including preparation")
                        appendLine("Upright source ${decoded.origW}×${decoded.origH}; working ${photo.width}×${photo.height}")
                        appendLine("Working crop: x=${box.x}, y=${box.y}, w=${box.width}, h=${box.height}")
                        appendLine("OCR input: ${width}×${height}; ${if(enlarge) "up to 2×" else "native working size"}")
                        appendLine("Crop→original matrix: ${mapping.values.joinToString(" ")}")
                        appendLine("Shared ML Kit adapter: grayscale NV21; even-edge padding without coordinate shift.")
                        appendLine("No wine dictionary, producer inference or field assignment.")
                        if(mlFailure!=null) appendLine(mlFailure)
                        if(tokens.isEmpty() && mlFailure==null) appendLine("\nNo text recognized. Try a different box or 2× enlargement.")
                        tokens.forEachIndexed { i,token ->
                            appendLine("\n${i+1}. ${token.text}")
                            appendLine("Confidence: ${String.format(Locale.ROOT,"%.3f",token.score)}${if(token.scoreIsEstimated) " (estimated, not measured)" else " (measured)"}")
                            appendLine("Original polygon: "+token.polygon.map(mapping::map).joinToString { point ->
                                String.format(Locale.ROOT,"(%.1f, %.1f)",point.x,point.y) })
                        }
                    }
                    if(comparePaddle) {
                        val alternative=try {
                            PaddleTextRecognizer.recognize(context,crop,workspace,deadline,WineScannerServices.nativeGate)
                        } catch(cancel:CancellationException) { throw cancel }
                        catch(error:Exception) { "PP-OCRv5 unavailable/failed (${error.javaClass.simpleName}). No alternative reading produced. Check model assets or use a shorter single-line crop." }
                        catch(_:LinkageError) { "PP-OCRv5 runtime unavailable on this device. ML Kit result retained." }
                        partial += "\n\n$alternative"
                    }
                    partial
                }
            }
        }
        return completed ?: partial.takeIf { it.isNotBlank() }?.plus("\n\n8-second comparison budget reached; remaining recognition cancelled. Partial results retained.")
    }
}

data class TextPhoto(val input:File,val preview:File,val width:Int,val height:Int,val originalW:Int,val originalH:Int)
