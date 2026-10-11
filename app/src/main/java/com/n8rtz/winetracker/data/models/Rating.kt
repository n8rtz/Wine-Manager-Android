package com.n8rtz.winetracker.data.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ratings",
    foreignKeys = [
        ForeignKey(
            entity = WineDefinition::class,
            parentColumns = ["definitionId"],
            childColumns = ["wineId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("wineId")]
)
data class Rating(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wineId: Long,
    val score: Int,
    val date: String
)
