package com.n8rtz.winetracker.data.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "wine_bottles",
    foreignKeys = [
        ForeignKey(
            entity = WineDefinition::class,
            parentColumns = ["definitionId"],
            childColumns = ["definitionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("definitionId"),
        Index(value = ["definitionId", "vintage", "status"], unique = true)
    ]
)
data class WineBottle(
    @SerializedName("id", alternate = ["bottleId"])
    @PrimaryKey(autoGenerate = true) val bottleId: Long = 0,
    val definitionId: Long,
    val vintage: String,
    val quantity: Int,
    val price: Double?,
    val qtyType: String?,
    val location: String?,
    val status: BottleStatus = BottleStatus.IN_CELLAR,
    val dateAdded: String? = null,
    val dateConsumed: Long? = null
)
