package com.n8rtz.winetracker.data.aroma

/**
 * Versioned aroma taxonomy for the aroma wheel picker.
 *
 * A single tree of [AromaNode]s: level 1 holds the four branches (primary,
 * secondary, tertiary aromas, and faults & other), level 2 holds the aroma
 * families, and level 3 holds the selectable leaf descriptors.
 *
 * Node IDs are stable, hierarchical path strings (for example
 * `primary/spice/black-pepper`) that are independent of the displayed label, so
 * wording can evolve without breaking descriptors stored on existing notes.
 *
 * Descriptor vocabulary is adapted from Wine Folly's "Updated Wine Flavor Wheel
 * with 100+ Flavors" page (https://winefolly.com/tips/wine-aroma-wheel-100-flavors/).
 * See [AromaTaxonomy.SOURCE_NOTE]; also documented in
 * `_refs/major-feature-planning/wine-aroma-wheel.md`.
 *
 * This module is intentionally free of Android/Compose types so it can be unit
 * tested on the JVM; [color] values are plain ARGB longs.
 */
data class AromaNode(
    val id: String,
    val label: String,
    val color: Long? = null,
    val children: List<AromaNode> = emptyList()
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

object AromaTaxonomy {

    /** Bump when the descriptor vocabulary changes in a way that affects stored IDs. */
    const val VERSION = 1

    /** Attribution shown in the picker footer for the adapted vocabulary. */
    const val SOURCE_NOTE: String =
        "Vocabulary adapted from Wine Folly's wine flavor wheel (winefolly.com)"

    /** Branch-level nodes of the taxonomy, in wheel order. */
    val roots: List<AromaNode> = listOf(
        branch(
            id = "primary",
            label = "Primary Aromas",
            color = 0xFF722F37,
            families = listOf(
                family(
                    "flowers", "Flowers", 0xFFB85C8E,
                    "Iris", "Peony", "Elderflower", "Acacia", "Lilac", "Jasmine",
                    "Honeysuckle", "Violet", "Lavender", "Rose", "Potpourri", "Hibiscus"
                ),
                family(
                    "citrus", "Citrus", 0xFFC9A227,
                    "Lime", "Lemon", "Grapefruit", "Orange", "Marmalade"
                ),
                family(
                    "tree-fruit", "Tree Fruit", 0xFFCB7E45,
                    "Quince", "Apple", "Pear", "Nectarine", "Peach", "Apricot", "Persimmon"
                ),
                family(
                    "tropical-fruit", "Tropical Fruit", 0xFFDD9E3A,
                    "Pineapple", "Mango", "Guava", "Passion Fruit", "Lychee", "Bubblegum"
                ),
                family(
                    "red-fruit", "Red Fruit", 0xFFB04A5A,
                    "Cranberry", "Red Plum", "Pomegranate", "Sour Cherry", "Strawberry",
                    "Cherry", "Raspberry"
                ),
                family(
                    "black-fruit", "Black Fruit", 0xFF6D3B52,
                    "Boysenberry", "Black Currant", "Black Cherry", "Plum", "Blackberry",
                    "Blueberry", "Olive"
                ),
                family(
                    "dried-fruit", "Dried Fruit", 0xFF8A5A38,
                    "Raisin", "Fig", "Date", "Fruitcake"
                ),
                family(
                    "noble-rot", "Noble Rot", 0xFFB98A2F,
                    "Beeswax", "Ginger", "Honey"
                ),
                family(
                    "spice", "Spice", 0xFFA44A3F,
                    "White Pepper", "Red Pepper", "Black Pepper", "Cinnamon", "Anise",
                    "5-Spice", "Fennel", "Eucalyptus", "Mint", "Thyme"
                ),
                family(
                    "vegetable", "Vegetable", 0xFF647A3A,
                    "Grass", "Tomato Leaf", "Gooseberry", "Bell Pepper", "Jalapeño",
                    "Bitter Almond", "Tomato", "Sun-Dried Tomato", "Black Tea"
                ),
                family(
                    "earth", "Earth", 0xFF7A6A55,
                    "Clay Pot", "Slate", "Wet Gravel", "Potting Soil", "Red Beet",
                    "Volcanic Rocks", "Petroleum"
                )
            )
        ),
        branch(
            id = "secondary",
            label = "Secondary Aromas",
            color = 0xFFC29B57,
            families = listOf(
                family(
                    "microbial", "Microbial", 0xFFC29B57,
                    "Butter", "Cream", "Sourdough", "Lager", "Truffle", "Mushroom"
                )
            )
        ),
        branch(
            id = "tertiary",
            label = "Tertiary Aromas",
            color = 0xFF9A6B33,
            families = listOf(
                family(
                    "oak-aging", "Oak Aging", 0xFF9A6B33,
                    "Vanilla", "Coconut", "Baking Spices", "Cigar Box", "Smoke", "Dill"
                ),
                family(
                    "general-aging", "General Aging", 0xFF7C6248,
                    "Dried Fruit", "Nutty Flavors", "Tobacco", "Coffee", "Cocoa", "Leather"
                )
            )
        ),
        branch(
            id = "faults",
            label = "Faults & Other",
            color = 0xFF5E5A54,
            families = listOf(
                family(
                    "cork-taint", "Cork Taint (TCA)", 0xFF8B7E66,
                    "Musty Cardboard", "Wet Dog"
                ),
                family(
                    "madeirized", "Madeirized (Cooked)", 0xFFB07D4A,
                    "Toffee", "Stewed Fruit"
                ),
                family(
                    "volatile-acidity", "Volatile Acidity", 0xFFA65E7B,
                    "Vinegar", "Nail Polish Remover"
                ),
                family(
                    "sulfides", "Sulfides & Mercaptans", 0xFF6F7A85,
                    "Cured Meat", "Boiled Eggs", "Burnt Rubber", "Lit Match", "Garlic",
                    "Onion", "Cat Pee"
                ),
                family(
                    "brettanomyces", "Brettanomyces", 0xFF7D5A44,
                    "Black Cardamom", "Band-Aid", "Sweaty Leather Saddle", "Horse Manure"
                )
            )
        )
    )

    private val indexById: Map<String, AromaNode>
    private val pathById: Map<String, List<AromaNode>>

    init {
        val index = mutableMapOf<String, AromaNode>()
        val paths = mutableMapOf<String, List<AromaNode>>()
        fun visit(node: AromaNode, trail: List<AromaNode>) {
            require(index.put(node.id, node) == null) {
                "Duplicate aroma node id: ${node.id}"
            }
            val chain = trail + node
            paths[node.id] = chain
            node.children.forEach { child -> visit(child, chain) }
        }
        roots.forEach { root -> visit(root, emptyList()) }
        indexById = index
        pathById = paths
    }

    /** All node IDs, branch and family nodes included. */
    val allIds: Set<String> get() = indexById.keys

    /** Number of selectable leaf descriptors in the taxonomy. */
    val leafCount: Int by lazy {
        indexById.values.count { it.isLeaf }
    }

    fun find(id: String): AromaNode? = indexById[id]

    /** Display label for a stored descriptor ID, or null when the ID is unknown. */
    fun labelFor(id: String): String? = indexById[id]?.label

    /** Chain of nodes from the branch root down to the node with [id]; empty when unknown. */
    fun pathFor(id: String): List<AromaNode> = pathById[id] ?: emptyList()

    /** All selectable leaves beneath [node] (the node itself when it is a leaf). */
    fun leavesUnder(node: AromaNode): List<AromaNode> =
        if (node.isLeaf) {
            listOf(node)
        } else {
            node.children.flatMap { leavesUnder(it) }
        }

    /** How many selected IDs fall under [node]; used for family/branch marker dots. */
    fun selectedLeafCountUnder(node: AromaNode, selectedIds: Collection<String>): Int {
        if (node.isLeaf) {
            return if (node.id in selectedIds) 1 else 0
        }
        return node.children.sumOf { selectedLeafCountUnder(it, selectedIds) }
    }

    private fun branch(id: String, label: String, color: Long, families: List<AromaNode>) =
        AromaNode(
            id = id,
            label = label,
            color = color,
            children = families.map { family ->
                family.copy(
                    id = "$id/${family.id}",
                    children = family.children.map { leaf ->
                        leaf.copy(id = "$id/${family.id}/${leaf.id}")
                    }
                )
            }
        )

    private fun family(id: String, label: String, color: Long, vararg descriptors: String) =
        AromaNode(
            id = id,
            label = label,
            color = color,
            children = descriptors.map { descriptor ->
                AromaNode(
                    id = kebabCase(descriptor),
                    label = descriptor
                )
            }
        )

    private fun kebabCase(label: String): String = label
        .lowercase()
        .replace("ñ", "n")
        .replace("&", " ")
        .replace("[^a-z0-9]+".toRegex(), "-")
        .trim('-')
}

/**
 * Pure helpers shared by the wheel, the list mode, and the editor chips for
 * maintaining the ordered selection of descriptor IDs.
 */
object AromaSelection {

    /** Toggles [id] in [current], preserving existing order and appending on add. */
    fun toggle(current: Collection<String>, id: String): List<String> =
        if (current.contains(id)) {
            remove(current, id)
        } else {
            current + id
        }

    /** Removes every occurrence of [id] while preserving order. */
    fun remove(current: Collection<String>, id: String): List<String> =
        current.filterNot { it == id }

    /** Drops IDs that no longer exist in the taxonomy (e.g. after a vocabulary change). */
    fun retainKnown(current: Collection<String>): List<String> =
        current.filter { AromaTaxonomy.find(it) != null }
}
