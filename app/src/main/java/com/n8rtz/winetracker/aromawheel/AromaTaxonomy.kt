package com.n8rtz.winetracker.aromawheel

import java.text.Normalizer

/*
 * Versioned wine-aroma vocabulary + selection rules. Pure Kotlin (no Android / Compose imports),
 * so it can be unit-tested on the JVM and reused outside the UI.
 *
 * ID rules (identical to the web version, so stored selections are portable between platforms):
 *   family id: "fam_<key>"   group id: "grp_<code>"   leaf id: "<groupCode>_<key>"
 * IDs are written out explicitly below. They are NEVER derived from display labels, so a label can be
 * reworded without touching stored notes. Distinct aromas that share a label (the "Dried Fruit" group
 * vs. the tertiary "Dried Fruit" descriptor) have different IDs and are never merged.
 *
 * Changing the vocabulary:
 *  - Reword: edit the label only, keep the ID.
 *  - Retire: remove the leaf from FAMILY_DEFS, add it to [AromaTaxonomy.retired] (old notes keep
 *    displaying it), and bump [TAXONOMY_VERSION]. `replacedBy` is informational; stored data is never
 *    silently remapped to a different aroma.
 */

const val TAXONOMY_VERSION = 1
const val ROOT_ID = "root"
const val MAX_SELECTED_DESCRIPTORS = 24

enum class TaxKind { ROOT, FAMILY, GROUP, LEAF }

class TaxNode internal constructor(
    val id: String,
    val label: String,
    val kind: TaxKind,
    val parentId: String?,
    val synonyms: List<String> = emptyList(),
) {
    private val _children = mutableListOf<TaxNode>()
    val children: List<TaxNode> get() = _children
    internal fun add(child: TaxNode) {
        _children += child
    }

    override fun toString() = "TaxNode($id)"
}

data class RetiredDescriptor(
    val label: String,
    val retiredInVersion: Int,
    /** Informational only. */
    val replacedBy: List<String> = emptyList(),
)

data class DescriptorInfo(
    val id: String,
    val status: Status,
    val label: String,
    /** e.g. ["Primary Aromas", "Citrus"]; empty for retired / unknown ids. */
    val pathLabels: List<String>,
    val familyId: String?,
    val groupId: String?,
) {
    enum class Status { ACTIVE, RETIRED, UNKNOWN }
}

data class SearchHit(val leaf: TaxNode, val pathLabels: List<String>)

private class LeafDef(val key: String, val label: String, val synonyms: List<String>)
private class GroupDef(val code: String, val label: String, val leaves: List<LeafDef>)
private class FamilyDef(val key: String, val label: String, val groups: List<GroupDef>)

private fun leaf(key: String, label: String, vararg synonyms: String) = LeafDef(key, label, synonyms.toList())

