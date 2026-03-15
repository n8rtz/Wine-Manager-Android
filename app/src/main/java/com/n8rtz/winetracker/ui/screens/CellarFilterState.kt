package com.n8rtz.winetracker.ui.screens

data class CellarFilterState(
    val sortOrder: String = "Newest",
    val priceRange: ClosedFloatingPointRange<Float>? = null,
    val vintageRange: ClosedFloatingPointRange<Float>? = null,
    val ratingRange: ClosedFloatingPointRange<Float>? = null,
    val makers: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val countries: Set<String> = emptySet()
)
