package com.n8rtz.winetracker.data.importer

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.n8rtz.winetracker.data.local.WineDatabase
import java.io.File
import java.io.OutputStreamWriter

object JsonExporter {
    suspend fun exportDataToJson(context: Context, db: WineDatabase): File {
        val file = File(context.cacheDir, "WineTracker_Backup.json")
        val backupData = db.withTransaction {
            BackupData(
                definitions = db.wineDao().getAllDefinitionsOnce(),
                bottles = db.inventoryDao().getAllBottlesOnce(),
                notes = db.tastingNoteDao().getAllNotesOnce(),
                ratings = db.ratingDao().getAllRatingsOnce(),
                consumptionEvents = db.consumptionEventDao().getAllEventsOnce(),
                imageAssets = db.wineImageAssetDao().getAllOnce().map { it.copy(relativePath = null) }
            ).copy(
                reviewerNames = BackupPreferences.readReviewers(context),
                imageStorageBudgetBytes = BackupPreferences.readBudgetBytes(context)
            )
        }

        OutputStreamWriter(file.outputStream(), Charsets.UTF_8).use { writer ->
            Gson().toJson(backupData, writer)
        }
        return file
    }
}
