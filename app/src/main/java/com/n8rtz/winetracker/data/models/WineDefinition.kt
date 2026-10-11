package com.n8rtz.winetracker.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "wine_definitions",
    indices = [Index(value = ["maker", "name"], unique = true)]
)
data class WineDefinition(
    @SerializedName("id", alternate = ["definitionId"])
    @PrimaryKey(autoGenerate = true) val definitionId: Long = 0,
    val maker: String,
    val name: String,
    val type: String,
    val country: String,
    val region: String?,
    val subregion: String? = null,
    val imageUrl: String? = null
)
