package com.n8rtz.winetracker.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// POJOs for complex queries
data class WineBottleWithDefinition(
    @Embedded val bottle: WineBottle,
    @Relation(
        parentColumn = "definitionId",
        entityColumn = "definitionId"
    )
    val definition: WineDefinition
)

data class TastingNoteWithDefinition(
    @Embedded val note: TastingNote,
    @Relation(
        parentColumn = "definitionId",
        entityColumn = "definitionId"
    )
    val definition: WineDefinition
)

@Dao
interface WineDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefinition(definition: WineDefinition): Long

    @Update
    suspend fun updateDefinition(definition: WineDefinition)

    @Query("SELECT * FROM wine_definitions WHERE maker = :maker AND name = :name LIMIT 1")
    suspend fun getDefinitionByDetails(maker: String, name: String): WineDefinition?

    @Query("SELECT * FROM wine_definitions")
    fun getAllDefinitions(): Flow<List<WineDefinition>>

    @Query("SELECT * FROM wine_definitions")
    suspend fun getAllDefinitionsOnce(): List<WineDefinition>

    @Query("SELECT * FROM wine_definitions WHERE imageUrl IS NULL")
    fun getDefinitionsWithNoImage(): Flow<List<WineDefinition>>

    // Added for image pipeline
    @Query("UPDATE wine_definitions SET imageUrl = :imageUrl WHERE definitionId = :definitionId")
    suspend fun updateImageUrl(definitionId: Long, imageUrl: String)

    @Query("SELECT * FROM wine_definitions WHERE imageUrl IS NULL OR imageUrl = '' OR imageUrl = ' '")
    suspend fun getAllWithoutImages(): List<WineDefinition>
}

@Dao
interface InventoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBottle(bottle: WineBottle): Long

    @Update
    suspend fun updateBottle(bottle: WineBottle)

    @Query("SELECT * FROM wine_bottles WHERE definitionId = :definitionId AND vintage = :vintage AND status = :status LIMIT 1")
    suspend fun getBottleByDetails(definitionId: Long, vintage: String, status: BottleStatus): WineBottle?

    @Query("SELECT * FROM wine_bottles WHERE definitionId = :definitionId AND vintage = :vintage AND status = 'IN_CELLAR' LIMIT 1")
    suspend fun getBottleInCellar(definitionId: Long, vintage: String): WineBottle?

    @Transaction
    @Query("SELECT * FROM wine_bottles WHERE status = 'IN_CELLAR' AND quantity > 0")
    fun getActiveInventory(): Flow<List<WineBottleWithDefinition>>

    @Transaction
    @Query("SELECT * FROM wine_bottles WHERE status = 'ARCHIVED' ORDER BY dateAdded DESC")
    fun getArchivedBottles(): Flow<List<WineBottleWithDefinition>>

    @Query("SELECT * FROM wine_bottles WHERE bottleId = :bottleId")
    suspend fun getBottleById(bottleId: Long): WineBottle?

    @Query("DELETE FROM wine_bottles WHERE bottleId = :bottleId")
    suspend fun deleteBottle(bottleId: Long)

    @Query("SELECT * FROM wine_bottles")
    suspend fun getAllBottlesOnce(): List<WineBottle>
}

@Dao
interface TastingNoteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNote(note: TastingNote): Long

    @Transaction
    @Query("SELECT * FROM tasting_notes ORDER BY rating DESC")
    fun getTastingHistory(): Flow<List<TastingNoteWithDefinition>>

    @Query("DELETE FROM tasting_notes WHERE definitionId = :definitionId AND vintage = :vintage AND noteId = (SELECT MAX(noteId) FROM tasting_notes WHERE definitionId = :definitionId AND vintage = :vintage)")
    suspend fun deleteLastNoteForWine(definitionId: Long, vintage: String)

    @Query("SELECT * FROM tasting_notes")
    suspend fun getAllNotesOnce(): List<TastingNote>
}

@Dao
interface RatingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRating(rating: Rating): Long
}