private val FAMILY_DEFS: List<FamilyDef> = listOf(
    FamilyDef(
        "primary", "Primary Aromas",
        listOf(
            GroupDef(
                "flw", "Flowers",
                listOf(
                    leaf("iris", "Iris"), leaf("peony", "Peony"), leaf("elderflower", "Elderflower"),
                    leaf("acacia", "Acacia"), leaf("lilac", "Lilac"), leaf("jasmine", "Jasmine"),
                    leaf("honeysuckle", "Honeysuckle"), leaf("violet", "Violet"), leaf("lavender", "Lavender"),
                    leaf("rose", "Rose"), leaf("potpourri", "Potpourri"), leaf("hibiscus", "Hibiscus"),
                ),
            ),
            GroupDef(
                "cit", "Citrus",
                listOf(
                    leaf("lime", "Lime"), leaf("lemon", "Lemon"), leaf("grapefruit", "Grapefruit"),
                    leaf("orange", "Orange"), leaf("marmalade", "Marmalade"),
                ),
            ),
            GroupDef(
                "tre", "Tree Fruit",
                listOf(
                    leaf("quince", "Quince"), leaf("apple", "Apple"), leaf("pear", "Pear"),
                    leaf("nectarine", "Nectarine"), leaf("peach", "Peach"), leaf("apricot", "Apricot"),
                    leaf("persimmon", "Persimmon"),
                ),
            ),
            GroupDef(
                "trp", "Tropical Fruit",
                listOf(
                    leaf("pineapple", "Pineapple"), leaf("mango", "Mango"), leaf("guava", "Guava"),
                    leaf("passion_fruit", "Passion Fruit", "passionfruit", "maracuja"),
                    leaf("kiwi", "Kiwi"), leaf("lychee", "Lychee"),
                    leaf("bubblegum", "Bubblegum", "bubble gum"),
                ),
            ),
            GroupDef(
                "red", "Red Fruit",
                listOf(
                    leaf("cranberry", "Cranberry"), leaf("red_plum", "Red Plum"), leaf("pomegranate", "Pomegranate"),
                    leaf("sour_cherry", "Sour Cherry", "tart cherry", "morello"),
                    leaf("strawberry", "Strawberry"), leaf("cherry", "Cherry"), leaf("raspberry", "Raspberry"),
                ),
            ),
            GroupDef(
                "blk", "Black Fruit",
                listOf(
                    leaf("boysenberry", "Boysenberry"),
                    leaf("black_currant", "Black Currant", "cassis", "blackcurrant"),
                    leaf("black_cherry", "Black Cherry"), leaf("plum", "Plum"),
                    leaf("blackberry", "Blackberry", "bramble"), leaf("blueberry", "Blueberry"),
                    leaf("olive", "Olive"),
                ),
            ),
            GroupDef(
                "drf", "Dried Fruit",
                listOf(
                    leaf("raisin", "Raisin", "sultana"), leaf("fig", "Fig"), leaf("date", "Date"),
                    leaf("fruitcake", "Fruitcake", "christmas cake"),
                ),
            ),
            GroupDef(
                "nbr", "Noble Rot",
                listOf(
                    leaf("beeswax", "Beeswax", "wax"), leaf("ginger", "Ginger"),
                    leaf("saffron", "Saffron"), leaf("honey", "Honey"),
                ),
            ),
            GroupDef(
                "spi", "Spice",
                listOf(
                    leaf("white_pepper", "White Pepper"), leaf("red_pepper", "Red Pepper"),
                    leaf("black_pepper", "Black Pepper"), leaf("cinnamon", "Cinnamon"),
                    leaf("anise", "Anise", "licorice", "liquorice", "aniseed"),
                    leaf("five_spice", "5-Spice", "five spice", "asian 5-spice"),
                    leaf("fennel", "Fennel"), leaf("eucalyptus", "Eucalyptus", "menthol"),
                    leaf("mint", "Mint"), leaf("thyme", "Thyme"),
                ),
            ),
            GroupDef(
                "veg", "Vegetable",
                listOf(
                    leaf("grass", "Grass", "grassy", "green"), leaf("tomato_leaf", "Tomato Leaf"),
                    leaf("gooseberry", "Gooseberry"),
                    leaf("bell_pepper", "Bell Pepper", "capsicum", "green pepper"),
                    leaf("jalapeno", "Jalapeño", "jalapeno"),
                    leaf("bitter_almond", "Bitter Almond", "green almond"),
                    leaf("tomato", "Tomato"),
                    leaf("sun_dried_tomato", "Sun-Dried Tomato", "sundried tomato"),
                    leaf("black_tea", "Black Tea"),
                ),
            ),
            GroupDef(
                "ear", "Earth",
                listOf(
                    leaf("clay_pot", "Clay Pot"), leaf("slate", "Slate"),
                    leaf("wet_gravel", "Wet Gravel", "minerality", "stony"),
                    leaf("potting_soil", "Potting Soil"), leaf("red_beet", "Red Beet", "beetroot"),
                    leaf("volcanic_rocks", "Volcanic Rocks", "volcanic", "ash"),
                    leaf("petroleum", "Petroleum", "petrol", "kerosene", "diesel"),
                ),
            ),
        ),
    ),
    FamilyDef(
        "secondary", "Secondary Aromas",
        listOf(
            GroupDef(
                "mic", "Microbial",
                listOf(
                    leaf("butter", "Butter"), leaf("cream", "Cream"),
                    leaf("sourdough", "Sourdough", "bread dough", "yeast"),
                    leaf("lager", "Lager", "beer"), leaf("truffle", "Truffle"), leaf("mushroom", "Mushroom"),
                ),
            ),
        ),
    ),
    FamilyDef(
        "tertiary", "Tertiary Aromas",
        listOf(
            GroupDef(
                "oak", "Oak Aging",
                listOf(
                    leaf("vanilla", "Vanilla"), leaf("coconut", "Coconut"),
                    leaf("baking_spices", "Baking Spices"), leaf("cigar_box", "Cigar Box", "cedar"),
                    leaf("smoke", "Smoke", "smoky", "toast"), leaf("dill", "Dill"),
                ),
            ),
            GroupDef(
                "gag", "General Aging",
                listOf(
                    leaf("dried_fruit", "Dried Fruit"),
                    leaf("nutty", "Nutty Flavors", "nuts", "walnut", "hazelnut"),
                    leaf("tobacco", "Tobacco"), leaf("coffee", "Coffee"),
                    leaf("cocoa", "Cocoa", "chocolate"), leaf("leather", "Leather"),
                ),
            ),
        ),
    ),
    FamilyDef(
        "faults", "Faults & Other",
        listOf(
            GroupDef(
                "tca", "TCA (Corked)",
                listOf(
                    leaf("musty_cardboard", "Musty Cardboard", "damp cellar"),
                    leaf("wet_dog", "Wet Dog"),
                    leaf("cork_taint", "Cork Taint (TCA)", "corked", "tca"),
                ),
            ),
            GroupDef(
                "sul", "Sulfides & Mercaptans",
                listOf(
                    leaf("cured_meat", "Cured Meat"),
                    leaf("boiled_eggs", "Boiled Eggs", "rotten egg", "h2s"),
                    leaf("burnt_rubber", "Burnt Rubber"),
                    leaf("lit_match", "Lit Match", "match box", "struck match"),
                    leaf("garlic", "Garlic"), leaf("onion", "Onion"), leaf("cat_pee", "Cat Pee"),
                ),
            ),
            GroupDef(
                "bre", "Brettanomyces",
                listOf(
                    leaf("black_cardamom", "Black Cardamom", "black cardamon"),
                    leaf("band_aid", "Band-Aid", "bandaid", "plaster"),
                    leaf("sweaty_saddle", "Sweaty Leather Saddle", "brett", "barnyard"),
                    leaf("horse_manure", "Horse Manure", "barnyard"),
                ),
            ),
            GroupDef(
                "ckd", "Madeirized (or Cooked)",
                listOf(leaf("toffee", "Toffee", "caramel"), leaf("stewed_fruit", "Stewed Fruit")),
            ),
            GroupDef(
                "vol", "Volatile Acidity (Acetic Acid)",
                listOf(
                    leaf("vinegar", "Vinegar"),
                    leaf("nail_polish", "Nail Polish Remover", "acetone", "ethyl acetate"),
                    leaf("balsamic", "Balsamic"),
                ),
            ),
        ),
    ),
)

