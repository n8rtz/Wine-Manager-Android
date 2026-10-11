package com.n8rtz.winetracker.data.importer

import com.n8rtz.winetracker.data.models.*

data class BackupData(
    val definitions: List<WineDefinition> = emptyList(),
    val bottles: List<WineBottle> = emptyList(),
    val notes: List<TastingNote> = emptyList()
)
