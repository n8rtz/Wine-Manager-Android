package com.yourapp.wine.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.TokenMerger
import java.util.Locale

private data class OverlayRead(val bounds: RectF, val caption: String)

/** Debug-build-only wireframe overlay in ORIGINAL-capture normalized coordinates; no full photo Bitmap is retained. */
@Composable
fun ScanDebugOverlay(report: ScanReport) {
    var tokens by remember(report) { mutableStateOf(emptyList<OverlayRead>()) }
    LaunchedEffect(report) {
        tokens = withContext(Dispatchers.Default) {
            TokenMerger().merge(report.tokens).map { token ->
                val votes = token.members.groupBy { it.engineId }.map { (engine, reads) ->
                    "$engine=${String.format(Locale.ROOT, "%.2f", reads.maxOf { it.score })}"
                }.joinToString(" ")
                OverlayRead(BoxMath.union(token.members.map { it.bboxOrig }),
                    "${token.canonical} $votes vote=${String.format(Locale.ROOT, "%.2f", token.vote)}")
            }
        }
    }
    Canvas(Modifier.fillMaxWidth().aspectRatio(report.origW.toFloat() / report.origH).background(Color(0xff141b20))) {
        val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 20f; isAntiAlias = true }
        report.quadOrig?.let { quad -> quad.indices.forEach { i ->
            val a = quad[i]; val b = quad[(i + 1) % quad.size]
            drawLine(Color.Cyan, Offset(a.x / report.origW * size.width, a.y / report.origH * size.height),
                Offset(b.x / report.origW * size.width, b.y / report.origH * size.height), 2f)
        } }
        tokens.forEach { token ->
            val box = token.bounds
            drawRect(Color.Yellow, Offset(box.left * size.width, box.top * size.height),
                Size(box.width() * size.width, box.height() * size.height), style = Stroke(2f))
            drawContext.canvas.nativeCanvas.drawText(token.caption,
                box.left * size.width, (box.top * size.height - 4f).coerceAtLeast(20f), paint)
        }
    }
}