object AromaTaxonomy {
    val root: TaxNode
    val families: List<TaxNode>
    val groups: List<TaxNode>
    val leaves: List<TaxNode>

    /** Descriptors removed from the live vocabulary but still resolvable on old notes. */
    val retired: Map<String, RetiredDescriptor> = emptyMap()

    private val index = HashMap<String, TaxNode>()
    private val leafOrder = HashMap<String, Int>()

    init {
        val r = TaxNode(ROOT_ID, "All aromas", TaxKind.ROOT, null)
        index[ROOT_ID] = r
        val allGroups = mutableListOf<TaxNode>()
        val allLeaves = mutableListOf<TaxNode>()
        for (fam in FAMILY_DEFS) {
            val famNode = TaxNode("fam_${fam.key}", fam.label, TaxKind.FAMILY, ROOT_ID)
            r.add(famNode)
            index[famNode.id] = famNode
            for (grp in fam.groups) {
                val grpNode = TaxNode("grp_${grp.code}", grp.label, TaxKind.GROUP, famNode.id)
                famNode.add(grpNode)
                index[grpNode.id] = grpNode
                allGroups += grpNode
                for (l in grp.leaves) {
                    val id = "${grp.code}_${l.key}"
                    check(!index.containsKey(id)) { "Duplicate aroma id: $id" }
                    val node = TaxNode(id, l.label, TaxKind.LEAF, grpNode.id, l.synonyms)
                    grpNode.add(node)
                    index[id] = node
                    leafOrder[id] = allLeaves.size
                    allLeaves += node
                }
            }
        }
        root = r
        families = r.children
        groups = allGroups
        leaves = allLeaves
    }

    fun node(id: String): TaxNode? = index[id]

    fun isActiveDescriptor(id: String) = index[id]?.kind == TaxKind.LEAF

