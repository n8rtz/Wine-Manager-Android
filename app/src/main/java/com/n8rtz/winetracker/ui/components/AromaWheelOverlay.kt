package com.n8rtz.winetracker.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.*

// ── Data ────────────────────────────────────────────────────────────────────────
data class AromaNode(val name: String, val children: List<AromaNode> = emptyList())
private fun AromaNode.totalLeaves(): Int =
    if (children.isEmpty()) 1 else children.sumOf { c -> if (c.children.isEmpty()) 1 else c.children.size }

// ── Hierarchy matching reference image EXACTLY ──────────────────────────────────
// "Continuous" = single child with same name → spans both rings visually
val aromaHierarchy: List<AromaNode> = listOf(
    // 0. VEGETAL
    AromaNode("VEGETAL", listOf(
        AromaNode("LEAVES", listOf("eucalyptus", "bay leaf", "black currant leaf").map { AromaNode(it) }),
        AromaNode("DRIED HERBS", listOf("tobacco", "black tea", "hay").map { AromaNode(it) }),
        AromaNode("FRESH HERBS", listOf("mint", "fern", "thyme", "allium", "cut grass").map { AromaNode(it) }),
        AromaNode("VEGETABLES", listOf("tomato", "fennel", "capsicum").map { AromaNode(it) })
    )),
    // 1. FLORAL
    AromaNode("FLORAL", listOf(
        AromaNode("COLORED FLOWERS", listOf("violet", "lavender", "rose").map { AromaNode(it) }),
        AromaNode("WHITE FLOWERS", listOf("acacia", "jasmine", "linden", "orange blossom", "hawthorn", "honeysuckle").map { AromaNode(it) })
    )),
    // 2. FRUITY RED WINE
    AromaNode("FRUITY\nRED WINE", listOf(
        AromaNode("FORTIFIED", listOf("prune").map { AromaNode(it) }),
        AromaNode("STONE FRUITS", listOf("plum", "cherry").map { AromaNode(it) }),
        AromaNode("RED BERRIES", listOf("blackberry", "strawberry", "blackcurrant", "raspberry").map { AromaNode(it) })
    )),
    // 3. FRUITY WHITE WINE
    AromaNode("FRUITY\nWHITE WINE", listOf(
        AromaNode("TROPICAL FRUITS", listOf("banana").map { AromaNode(it) }),
        AromaNode("BOTRYTIZED", listOf("orange peel", "dried apricot").map { AromaNode(it) }),
        AromaNode("TROPICAL FRUITS", listOf("lychee", "passion fruit", "pineapple", "guava", "melon").map { AromaNode(it) }),
        AromaNode("STONE FRUITS", listOf("peach").map { AromaNode(it) }),
        AromaNode("POME FRUITS", listOf("green apple", "apple", "pear").map { AromaNode(it) }),
        AromaNode("WHITE BERRIES", listOf("gooseberry").map { AromaNode(it) }),
        AromaNode("CITRUS", listOf("grapefruit", "lime", "lemon").map { AromaNode(it) })
    )),
    // 4. WINE FAULTS
    AromaNode("WINE FAULTS", listOf(
        AromaNode("BRETT", listOf("horse sweat").map { AromaNode(it) }),
        AromaNode("SULFITES", listOf("sweet corn", "onion", "rubber").map { AromaNode(it) }),
        AromaNode("VOLATILE\nACIDITY", listOf("nail polish remover", "vinegar").map { AromaNode(it) }),
        AromaNode("HEAT", listOf("madeira").map { AromaNode(it) }),
        AromaNode("OXIDATION", listOf("sherry").map { AromaNode(it) }),
        AromaNode("CORK TAINT", listOf("corked").map { AromaNode(it) })
    )),
    // 5. AGED RED WINE
    AromaNode("AGED\nRED WINE", listOf(
        AromaNode("UNDERGROWTH", listOf("tree moss", "truffle", "mushroom").map { AromaNode(it) }),
        AromaNode("ANIMAL", listOf("gravy", "leather").map { AromaNode(it) }),
        AromaNode("soy sauce")
    )),
    // 6. AGED WHITE WINE — continuous
    AromaNode("AGED\nWHITE WINE", listOf(
        AromaNode("AGED WHITE WINE", listOf("honey", "quince jelly").map { AromaNode(it) })
    )),
    // 7. AGED IN OAK BARREL
    AromaNode("AGED IN\nOAK BARREL", listOf(
        AromaNode("WOODS", listOf("pine", "cedar", "sandalwood", "oak").map { AromaNode(it) }),
        AromaNode("NUTS", listOf("almond", "hazelnut", "coconut").map { AromaNode(it) }),
        AromaNode("SPICES", listOf("cloves", "nutmeg", "liquorice", "cinnamon", "pepper", "vanilla").map { AromaNode(it) }),
        AromaNode("TOASTED", listOf("tar", "smoke", "bacon", "coffee", "toast", "chocolate", "caramel").map { AromaNode(it) })
    )),
    // 8. MALOLACTIC — continuous
    AromaNode("MALOLACTIC", listOf(
        AromaNode("MALOLACTIC", listOf("butter").map { AromaNode(it) })
    )),
    // 9. YEAST — continuous
    AromaNode("YEAST", listOf(
        AromaNode("YEAST", listOf("bread").map { AromaNode(it) })
    )),
    // 10. MINERAL — continuous
    AromaNode("MINERAL", listOf(
        AromaNode("MINERAL", listOf("kerosene", "flint", "iodine").map { AromaNode(it) })
    ))
)

