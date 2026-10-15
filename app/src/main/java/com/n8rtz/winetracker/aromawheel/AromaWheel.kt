package com.n8rtz.winetracker.aromawheel

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Interactive wine aroma wheel.
 *
 * Gestures: pinch to zoom, drag to pan (both at once), tap a family / group / outer-band label to zoom to that
 * sector (tap it again to zoom back out), tap the centre badge to reset, tap a descriptor to toggle it.
 * A selected descriptor is marked by an outline that follows the cell's exact perimeter and nothing else.
 *
 * The wheel is stateless about the selection: it only reports taps through [onToggle]. Apply the change with
 * [AromaSelection.toggle] (which enforces the max and rejects unknown ids) or your own rule.
 *
 * Give it a size, e.g. `Modifier.fillMaxSize()`. It draws over the whole area (the wheel is fitted and centred
 * on open), so for the "wheel only" experience make the host fullscreen and pass [onClose] for the tiny ×.
 *
 * @param selectedIds stable descriptor ids currently selected.
 * @param onToggle called with a descriptor id when the user taps it.
 * @param onClose if non-null, shows a small × in the top-right corner (overlaid; it never shrinks the wheel).
 * @param typeface base typeface for labels (defaults to the system sans). Bold/medium variants are derived.
 */
@Composable
fun AromaWheel(
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    typeface: Typeface? = null,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val controller = remember { WheelController(scope) }
    val paints = remember(typeface) { WheelPaints(typeface) }

    SideEffect {
        controller.onToggle = onToggle
        controller.minTouchPx = with(density) { MIN_TOUCH_CELL_DP.dp.toPx() }
    }
    val outlinePx = with(density) { OUTLINE_DP.dp.toPx() }

    val description = "Aroma wheel. ${selectedIds.size} descriptors selected. Pinch to zoom, drag to pan."

    Box(modifier.clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { controller.onSize(it.width, it.height) }
                .semantics {
                    contentDescription = description
                    customActions = listOf(
                        CustomAccessibilityAction("Zoom in") { controller.zoomBy(1.5f); true },
                        CustomAccessibilityAction("Zoom out") { controller.zoomBy(1f / 1.5f); true },
                        CustomAccessibilityAction("Show whole wheel") { controller.reset(); true },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ -> controller.transform(centroid, pan, zoom) }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> controller.tap(offset) }
                },
        ) {
            drawRect(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color(AromaWheelTheme.backgroundInner),
                        0.7f to Color(AromaWheelTheme.backgroundMid),
                        1f to Color(AromaWheelTheme.backgroundOuter),
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = max(size.width, size.height) * 0.75f,
                ),
            )
            val v = controller.view
            if (v.k <= 0f) return@Canvas
            withTransform({
                translate(size.width / 2f, size.height / 2f)
                scale(v.k, v.k, pivot = Offset.Zero)
                translate(-v.cx, -v.cy)
            }) {
                drawWheel(selectedIds, v, outlinePx, paints)
            }
        }

        if (onClose != null) {
            CloseButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)),
            )
        }
    }
}

private const val MIN_TOUCH_CELL_DP = 20
private const val OUTLINE_DP = 4

/* ------------------------------------------------------------------------------------------------ */
/* View state + gesture logic                                                                         */
/* ------------------------------------------------------------------------------------------------ */

/** [cx],[cy]: wheel-space point shown at the centre of the screen; [k]: screen pixels per wheel unit (0 = not laid out yet). */
internal data class WheelView(val cx: Float, val cy: Float, val k: Float)

@Stable
internal class WheelController(private val scope: CoroutineScope) {
    var view by mutableStateOf(WheelView(0f, 0f, 0f))
        private set

    var onToggle: (String) -> Unit = {}
    var minTouchPx: Float = 60f

    private var w = 0f
    private var h = 0f
    private var animJob: Job? = null

    private fun fit() = max(min(w, h), 1f) / (2f * VIEW_R)
    private fun kMin() = fit() * 0.8f
    private fun kMax() = max(fit() * 8f, minTouchPx / WheelLayout.leafArcUnits * 2f)

