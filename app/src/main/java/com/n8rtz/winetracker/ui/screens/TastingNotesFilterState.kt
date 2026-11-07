package com.n8rtz.winetracker.ui.screens

import com.n8rtz.winetracker.data.local.TastingPhase

data class TastingNotesFilterState(
    val sortOrder: String = "Newest",
    val status: String = "All",
    val types: Set<String> = emptySet(),
    val priceRange: ClosedFloatingPointRange<Float>? = null,
    val country: String? = null,
    val region: String? = null,
    val subregion: String? = null,
    val aromaQuery: String = "",
    val aromaPhase: TastingPhase? = null
)
