package com.n8rtz.winetracker.data.importer

import android.content.Context
import com.n8rtz.winetracker.data.local.WineDatabase
import java.io.File
import java.io.FileWriter

object CsvExporter {
    suspend fun exportDataToCsv(context: Context, db: WineDatabase): File {
        val file = File(context.cacheDir, "WineTracker_Backup.csv")
        
        FileWriter(file).use { writer ->
            // Definitions
            writer.append("--- Definitions ---\n")
            writer.append("ID,Maker,Name,Type,Country,Region,Subregion\n")
            val definitions = db.wineDao().getAllDefinitionsOnce()
            for (it in definitions) {
                writer.append("${it.definitionId},\"${it.maker}\",\"${it.name}\",\"${it.type}\",\"${it.country}\",\"${it.region}\",\"${it.subregion}\"\n")
            }
            
            // Bottles
            writer.append("\n--- Bottles ---\n")
            writer.append("ID,DefinitionID,Vintage,Quantity,Price,Location,Status,DateAdded,DateConsumed\n")
            val bottles = db.inventoryDao().getAllBottlesOnce()
            for (it in bottles) {
                writer.append("${it.bottleId},${it.definitionId},\"${it.vintage}\",${it.quantity},${it.price ?: 0.0},\"${it.location}\",${it.status},${it.dateAdded},${it.dateConsumed}\n")
            }
            
            // Tasting Notes
            writer.append("\n--- Tasting Notes ---\n")
            writer.append("ID,BottleID,DefinitionID,Vintage,Date,Rating,Notes,Rebuy\n")
            val notes = db.tastingNoteDao().getAllNotesOnce()
            for (it in notes) {
                // Note: Using 0 for BottleID since we map by DefinitionID/Vintage during import
                writer.append("${it.noteId},0,${it.definitionId},\"${it.vintage}\",${it.dateTasted},${it.rating},\"${it.notes ?: ""}\",${it.rebuy}\n")
            }
        }
        return file
    }
}