// "Continuous" = the Tier-1 section has only 1 child → no inner/outer split
private fun isContinuous(i: Int): Boolean {
    val g = aromaHierarchy[i]
    return g.children.size == 1
}

// ── Tier-1 colors (11 segments) ─────────────────────────────────────────────────
private val tier1Colors = listOf(
    Color(0xFF4E8C3C), // 0  VEGETAL
    Color(0xFFE8A0BC), // 1  FLORAL
    Color(0xFFB8174C), // 2  FRUITY RED WINE
    Color(0xFFF5C842), // 3  FRUITY WHITE WINE
    Color(0xFFB0B8BC), // 4  WINE FAULTS
    Color(0xFF6A4030), // 5  AGED RED WINE
    Color(0xFFC0A070), // 6  AGED WHITE WINE
    Color(0xFF4A2D1E), // 7  AGED IN OAK BARREL
    Color(0xFFD4C9A0), // 8  MALOLACTIC
    Color(0xFFE8D4A8), // 9  YEAST
    Color(0xFF6A92B4)  // 10 MINERAL
)

// ── Tier-2 colors ──────────────────────────────────────────────────────────────
private val tier2ColorMap: Map<Int, List<Color>> = mapOf(
    // 0 VEGETAL: LEAVES, DRIED HERBS, FRESH HERBS, VEGETABLES
    0 to listOf(Color(0xFF8AB870), Color(0xFFBDAA6B), Color(0xFF68C050), Color(0xFFB04830)),
    // 1 FLORAL: COLORED FLOWERS, WHITE FLOWERS
    1 to listOf(Color(0xFFCC80B0), Color(0xFFEDD8E8)),
    // 2 FRUITY RED WINE: FORTIFIED, STONE FRUITS, RED BERRIES
    2 to listOf(Color(0xFF7A2860), Color(0xFFC06070), Color(0xFFE03060)),
    // 3 FRUITY WHITE WINE: TROPICAL(banana), BOTRYTIZED, TROPICAL(multi), STONE FRUITS, POME FRUITS, WHITE BERRIES, CITRUS
    3 to listOf(Color(0xFFFFF59D), Color(0xFFFFCC80), Color(0xFFFFD060),
        Color(0xFFFFB870), Color(0xFFD4E898), Color(0xFFCCE088), Color(0xFFFFE040)),
    // 4 WINE FAULTS: BRETT, SULFITES, VOLATILE ACIDITY, HEAT, OXIDATION, CORK TAINT
    4 to listOf(Color(0xFFD4A020), Color(0xFF90B8C0), Color(0xFFE04090),
        Color(0xFFD04030), Color(0xFF4890C0), Color(0xFF508870)),
    // 5 AGED RED WINE: UNDERGROWTH, ANIMAL, soy sauce
    5 to listOf(Color(0xFF7B5E3C), Color(0xFF8A3040), Color(0xFF6B3020)),
    6 to listOf(Color(0xFFC0A070)),       // AGED WHITE WINE — continuous
    // 7 AGED IN OAK BARREL: WOODS, NUTS, SPICES, TOASTED
    7 to listOf(Color(0xFF8B5E3C), Color(0xFFB8A06A), Color(0xFFD2691E), Color(0xFF6B4226)),
    8 to listOf(Color(0xFFD4C9A0)),       // MALOLACTIC — continuous
    9 to listOf(Color(0xFFE8D4A8)),       // YEAST — continuous
    10 to listOf(Color(0xFF6A92B4))       // MINERAL — continuous
)

