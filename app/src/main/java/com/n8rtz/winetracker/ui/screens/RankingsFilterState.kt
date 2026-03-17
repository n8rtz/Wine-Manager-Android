package com.n8rtz.winetracker.ui.screens

data class RankingsFilterState(
    val sortOrder: String = "Rating (High)",
    val priceRange: ClosedFloatingPointRange<Float>? = null,
    val country: String? = null,
    val region: String? = null,
    val subregion: String? = null,
    val types: Set<String> = emptySet(),
    val ratingRange: ClosedFloatingPointRange<Float>? = null
)