    fun isRetiredDescriptor(id: String) = retired.containsKey(id)

    /** Ancestors from family down to (and including) the node. Root is excluded. */
    fun path(id: String): List<TaxNode> {
        val out = ArrayDeque<TaxNode>()
        var cur = index[id]
        while (cur != null && cur.kind != TaxKind.ROOT) {
            out.addFirst(cur)
            cur = cur.parentId?.let { index[it] }
        }
        return out.toList()
    }

    fun leavesUnder(node: TaxNode): List<TaxNode> =
        if (node.kind == TaxKind.LEAF) listOf(node) else node.children.flatMap { leavesUnder(it) }

    fun countSelectedUnder(node: TaxNode, selected: Set<String>): Int =
        leavesUnder(node).count { it.id in selected }

    /** Resolve any stored id. Retired and unknown ids stay visible rather than being dropped or remapped. */
    fun describe(id: String): DescriptorInfo {
        val n = index[id]
        if (n != null && n.kind == TaxKind.LEAF) {
            val p = path(id)
            return DescriptorInfo(
                id, DescriptorInfo.Status.ACTIVE, n.label,
                p.dropLast(1).map { it.label }, p.getOrNull(0)?.id, p.getOrNull(1)?.id,
            )
        }
        retired[id]?.let {
            return DescriptorInfo(id, DescriptorInfo.Status.RETIRED, it.label, emptyList(), null, null)
        }
        return DescriptorInfo(id, DescriptorInfo.Status.UNKNOWN, id, emptyList(), null, null)
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    /** Offline search over labels, synonyms and group/family names. */
    fun search(query: String, limit: Int = 60): List<SearchHit> {
        val tokens = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val hits = ArrayList<Triple<SearchHit, Int, Int>>()
        for (l in leaves) {
            val pathLabels = path(l.id).dropLast(1).map { it.label }
            val label = fold(l.label)
            val hay = (listOf(label) + l.synonyms.map(::fold) + pathLabels.map(::fold)).joinToString(" | ")
            if (!tokens.all { hay.contains(it) }) continue
            val score = when {
                label == tokens.joinToString(" ") -> 0
                label.startsWith(tokens[0]) -> 1
                tokens.all { label.contains(it) } -> 2
                else -> 3
            }
            hits += Triple(SearchHit(l, pathLabels), score, leafOrder.getValue(l.id))
        }
        return hits.sortedWith(compareBy({ it.second }, { it.third })).take(limit).map { it.first }
    }

    /** Stable display order: taxonomy order for known leaves, then retired/unknown in original order. */
    fun sortByTaxonomy(ids: List<String>): List<String> {
        val known = ids.filter { leafOrder.containsKey(it) }.sortedBy { leafOrder.getValue(it) }
        return known + ids.filterNot { leafOrder.containsKey(it) }
    }
}

/** Selection contract shared by every UI (wheel, list, search) and by persistence code. */
object AromaSelection {
    enum class Reason { UNKNOWN, LIMIT }

    data class ToggleResult(val ids: List<String>, val changed: Boolean, val reason: Reason? = null)

    data class Normalized(val ids: List<String>, val rejected: List<String>)

    /** Add if absent, remove if present. Only active leaves can be newly added; the max is enforced. */
    fun toggle(ids: List<String>, id: String): ToggleResult = when {
        id in ids -> ToggleResult(ids - id, changed = true)
        !AromaTaxonomy.isActiveDescriptor(id) -> ToggleResult(ids, changed = false, reason = Reason.UNKNOWN)
        ids.size >= MAX_SELECTED_DESCRIPTORS -> ToggleResult(ids, changed = false, reason = Reason.LIMIT)
        else -> ToggleResult(ids + id, changed = true)
    }

    /** Dedupe (first occurrence wins, order kept) and separate ids that are neither active nor retired. */
    fun normalize(input: List<String>): Normalized {
        val seen = HashSet<String>()
        val ids = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (raw in input) {
            if (!seen.add(raw)) continue
            if (AromaTaxonomy.isActiveDescriptor(raw) || AromaTaxonomy.isRetiredDescriptor(raw)) ids += raw
            else rejected += raw
        }
        return Normalized(ids, rejected)
    }

    /** A stored selection is readable if it was made with this or an older taxonomy version. */
    fun isVersionCompatible(version: Int) = version in 1..TAXONOMY_VERSION
}
