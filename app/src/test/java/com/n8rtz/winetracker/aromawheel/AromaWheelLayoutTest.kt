package com.n8rtz.winetracker.aromawheel

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AromaWheelLayoutTest {
    @Test
    fun flowerStartsAtTwelveOClock_andGroupsFollowReferenceOrder() {
        assertEquals(0f, WheelLayout.spanOf("grp_flw").a0, 1e-6f)
        assertEquals(
            listOf("flw", "cit", "tre", "trp", "red", "blk", "drf", "nbr", "spi", "veg", "ear", "mic", "oak", "gag", "tca", "sul", "bre", "ckd", "vol"),
            AromaTaxonomy.groups.map { it.id.removePrefix("grp_") },
        )
    }

    @Test
    fun sectorsTileTheFullCircle_withNoGapsOrOverlaps() {
        var cursor = 0f
        for (leaf in AromaTaxonomy.leaves) {
            val s = WheelLayout.spanOf(leaf.id)
            assertTrue(abs(s.a0 - cursor) < 1e-3f)
            assertTrue(abs(s.sweep - WheelLayout.degPerLeaf) < 1e-4f)
            cursor = s.a1
        }
        assertTrue(abs(cursor - 360f) < 1e-2f)
    }

    @Test
    fun everyGroupHasThemeColoursAndLabels() {
        for (g in AromaTaxonomy.groups) {
            val t = AromaWheelTheme.forGroup(g.id)
            assertNotNull(g.id, t)
            assertTrue(t!!.ringLabel.isNotEmpty() && t.bandLabel.isNotEmpty())
        }
    }

    @Test
    fun everyLabelHasAPlan_atAReadableSize() {
        val small = mutableListOf<String>()
        for (fam in AromaTaxonomy.families) {
            assertNotNull(WheelLayout.labelPlan("r1", fam.id))
            for (g in fam.children) {
                for (ring in listOf("r2", "r4")) {
                    val p = WheelLayout.labelPlan(ring, g.id)
                    assertNotNull("$ring:${g.id}", p)
                    if (p!!.size < 7f) small += "$ring:${g.id}"
                }
                for (l in g.children) {
                    val p = WheelLayout.labelPlan("r3", l.id)
                    assertNotNull(l.id, p)
                    if (p!!.size < 9f) small += l.id
                }
            }
        }
        assertEquals(emptyList<String>(), small)
    }

    @Test
    fun outerBandLabels_areWrappedIntoStackedCurvedLines_insideTheirCell() {
        val ring = WheelRadii.BAND
        var wrapped = 0
        for (g in AromaTaxonomy.groups) {
            val plan = WheelLayout.labelPlan("r4", g.id)!!
            assertEquals(LabelMode.TANGENTIAL, plan.mode)
            assertEquals(plan.lines.size, plan.baseRadii!!.size)
            if (plan.lines.size > 1) wrapped++
            val span = WheelLayout.spanOf(g.id)
            val lower = WheelLayout.isLowerHalf(span.mid)
            assertTrue("${g.id} stack too tall", plan.lines.size * plan.size * WheelLayout.LINE_HEIGHT <= ring.thickness)
            plan.lines.forEachIndexed { i, line ->
                val rc = if (lower) plan.baseRadii[i] - plan.size * 0.36f else plan.baseRadii[i] + plan.size * 0.36f
                val rInner = rc - plan.size * 0.5f
                assertTrue("${g.id}:$line too wide", WheelLayout.textEm(line, true, plan.track) * plan.size <= rad(span.sweep) * rInner)
                assertTrue("${g.id} line $i leaves the ring", rc - plan.size * 0.5f >= ring.inner - 0.01f && rc + plan.size * 0.5f <= ring.outer + 0.01f)
            }
            // upright reading order: first line outermost on the upper half, innermost on the lower half
            if (plan.lines.size > 1) assertEquals("${g.id} order", !lower, plan.baseRadii[0] > plan.baseRadii[1])
        }
        assertTrue("expected most multi-word labels wrapped, got $wrapped", wrapped >= 8)
    }

    @Test
    fun wrapVariants_andSectorBounds() {
        assertEquals(listOf("TREE", "FRUIT"), WheelLayout.wrapVariants("TREE FRUIT")[1])
        assertEquals(4, WheelLayout.wrapVariants("SULFIDE & MERCAPTAN").size)
        val b = WheelLayout.sectorBounds("grp_cit")
        assertTrue(b.maxX > b.minX && b.maxY > b.minY)
        assertTrue(b.maxX - b.minX < 400f)
    }

    @Test
    fun hitTest_findsTheCellUnderAPoint_ineveryRing() {
        // Centre of every descriptor cell hits that descriptor.
        for (leaf in AromaTaxonomy.leaves) {
            val s = WheelLayout.spanOf(leaf.id)
            val p = WheelLayout.pt(WheelRadii.RING3.mid, s.mid)
            assertEquals(WheelHit(HitKind.LEAF, leaf.id), WheelLayout.hitTest(p.x, p.y))
        }
        // Group ring, outer band (same group) and family ring.
        val cit = WheelLayout.spanOf("grp_cit")
        val g = WheelLayout.pt(WheelRadii.RING2.mid, cit.mid)
        assertEquals(WheelHit(HitKind.GROUP, "grp_cit"), WheelLayout.hitTest(g.x, g.y))
        val b = WheelLayout.pt(WheelRadii.BAND.mid, cit.mid)
        assertEquals(WheelHit(HitKind.GROUP, "grp_cit"), WheelLayout.hitTest(b.x, b.y))
        val f = WheelLayout.pt(WheelRadii.RING1.mid, WheelLayout.spanOf("fam_faults").mid)
        assertEquals(WheelHit(HitKind.FAMILY, "fam_faults"), WheelLayout.hitTest(f.x, f.y))
        // Centre badge and outside the wheel.
        assertEquals(HitKind.CENTER, WheelLayout.hitTest(0f, 0f)!!.kind)
        assertNull(WheelLayout.hitTest(WheelRadii.BAND.outer + 5f, 0f))
    }
}
