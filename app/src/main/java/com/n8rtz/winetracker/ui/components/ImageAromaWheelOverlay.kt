package com.n8rtz.winetracker.ui.components

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.n8rtz.winetracker.R
import kotlin.math.*

// ── Aroma data for the image-based wheel ────────────────────────────────────────
// Each Tier3Aroma maps a name to an angular position on the wheel image.
// Angles are measured in degrees, 0° = 3 o'clock, going clockwise.
// The image starts with CITRUS at roughly -75° (top-right).

private data class WheelAroma(
    val name: String,
    val tier2: String,
    val tier1: String,
    val startAngle: Float,
    val sweepAngle: Float,
    val ring: Int // 2 = tier2 (middle ring), 3 = tier3 (outer ring)
)

// Build the full aroma map from the hierarchy data, using proportional angles.
// This reuses the same aromaHierarchy from AromaWheelOverlay.kt
private val wheelAromas: List<WheelAroma> by lazy {
    val result = mutableListOf<WheelAroma>()
    val totalLeaves = aromaHierarchy.sumOf { t1 ->
        t1.children.sumOf { t2 -> if (t2.children.isEmpty()) 1 else t2.children.size }
    }
    val degreesPerLeaf = 360f / totalLeaves

    var currentAngle = -75f // starting angle (CITRUS at ~top-right in the image)

    for (t1 in aromaHierarchy) {
        val t1Name = t1.name.replace("\n", " ")
        for (t2 in t1.children) {
            val t2Name = t2.name.replace("\n", " ")
            val t2LeafCount = if (t2.children.isEmpty()) 1 else t2.children.size
            val t2Sweep = t2LeafCount * degreesPerLeaf
            val t2Start = currentAngle

            // Add Tier 2 entry
            result.add(WheelAroma(t2Name, t2Name, t1Name, t2Start, t2Sweep, 2))

            if (t2.children.isNotEmpty()) {
                // Add individual Tier 3 aromas
                val aromaAngle = t2Sweep / t2.children.size
                var aStart = t2Start
                for (aroma in t2.children) {
                    result.add(WheelAroma(aroma.name, t2Name, t1Name, aStart, aromaAngle, 3))
                    aStart += aromaAngle
                }
            }
            currentAngle += t2Sweep
        }
    }
    result
}

// ── Composable ──────────────────────────────────────────────────────────────────
@Composable
fun ImageAromaWheelOverlay(
    selectedAromas: List<String>,
    onToggleAroma: (String) -> Unit,
    onClose: () -> Unit
) {
    // Zoom & pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        val maxOffX = (containerSize.width * (newScale - 1f)) / 2f
        val maxOffY = (containerSize.height * (newScale - 1f)) / 2f
        scale = newScale
        offsetX = (offsetX + panChange.x).coerceIn(-maxOffX, maxOffX)
        offsetY = (offsetY + panChange.y).coerceIn(-maxOffY, maxOffY)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                // Header
                Row(
                    Modifier.fillMaxWidth(),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically
                ) {
                    Text(
                        "Wine Aroma Wheel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedAromas.isNotEmpty())
                            Text(
                                "${selectedAromas.size} selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                    }
                }

                // Zoomable wheel image with tap targets + selection highlights
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .onSizeChanged { containerSize = it }
                        .transformable(state = transformState)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                ) {
                    // The wheel image
                    Image(
                        painter = painterResource(R.drawable.wine_aroma_wheel),
                        contentDescription = "Wine Aroma Wheel",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    // Selection highlight overlay + tap handler
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(scale, offsetX, offsetY) {
                                detectTapGestures { tap ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val cx = w / 2f
                                    val cy = h / 2f
                                    val maxR = min(w, h) / 2f

                                    val dx = tap.x - cx
                                    val dy = tap.y - cy
                                    val dist = sqrt(dx * dx + dy * dy)

                                    // Normalize distance as fraction of wheel radius
                                    // The image has: center hole ~12%, inner ring ~30%, mid ring ~55%, outer ring ~92%
                                    val frac = dist / (maxR * 0.92f)

                                    if (frac < 0.13f || frac > 1.08f) return@detectTapGestures

                                    // Calculate angle (0° = right, clockwise)
                                    var ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                    ang = ((ang % 360f) + 360f) % 360f

                                    // Determine which ring was tapped
                                    val targetRing = when {
                                        frac > 0.55f -> 3  // Outer ring = individual aromas
                                        frac > 0.30f -> 2  // Middle ring = subcategories
                                        else -> 1           // Inner ring = main categories
                                    }

                                    if (targetRing == 1) return@detectTapGestures // Don't select categories

                                    // Find matching aroma by angle
                                    val matches = wheelAromas.filter { it.ring == targetRing }
                                    for (wa in matches) {
                                        val s = ((wa.startAngle % 360f) + 360f) % 360f
                                        val e = ((s + wa.sweepAngle) % 360f + 360f) % 360f
                                        val inArc = if (s < e) ang in s..e else (ang >= s || ang <= e)
                                        if (inArc) {
                                            Log.d("WineTracker", "Tapped: ${wa.name} (ring=${wa.ring})")
                                            onToggleAroma(wa.name)
                                            break
                                        }
                                    }
                                }
                            }
                    ) {
                        // Draw highlight arcs for selected aromas
                        val w = size.width
                        val h = size.height
                        val cx = w / 2f
                        val cy = h / 2f
                        val maxR = min(w, h) / 2f * 0.92f

                        for (wa in wheelAromas) {
                            if (!selectedAromas.contains(wa.name)) continue

                            val rInner: Float
                            val rOuter: Float
                            when (wa.ring) {
                                2 -> { rInner = maxR * 0.30f; rOuter = maxR * 0.55f }
                                3 -> { rInner = maxR * 0.55f; rOuter = maxR * 1.0f }
                                else -> continue
                            }

                            // Draw a semi-transparent highlight arc
                            val arcR = rOuter
                            drawArc(
                                color = Color(0x40FFFFFF),
                                startAngle = wa.startAngle,
                                sweepAngle = wa.sweepAngle,
                                useCenter = true,
                                topLeft = Offset(cx - arcR, cy - arcR),
                                size = Size(arcR * 2, arcR * 2)
                            )
                            // Bright border
                            drawArc(
                                color = Color(0xFF722F37),
                                startAngle = wa.startAngle,
                                sweepAngle = wa.sweepAngle,
                                useCenter = true,
                                topLeft = Offset(cx - arcR, cy - arcR),
                                size = Size(arcR * 2, arcR * 2),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                }

                // Selected aromas text
                if (selectedAromas.isNotEmpty()) {
                    Text(
                        "Selected: ${selectedAromas.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