    private fun clampView(v: WheelView): WheelView {
        val k = v.k.coerceIn(kMin(), kMax())
        val limX = max(0f, VIEW_R - w / k / 2f)
        val limY = max(0f, VIEW_R - h / k / 2f)
        return WheelView(v.cx.coerceIn(-limX, limX), v.cy.coerceIn(-limY, limY), k)
    }

    private fun fitView() = WheelView(0f, 0f, fit())

    private fun viewForNode(id: String): WheelView {
        val b = WheelLayout.sectorBounds(id)
        val pad = 14f
        val bw = b.maxX - b.minX + pad * 2f
        val bh = b.maxY - b.minY + pad * 2f
        return WheelView((b.minX + b.maxX) / 2f, (b.minY + b.maxY) / 2f, min(w / bw, h / bh))
    }

    fun onSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val prevFit = if (w > 0f) fit() else 0f
        val ratio = if (view.k > 0f && prevFit > 0f) view.k / prevFit else 0f
        w = width.toFloat()
        h = height.toFloat()
        view = if (ratio == 0f) fitView() else clampView(view.copy(k = ratio * fit()))
    }

    /** Pinch + pan in one step: the wheel point under the old gesture centre follows the new centre. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val v = view
        if (v.k <= 0f) return
        animJob?.cancel()
        val wx = v.cx + (centroid.x - w / 2f) / v.k
        val wy = v.cy + (centroid.y - h / 2f) / v.k
        val k2 = (v.k * zoom).coerceIn(kMin(), kMax())
        val mx = centroid.x + pan.x
        val my = centroid.y + pan.y
        view = clampView(WheelView(wx - (mx - w / 2f) / k2, wy - (my - h / 2f) / k2, k2))
    }

    fun zoomBy(factor: Float) {
        val v = view
        if (v.k <= 0f) return
        animateTo(v.copy(k = v.k * factor))
    }

    fun reset() {
        if (view.k > 0f) animateTo(fitView())
    }

    fun tap(offset: Offset) {
        val v = view
        if (v.k <= 0f) return
        val wx = v.cx + (offset.x - w / 2f) / v.k
        val wy = v.cy + (offset.y - h / 2f) / v.k
        val hit = WheelLayout.hitTest(wx, wy) ?: return
        when (hit.kind) {
            HitKind.CENTER -> reset()
            HitKind.FAMILY, HitKind.GROUP -> {
                val target = viewForNode(hit.id!!)
                val t = clampView(target)
                val alreadyThere = abs(kotlin.math.ln(v.k / t.k)) < 0.08f && hypot(v.cx - t.cx, v.cy - t.cy) < 10f
                animateTo(if (alreadyThere) fitView() else target)
            }
            HitKind.LEAF -> {
                // Too small to hit precisely at this zoom: the first tap zooms to the descriptor's group,
                // the next tap selects. Prevents mis-taps at the overview zoom on a phone.
                if (WheelLayout.leafArcUnits * v.k < minTouchPx) {
                    val groupId = AromaTaxonomy.node(hit.id!!)?.parentId
                    if (groupId != null) {
                        val target = viewForNode(groupId)
                        if (clampView(target).k > v.k * 1.08f) {
                            animateTo(target)
                            return
                        }
                    }
                }
                onToggle(hit.id!!)
            }
        }
    }

    private fun animateTo(target: WheelView) {
        animJob?.cancel()
        val from = view
        val to = clampView(target)
        // Honours the system "remove animations" setting: Compose scales tween durations by it (0 = instant).
        animJob = scope.launch {
            Animatable(0f).animateTo(1f, tween(320, easing = FastOutSlowInEasing)) {
                val e = value
                view = clampView(
                    WheelView(
                        from.cx + (to.cx - from.cx) * e,
                        from.cy + (to.cy - from.cy) * e,
                        from.k * (to.k / from.k).pow(e),
                    ),
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Drawing                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

internal class WheelPaints(base: Typeface?) {
    val regular: Typeface = base ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create(base ?: Typeface.DEFAULT, Typeface.BOLD)
    val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
}

private val SEPARATOR_STROKE = Stroke(width = 1.6f, join = StrokeJoin.Round)
private val LEAF_EDGE_STROKE = Stroke(width = 0.8f, join = StrokeJoin.Round)

private fun DrawScope.drawWheel(selected: Set<String>, view: WheelView, outlinePx: Float, p: WheelPaints) {
    val nc = drawContext.canvas.nativeCanvas
    val G = WheelGraphics
    val sep = Color(AromaWheelTheme.separator)
    val halfW = size.width / 2f / view.k
    val halfH = size.height / 2f / view.k
    fun visible(x: Float, y: Float) = abs(x - view.cx) < halfW + 60f && abs(y - view.cy) < halfH + 60f

    // Ring 1: Primary / Secondary / Tertiary / Faults
    for (c in G.ring1) {
        drawPath(c.path, Color(AromaWheelTheme.ring1Fill))
        drawPath(c.path, sep, style = SEPARATOR_STROKE)
        drawPlannedLabel(nc, p, "r1", c, WheelRadii.RING1, AromaWheelTheme.ring1Ink)
    }

    // Ring 2: groups
    for (c in G.ring2) {
        val th = AromaWheelTheme.forGroup(c.node.id)!!
        drawPath(c.path, Color(th.main))
        drawPath(c.path, sep, style = SEPARATOR_STROKE)
        drawPlannedLabel(nc, p, "r2", c, WheelRadii.RING2, AromaWheelTheme.white)
    }

    // Ring 3: descriptors (no per-descriptor dividers; each group gets one outline)
    for ((gi, group) in AromaTaxonomy.groups.withIndex()) {
        val th = AromaWheelTheme.forGroup(group.id)!!
        val tint = Color(th.tint)
        for (c in G.ring3ByGroup[gi]) {
            if (!visible(c.cx, c.cy)) continue
            drawPath(c.path, tint)
            drawPath(c.path, tint, style = LEAF_EDGE_STROKE)
            drawLeafLabel(nc, p, c, WheelLayout.labelPlan("r3", c.node.id), c.node.label, c.node.id in selected, th.ink)
        }
        drawPath(G.groupOutlines[gi], sep, style = SEPARATOR_STROKE)
    }

    // Outer band: group name again, wrapped into stacked curved lines
    for (c in G.band) {
        val th = AromaWheelTheme.forGroup(c.node.id)!!
        drawPath(c.path, Color(th.band ?: th.main))
        drawPath(c.path, sep, style = SEPARATOR_STROKE)
        drawPlannedLabel(nc, p, "r4", c, WheelRadii.BAND, AromaWheelTheme.white)
    }

    drawCenterBadge(nc, p)

    // Selection = ONE shape: the cell's own perimeter. The stroke is centred on the edge and clipped to the cell,
    // so only the inner half shows; its width is divided by the zoom so it stays crisp at any scale.
    val outline = Color(AromaWheelTheme.outline)
    for (id in selected) {
        val c = G.leafCellById[id] ?: continue
        clipPath(c.path) {
            drawPath(c.path, outline, style = Stroke(width = outlinePx / view.k, join = StrokeJoin.Round))
        }
    }
}

private fun mid(span: Span) = span.mid

/** Ring 1 / 2 / outer-band label: curved (one or more stacked lines) or radial, as decided by the layout planner. */
private fun drawPlannedLabel(
    nc: android.graphics.Canvas, p: WheelPaints, ringKey: String, c: WheelCell, ring: Radii, color: Int,
) {
    val plan = WheelLayout.labelPlan(ringKey, c.node.id) ?: return
    val paint = p.text
    paint.color = color
    paint.textSize = plan.size
    paint.letterSpacing = plan.track
    paint.typeface = if (plan.bold) p.bold else p.regular
    paint.textAlign = Paint.Align.LEFT

    if (plan.mode == LabelMode.TANGENTIAL) {
        val arcs = WheelGraphics.textArcs[WheelLayout.planKey(ringKey, c.node.id)] ?: return
        plan.lines.forEachIndexed { i, line ->
            val arc = arcs[i]
            // Centre along the arc (measure the real text, so any typeface stays centred).
            val offset = (arc.length - paint.measureText(line)) / 2f
            nc.drawTextOnPath(line, arc.path, offset, 0f, paint)
        }
        return
    }

    val m = mid(c.span)
    val at = WheelLayout.pt(ring.mid, m)
    nc.save()
    nc.translate(at.x, at.y)
    nc.rotate(WheelLayout.radialRotation(m))
    paint.textAlign = Paint.Align.CENTER
    val fm = paint.fontMetrics
    val baselineShift = -(fm.ascent + fm.descent) / 2f // vertically centre on the line
    val n = plan.lines.size
    plan.lines.forEachIndexed { i, line ->
        nc.drawText(line, 0f, (i - (n - 1) / 2f) * plan.size * 1.15f + baselineShift, paint)
    }
    nc.restore()
}

