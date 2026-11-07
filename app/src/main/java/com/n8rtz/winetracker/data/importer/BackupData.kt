package com.n8rtz.winetracker.data.importer

import com.n8rtz.winetracker.data.local.ConsumptionEvent
import com.n8rtz.winetracker.data.local.Rating
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.WineImageAsset

data class BackupData(
    val definitions: List<WineDefinition> = emptyList(),
    val bottles: List<WineBottle> = emptyList(),
    val notes: List<TastingNote> = emptyList(),
    val ratings: List<Rating> = emptyList(),
    val consumptionEvents: List<ConsumptionEvent> = emptyList(),
    /** Provenance is retained, but app-private file paths and image bytes are intentionally not exported. */
    val imageAssets: List<WineImageAsset> = emptyList(),
    /**
     * Optional preferences (W-057 Phase 2). Older backups omit them; the importer then leaves the
     * current reviewer list and storage budget untouched instead of guessing defaults.
     */
    val reviewerNames: List<String>? = null,
    val imageStorageBudgetBytes: Long? = null
)
