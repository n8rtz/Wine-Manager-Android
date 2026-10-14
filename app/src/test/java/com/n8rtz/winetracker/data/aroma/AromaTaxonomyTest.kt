package com.n8rtz.winetracker.data.aroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AromaTaxonomyTest {

    private fun walk(node: AromaNode, parent: AromaNode? = null, visit: (AromaNode, AromaNode?) -> Unit) {
        visit(node, parent)
        node.children.forEach { child -> walk(child, node, visit) }
    }

    private fun allNodes(): List<Pair<AromaNode, AromaNode?>> {
        val nodes = mutableListOf<Pair<AromaNode, AromaNode?>>()
        AromaTaxonomy.roots.forEach { root -> walk(root) { node, parent -> nodes.add(node to parent) } }
        return nodes
    }

    @Test
    fun rootsAreTheFourWineFollyBranchesInWheelOrder() {
        assertEquals(
            listOf("Primary Aromas", "Secondary Aromas", "Tertiary Aromas", "Faults & Other"),
            AromaTaxonomy.roots.map { it.label }
        )
        assertEquals(listOf("primary", "secondary", "tertiary", "faults"), AromaTaxonomy.roots.map { it.id })
    }

    @Test
    fun everyNodeIdIsUnique() {
        val nodes = allNodes()
        val ids = nodes.map { it.first.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(ids.size, AromaTaxonomy.allIds.size)
    }

    @Test
    fun childIdsArePathExtensionsOfTheirParent() {
        allNodes().filter { it.second != null }.forEach { (node, parent) ->
            assertNotNull(parent)
            assertTrue(
                "Expected ${node.id} to start with ${parent!!.id}/",
                node.id.startsWith(parent.id + "/")
            )
        }
    }

    @Test
    fun branchNodesHaveFamiliesAndFamiliesHaveLeaves() {
        AromaTaxonomy.roots.forEach { branch ->
            assertTrue("Branch ${branch.id} has no families", branch.children.isNotEmpty())
            assertTrue("Branch ${branch.id} should not be a leaf", !branch.isLeaf)
            branch.children.forEach { family ->
                assertTrue("Family ${family.id} has no descriptors", family.children.isNotEmpty())
                family.children.forEach { leaf ->
                    assertTrue("Leaf ${leaf.id} unexpectedly has children", leaf.isLeaf)
                }
            }
        }
    }

    @Test
    fun totalLeafCountMatchesThePublishedWineFollySet() {
        assertEquals(112, AromaTaxonomy.leafCount)
    }

    @Test
    fun familyLeafCountsMatchTheSource() {
        val counts = mapOf(
            "primary/flowers" to 12,
            "primary/citrus" to 5,
            "primary/tree-fruit" to 7,
            "primary/tropical-fruit" to 6,
            "primary/red-fruit" to 7,
            "primary/black-fruit" to 7,
            "primary/dried-fruit" to 4,
            "primary/noble-rot" to 3,
            "primary/spice" to 10,
            "primary/vegetable" to 9,
            "primary/earth" to 7,
            "secondary/microbial" to 6,
            "tertiary/oak-aging" to 6,
            "tertiary/general-aging" to 6,
            "faults/cork-taint" to 2,
            "faults/madeirized" to 2,
            "faults/volatile-acidity" to 2,
            "faults/sulfides" to 7,
            "faults/brettanomyces" to 4
        )
        counts.forEach { (familyId, expected) ->
            val family = AromaTaxonomy.find(familyId)
            assertNotNull("Missing family $familyId", family)
            assertEquals("Leaf count mismatch for $familyId", expected, family!!.children.size)
        }
        assertEquals(19, counts.size)
    }

    @Test
    fun familyAndBranchNodesCarryWheelColors() {
        AromaTaxonomy.roots.forEach { branch ->
            assertNotNull("Branch ${branch.id} has no color", branch.color)
            branch.children.forEach { family ->
                assertNotNull("Family ${family.id} has no color", family.color)
            }
        }
    }

    @Test
    fun findAndLabelForResolveLeaves() {
        assertEquals("Black Pepper", AromaTaxonomy.labelFor("primary/spice/black-pepper"))
        assertEquals("Jalapeño", AromaTaxonomy.labelFor("primary/vegetable/jalapeno"))
        assertEquals("Wet Dog", AromaTaxonomy.labelFor("faults/cork-taint/wet-dog"))
        assertNotNull(AromaTaxonomy.find("primary/spice/black-pepper"))
        assertNull(AromaTaxonomy.labelFor("primary/spice/vaseline"))
        assertNull(AromaTaxonomy.find(""))
    }

    @Test
    fun pathForReturnsChainFromBranchToLeaf() {
        val path = AromaTaxonomy.pathFor("primary/spice/black-pepper")
        assertEquals(
            listOf("primary", "primary/spice", "primary/spice/black-pepper"),
            path.map { it.id }
        )
        assertEquals(
            listOf("Primary Aromas", "Spice", "Black Pepper"),
            path.map { it.label }
        )
        assertTrue(AromaTaxonomy.pathFor("unknown/id").isEmpty())
    }

    @Test
    fun leavesUnderCollectsEveryDescendantLeaf() {
        val spice = AromaTaxonomy.find("primary/spice")
        assertNotNull(spice)
        assertEquals(10, AromaTaxonomy.leavesUnder(spice!!).size)
        val primary = AromaTaxonomy.find("primary")
        assertEquals(77, AromaTaxonomy.leavesUnder(primary!!).size)
        val leaf = AromaTaxonomy.find("primary/spice/mint")
        assertEquals(listOf(leaf), AromaTaxonomy.leavesUnder(leaf!!))
    }

    @Test
    fun selectedLeafCountUnderCountsOnlySelectedLeaves() {
        val selected = listOf(
            "primary/spice/mint",
            "primary/spice/anise",
            "tertiary/oak-aging/vanilla"
        )
        val spice = AromaTaxonomy.find("primary/spice")!!
        assertEquals(2, AromaTaxonomy.selectedLeafCountUnder(spice, selected))
        assertEquals(2, AromaTaxonomy.selectedLeafCountUnder(AromaTaxonomy.roots[0], selected))
        assertEquals(1, AromaTaxonomy.selectedLeafCountUnder(AromaTaxonomy.roots[2], selected))
        assertEquals(0, AromaTaxonomy.selectedLeafCountUnder(AromaTaxonomy.roots[3], selected))
        // The three selections split across primary and tertiary total three leaves.
        assertEquals(
            3,
            AromaTaxonomy.roots.sumOf { AromaTaxonomy.selectedLeafCountUnder(it, selected) }
        )
    }

    @Test
    fun selectionToggleAddsThenRemovesWhilePreservingOrder() {
        val current = listOf("primary/spice/mint", "tertiary/oak-aging/vanilla")
        val added = AromaSelection.toggle(current, "primary/red-fruit/cherry")
        assertEquals(listOf("primary/spice/mint", "tertiary/oak-aging/vanilla", "primary/red-fruit/cherry"), added)
        val removed = AromaSelection.toggle(added, "primary/spice/mint")
        assertEquals(listOf("tertiary/oak-aging/vanilla", "primary/red-fruit/cherry"), removed)
        // Toggling twice never duplicates: the id is removed, then re-appended at the end.
        val toggledTwice = AromaSelection.toggle(AromaSelection.toggle(current, "primary/spice/mint"), "primary/spice/mint")
        assertEquals(listOf("tertiary/oak-aging/vanilla", "primary/spice/mint"), toggledTwice)
        assertEquals(2, toggledTwice.size)
    }

    @Test
    fun selectionRemoveDropsEveryOccurrence() {
        val current = listOf("a", "b", "a")
        assertEquals(listOf("b"), AromaSelection.remove(current, "a"))
    }

    @Test
    fun retainKnownDropsUnknownIds() {
        val current = listOf("primary/spice/mint", "legacy/retired-descriptor")
        assertEquals(listOf("primary/spice/mint"), AromaSelection.retainKnown(current))
    }

    @Test
    fun taxonomyVersionIsAtLeastOne() {
        assertTrue(AromaTaxonomy.VERSION >= 1)
    }
}