/** Descriptor label: runs along the radius, aligned to the outer edge, flipped on the left half so it reads upright. */
private fun drawLeafLabel(
    nc: android.graphics.Canvas, p: WheelPaints, c: WheelCell, plan: LabelPlan?, label: String, isSelected: Boolean, ink: Int,
) {
    val m = mid(c.span)
    val left = WheelLayout.isLeftHalf(m)
    val at = WheelLayout.pt(WheelRadii.RING3.outer - 7f, m)
    val paint = p.text
    paint.color = if (isSelected) AromaWheelTheme.outline else ink
    paint.textSize = plan?.size ?: 9f
    paint.letterSpacing = 0f
    paint.typeface = if (isSelected) p.bold else p.regular
    paint.textAlign = if (left) Paint.Align.LEFT else Paint.Align.RIGHT
    val fm = paint.fontMetrics
    nc.save()
    nc.translate(at.x, at.y)
    nc.rotate(if (left) m + 90f else m - 90f)
    nc.drawText(label, 0f, -(fm.ascent + fm.descent) / 2f, paint)
    nc.restore()
}

private fun DrawScope.drawCenterBadge(nc: android.graphics.Canvas, p: WheelPaints) {
    val r = WheelRadii.CENTER
    drawCircle(Color(AromaWheelTheme.centerRed), radius = r)
    drawCircle(Color.White, radius = r, style = Stroke(width = 2.5f))
    drawCircle(Color.White, radius = r - 17f)

    val paint = p.text
    paint.typeface = p.bold
    paint.textAlign = Paint.Align.LEFT

    // Ring text, spaced so it exactly fills the circle.
    val ringText = "AROMA FINDER \u2022 FLAVOR FINDER \u2022"
    paint.color = AromaWheelTheme.white
    paint.textSize = 5.6f
    paint.letterSpacing = 0f
    val natural = paint.measureText(ringText)
    paint.letterSpacing = ((WheelGraphics.CENTER_RING_LENGTH - natural) / ringText.length) / paint.textSize
    nc.drawTextOnPath(ringText, WheelGraphics.centerRingText, 0f, 0f, paint)

    paint.color = AromaWheelTheme.centerRed
    paint.textSize = 9.5f
    paint.letterSpacing = 0.04f
    paint.textAlign = Paint.Align.CENTER
    nc.drawText("AROMA", 0f, -2f, paint)
    nc.drawText("WHEEL", 0f, 9f, paint)
}

/* ------------------------------------------------------------------------------------------------ */
/* Close button                                                                                       */
/* ------------------------------------------------------------------------------------------------ */

/** A tiny × with a full 44dp touch target, drawn over the wheel. */
@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .semantics { contentDescription = "Close aroma wheel" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .background(Color.White.copy(alpha = 0.85f), CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(10.dp)) {
                val s = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                val c = Color(0xFF1F2937)
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = s.width, cap = s.cap)
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = s.width, cap = s.cap)
            }
        }
    }
}
