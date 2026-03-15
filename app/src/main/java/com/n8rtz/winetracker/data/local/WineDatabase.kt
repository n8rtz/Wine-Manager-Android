package com.n8rtz.winetracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.annotations.SerializedName

enum class BottleStatus {
    IN_CELLAR,
    ARCHIVED
}

class Converters {
    @TypeConverter
    fun fromStatus(status: BottleStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): BottleStatus = BottleStatus.valueOf(value)
}

// 1. THE WINE CATALOG (Static metadata)
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

// 2. THE INVENTORY (Specific bottles/vintages)
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

// 3. TASTING HISTORY & RANKINGS
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
    val initialRating: Int? = null
)

// 4. HISTORICAL RATINGS
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

@Database(
    entities = [WineDefinition::class, WineBottle::class, TastingNote::class, Rating::class],
    version = 12,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class WineDatabase : RoomDatabase() {
    abstract fun wineDao(): WineDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun tastingNoteDao(): TastingNoteDao
    abstract fun ratingDao(): RatingDao
}

/**
 * Migration 10 -> 11: Consolidate producer names and merge duplicate definitions.
 * Fixes "Kirkland" vs "Kirkland Signature" discrepancy.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. Redirect dependencies (bottles, notes, ratings) from 'Kirkland' to 'Kirkland Signature' where both definitions exist
        val tablesWithDefId = listOf("wine_bottles", "tasting_notes")
        tablesWithDefId.forEach { table ->
            database.execSQL("""
                UPDATE $table 
                SET definitionId = (
                    SELECT target.definitionId 
                    FROM wine_definitions target 
                    JOIN wine_definitions source ON target.name = source.name 
                    WHERE source.definitionId = $table.definitionId 
                      AND source.maker = 'Kirkland' 
                      AND target.maker = 'Kirkland Signature'
                    LIMIT 1
                )
                WHERE definitionId IN (
                    SELECT s.definitionId 
                    FROM wine_definitions s
                    JOIN wine_definitions t ON s.name = t.name
                    WHERE s.maker = 'Kirkland' AND t.maker = 'Kirkland Signature'
                )
            """)
        }

        // Special case for 'ratings' table which uses 'wineId' instead of 'definitionId'
        database.execSQL("""
            UPDATE ratings 
            SET wineId = (
                SELECT target.definitionId 
                FROM wine_definitions target 
                JOIN wine_definitions source ON target.name = source.name 
                WHERE source.definitionId = ratings.wineId 
                  AND source.maker = 'Kirkland' 
                  AND target.maker = 'Kirkland Signature'
                LIMIT 1
            )
            WHERE wineId IN (
                SELECT s.definitionId 
                FROM wine_definitions s
                JOIN wine_definitions t ON s.name = t.name
                WHERE s.maker = 'Kirkland' AND t.maker = 'Kirkland Signature'
            )
        """)

        // 2. Delete 'Kirkland' definitions that were merged (now redundant)
        database.execSQL("""
            DELETE FROM wine_definitions 
            WHERE maker = 'Kirkland' 
              AND EXISTS (
                SELECT 1 FROM wine_definitions target 
                WHERE target.name = wine_definitions.name 
                  AND target.maker = 'Kirkland Signature'
              )
        """)

        // 3. Rename remaining 'Kirkland' entries to 'Kirkland Signature' (those that were unique)
        database.execSQL("UPDATE wine_definitions SET maker = 'Kirkland Signature' WHERE maker = 'Kirkland'")
    }
}

/**
 * Migration 11 -> 12: Backfill missing dateAdded for existing bottles.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Use current timestamp as a reasonable fallback for existing stock
        database.execSQL("UPDATE wine_bottles SET dateAdded = datetime('now') WHERE dateAdded IS NULL")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // --- 1. Recreate wine_bottles with correct indices ---
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `wine_bottles_new` (
                `bottleId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `definitionId` INTEGER NOT NULL, 
                `vintage` TEXT NOT NULL, 
                `quantity` INTEGER NOT NULL, 
                `price` REAL, 
                `qtyType` TEXT, 
                `location` TEXT, 
                `status` TEXT NOT NULL, 
                `dateAdded` TEXT, 
                FOREIGN KEY(`definitionId`) REFERENCES `wine_definitions`(`definitionId`) ON DELETE CASCADE
            )
        """)
        
        database.execSQL("""
            INSERT INTO wine_bottles_new (bottleId, definitionId, vintage, quantity, status)
            SELECT bottleId, definitionId, vintage, quantity, status FROM wine_bottles
        """)
        
        database.execSQL("DROP TABLE wine_bottles")
        database.execSQL("ALTER TABLE wine_bottles_new RENAME TO wine_bottles")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_wine_bottles_definitionId` ON `wine_bottles` (`definitionId`)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wine_bottles_definitionId_vintage_status` ON `wine_bottles` (`definitionId`, `vintage`, `status`)")
        database.execSQL("ALTER TABLE tasting_notes ADD COLUMN recommendedBy TEXT")
        database.execSQL("ALTER TABLE tasting_notes ADD COLUMN initialRating INTEGER")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `ratings` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `wineId` INTEGER NOT NULL, 
                `score` INTEGER NOT NULL, 
                `date` TEXT NOT NULL, 
                FOREIGN KEY(`wineId`) REFERENCES `wine_definitions`(`definitionId`) ON DELETE CASCADE
            )
        """)
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_ratings_wineId` ON `ratings` (`wineId`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        val cursor = database.query("PRAGMA table_info(wine_definitions)")
        var hasColumn = false
        while (cursor.moveToNext()) {
            val columnIndex = cursor.getColumnIndex("name")
            if (columnIndex != -1 && cursor.getString(columnIndex) == "imageUrl") {
                hasColumn = true
                break
            }
        }
        cursor.close()
        if (!hasColumn) {
            database.execSQL("ALTER TABLE wine_definitions ADD COLUMN imageUrl TEXT DEFAULT NULL")
        }
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE wine_bottles ADD COLUMN dateConsumed INTEGER DEFAULT NULL")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Backfill dateConsumed for archived bottles based on the most recent tasting note for that wine
        database.execSQL("""
            UPDATE wine_bottles 
            SET dateConsumed = (
                SELECT dateTasted 
                FROM tasting_notes 
                WHERE tasting_notes.definitionId = wine_bottles.definitionId 
                  AND tasting_notes.vintage = wine_bottles.vintage 
                ORDER BY dateTasted DESC 
                LIMIT 1
            ) 
            WHERE status = 'ARCHIVED' AND dateConsumed IS NULL
        """)
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Step 1: Fix tasting note vintages — copy from the bottle where unambiguous
        // Only update notes with 'NV' where there's exactly one distinct non-NV vintage for that definition
        database.execSQL("""
            UPDATE tasting_notes 
            SET vintage = (
                SELECT DISTINCT wb.vintage 
                FROM wine_bottles wb 
                WHERE wb.definitionId = tasting_notes.definitionId 
                  AND wb.vintage != 'NV'
            )
            WHERE vintage = 'NV' 
              AND (
                SELECT COUNT(DISTINCT wb.vintage) 
                FROM wine_bottles wb 
                WHERE wb.definitionId = tasting_notes.definitionId 
                  AND wb.vintage != 'NV'
              ) = 1
        """)

        // Step 2: Re-backfill dateConsumed from tasting notes, matching by definitionId only
        database.execSQL("""
            UPDATE wine_bottles 
            SET dateConsumed = (
                SELECT dateTasted 
                FROM tasting_notes 
                WHERE tasting_notes.definitionId = wine_bottles.definitionId 
                ORDER BY dateTasted DESC 
                LIMIT 1
            ) 
            WHERE status = 'ARCHIVED' AND dateConsumed IS NULL
        """)
    }
}
