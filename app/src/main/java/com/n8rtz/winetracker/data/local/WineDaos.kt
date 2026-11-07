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

// POJOs for Room relation queries.
data class WineBottleWithDefinition(
    @Embedded val bottle: WineBottle,
    @Relation(parentColumn = "definitionId", entityColumn = "definitionId")
    val definition: WineDefinition,
    @Relation(parentColumn = "definitionId", entityColumn = "definitionId")
    val imageAsset: WineImageAsset?
)

data class TastingNoteWithDefinition(
    @Embedded val note: TastingNote,
    @Relation(parentColumn = "definitionId", entityColumn = "definitionId")
    val definition: WineDefinition,
    @Relation(parentColumn = "definitionId", entityColumn = "definitionId")
    val imageAsset: WineImageAsset?,
    @Relation(parentColumn = "bottleId", entityColumn = "bottleId")
    val bottle: WineBottle?
)

data class WineImageAssetWithDefinition(
    @Embedded val imageAsset: WineImageAsset,
    @Relation(parentColumn = "definitionId", entityColumn = "definitionId")
    val definition: WineDefinition
)

data class ConsumptionEventWithDefinition(
    @Embedded val event: ConsumptionEvent,
    @Relation(parentColumn = "definitionId", entityColumn = "definitionId")
    val definition: WineDefinition,
    @Relation(parentColumn = "noteId", entityColumn = "noteId")
    val note: TastingNote?
)

@Dao
interface WineDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefinition(definition: WineDefinition): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDefinitions(definitions: List<WineDefinition>): List<Long>

    @Update
    suspend fun updateDefinition(definition: WineDefinition)

    @Query("SELECT * FROM wine_definitions WHERE definitionId = :definitionId LIMIT 1")
    suspend fun getDefinitionById(definitionId: Long): WineDefinition?

    @Query("SELECT * FROM wine_definitions WHERE maker = :maker AND name = :name LIMIT 1")
    suspend fun getDefinitionByDetails(maker: String, name: String): WineDefinition?

    @Query("SELECT * FROM wine_definitions")
    fun getAllDefinitions(): Flow<List<WineDefinition>>

    @Query("SELECT * FROM wine_definitions")
    suspend fun getAllDefinitionsOnce(): List<WineDefinition>

    @Query("DELETE FROM wine_definitions")
    suspend fun deleteAllDefinitions()

    @Query("""
        SELECT d.* FROM wine_definitions d
        WHERE (
            (d.imageUrl IS NULL OR TRIM(d.imageUrl) = '')
            AND NOT EXISTS (
                SELECT 1 FROM wine_image_assets a WHERE a.definitionId = d.definitionId
            )
        ) OR EXISTS (
            SELECT 1 FROM wine_image_assets a
            WHERE a.definitionId = d.definitionId AND a.relativePath IS NULL
        )
    """)
    fun getDefinitionsWithNoImage(): Flow<List<WineDefinition>>

    @Query("UPDATE wine_definitions SET imageUrl = :imageUrl WHERE definitionId = :definitionId")
    suspend fun updateImageUrl(definitionId: Long, imageUrl: String)

    @Query("""
        SELECT d.* FROM wine_definitions d
        WHERE (
            (d.imageUrl IS NULL OR TRIM(d.imageUrl) = '')
            AND NOT EXISTS (
                SELECT 1 FROM wine_image_assets a WHERE a.definitionId = d.definitionId
            )
        ) OR EXISTS (
            SELECT 1 FROM wine_image_assets a
            WHERE a.definitionId = d.definitionId AND a.relativePath IS NULL
        )
    """)
    suspend fun getAllMissingOfflineImages(): List<WineDefinition>
}

@Dao
interface WineImageAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: WineImageAsset)

    @Query("SELECT * FROM wine_image_assets WHERE definitionId = :definitionId LIMIT 1")
    suspend fun getByDefinitionId(definitionId: Long): WineImageAsset?

    @Query("SELECT * FROM wine_image_assets")
    suspend fun getAllOnce(): List<WineImageAsset>

    @Transaction
    @Query("SELECT * FROM wine_image_assets WHERE relativePath IS NOT NULL ORDER BY importedAt DESC, definitionId")
    fun observeStoredWithDefinitions(): Flow<List<WineImageAssetWithDefinition>>

    @Query("UPDATE wine_image_assets SET relativePath = NULL WHERE definitionId = :definitionId AND relativePath = :expectedRelativePath")
    suspend fun clearRelativePathIfMatches(definitionId: Long, expectedRelativePath: String): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM wine_image_assets WHERE relativePath IS NOT NULL")
    suspend fun getStoredBytes(): Long

    @Query("SELECT COUNT(*) FROM wine_image_assets WHERE relativePath = :relativePath")
    suspend fun countReferences(relativePath: String): Int

    @Query("DELETE FROM wine_image_assets")
    suspend fun deleteAll()
}

