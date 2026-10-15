package com.n8rtz.winetracker.aromawheel

import android.graphics.Path as AndroidPath
import androidx.compose.ui.graphics.Path

/** One drawable cell (a ring segment) in wheel units. */
internal class WheelCell(
    val node: TaxNode,
    val path: Path,
    val span: Span,
    /** Centre of the cell, used for off-screen culling. */
    val cx: Float,
    val cy: Float,
)

/** A baseline for curved text, plus its length so the line can be centred. */
internal class TextArc(val path: AndroidPath, val length: Float)

/**
 * Paths and text baselines for the whole wheel, built once (lazily, on first use) and reused every frame.
 *
 * Arcs are built from short straight segments (<= 0.5 degree) instead of relying on arcTo() sweep
 * semantics: the result is indistinguishable even at maximum zoom, and it behaves identically on every API level.
 */
internal object WheelGraphics {
    val ring1: List<WheelCell>
    val ring2: List<WheelCell>
    val band: List<WheelCell>

    /** Descriptor cells grouped like [AromaTaxonomy.groups] (same order, same size). */
    val ring3ByGroup: List<List<WheelCell>>
    val leafCellById: Map<String, WheelCell>

    /** White outline around each group's whole descriptor sector (same order as groups). */
    val groupOutlines: List<Path>

    /** Curved-text baselines keyed like [WheelLayout.planKey]; one per line. */
    val textArcs: Map<String, List<TextArc>>

    /** Circle (starting at 9 o'clock, clockwise) the centre badge text runs along. */
    val centerRingText: AndroidPath
    const val CENTER_RING_R = 38f
    const val CENTER_RING_LENGTH = 232f

    private fun sectorPath(ring: Radii, span: Span): Path = Path().apply {
        val outer = WheelLayout.arcPoints(ring.outer, span.a0, span.a1)
        val inner = WheelLayout.arcPoints(ring.inner, span.a1, span.a0)
        moveTo(outer[0].x, outer[0].y)
        for (i in 1 until outer.size) lineTo(outer[i].x, outer[i].y)
        for (p in inner) lineTo(p.x, p.y)
        close()
    }

    private fun cell(node: TaxNode, ring: Radii): WheelCell {
        val s = WheelLayout.spanOf(node.id)
        val m = WheelLayout.pt(ring.mid, s.mid)
        return WheelCell(node, sectorPath(ring, s), s, m.x, m.y)
    }

    private fun arcText(r: Float, span: Span, clockwise: Boolean): TextArc {
        val pts = if (clockwise) WheelLayout.arcPoints(r, span.a0, span.a1, 1f) else WheelLayout.arcPoints(r, span.a1, span.a0, 1f)
        val p = AndroidPath()
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
        return TextArc(p, rad(span.sweep) * r)
    }

    init {
        ring1 = AromaTaxonomy.families.map { cell(it, WheelRadii.RING1) }
        ring2 = AromaTaxonomy.groups.map { cell(it, WheelRadii.RING2) }
        band = AromaTaxonomy.groups.map { cell(it, WheelRadii.BAND) }
        ring3ByGroup = AromaTaxonomy.groups.map { g -> g.children.map { cell(it, WheelRadii.RING3) } }
        leafCellById = ring3ByGroup.flatten().associateBy { it.node.id }
        groupOutlines = AromaTaxonomy.groups.map { sectorPath(WheelRadii.RING3, WheelLayout.spanOf(it.id)) }

        val arcs = HashMap<String, List<TextArc>>()
        fun add(ringKey: String, cells: List<WheelCell>) {
            for (c in cells) {
                val plan = WheelLayout.labelPlan(ringKey, c.node.id) ?: continue
                if (plan.mode != LabelMode.TANGENTIAL) continue
                val clockwise = !WheelLayout.isLowerHalf(c.span.mid)
                arcs[WheelLayout.planKey(ringKey, c.node.id)] = plan.baseRadii!!.map { arcText(it, c.span, clockwise) }
            }
        }
        add("r1", ring1)
        add("r2", ring2)
        add("r4", band)
        textArcs = arcs

        centerRingText = AndroidPath().also { p ->
            val pts = WheelLayout.arcPoints(CENTER_RING_R, 270f, 630f, 2f)
            p.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
        }
    }
}
