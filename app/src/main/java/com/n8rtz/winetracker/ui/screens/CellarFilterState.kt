package com.n8rtz.winetracker.ui.screens

enum class CellarSortField {
    QUANTITY,
    DATE_ADDED,
    RATING,
    PRICE,
    DRINKING_WINDOW_SOONEST,
    DRINKING_WINDOW_FURTHEST
}

data class CellarFilterState(
    val sortField: CellarSortField = CellarSortField.DATE_ADDED,
    val sortAscending: Boolean = false,
    val priceRange: ClosedFloatingPointRange<Float>? = null,
    val vintageRange: ClosedFloatingPointRange<Float>? = null,
    val ratingRange: ClosedFloatingPointRange<Float>? = null,
    val makers: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val country: String? = null,
    val region: String? = null,
    val subregion: String? = null,
    val drinkingWindowFilter: String = "Any",
    val drinkingWindowRange: ClosedFloatingPointRange<Float>? = null
)
