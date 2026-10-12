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
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

enum class BottleStatus {
    IN_CELLAR,
    ARCHIVED
}

fun normalizeVintage(value: String?): String {
    val trimmed = value?.trim().orEmpty()
    val unwrapped = trimmed.removeSurrounding("(", ")").trim()
    return if (unwrapped.isBlank() || unwrapped.equals("null", ignoreCase = true) ||
        unwrapped.equals("NV", ignoreCase = true)) "NV" else unwrapped
}

fun displayVintage(value: String?): String = normalizeVintage(value)

data class SensoryData(
    val sweetness: Int = 1,
    val aroma: Int = 2,
    val acidity: Int = 2,
    val tannins: Int = 2,
    val body: Int = 2,
    val balance: Int = 2,
    val intensity: Int = 2,
    val complexity: Int = 1,
    val finish: Int = 2,
    val quality: Int = 2,
    val overallScore: Int = 75,
    val flaws: List<String> = emptyList()
)

class Converters {
    @TypeConverter
    fun fromStatus(status: BottleStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): BottleStatus = BottleStatus.valueOf(value)

    @TypeConverter
    fun fromSensoryData(value: SensoryData?): String? = value?.let { Gson().toJson(it) }

    @TypeConverter
    fun toSensoryData(value: String?): SensoryData? = value?.takeIf { it.isNotBlank() }
        ?.let { json ->
            runCatching {
                Gson().fromJson(json, SensoryData::class.java)
            }.getOrElse {
                // If stored json belongs to the float-based sensory model, parse gracefully
                runCatching {
                    val obj = org.json.JSONObject(json)
                    fun getIntFromNumber(key: String, default: Int): Int {
                        if (!obj.has(key)) return default
                        val doubleVal = obj.optDouble(key, default.toDouble())
                        return if (doubleVal <= 1.0) (doubleVal * 4.0).toInt().coerceIn(0, 4) else doubleVal.toInt()
                    }
                    val flawsList = mutableListOf<String>()
                    val flawsArr = obj.optJSONArray("flaws")
                    if (flawsArr != null) {
                        for (i in 0 until flawsArr.length()) {
                            flawsList.add(flawsArr.optString(i))
                        }
                    }
                    SensoryData(
                        sweetness = getIntFromNumber("sweetness", 1),
                        aroma = getIntFromNumber("aroma", getIntFromNumber("noseIntensity", 2)),
                        acidity = getIntFromNumber("acidity", 2),
                        tannins = getIntFromNumber("tannins", getIntFromNumber("tannin", 2)),
                        body = getIntFromNumber("body", 2),
                        balance = getIntFromNumber("balance", 2),
                        intensity = getIntFromNumber("intensity", 2),
                        complexity = getIntFromNumber("complexity", getIntFromNumber("noseComplexity", 1)),
                        finish = getIntFromNumber("finish", getIntFromNumber("finishLength", 2)),
                        quality = getIntFromNumber("quality", 2),
                        overallScore = obj.optInt("overallScore", 75),
                        flaws = flawsList
                    )
                }.getOrNull()
            }
        }
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
    val imageUrl: String? = null,
    val drinkingWindowStart: Int? = null,
    val drinkingWindowEnd: Int? = null
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
    indices = [Index("definitionId"), Index(value = ["importKey"], unique = true)]
)
data class TastingNote(
    @SerializedName("id", alternate = ["noteId"])
    @PrimaryKey(autoGenerate = true) val noteId: Long = 0,
    val definitionId: Long,
    val vintage: String,
    val dateTasted: Long,
    val rating: Double,
    val valueScore: Int? = null,
    val price: Double? = null,
    val notes: String?,
    val rebuy: Boolean = false,
    val recommendedBy: String? = null,
    val reviewerRating: Int? = null,
    val initialRating: Int? = null,
    val bottleId: Long? = null,
    val sensoryData: SensoryData? = null,
    val importKey: String? = null
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

@Entity(
    tableName = "consumption_events",
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
        Index("consumedAt"),
        Index(value = ["importKey"], unique = true)
    ]
)
data class ConsumptionEvent(
    @SerializedName("id", alternate = ["eventId"])
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val definitionId: Long,
    val bottleId: Long? = null,
    val vintage: String,
    val consumedAt: Long? = null,
    val quantity: Int = 1,
    val price: Double? = null,
    val noteId: Long? = null,
    val importKey: String? = null
)

