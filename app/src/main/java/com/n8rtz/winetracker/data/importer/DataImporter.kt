package com.n8rtz.winetracker.data.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.n8rtz.winetracker.data.local.BottleStatus
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.TastingNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class DataImporter(
    private val context: Context,
    private val database: WineDatabase
) {
    suspend fun standardImport(uri: Uri) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val lines = reader.readLines()
                if (lines.isEmpty()) return@use

                val wineDao = database.wineDao()
                val inventoryDao = database.inventoryDao()
                val tastingNoteDao = database.tastingNoteDao()
                val idMapping = mutableMapOf<Long, Long>()
                val vintageMapping = mutableMapOf<Long, String>()
                var currentSection = ""
                
                var definitionCount = 0
                var bottleCount = 0

                Log.d("WineTracker", "Starting standard import. Total lines: ${lines.size}")

                database.withTransaction {
                    lines.forEach { line ->
                        val trimmedLine = line.trim()
                        
                        if (trimmedLine.startsWith("---")) {
                            // Normalize section names by removing potential trailing commas from spreadsheet exports
                            currentSection = when {
                                trimmedLine.contains("Definitions") -> "--- Definitions ---"
                                trimmedLine.contains("Bottles") -> "--- Bottles ---"
                                trimmedLine.contains("Tasting Notes") -> "--- Tasting Notes ---"
                                else -> trimmedLine
                            }
                            Log.d("WineTracker", "Normalized section: $currentSection")
                            return@forEach
                        }
                        
                        // Skip headers and blank lines (be robust against trailing commas in headers)
                        if (trimmedLine.isBlank() || trimmedLine.startsWith("ID,") || trimmedLine.all { it == ',' }) return@forEach

                        // Use regex to handle quoted values containing commas
                        val columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                            .map { it.replace("\"", "").trim() }

                        try {
                            when (currentSection) {
                                "--- Definitions ---" -> {
                                    if (columns.size >= 7) {
                                        val oldId = columns[0].toLongOrNull() ?: return@forEach
                                        val maker = columns[1].takeIf { it != "null" && it.isNotBlank() } ?: "Unknown"
                                        val name = columns[2].takeIf { it != "null" && it.isNotBlank() } ?: "Unknown"
                                        val type = columns[3].takeIf { it != "null" && it.isNotBlank() } ?: "Unknown"
                                        val country = columns[4].takeIf { it != "null" && it.isNotBlank() } ?: "Unknown"
                                        val region = columns[5].takeIf { it != "null" && it.isNotBlank() }
                                        val subregion = columns[6].takeIf { it != "null" && it.isNotBlank() }

                                        val existing = wineDao.getDefinitionByDetails(maker, name)
                                        if (existing != null) {
                                            idMapping[oldId] = existing.definitionId
                                            Log.d("WineTracker", "Def mapping: $oldId -> ${existing.definitionId} (Existing)")
                                        } else {
                                            val newId = wineDao.insertDefinition(
                                                WineDefinition(maker = maker, name = name, type = type, country = country, region = region, subregion = subregion)
                                            )
                                            idMapping[oldId] = newId
                                            definitionCount++
                                            Log.d("WineTracker", "Def mapping: $oldId -> $newId (New: $maker $name)")
                                        }
                                    }
                                }
                                "--- Bottles ---" -> {
                                    if (columns.size >= 7) {
                                        val oldBottleId = columns[0].toLongOrNull() ?: return@forEach
                                        val fileDefId = columns[1].toLongOrNull() ?: return@forEach
                                        val mappedDefinitionId = idMapping[fileDefId]
                                        
                                        if (mappedDefinitionId == null) {
                                            Log.e("WineTracker", "No mapping for DefID: $fileDefId in Bottle line: $line")
                                            return@forEach
                                        }

                                        val vintage = columns[2].takeIf { it != "null" && it.isNotBlank() } ?: "NV"
                                        val qty = columns[3].toIntOrNull() ?: 0
                                        val price = columns[4].toDoubleOrNull() ?: 0.0
                                        val loc = columns[5].takeIf { it != "null" && it.isNotBlank() }
                                        val statusStr = columns[6].takeIf { it != "null" && it.isNotBlank() } ?: "IN_CELLAR"
                                        val status = try { BottleStatus.valueOf(statusStr) } catch (e: Exception) { BottleStatus.IN_CELLAR }
                                        
                                        vintageMapping[oldBottleId] = vintage
                                        val insertedId = inventoryDao.insertBottle(
                                            WineBottle(
                                                definitionId = mappedDefinitionId,
                                                vintage = vintage,
                                                quantity = qty,
                                                price = price,
                                                qtyType = "Bottle",
                                                location = loc,
                                                status = status,
                                                dateAdded = System.currentTimeMillis().toString()
                                            )
                                        )
                                        bottleCount++
                                        Log.d("WineTracker", "Imported bottle: $insertedId (Def: $mappedDefinitionId, Qty: $qty, Status: $status)")
                                    }
                                }
                                "--- Tasting Notes ---" -> {
                                    if (columns.size >= 7) {
                                        val fileBottleId = columns[1].toLongOrNull() ?: return@forEach
                                        val fileDefId = columns[2].toLongOrNull() ?: return@forEach
                                        val mappedDefinitionId = idMapping[fileDefId]
                                        
                                        if (mappedDefinitionId == null) {
                                            Log.e("WineTracker", "No mapping for Note DefID: $fileDefId")
                                            return@forEach
                                        }

                                        val vintage = vintageMapping[fileBottleId] ?: "NV"
                                        
                                        val noteId = tastingNoteDao.insertNote(
                                            TastingNote(
                                                definitionId = mappedDefinitionId,
                                                vintage = vintage,
                                                dateTasted = System.currentTimeMillis(),
                                                rating = columns[4].toDoubleOrNull() ?: 0.0,
                                                notes = columns[5].takeIf { it != "null" && it.isNotBlank() } ?: "",
                                                rebuy = columns[6].toBooleanStrictOrNull() ?: false
                                            )
                                        )
                                        Log.d("WineTracker", "Imported Note: $noteId for Def: $mappedDefinitionId")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("DataImporter", "Error parsing line: $line", e)
                        }
                    }
                }
                Log.d("WineTracker", "Import finished. Imported $definitionCount definitions and $bottleCount bottles")
            }
        }
    }
}
