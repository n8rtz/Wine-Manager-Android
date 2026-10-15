package com.n8rtz.winetracker.aromawheel

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Wheel geometry and label planning. Pure Kotlin (no Android / Compose imports) so it can be
 * unit-tested on the JVM. All lengths are in "wheel units"; the composable scales them to pixels.
 *
 * Angles are DEGREES CLOCKWISE FROM 12 O'CLOCK (the "Flower" group starts at 0).
 */

class Radii(val inner: Float, val outer: Float) {
    val mid: Float get() = (inner + outer) / 2f
    val thickness: Float get() = outer - inner
}

object WheelRadii {
    const val CENTER = 48f
    val RING1 = Radii(50f, 114f) // Primary / Secondary / Tertiary / Faults
    val RING2 = Radii(114f, 196f) // groups
    val RING3 = Radii(196f, 340f) // descriptors
    val BAND = Radii(340f, 376f) // outer band repeating the group name
}

/** Half-size of the bounding square of the whole wheel. */
const val VIEW_R = 386f

data class Pt(val x: Float, val y: Float)

data class Span(val a0: Float, val a1: Float) {
    val mid: Float get() = (a0 + a1) / 2f
    val sweep: Float get() = a1 - a0
}

data class Bounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

enum class HitKind { CENTER, FAMILY, GROUP, LEAF }

/** [id] is null for [HitKind.CENTER]. The outer band reports its group (HitKind.GROUP). */
data class WheelHit(val kind: HitKind, val id: String?)

enum class LabelMode { TANGENTIAL, RADIAL }

class LabelPlan(
    val mode: LabelMode,
    val lines: List<String>,
    val size: Float,
    /** Letter-spacing in em. */
    val track: Float,
    val bold: Boolean,
    /** Tangential only: baseline radius per line, ordered so the text reads upright. */
    val baseRadii: List<Float>? = null,
)

internal fun rad(deg: Float): Float = (deg * PI / 180.0).toFloat()

object WheelLayout {
    /** Every descriptor gets the same angle; groups/families span their descriptors (as in the reference). */
    val degPerLeaf: Float = 360f / AromaTaxonomy.leaves.size

    /** Width (wheel units) of one descriptor cell at the middle of the descriptor ring. */
    val leafArcUnits: Float = rad(degPerLeaf) * WheelRadii.RING3.mid

    const val MIN_LABEL_SIZE = 6.4f

    /** Line height as a multiple of font size for stacked lines. */
    const val LINE_HEIGHT = 1.2f

    private val spans = HashMap<String, Span>()

    init {
        var cursor = 0f
        for (fam in AromaTaxonomy.families) {
            val famStart = cursor
            for (grp in fam.children) {
                val grpStart = cursor
                for (leaf in grp.children) {
                    spans[leaf.id] = Span(cursor, cursor + degPerLeaf)
                    cursor += degPerLeaf
                }
                spans[grp.id] = Span(grpStart, cursor)
            }
            spans[fam.id] = Span(famStart, cursor)
        }
        spans[ROOT_ID] = Span(0f, 360f)
    }

    fun spanOf(id: String): Span = spans[id] ?: error("No wheel position for $id")

    /** Point at radius [r] and angle [deg] (clockwise from the top). Screen y grows downward. */
    fun pt(r: Float, deg: Float): Pt {
        val a = rad(deg)
        return Pt(r * sin(a), -r * cos(a))
    }

    /** Points along an arc from [a0] to [a1] (either direction), at most [stepDeg] apart. */
    fun arcPoints(r: Float, a0: Float, a1: Float, stepDeg: Float = 0.5f): List<Pt> {
        val steps = max(1, kotlin.math.ceil(kotlin.math.abs(a1 - a0) / stepDeg).toInt())
        return (0..steps).map { i -> pt(r, a0 + (a1 - a0) * i / steps) }
    }

    /** True when the mid-angle is on the lower half, where upright text needs the reversed direction. */
    fun isLowerHalf(midDeg: Float): Boolean {
        val m = ((midDeg % 360f) + 360f) % 360f
        return m > 90f && m < 270f
    }

    /** Rotation that makes radial text upright, reading outward on the right and inward on the left. */
    fun isLeftHalf(midDeg: Float): Boolean = ((midDeg % 360f) + 360f) % 360f > 180f

    fun radialRotation(midDeg: Float): Float = if (isLeftHalf(midDeg)) midDeg + 90f else midDeg - 90f

    /* ---------------------------- bounds / hit-testing ---------------------------- */

