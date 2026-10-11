package com.n8rtz.winetracker.data.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "tasting_notes",
    foreignKeys = [
        ForeignKey(
            entity = WineDefinition::class,
            parentColumns = ["definitionId"],
            childColumns = ["definitionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("definitionId")]
)
data class TastingNote(
    @SerializedName("id", alternate = ["noteId"])
    @PrimaryKey(autoGenerate = true) val noteId: Long = 0,
    val definitionId: Long,
    val vintage: String,
    val dateTasted: Long,
    val rating: Double,
    val notes: String?,
    val rebuy: Boolean = false,
    val recommendedBy: String? = null,
    val initialRating: Int? = null,
    val bottleId: Long? = null,
    val sensoryData: SensoryData? = null
)
