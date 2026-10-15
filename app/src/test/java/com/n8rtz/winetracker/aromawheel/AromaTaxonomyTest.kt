package com.n8rtz.winetracker.aromawheel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AromaTaxonomyTest {
    @Test
    fun hierarchyLookup_leafToGroupToFamily() {
        val path = AromaTaxonomy.path("cit_lemon")
        assertEquals(listOf(TaxKind.FAMILY, TaxKind.GROUP, TaxKind.LEAF), path.map { it.kind })
        assertEquals("Primary Aromas", path[0].label)
        assertEquals("Citrus", path[1].label)
        assertEquals("Lemon", AromaTaxonomy.node("cit_lemon")?.label)
    }

    @Test
    fun stableIds_areUnique_andEveryLeafIsReachable() {
        val ids = AromaTaxonomy.leaves.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(116, ids.size)
        assertEquals(ids.size, AromaTaxonomy.leavesUnder(AromaTaxonomy.root).size)
    }

    @Test
    fun sameLabelInDifferentBranches_keepsDistinctIds() {
        val a = AromaTaxonomy.node("grp_drf")!!
        val b = AromaTaxonomy.node("gag_dried_fruit")!!
        assertEquals("Dried Fruit", a.label)
        assertEquals("Dried Fruit", b.label)
        assertNotEquals(a.id, b.id)
        assertEquals("General Aging", AromaTaxonomy.describe(b.id).pathLabels[1])
    }

    @Test
    fun toggle_addsThenRemoves_withoutDuplicates() {
        var r = AromaSelection.toggle(emptyList(), "cit_lemon")
        assertEquals(listOf("cit_lemon"), r.ids)
        r = AromaSelection.toggle(r.ids, "tre_apple")
        assertEquals(listOf("cit_lemon", "tre_apple"), r.ids)
        r = AromaSelection.toggle(r.ids, "cit_lemon")
        assertEquals(listOf("tre_apple"), r.ids)
        assertEquals(listOf("cit_lemon", "tre_apple"), AromaSelection.normalize(listOf("cit_lemon", "cit_lemon", "tre_apple")).ids)
    }

    @Test
    fun toggle_rejectsGroupsAndUnknownIds_andEnforcesMax() {
        assertFalse(AromaSelection.toggle(emptyList(), "grp_cit").changed)
        assertEquals(AromaSelection.Reason.UNKNOWN, AromaSelection.toggle(emptyList(), "nope").reason)
        val full = AromaTaxonomy.leaves.take(MAX_SELECTED_DESCRIPTORS).map { it.id }
        val r = AromaSelection.toggle(full, AromaTaxonomy.leaves[MAX_SELECTED_DESCRIPTORS].id)
        assertEquals(AromaSelection.Reason.LIMIT, r.reason)
        assertEquals(MAX_SELECTED_DESCRIPTORS, r.ids.size)
        // removal still works at the limit
        assertEquals(MAX_SELECTED_DESCRIPTORS - 1, AromaSelection.toggle(full, full[0]).ids.size)
    }

    @Test
    fun normalize_separatesRejectedIds() {
        val n = AromaSelection.normalize(listOf("cit_lemon", "bogus", "grp_cit"))
        assertEquals(listOf("cit_lemon"), n.ids)
        assertEquals(listOf("bogus", "grp_cit"), n.rejected)
    }

    @Test
    fun sortByTaxonomy_ordersByVocabulary_unknownsLast() {
        assertEquals(
            listOf("flw_iris", "tre_apple", "legacy_x"),
            AromaTaxonomy.sortByTaxonomy(listOf("tre_apple", "flw_iris", "legacy_x")),
        )
    }

    @Test
    fun versionCompatibility_andUnknownIdResolution() {
        assertTrue(AromaSelection.isVersionCompatible(TAXONOMY_VERSION))
        assertFalse(AromaSelection.isVersionCompatible(TAXONOMY_VERSION + 1))
        assertFalse(AromaSelection.isVersionCompatible(0))
        assertEquals(DescriptorInfo.Status.UNKNOWN, AromaTaxonomy.describe("legacy_x").status)
        assertEquals(DescriptorInfo.Status.ACTIVE, AromaTaxonomy.describe("cit_lemon").status)
    }

    @Test
    fun search_usesLabelsSynonymsAndPath_offline() {
        assertEquals("blk_black_currant", AromaTaxonomy.search("cassis").first().leaf.id)
        assertEquals("veg_jalapeno", AromaTaxonomy.search("jalapeno").first().leaf.id)
        assertTrue(AromaTaxonomy.search("citrus").size >= 5)
        assertTrue(AromaTaxonomy.search("   ").isEmpty())
    }
}