// ── Per-aroma colors ────────────────────────────────────────────────────────────
private val aromaColors: Map<String, Color> = mapOf(
    // VEGETAL
    "eucalyptus" to Color(0xFFC8E6C9), "bay leaf" to Color(0xFFB8D8A8), "black currant leaf" to Color(0xFF8AAC78),
    "tobacco" to Color(0xFFD7CCC8), "black tea" to Color(0xFFC8B0A8), "hay" to Color(0xFFF5F0D0),
    "mint" to Color(0xFFB2DFDB), "fern" to Color(0xFFB2DFDB), "thyme" to Color(0xFFC8E6C9),
    "allium" to Color(0xFFE8ECD8), "cut grass" to Color(0xFFDCEDC8),
    "tomato" to Color(0xFFFFCDD2), "fennel" to Color(0xFFD4EBC8), "capsicum" to Color(0xFFC8E6C9),
    // FLORAL
    "violet" to Color(0xFFD1C4E9), "lavender" to Color(0xFFD8CCF0), "rose" to Color(0xFFF8BBD0),
    "acacia" to Color(0xFFE8F0D8), "jasmine" to Color(0xFFF8F8DC), "linden" to Color(0xFFEEF8D0),
    "orange blossom" to Color(0xFFFFE8C8), "hawthorn" to Color(0xFFFFF0E8), "honeysuckle" to Color(0xFFFFF0D0),
    // FRUITY RED WINE
    "prune" to Color(0xFFD1C4E9), "plum" to Color(0xFFCE93D8), "cherry" to Color(0xFFEF9A9A),
    "blackberry" to Color(0xFFCE93D8), "strawberry" to Color(0xFFFF8A80),
    "blackcurrant" to Color(0xFFB39DDB), "raspberry" to Color(0xFFFF8A80),
    // FRUITY WHITE WINE
    "banana" to Color(0xFFFFF59D), "orange peel" to Color(0xFFFFCC80), "dried apricot" to Color(0xFFFFB060),
    "lychee" to Color(0xFFFFE0EC), "passion fruit" to Color(0xFFFFB74D), "pineapple" to Color(0xFFFFF3E0),
    "guava" to Color(0xFFFFCCB0), "melon" to Color(0xFFE8F0B0), "peach" to Color(0xFFFFCCB0),
    "green apple" to Color(0xFFD4EBC8), "apple" to Color(0xFFDCEDC8), "pear" to Color(0xFFF0ECC8),
    "gooseberry" to Color(0xFFCCDD88), "grapefruit" to Color(0xFFFFCCBC), "lime" to Color(0xFFF0F4C3),
    "lemon" to Color(0xFFFFF59D),
    // WINE FAULTS
    "horse sweat" to Color(0xFFD4C4A0), "sweet corn" to Color(0xFFFFF0A0), "onion" to Color(0xFFEEEEC0),
    "rubber" to Color(0xFFCFD8DC), "nail polish remover" to Color(0xFFFFB8D0), "vinegar" to Color(0xFFE0E8F0),
    "madeira" to Color(0xFFD4A060), "sherry" to Color(0xFFD4C0A0), "corked" to Color(0xFFD0D8D0),
    // AGED RED WINE
    "tree moss" to Color(0xFF8AAC78), "truffle" to Color(0xFFC8B8A8), "mushroom" to Color(0xFFD8CCC0),
    "gravy" to Color(0xFFBCA89A), "leather" to Color(0xFFD0C0B0), "soy sauce" to Color(0xFF8C6848),
    // AGED WHITE WINE
    "honey" to Color(0xFFFFC107), "quince jelly" to Color(0xFFFFF0B0),
    // AGED IN OAK BARREL
    "pine" to Color(0xFFC8D8C0), "cedar" to Color(0xFFD7CCC8), "sandalwood" to Color(0xFFE0D0C8), "oak" to Color(0xFFD2C0B0),
    "almond" to Color(0xFFF5F0E8), "hazelnut" to Color(0xFFEDE8E0), "coconut" to Color(0xFFFFF8F0),
    "cloves" to Color(0xFFE8D0B0), "nutmeg" to Color(0xFFE8D8C8), "liquorice" to Color(0xFFCFD8DC),
    "cinnamon" to Color(0xFFE8C8B0), "pepper" to Color(0xFFE0E0E0), "vanilla" to Color(0xFFFFF8E8),
    "tar" to Color(0xFF707070), "smoke" to Color(0xFFCFD8DC), "bacon" to Color(0xFFFFCCC0),
    "coffee" to Color(0xFFBCAAA4), "toast" to Color(0xFFFFE0B2), "chocolate" to Color(0xFFD7CCC8),
    "caramel" to Color(0xFFD2B48C),
    // MALOLACTIC
    "butter" to Color(0xFFFFF8E1),
    // YEAST
    "bread" to Color(0xFFF5E8D0),
    // MINERAL
    "kerosene" to Color(0xFFFFF9C4), "flint" to Color(0xFFECEFF1), "iodine" to Color(0xFFCEC4D8)
)