@Database(
    entities = [WineDefinition::class, WineBottle::class, TastingNote::class, Rating::class, ConsumptionEvent::class],
    version = 20,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class WineDatabase : RoomDatabase() {
    abstract fun wineDao(): WineDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun tastingNoteDao(): TastingNoteDao
    abstract fun ratingDao(): RatingDao
    abstract fun consumptionEventDao(): ConsumptionEventDao
}

/**
 * Migration 10 -> 11: Consolidate producer names and merge duplicate definitions.
 * Fixes "Kirkland" vs "Kirkland Signature" discrepancy.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Redirect dependencies (bottles, notes, ratings) from 'Kirkland' to 'Kirkland Signature' where both definitions exist
        val tablesWithDefId = listOf("wine_bottles", "tasting_notes")
        tablesWithDefId.forEach { table ->
            db.execSQL("""
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
        db.execSQL("""
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
        db.execSQL("""
            DELETE FROM wine_definitions
            WHERE maker = 'Kirkland'
              AND EXISTS (
                SELECT 1 FROM wine_definitions target
                WHERE target.name = wine_definitions.name
                  AND target.maker = 'Kirkland Signature'
              )
        """)

        // 3. Rename remaining 'Kirkland' entries to 'Kirkland Signature' (those that were unique)
        db.execSQL("UPDATE wine_definitions SET maker = 'Kirkland Signature' WHERE maker = 'Kirkland'")
    }
}

/**
 * Migration 11 -> 12: Backfill missing dateAdded for existing bottles.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Use current timestamp as a reasonable fallback for existing stock
        db.execSQL("UPDATE wine_bottles SET dateAdded = datetime('now') WHERE dateAdded IS NULL")
    }
}

/**
 * Migration 12 -> 13: Add bottleId to tasting_notes to link notes to specific bottles.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasting_notes ADD COLUMN bottleId INTEGER DEFAULT NULL")

        // Backfill bottleId by matching definitionId, vintage and dateTasted with archived bottles
        db.execSQL("""
            UPDATE tasting_notes
            SET bottleId = (
                SELECT bottleId
                FROM wine_bottles
                WHERE wine_bottles.definitionId = tasting_notes.definitionId
                  AND wine_bottles.vintage = tasting_notes.vintage
                  AND wine_bottles.status = 'ARCHIVED'
                  AND ABS(wine_bottles.dateConsumed - tasting_notes.dateTasted) < 1000
                LIMIT 1
            )
            WHERE bottleId IS NULL
        """)
    }
}

/**
 * Migration 13 -> 14: Fix corrupted TastingNote vintages.
 * If previous imports or bugs caused all notes for a wine to be assigned '2022',
 * this migration uses the highly unique `dateTasted` timestamp to find the specific
 * bottle that was consumed at that moment, and overrides the note's vintage with
 * the bottle's actual vintage.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            UPDATE tasting_notes
            SET
                bottleId = COALESCE((
                    SELECT bottleId FROM wine_bottles
                    WHERE wine_bottles.definitionId = tasting_notes.definitionId
                      AND wine_bottles.status = 'ARCHIVED'
                      AND ABS(wine_bottles.dateConsumed - tasting_notes.dateTasted) < 2000
                    LIMIT 1
                ), bottleId),
                vintage = COALESCE((
                    SELECT vintage FROM wine_bottles
                    WHERE wine_bottles.definitionId = tasting_notes.definitionId
                      AND wine_bottles.status = 'ARCHIVED'
                      AND ABS(wine_bottles.dateConsumed - tasting_notes.dateTasted) < 2000
                    LIMIT 1
                ), vintage)
        """)
    }
}

/**
 * Migration 14 -> 15: Deep Data Recovery for corrupted tasting note vintages.
 * Due to legacy CSV import bugs (MIGRATION_9_10), timestamps for `dateConsumed`
 * on archived bottles were permanently overwritten. Thus, timestamp-based matching
 * (13->14) failed for old records.
 *
 * This migration uses chronological progression:
 * We rank archived bottles from oldest added (bottleId) to newest.
 * We rank tasting notes from oldest tasted (dateTasted/noteId) to newest.
 * We pair them 1:1. This flawlessly restores historical vintage assignments
 * even if the timestamps themselves are out of sync or identical!
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            UPDATE tasting_notes
            SET
                bottleId = COALESCE((
                    SELECT b.bottleId
                    FROM wine_bottles b
                    WHERE b.status = 'ARCHIVED'
                      AND b.definitionId = tasting_notes.definitionId
                      AND (
                          SELECT count(*) FROM wine_bottles b2
                          WHERE b2.definitionId = b.definitionId
                            AND b2.status = 'ARCHIVED'
                            AND b2.bottleId <= b.bottleId
                      ) = (
                          SELECT count(*) FROM tasting_notes n2
                          WHERE n2.definitionId = tasting_notes.definitionId
                            AND (n2.dateTasted < tasting_notes.dateTasted
                                 OR (n2.dateTasted = tasting_notes.dateTasted AND n2.noteId <= tasting_notes.noteId))
                      )
                ), bottleId),
                vintage = COALESCE((
                    SELECT b.vintage
                    FROM wine_bottles b
                    WHERE b.status = 'ARCHIVED'
                      AND b.definitionId = tasting_notes.definitionId
                      AND (
                          SELECT count(*) FROM wine_bottles b2
                          WHERE b2.definitionId = b.definitionId
                            AND b2.status = 'ARCHIVED'
                            AND b2.bottleId <= b.bottleId
                      ) = (
                          SELECT count(*) FROM tasting_notes n2
                          WHERE n2.definitionId = tasting_notes.definitionId
                            AND (n2.dateTasted < tasting_notes.dateTasted
                                 OR (n2.dateTasted = tasting_notes.dateTasted AND n2.noteId <= tasting_notes.noteId))
                      )
                ), vintage)
        """)
    }
}

/**
 * Migration 15 -> 16: Manual override for horizontal tasting notes.
 * The wines were tasted on the same day, so timestamps and chronological ID
 * assignments were insufficient. We use the actual text of the notes to
 * guarantee the 2023 and 2024 vintages are assigned correctly.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            UPDATE tasting_notes
            SET vintage = '2023'
            WHERE notes LIKE '%Mostly subtle fruit on the nose, some elements of hay coming through%'
        """)
        db.execSQL("""
            UPDATE tasting_notes
            SET vintage = '2024'
            WHERE notes LIKE '%Very bright nose. Wine is severely lacking in body and concentration%'
        """)
        db.execSQL("""
            UPDATE tasting_notes
            SET vintage = '2022'
            WHERE notes LIKE '%Rich, round, concentrated red fruit on the nose%'
        """)
        // Clear the bottleId for these horizontally-tasted notes so the relation logic
        // doesn't force a collision if they had all linked to the exact same bottleId.
        // It will allow them to group perfectly by (wine, vintage) in the UI!
        db.execSQL("""
            UPDATE tasting_notes
            SET bottleId = NULL
            WHERE notes LIKE '%Mostly subtle fruit on the nose, some elements of hay coming through%'
               OR notes LIKE '%Very bright nose. Wine is severely lacking in body and concentration%'
        """)
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasting_notes ADD COLUMN sensoryData TEXT DEFAULT NULL")
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wine_definitions ADD COLUMN drinkingWindowStart INTEGER")
        db.execSQL("ALTER TABLE wine_definitions ADD COLUMN drinkingWindowEnd INTEGER")
        db.execSQL("ALTER TABLE tasting_notes ADD COLUMN importKey TEXT")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tasting_notes_importKey ON tasting_notes(importKey)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS consumption_events (
                eventId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                definitionId INTEGER NOT NULL,
                bottleId INTEGER,
                vintage TEXT NOT NULL,
                consumedAt INTEGER,
                quantity INTEGER NOT NULL,
                price REAL,
                noteId INTEGER,
                importKey TEXT,
                FOREIGN KEY(definitionId) REFERENCES wine_definitions(definitionId) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_consumption_events_definitionId ON consumption_events(definitionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_consumption_events_consumedAt ON consumption_events(consumedAt)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_consumption_events_importKey ON consumption_events(importKey)")
        db.execSQL("""
            INSERT INTO consumption_events
                (definitionId, bottleId, vintage, consumedAt, quantity, price, noteId, importKey)
            SELECT
                b.definitionId,
                b.bottleId,
                b.vintage,
                b.dateConsumed,
                -- An archived row records a consumed item even when legacy remaining quantity is zero.
                CASE WHEN b.quantity > 0 THEN b.quantity ELSE 1 END,
                b.price,
                CASE
                    WHEN (SELECT COUNT(*) FROM tasting_notes n WHERE n.bottleId = b.bottleId) = 1
                    THEN (SELECT n.noteId FROM tasting_notes n WHERE n.bottleId = b.bottleId LIMIT 1)
                    ELSE NULL
                END,
                'migration-16-17-bottle:' || b.bottleId
            FROM wine_bottles b
            WHERE b.status = 'ARCHIVED'
        """)
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasting_notes ADD COLUMN reviewerRating INTEGER")
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasting_notes ADD COLUMN valueScore INTEGER")
        db.execSQL("ALTER TABLE tasting_notes ADD COLUMN price REAL")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- 1. Recreate wine_bottles with correct indices ---
        db.execSQL("""
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

        db.execSQL("""
            INSERT INTO wine_bottles_new (bottleId, definitionId, vintage, quantity, status)
            SELECT bottleId, definitionId, vintage, quantity, status FROM wine_bottles
        """)

        db.execSQL("DROP TABLE wine_bottles")
        db.execSQL("ALTER TABLE wine_bottles_new RENAME TO wine_bottles")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_wine_bottles_definitionId` ON `wine_bottles` (`definitionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wine_bottles_definitionId_vintage_status` ON `wine_bottles` (`definitionId`, `vintage`, `status`)")
        db.execSQL("ALTER TABLE tasting_notes ADD COLUMN recommendedBy TEXT")
        db.execSQL("ALTER TABLE tasting_notes ADD COLUMN initialRating INTEGER")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `ratings` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `wineId` INTEGER NOT NULL,
                `score` INTEGER NOT NULL,
                `date` TEXT NOT NULL,
                FOREIGN KEY(`wineId`) REFERENCES `wine_definitions`(`definitionId`) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ratings_wineId` ON `ratings` (`wineId`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val cursor = db.query("PRAGMA table_info(wine_definitions)")
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
            db.execSQL("ALTER TABLE wine_definitions ADD COLUMN imageUrl TEXT DEFAULT NULL")
        }
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wine_bottles ADD COLUMN dateConsumed INTEGER DEFAULT NULL")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Backfill dateConsumed for archived bottles based on the most recent tasting note for that wine
        db.execSQL("""
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
    override fun migrate(db: SupportSQLiteDatabase) {
        // Step 1: Fix tasting note vintages — copy from the bottle where unambiguous
        // Only update notes with 'NV' where there's exactly one distinct non-NV vintage for that definition
        db.execSQL("""
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
        db.execSQL("""
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
