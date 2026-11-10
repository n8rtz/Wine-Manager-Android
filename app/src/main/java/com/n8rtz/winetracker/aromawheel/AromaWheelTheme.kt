package com.n8rtz.winetracker.aromawheel

/**
 * Colours and ring labels for the wheel (sampled from the reference wheel image).
 * Plain ARGB ints, so this file has no Android / Compose dependency. Colours are presentation only;
 * stable IDs live in [AromaTaxonomy].
 */
class GroupTheme(
    /** Saturated colour: group ring (ring 2) and, unless [band] is set, the outer band. */
    val main: Int,
    /** Pale tint behind the descriptor labels (ring 3). */
    val tint: Int,
    /** Descriptor label colour on the tint. */
    val ink: Int,
    /** Ring-2 label lines. A hyphen at a line end means "word is broken here". */
    val ringLabel: List<String>,
    /** Outer-band label (wrapped automatically into stacked curved lines). */
    val bandLabel: String,
    /** Outer-band colour when it differs from [main]. */
    val band: Int? = null,
)

object AromaWheelTheme {
    private fun c(argb: Long) = argb.toInt()

    val ring1Fill = c(0xFFB4BFD9)
    val ring1Ink = c(0xFF33405F)
    val ring1Swatch = c(0xFF8F9CBD)
    val separator = c(0xFFF7F7F8)
    /**
     * Fill for a selected descriptor cell: a deep variant of the group colour, clearly
     * distinct from the pale tint of unselected neighbours. [white] is the label colour on it.
     */
    fun selectedFill(theme: GroupTheme): Int = darken(theme.main, 0.38f)

    /** Blends an ARGB int toward black by [fraction] (0..1). */
    private fun darken(argb: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int =
            (((argb ushr shift) and 0xFF).toFloat() * (1f - f)).toInt().coerceIn(0, 255)
        val a = (argb ushr 24) and 0xFF
        return (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    val centerRed = c(0xFFB8262E)
    val white = c(0xFFFFFFFF)
    val backgroundInner = c(0xFFECECEE)
    val backgroundMid = c(0xFFD9D9DC)
    val backgroundOuter = c(0xFFCDCDD0)

    val familyWheelLabel = mapOf(
        "fam_primary" to "PRIMARY AROMAS",
        "fam_secondary" to "SECONDARY",
        "fam_tertiary" to "TERTIARY",
        "fam_faults" to "FAULTS",
    )

    /** Keyed by group code (the part after "grp_"). */
    val groups: Map<String, GroupTheme> = mapOf(
        "flw" to GroupTheme(c(0xFF5B5EA6), c(0xFFBCBEE3), c(0xFF6567B4), listOf("FLOWER"), "FLOWER"),
        "cit" to GroupTheme(c(0xFF7DC242), c(0xFFD3E8B3), c(0xFF68A232), listOf("CITRUS"), "CITRUS"),
        "tre" to GroupTheme(c(0xFFF0A050), c(0xFFFBDCB8), c(0xFFD27A1F), listOf("TREE", "FRUIT"), "TREE FRUIT"),
        "trp" to GroupTheme(c(0xFFEE5A2A), c(0xFFF9BDA3), c(0xFFD44A1B), listOf("TROPICAL", "FRUIT"), "TROPICAL FRUIT"),
        "red" to GroupTheme(c(0xFFC3262D), c(0xFFE9A99B), c(0xFFB3232A), listOf("RED", "FRUIT"), "RED FRUIT"),
        "blk" to GroupTheme(c(0xFF8E1C3C), c(0xFFE7AAA4), c(0xFF8E1C3C), listOf("BLACK", "FRUIT"), "BLACK FRUIT"),
        "drf" to GroupTheme(c(0xFFC0538A), c(0xFFECBED6), c(0xFFBB5B8D), listOf("DRIED", "FRUIT"), "DRIED FRUIT"),
        "nbr" to GroupTheme(c(0xFF3B8FD1), c(0xFFBAD6EF), c(0xFF3A83C4), listOf("NOBLE", "ROT"), "NOBLE ROT"),
        "spi" to GroupTheme(c(0xFF0DB4A8), c(0xFFC0E7E3), c(0xFF10968C), listOf("SPICE"), "SPICE"),
        "veg" to GroupTheme(c(0xFF26994A), c(0xFFC9E4C2), c(0xFF23903F), listOf("VEGETABLE"), "VEGETABLE"),
        "ear" to GroupTheme(c(0xFF79A63A), c(0xFFD6E6BA), c(0xFF5D8A2A), listOf("EARTH"), "EARTH"),
        "mic" to GroupTheme(c(0xFFB6B62A), c(0xFFEDECAB), c(0xFF8D8D1C), listOf("MICROBIAL"), "MICROBIAL"),
        "oak" to GroupTheme(c(0xFFB98A2A), c(0xFFE8D4A3), c(0xFF9A7116), listOf("OAK", "AGING"), "OAK AGING"),
        "gag" to GroupTheme(c(0xFFDC7F22), c(0xFFF9CCA3), c(0xFFC46A12), listOf("GENERAL", "AGING"), "GENERAL AGING"),
        "tca" to GroupTheme(c(0xFFB97B6E), c(0xFFEDCAC3), c(0xFFA8665A), listOf("TCA", "(CORKED)"), "TCA"),
        "sul" to GroupTheme(
            c(0xFFB9564E), c(0xFFF5C9B4), c(0xFFCF7440), listOf("SULFIDES &", "MERCAPTANS"),
            "SULFIDE & MERCAPTAN", band = c(0xFFD8864F),
        ),
        "bre" to GroupTheme(
            c(0xFFBD2A3D), c(0xFFEAB3B9), c(0xFFC0434D), listOf("BRETTANO-", "MYCES"),
            "BRETT", band = c(0xFFC5434D),
        ),
        "ckd" to GroupTheme(
            c(0xFFB3206B), c(0xFFEFB1C9), c(0xFFBD2F70), listOf("COOKED"),
            "COOK", band = c(0xFFC0306F),
        ),
        "vol" to GroupTheme(
            c(0xFF8A3A97), c(0xFFDCB6DD), c(0xFF8A3A97), listOf("VOLATILE", "ACIDITY"),
            "V.A.", band = c(0xFF9A4AA8),
        ),
    )

    private val fallback = c(0xFF64748B)

    /** Theme for a group id such as "grp_cit" (null if unknown). */
    fun forGroup(groupId: String?): GroupTheme? = groupId?.removePrefix("grp_")?.let { groups[it] }

    /** Saturated colour for a group id: handy for chips and list swatches elsewhere in your app. */
    fun groupColor(groupId: String?): Int = forGroup(groupId)?.main ?: fallback

    /** Pale wheel tint behind a group's descriptor labels (ring 3): chip background. */
    fun groupTint(groupId: String?): Int = forGroup(groupId)?.tint ?: fallback

    /** Wheel label colour on the tint: chip text colour. */
    fun groupInk(groupId: String?): Int = forGroup(groupId)?.ink ?: white
}