    /** Bounding box of a family/group sector, from its inner ring edge to the outer band. */
    fun sectorBounds(id: String): Bounds {
        val node = AromaTaxonomy.node(id)
        if (node == null || node.kind == TaxKind.ROOT) return Bounds(-VIEW_R, VIEW_R, -VIEW_R, VIEW_R)
        val s = spanOf(id)
        val rIn = when (node.kind) {
            TaxKind.FAMILY -> WheelRadii.RING1.inner
            TaxKind.GROUP -> WheelRadii.RING2.inner
            else -> WheelRadii.RING3.inner
        }
        val pts = arcPoints(rIn, s.a0, s.a1, 3f) + arcPoints(WheelRadii.BAND.outer, s.a0, s.a1, 3f)
        return Bounds(pts.minOf { it.x }, pts.maxOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.y })
    }

    /** Which cell is under the wheel-space point ([x], [y])? Null outside the wheel. */
    fun hitTest(x: Float, y: Float): WheelHit? {
        val r = hypot(x, y)
        if (r < WheelRadii.RING1.inner) return WheelHit(HitKind.CENTER, null)
        if (r > WheelRadii.BAND.outer) return null
        var deg = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        fun find(nodes: List<TaxNode>): TaxNode? =
            nodes.firstOrNull { val s = spanOf(it.id); deg >= s.a0 && deg < s.a1 }
        return when {
            r < WheelRadii.RING1.outer -> find(AromaTaxonomy.families)?.let { WheelHit(HitKind.FAMILY, it.id) }
            r < WheelRadii.RING2.outer -> find(AromaTaxonomy.groups)?.let { WheelHit(HitKind.GROUP, it.id) }
            r < WheelRadii.RING3.outer -> find(AromaTaxonomy.leaves)?.let { WheelHit(HitKind.LEAF, it.id) }
            else -> find(AromaTaxonomy.groups)?.let { WheelHit(HitKind.GROUP, it.id) }
        }
    }

    /* ------------------------------ label planning ------------------------------ */
    // Every label gets a mode + font size that fits inside its OWN cell, so labels never spill into
    // neighbouring cells. Text widths are estimated with deliberately generous glyph metrics.

    fun glyphEm(ch: Char, bold: Boolean): Float = when {
        ch == ' ' -> 0.3f
        ch in "ijl.,:;'|!" -> 0.34f
        ch in "()-/[]" -> 0.42f
        ch == 'I' -> 0.38f
        ch in "mMwW" -> if (bold) 1.02f else 0.96f
        ch == '&' -> 0.86f
        ch in 'A'..'Z' -> if (bold) 0.8f else 0.76f
        ch in '0'..'9' -> 0.68f
        else -> if (bold) 0.64f else 0.6f
    }

    /** Width in em of one line; [track] is letter-spacing in em. */
    fun textEm(text: String, bold: Boolean, track: Float = 0f): Float =
        text.sumOf { glyphEm(it, bold).toDouble() }.toFloat() + track * max(0, text.length - 1)

    private fun sizes(maxSize: Float): Sequence<Float> =
        generateSequence(maxSize) { it - 0.1f }.takeWhile { it >= MIN_LABEL_SIZE }

    private fun baselineFor(center: Float, size: Float, lower: Boolean) =
        if (lower) center + size * 0.36f else center - size * 0.36f

    private fun radialSize(
        lines: List<String>, a0: Float, a1: Float, ring: Radii, maxSize: Float, bold: Boolean, track: Float,
    ): Float {
        val em = lines.maxOf { textEm(it, bold, track) }
        for (s in sizes(maxSize)) {
            val len = em * s
            if (len > ring.thickness - 8f) continue
            val arcAtInnerEnd = rad(a1 - a0) * (ring.mid - len / 2f) - 3.5f
            val cross = (lines.size - 1) * 1.15f * s + s * 0.8f
            if (cross <= arcAtInnerEnd) return s
        }
        return 0f
    }

    private fun tangentialSize(
        text: String, a0: Float, a1: Float, ring: Radii, maxSize: Float, bold: Boolean, track: Float,
    ): Float {
        val em = textEm(text, bold, track)
        for (s in sizes(maxSize)) {
            if (s * 0.85f > ring.thickness - 6f) continue
            val arc = rad(a1 - a0) * (ring.mid - s * 0.45f) - 9f
            if (em * s <= arc) return s
        }
        return 0f
    }

    private fun choose(
        span: Span, ring: Radii, maxSize: Float, bold: Boolean, track: Float, variants: List<List<String>>,
    ): LabelPlan? {
        val options = mutableListOf<LabelPlan>()
        val lower = isLowerHalf(span.mid)
        variants.firstOrNull { it.size == 1 }?.let { single ->
            val s = tangentialSize(single[0], span.a0, span.a1, ring, maxSize, bold, track)
            if (s > 0f) options += LabelPlan(LabelMode.TANGENTIAL, single, s, track, bold, listOf(baselineFor(ring.mid, s, lower)))
        }
        for (lines in variants) {
            val s = radialSize(lines, span.a0, span.a1, ring, maxSize, bold, track)
            if (s > 0f) options += LabelPlan(LabelMode.RADIAL, lines, s, track, bold)
        }
        if (options.isEmpty()) return null
        val best = options.maxOf { it.size }
        // Prefer the curved, horizontal-reading style whenever it is nearly as large as the best alternative.
        return options.firstOrNull { it.mode == LabelMode.TANGENTIAL && it.size >= best * 0.9f }
            ?: options.first { it.size == best }
    }

    /** Every way to break [text] into 1-3 lines at word boundaries. */
    fun wrapVariants(text: String): List<List<String>> {
        val words = text.split(" ").filter { it.isNotEmpty() }
        val out = mutableListOf(listOf(words.joinToString(" ")))
        for (i in 1 until words.size) out += listOf(words.take(i).joinToString(" "), words.drop(i).joinToString(" "))
        if (words.size == 3) out += words
        return out
    }

    /**
     * Outer-band label: wrapped into stacked curved lines across the ring's thickness. Each line follows the
     * arc at its own radius; the first line is outermost on the upper half and innermost on the lower half,
     * so the text always reads upright.
     */
    private fun planBand(text: String, span: Span, ring: Radii, maxSize: Float, track: Float): LabelPlan? {
        val lower = isLowerHalf(span.mid)
        var best: LabelPlan? = null
        for (lines in wrapVariants(text)) {
            val n = lines.size
            for (s in sizes(maxSize)) {
                val lh = s * LINE_HEIGHT
                if (n * lh > ring.thickness - 5f) continue
                val centers = lines.indices.map { i ->
                    ring.mid + (if (lower) i - (n - 1) / 2f else (n - 1) / 2f - i) * lh
                }
                val fits = lines.indices.all { i ->
                    val rInner = centers[i] - s * 0.5f
                    textEm(lines[i], true, track) * s <= rad(span.sweep) * rInner - 6f
                }
                if (!fits) continue
                val cand = LabelPlan(LabelMode.TANGENTIAL, lines, s, track, true, centers.map { baselineFor(it, s, lower) })
                val b = best
                // Largest text wins; within 6% prefer the wrapped (more lines) arrangement for a consistent look.
                if (b == null || cand.size > b.size * 1.06f || (cand.size >= b.size * 0.94f && cand.lines.size > b.lines.size)) best = cand
                break
            }
        }
        return best
    }

    private val plans = HashMap<String, LabelPlan?>()

    /** Key = ring ("r1" family, "r2" group, "r3" descriptor, "r4" outer band) + ":" + node id. */
    fun planKey(ring: String, id: String) = "$ring:$id"

    fun labelPlan(ring: String, id: String): LabelPlan? = plans[planKey(ring, id)]

    init {
        for (fam in AromaTaxonomy.families) {
            val text = AromaWheelTheme.familyWheelLabel[fam.id] ?: fam.label.uppercase()
            plans[planKey("r1", fam.id)] = choose(spanOf(fam.id), WheelRadii.RING1, 9.6f, true, 0.06f, listOf(listOf(text)))
            for (grp in fam.children) {
                val th = checkNotNull(AromaWheelTheme.forGroup(grp.id)) { "No theme for ${grp.id}" }
                val g = spanOf(grp.id)
                // A hyphen at a line end means "break the word here" (no space when rejoined).
                val joined = th.ringLabel.fold("") { acc, l ->
                    if (acc.isEmpty()) l else if (acc.endsWith("-")) acc + l else "$acc $l"
                }
                val variants = mutableListOf(listOf(joined))
                if (th.ringLabel.size > 1) variants += th.ringLabel
                plans[planKey("r2", grp.id)] = choose(g, WheelRadii.RING2, 11.5f, true, 0.04f, variants)
                plans[planKey("r4", grp.id)] = planBand(th.bandLabel, g, WheelRadii.BAND, 12f, 0.05f)
                for (leaf in grp.children) {
                    val s = spanOf(leaf.id)
                    // Planned with bold metrics so the size never changes (or overflows) when a descriptor is selected.
                    val outer = WheelRadii.RING3.outer - 7f
                    val em = textEm(leaf.label, true)
                    val avail = outer - (WheelRadii.RING3.inner + 6f)
                    var size = min(12.4f, avail / em)
                    val cell = rad(s.sweep)
                    while (size > 5f && size * 0.85f > cell * (outer - em * size) - 1.5f) size -= 0.1f
                    plans[planKey("r3", leaf.id)] = LabelPlan(LabelMode.RADIAL, listOf(leaf.label), size, 0f, false)
                }
            }
        }
    }
}