@Dao
interface InventoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBottle(bottle: WineBottle): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBottles(bottles: List<WineBottle>): List<Long>

    @Update
    suspend fun updateBottle(bottle: WineBottle)

    @Query("SELECT * FROM wine_bottles WHERE definitionId = :definitionId AND vintage = :vintage AND status = :status LIMIT 1")
    suspend fun getBottleByDetails(definitionId: Long, vintage: String, status: BottleStatus): WineBottle?

    @Query("""
        SELECT * FROM wine_bottles
        WHERE definitionId = :definitionId
          AND (vintage = :vintage OR (:vintage IN ('NV', '(NV)') AND vintage IN ('NV', '(NV)')))
          AND status = 'IN_CELLAR'
        ORDER BY (vintage = :vintage) DESC
        LIMIT 1
    """)
    suspend fun getBottleInCellar(definitionId: Long, vintage: String): WineBottle?

    @Transaction
    @Query("SELECT * FROM wine_bottles WHERE status = 'IN_CELLAR' AND quantity > 0")
    fun getActiveInventory(): Flow<List<WineBottleWithDefinition>>

    @Transaction
    @Query("SELECT * FROM wine_bottles WHERE status = 'ARCHIVED' ORDER BY dateConsumed DESC")
    fun getArchivedBottles(): Flow<List<WineBottleWithDefinition>>

    @Query("SELECT * FROM wine_bottles WHERE bottleId = :bottleId LIMIT 1")
    suspend fun getBottleById(bottleId: Long): WineBottle?

    @Query("DELETE FROM wine_bottles WHERE bottleId = :bottleId")
    suspend fun deleteBottle(bottleId: Long)

    @Query("SELECT * FROM wine_bottles")
    suspend fun getAllBottlesOnce(): List<WineBottle>

    @Query("DELETE FROM wine_bottles")
    suspend fun deleteAllBottles()

    @Query("""
        SELECT price FROM wine_bottles
        WHERE definitionId = :definitionId
          AND (vintage = :vintage OR (:vintage IN ('NV', '(NV)') AND vintage IN ('NV', '(NV)')))
        ORDER BY (vintage = :vintage) DESC, (price > 0) DESC, (status = 'IN_CELLAR') DESC, price DESC
        LIMIT 1
    """)
    suspend fun getBottlePrice(definitionId: Long, vintage: String): Double?

    @Query("""
        SELECT price FROM wine_bottles
        WHERE definitionId = :definitionId AND price > 0
        ORDER BY (status = 'IN_CELLAR') DESC, price DESC
        LIMIT 1
    """)
    suspend fun getLatestAnyPrice(definitionId: Long): Double?

    @Query("SELECT definitionId, vintage, price, status FROM wine_bottles WHERE definitionId IN (:definitionIds)")
    suspend fun getAllPricesForDefinitions(definitionIds: List<Long>): List<PriceLookupResult>
}

data class PriceLookupResult(
    val definitionId: Long,
    val vintage: String,
    val price: Double?,
    val status: BottleStatus
)

@Dao
interface TastingNoteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNote(note: TastingNote): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNotes(notes: List<TastingNote>): List<Long>

    @Transaction
    @Query("SELECT * FROM tasting_notes ORDER BY dateTasted DESC, noteId DESC")
    fun getTastingHistory(): Flow<List<TastingNoteWithDefinition>>

    @Query("""
        SELECT * FROM tasting_notes
        WHERE definitionId = :definitionId
          AND (
            vintage = :vintage
            OR (:vintage IN ('NV', '(NV)') AND vintage IN ('NV', '(NV)'))
          )
        ORDER BY dateTasted DESC, noteId DESC
    """)
    fun getTastingNotesForWine(definitionId: Long, vintage: String): Flow<List<TastingNote>>

    @Query("SELECT * FROM tasting_notes WHERE noteId = :noteId LIMIT 1")
    suspend fun getNoteById(noteId: Long): TastingNote?

    @Query("SELECT * FROM tasting_notes WHERE importKey = :importKey LIMIT 1")
    suspend fun getNoteByImportKey(importKey: String): TastingNote?

    @Update
    suspend fun updateNote(note: TastingNote)

    @Query("DELETE FROM tasting_notes WHERE noteId = :noteId")
    suspend fun deleteNoteById(noteId: Long)

    @Query("DELETE FROM tasting_notes")
    suspend fun deleteAllNotes()

    @Query("SELECT * FROM tasting_notes")
    suspend fun getAllNotesOnce(): List<TastingNote>
}

@Dao
interface RatingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRating(rating: Rating): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRatings(ratings: List<Rating>): List<Long>

    @Query("SELECT * FROM ratings WHERE wineId = :wineId")
    fun getRatingsByWineId(wineId: Long): Flow<List<Rating>>

    @Query("SELECT * FROM ratings")
    suspend fun getAllRatingsOnce(): List<Rating>

    @Query("DELETE FROM ratings")
    suspend fun deleteAllRatings()
}

@Dao
interface ConsumptionEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: ConsumptionEvent): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvents(events: List<ConsumptionEvent>): List<Long>

    @Query("SELECT * FROM consumption_events WHERE eventId = :eventId LIMIT 1")
    suspend fun getEventById(eventId: Long): ConsumptionEvent?

    @Query("SELECT * FROM consumption_events WHERE importKey = :importKey LIMIT 1")
    suspend fun getEventByImportKey(importKey: String): ConsumptionEvent?

    @Transaction
    @Query("SELECT * FROM consumption_events ORDER BY consumedAt DESC, eventId DESC")
    fun getConsumptionHistory(): Flow<List<ConsumptionEventWithDefinition>>

    @Query("SELECT * FROM consumption_events")
    suspend fun getAllEventsOnce(): List<ConsumptionEvent>

    @Query("UPDATE consumption_events SET noteId = :noteId WHERE eventId = :eventId")
    suspend fun attachNote(eventId: Long, noteId: Long): Int

    @Query("UPDATE consumption_events SET noteId = NULL WHERE noteId = :noteId")
    suspend fun clearNoteReference(noteId: Long)

    @Query("DELETE FROM consumption_events WHERE eventId = :eventId")
    suspend fun deleteEventById(eventId: Long)

    @Query("DELETE FROM consumption_events")
    suspend fun deleteAllEvents()
}