private const val ZOOM_SCALE = 3.2f

// ── COMPOSABLE ──────────────────────────────────────────────────────────────────
@Composable
fun AromaWheelOverlay(
    selectedAromas: List<String>,
    onToggleAroma: (String) -> Unit,
    onClose: () -> Unit
) {
    var zoomedIndex by remember { mutableStateOf<Int?>(null) }
    val animScale by animateFloatAsState(
        if (zoomedIndex != null) ZOOM_SCALE else 1f, tween(450), label = "s"
    )
    val totalLeaves = aromaHierarchy.sumOf { it.totalLeaves() }
    val segSweeps = aromaHierarchy.map { it.totalLeaves().toFloat() / totalLeaves * 360f }
    val segStarts = buildList { var a = -90f; segSweeps.forEach { s -> add(a); a += s } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(
                        if (zoomedIndex != null) aromaHierarchy[zoomedIndex!!].name.replace("\n", " ")
                        else "Wine Aroma Wheel",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedAromas.isNotEmpty())
                            Text("${selectedAromas.size} selected", style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray, modifier = Modifier.padding(end = 8.dp))
                        if (zoomedIndex != null)
                            TextButton(onClick = { zoomedIndex = null }) { Text("Back") }
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                    }
                }

                Canvas(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .pointerInput(zoomedIndex) {
                            detectTapGestures { tap ->
                                val cw = size.width.toFloat(); val ch = size.height.toFloat()
                                val wcx = cw / 2f; val wcy = ch / 2f
                                val r = min(cw, ch) / 2f * 0.97f
                                val zi = zoomedIndex; val sc = if (zi != null) ZOOM_SCALE else 1f

                                if (zi == null) {
                                    val dx = tap.x - wcx; val dy = tap.y - wcy
                                    val dist = sqrt(dx * dx + dy * dy)
                                    if (dist < r * 0.13f || dist > r) return@detectTapGestures
                                    val ang = na(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
                                    for (i in segStarts.indices) {
                                        if (inArc(ang, segStarts[i], segSweeps[i])) { zoomedIndex = i; break }
                                    }
                                } else {
                                    val midRad = Math.toRadians((segStarts[zi] + segSweeps[zi] / 2f).toDouble())
                                    val fr = r * 0.52f
                                    val fx = wcx + fr * cos(midRad).toFloat()
                                    val fy = wcy + fr * sin(midRad).toFloat()
                                    val tx = cw / 2f - fx * sc; val ty = ch / 2f - fy * sc
                                    val wx = (tap.x - tx) / sc; val wy = (tap.y - ty) / sc
                                    val dx = wx - wcx; val dy = wy - wcy
                                    val dist = sqrt(dx * dx + dy * dy)
                                    if (dist < r * 0.13f) { zoomedIndex = null; return@detectTapGestures }
                                    val ang = na(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
                                    val group = aromaHierarchy[zi]
                                    val catSw = buildCatSweeps(zi, segSweeps)
                                    val rT2 = r * 0.52f
                                    var ca = segStarts[zi]
                                    for (ci in group.children.indices) {
                                        if (inArc(ang, ca, catSw[ci])) {
                                            if (dist in (r * 0.13f)..rT2) {
                                                onToggleAroma(group.children[ci].name.replace("\n", " "))
                                            } else if (dist in rT2..r) {
                                                val cat = group.children[ci]
                                                if (cat.children.isNotEmpty()) {
                                                    val asw = catSw[ci] / cat.children.size
                                                    val la = angleDiff(ang, ca)
                                                    val ai = (la / asw).toInt().coerceIn(0, cat.children.size - 1)
                                                    onToggleAroma(cat.children[ai].name)
                                                }
                                            }
                                            break
                                        }
                                        ca += catSw[ci]
                                    }
                                }
                            }
                        }
                ) {
                    val cw = size.width; val ch = size.height
                    val center = Offset(cw / 2f, ch / 2f)
                    val r = min(cw, ch) / 2f * 0.97f
                    val nc = drawContext.canvas.nativeCanvas
                    val paint = Paint().apply {
                        isAntiAlias = true; textAlign = Paint.Align.CENTER
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        color = android.graphics.Color.BLACK
                    }
                    val sc = animScale
                    var panX = 0f; var panY = 0f
                    if (zoomedIndex != null) {
                        val midRad = Math.toRadians((segStarts[zoomedIndex!!] + segSweeps[zoomedIndex!!] / 2f).toDouble())
                        val fr = r * 0.52f
                        val fx = center.x + fr * cos(midRad).toFloat()
                        val fy = center.y + fr * sin(midRad).toFloat()
                        panX = cw / 2f - fx * sc; panY = ch / 2f - fy * sc
                    }

                    drawContext.canvas.save()
                    if (sc != 1f) { drawContext.canvas.translate(panX, panY); drawContext.canvas.scale(sc, sc) }

                    val rIn = r * 0.13f; val rOut = r; val rT2 = r * 0.52f
                    val dw = (1.5f / sc).dp.toPx()

                    // ── Pass 1: Tier-2 outer ring ──
                    for (i in aromaHierarchy.indices) {
                        val group = aromaHierarchy[i]
                        val catSw = buildCatSweeps(i, segSweeps)
                        val t2c = tier2ColorMap[i] ?: emptyList()
                        var ca = segStarts[i]
                        group.children.forEachIndexed { ci, _ ->
                            val c = t2c.getOrElse(ci) { tier1Colors[i] }
                            drawArc(c, ca, catSw[ci], true,
                                Offset(center.x - rOut, center.y - rOut), Size(rOut * 2, rOut * 2))
                            drawArc(Color.White, ca, catSw[ci], true,
                                topLeft = Offset(center.x - rOut, center.y - rOut),
                                size = Size(rOut * 2, rOut * 2), style = Stroke(dw * 0.6f))
                            ca += catSw[ci]
                        }
                    }

                    // ── Pass 2: Tier-1 inner ring (SKIP continuous sections) ──
                    for (i in aromaHierarchy.indices) {
                        if (!isContinuous(i)) {
                            drawArc(tier1Colors[i], segStarts[i], segSweeps[i], true,
                                Offset(center.x - rT2, center.y - rT2), Size(rT2 * 2, rT2 * 2))
                            drawArc(Color.White, segStarts[i], segSweeps[i], true,
                                topLeft = Offset(center.x - rT2, center.y - rT2),
                                size = Size(rT2 * 2, rT2 * 2), style = Stroke(dw))
                        }
                    }
                    // Outer divider lines for all Tier-1 segments
                    for (i in aromaHierarchy.indices) {
                        drawArc(Color.White, segStarts[i], segSweeps[i], true,
                            topLeft = Offset(center.x - rOut, center.y - rOut),
                            size = Size(rOut * 2, rOut * 2), style = Stroke(dw))
                    }

                    // ── Zoomed: Tier-3 aromas ──
                    if (zoomedIndex != null && sc > 1.8f) {
                        val zi = zoomedIndex!!; val group = aromaHierarchy[zi]
                        val catSw = buildCatSweeps(zi, segSweeps)
                        var ca = segStarts[zi]
                        group.children.forEachIndexed { ci, cat ->
                            if (cat.children.isNotEmpty()) {
                                val asw = catSw[ci] / cat.children.size
                                cat.children.forEachIndexed { ai, aroma ->
                                    val aS = ca + ai * asw
                                    val sel = selectedAromas.contains(aroma.name)
                                    val ac = aromaColors[aroma.name] ?: tier1Colors[zi].copy(alpha = 0.5f)
                                    drawRingSegmentFill(aS, asw, rT2, rOut, center, if (sel) ac else ac.copy(alpha = 0.8f))
                                    if (sel) drawRingSegmentStroke(aS, asw, rT2, rOut, center, Color(0xFF722F37), 3.dp.toPx() / sc)
                                    drawRingSegmentStroke(aS, asw, rT2, rOut, center, Color.White, dw * 0.5f)
                                    paint.textSize = 10.sp.toPx() / sc
                                    paint.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                    drawRadialText(aroma.name, aS + asw / 2f, (rT2 + rOut) / 2f, center, paint, nc)
                                }
                            }
                            ca += catSw[ci]
                        }
                        // Re-draw Tier-2 on top for zoomed section
                        ca = segStarts[zi]
                        group.children.forEachIndexed { ci, cat ->
                            val catSel = selectedAromas.contains(cat.name.replace("\n", " "))
                            val bc = tier2ColorMap[zi]?.getOrNull(ci) ?: tier1Colors[zi]
                            drawArc(if (catSel) bc else bc.copy(alpha = 0.85f), ca, catSw[ci], true,
                                Offset(center.x - rT2, center.y - rT2), Size(rT2 * 2, rT2 * 2))
                            if (catSel) drawRingSegmentStroke(ca, catSw[ci], rIn, rT2, center, Color(0xFF722F37), 3.dp.toPx() / sc)
                            drawArc(Color.White, ca, catSw[ci], true,
                                topLeft = Offset(center.x - rT2, center.y - rT2),
                                size = Size(rT2 * 2, rT2 * 2), style = Stroke(dw))
                            paint.textSize = 12.sp.toPx() / sc; paint.typeface = Typeface.DEFAULT_BOLD
                            drawRadialText(cat.name, ca + catSw[ci] / 2f, (rIn + rT2) / 2f, center, paint, nc)
                            ca += catSw[ci]
                        }
                    }

                    // ── Tier-2 labels (initial view only, skip continuous) ──
                    if (sc < 1.8f) {
                        paint.typeface = Typeface.DEFAULT_BOLD
                        for (i in aromaHierarchy.indices) {
                            if (isContinuous(i)) continue // no separate T2 label for continuous
                            val group = aromaHierarchy[i]
                            val catSw = buildCatSweeps(i, segSweeps)
                            var ca = segStarts[i]
                            group.children.forEachIndexed { _, cat ->
                                val sw = catSw[group.children.indexOf(cat)]
                                paint.textSize = clamp(sw * 0.14f, 10f, 15f).sp.toPx()
                                drawRadialText(cat.name, ca + sw / 2f, (rT2 + rOut) / 2f, center, paint, nc)
                                ca += sw
                            }
                        }
                    }

                    // ── Tier-1 labels ──
                    paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    for (i in aromaHierarchy.indices) {
                        val sw = segSweeps[i]
                        paint.textSize = clamp(sw * 0.14f, 11f, 20f).sp.toPx() / sc.coerceAtLeast(0.01f)
                        if (sc < 1.8f || i == zoomedIndex) {
                            // Continuous sections: label centered across full width; others: inner ring only
                            val labelR = if (isContinuous(i)) (rIn + rOut) / 2f else (rIn + rT2) / 2f
                            drawRadialText(aromaHierarchy[i].name,
                                segStarts[i] + sw / 2f, labelR, center, paint, nc)
                        }
                    }

                    // ── Center circle ──
                    drawCircle(Color(0xFF5C2030), rIn, center)
                    paint.textSize = 14.sp.toPx() / sc; paint.color = android.graphics.Color.WHITE
                    if (zoomedIndex == null)
                        nc.drawText("🍇", center.x, center.y + paint.textSize * 0.35f, paint)
                    else {
                        paint.textSize = 10.sp.toPx() / sc
                        nc.drawText("← BACK", center.x, center.y + paint.textSize * 0.4f, paint)
                    }
                    paint.color = android.graphics.Color.BLACK

                    drawContext.canvas.restore()
                }

                if (selectedAromas.isNotEmpty()) {
                    Text("Selected: ${selectedAromas.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────────
private fun na(a: Float) = ((a % 360f) + 360f) % 360f

private fun inArc(angle: Float, start: Float, sweep: Float): Boolean {
    val s = na(start); val e = na(start + sweep)
    return if (s < e) angle in s..e else (angle >= s || angle <= e)
}

private fun angleDiff(angle: Float, start: Float): Float {
    val s = na(start); val a = na(angle)
    return if (a >= s) a - s else (360f - s) + a
}

private fun buildCatSweeps(gi: Int, sweeps: List<Float>): List<Float> {
    val g = aromaHierarchy[gi]; val gSw = sweeps[gi]
    val cl = g.children.map { c -> if (c.children.isEmpty()) 1 else c.children.size }
    val t = cl.sum().coerceAtLeast(1)
    return cl.map { it.toFloat() / t * gSw }
}

private fun clamp(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)

private fun DrawScope.drawRadialText(
    text: String, angleDeg: Float, radius: Float,
    center: Offset, paint: Paint, nc: android.graphics.Canvas
) {
    val lines = text.split("\n"); val lh = paint.textSize * 1.18f
    val totalH = (lines.size - 1) * lh
    lines.forEachIndexed { i, line ->
        nc.save(); nc.translate(center.x, center.y); nc.rotate(angleDeg)
        val yOff = -totalH / 2f + i * lh + paint.textSize * 0.35f
        val norm = na(angleDeg)
        if (norm > 90 && norm < 270) { nc.translate(radius, 0f); nc.rotate(180f); nc.drawText(line, 0f, yOff, paint) }
        else nc.drawText(line, radius, yOff, paint)
        nc.restore()
    }
}
// ── Shared ring-segment path builder ───────────────────────────────────────────
private fun buildRingSegmentPath(
    startDeg: Float, sweepDeg: Float,
    innerR: Float, outerR: Float,
    center: Offset
): Path = Path().apply {
    val startRad = Math.toRadians(startDeg.toDouble())
    val endRad   = Math.toRadians((startDeg + sweepDeg).toDouble())
    moveTo(
        center.x + (outerR * cos(startRad)).toFloat(),
        center.y + (outerR * sin(startRad)).toFloat()
    )
    arcTo(
        rect = androidx.compose.ui.geometry.Rect(
            center.x - outerR, center.y - outerR,
            center.x + outerR, center.y + outerR
        ),
        startAngleDegrees = startDeg, sweepAngleDegrees = sweepDeg, forceMoveTo = false
    )
    lineTo(
        center.x + (innerR * cos(endRad)).toFloat(),
        center.y + (innerR * sin(endRad)).toFloat()
    )
    arcTo(
        rect = androidx.compose.ui.geometry.Rect(
            center.x - innerR, center.y - innerR,
            center.x + innerR, center.y + innerR
        ),
        startAngleDegrees = startDeg + sweepDeg, sweepAngleDegrees = -sweepDeg, forceMoveTo = false
    )
    close()
}

private fun DrawScope.drawRingSegmentFill(
    startDeg: Float, sweepDeg: Float,
    innerR: Float, outerR: Float,
    center: Offset, color: Color
) = drawPath(buildRingSegmentPath(startDeg, sweepDeg, innerR, outerR, center), color)

private fun DrawScope.drawRingSegmentStroke(
    startDeg: Float, sweepDeg: Float,
    innerR: Float, outerR: Float,
    center: Offset, color: Color, strokeWidth: Float
) = drawPath(
    buildRingSegmentPath(startDeg, sweepDeg, innerR, outerR, center),
    color,
    style = Stroke(width = strokeWidth)
)
