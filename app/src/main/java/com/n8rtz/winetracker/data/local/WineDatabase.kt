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
    @PrimaryKey(autoGenerate = true) val bottleId: Long = 0,
    val definitionId: Long,
    val vintage: String,
    val quantity: Int,
    val price: Double?,
    val qtyType: String?,
    val location: String?,
    val status: BottleStatus = BottleStatus.IN_CELLAR,
    val dateAdded: String? = null
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
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class WineDatabase : RoomDatabase() {
    abstract fun wineDao(): WineDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun tastingNoteDao(): TastingNoteDao
    abstract fun ratingDao(): RatingDao
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
        
        // Copy only existing columns
        database.execSQL("""
            INSERT INTO wine_bottles_new (bottleId, definitionId, vintage, quantity, status)
            SELECT bottleId, definitionId, vintage, quantity, status FROM wine_bottles
        """)
        
        database.execSQL("DROP TABLE wine_bottles")
        database.execSQL("ALTER TABLE wine_bottles_new RENAME TO wine_bottles")
        
        // Add indices
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_wine_bottles_definitionId` ON `wine_bottles` (`definitionId`)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wine_bottles_definitionId_vintage_status` ON `wine_bottles` (`definitionId`, `vintage`, `status`)")

        // --- 2. Update tasting_notes ---
        database.execSQL("ALTER TABLE tasting_notes ADD COLUMN recommendedBy TEXT")
        database.execSQL("ALTER TABLE tasting_notes ADD COLUMN initialRating INTEGER")

        // --- 3. Create ratings table with index ---
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `ratings` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `wineId` INTEGER NOT NULL, 
                `score` INTEGER NOT NULL, 
                `date` TEXT NOT NULL, 
                FOREIGN KEY(`wineId`) REFERENCES `wine_definitions`(`definitionId`) ON DELETE CASCADE
            )
        """)
        
        // Add missing index to ratings
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_ratings_wineId` ON `ratings` (`wineId`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Check if column already exists to prevent crash
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
