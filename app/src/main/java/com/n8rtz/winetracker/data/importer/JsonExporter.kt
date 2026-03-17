package com.n8rtz.winetracker.data.importer

import android.content.Context
import com.google.gson.Gson
import com.n8rtz.winetracker.data.local.WineDatabase
import java.io.File
import java.io.FileWriter

object JsonExporter {
    suspend fun exportDataToJson(context: Context, db: WineDatabase): File {
        val file = File(context.cacheDir, "WineTracker_Backup.json")
        
        val definitions = db.wineDao().getAllDefinitionsOnce()
        val bottles = db.inventoryDao().getAllBottlesOnce()
        val notes = db.tastingNoteDao().getAllNotesOnce()
        
        val backupData = BackupData(
            definitions = definitions,
            bottles = bottles,
            notes = notes
        )
        
        FileWriter(file).use { writer ->
            Gson().toJson(backupData, writer)
        }
        
        return file
    }
}
