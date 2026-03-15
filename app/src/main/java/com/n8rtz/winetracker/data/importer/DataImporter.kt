package com.n8rtz.winetracker.data.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.TastingNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class DataImporter(
    private val context: Context,
    private val database: WineDatabase
) {
    suspend fun standardImport(uri: Uri) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        contentResolver.openInputStream(uri)?.use { inputStream ->
            InputStreamReader(inputStream).use { reader ->
                
                Log.d("WineTracker", "Starting standard JSON import")
                
                val gson = com.google.gson.GsonBuilder()
                    .registerTypeAdapter(Long::class.java, com.google.gson.JsonDeserializer { json, _, _ ->
                        try {
                            json.asLong
                        } catch (e: Exception) {
                            try {
                                val dateStr = json.asString
                                val format = java.text.SimpleDateFormat("M/d/yyyy", java.util.Locale.US)
                                format.parse(dateStr)?.time ?: 0L
                            } catch (e2: Exception) {
                                0L
                            }
                        }
                    })
                    .create()
                    
                val backupData = try {
                    gson.fromJson(reader, BackupData::class.java)
                } catch (e: Exception) {
                    Log.e("DataImporter", "Error parsing JSON backup", e)
                    return@use
                }
                
                if (backupData == null) {
                    Log.e("DataImporter", "Parsed BackupData was null")
                    return@use
                }

                val wineDao = database.wineDao()
                val inventoryDao = database.inventoryDao()
                val tastingNoteDao = database.tastingNoteDao()
                
                val idMapping = mutableMapOf<Long, Long>()
                
                var definitionCount = 0
                var bottleCount = 0
                var noteCount = 0

                // Pre-process: build lookup maps from backup data for cross-referencing
                // Map of old definitionId -> best known vintage (from bottles)
                val vintageByOldDefId = mutableMapOf<Long, String>()
                backupData.bottles.forEach { bottle ->
                    val v = bottle.vintage?.takeIf { it.isNotBlank() && it != "NV" }
                    if (v != null) {
                        vintageByOldDefId[bottle.definitionId] = v
                    }
                }
                Log.d("WineTracker", "Built vintage lookup with ${vintageByOldDefId.size} entries")

                // Map of old definitionId -> latest dateTasted (from notes)
                val latestDateByOldDefId = mutableMapOf<Long, Long>()
                backupData.notes.forEach { note ->
                    if (note.dateTasted > 0) {
                        val existing = latestDateByOldDefId[note.definitionId]
                        if (existing == null || note.dateTasted > existing) {
                            latestDateByOldDefId[note.definitionId] = note.dateTasted
                        }
                    }
                }
                Log.d("WineTracker", "Built dateTasted lookup with ${latestDateByOldDefId.size} entries")

                database.withTransaction {
                    // 1. Process Definitions using old IDs to build mapping
                    backupData.definitions.forEach { oldDef ->
                        try {
                            val maker = oldDef.maker.takeIf { it.isNotBlank() } ?: "Unknown"
                            val name = oldDef.name.takeIf { it.isNotBlank() } ?: "Unknown"
                            val type = oldDef.type.takeIf { it.isNotBlank() } ?: "Unknown"
                            val country = oldDef.country.takeIf { it.isNotBlank() } ?: "Unknown"

                            val existing = wineDao.getDefinitionByDetails(maker, name)
                            if (existing != null) {
                                idMapping[oldDef.definitionId] = existing.definitionId
                                Log.d("WineTracker", "Def mapping (Existing): ${oldDef.definitionId} -> ${existing.definitionId}")
                            } else {
                                val newDef = WineDefinition(
                                    maker = maker,
                                    name = name,
                                    type = type,
                                    country = country,
                                    region = oldDef.region,
                                    subregion = oldDef.subregion,
                                    imageUrl = oldDef.imageUrl
                                )
                                val newId = wineDao.insertDefinition(newDef)
                                idMapping[oldDef.definitionId] = newId
                                definitionCount++
                                Log.d("WineTracker", "Def mapping (New): ${oldDef.definitionId} -> $newId")
                            }
                        } catch (e: Exception) {
                            Log.e("DataImporter", "Error importing definition: ${oldDef.name}", e)
                        }
                    }

                    // 2. Process Bottles — backfill dateConsumed from notes if missing
                    backupData.bottles.forEach { oldBottle ->
                        try {
                            val mappedDefId = idMapping[oldBottle.definitionId]
                            if (mappedDefId == null) {
                                Log.e("WineTracker", "No mapping for DefID: ${oldBottle.definitionId} in Bottle ${oldBottle.bottleId}")
                                return@forEach
                            }

                            // If ARCHIVED and no dateConsumed, use latest dateTasted from notes
                            val resolvedDateConsumed = oldBottle.dateConsumed
                                ?: if (oldBottle.status == com.n8rtz.winetracker.data.local.BottleStatus.ARCHIVED) {
                                    latestDateByOldDefId[oldBottle.definitionId]
                                } else null

                            val newBottle = WineBottle(
                                definitionId = mappedDefId,
                                vintage = oldBottle.vintage?.takeIf { it.isNotBlank() } ?: "NV",
                                quantity = oldBottle.quantity,
                                price = oldBottle.price,
                                qtyType = oldBottle.qtyType,
                                location = oldBottle.location,
                                status = oldBottle.status,
                                dateAdded = oldBottle.dateAdded?.takeIf { it.isNotBlank() },
                                dateConsumed = resolvedDateConsumed
                            )
                            inventoryDao.insertBottle(newBottle)
                            bottleCount++
                            if (resolvedDateConsumed != null && oldBottle.dateConsumed == null) {
                                Log.d("WineTracker", "Backfilled dateConsumed for bottle ${oldBottle.bottleId} -> $resolvedDateConsumed")
                            }
                        } catch (e: Exception) {
                            Log.e("DataImporter", "Error importing bottle", e)
                        }
                    }

                    // 3. Process Tasting Notes — fix NV vintage from bottle data
                    backupData.notes.forEach { oldNote ->
                        try {
                            val mappedDefId = idMapping[oldNote.definitionId]
                            if (mappedDefId == null) {
                                Log.e("WineTracker", "No mapping for DefID: ${oldNote.definitionId} in Note ${oldNote.noteId}")
                                return@forEach
                            }
                            
                            // If note has blank/NV vintage, use the vintage from the bottle
                            val resolvedVintage = oldNote.vintage?.takeIf { it.isNotBlank() && it != "NV" }
                                ?: vintageByOldDefId[oldNote.definitionId]
                                ?: "NV"

                            val newNote = TastingNote(
                                definitionId = mappedDefId,
                                vintage = resolvedVintage,
                                dateTasted = oldNote.dateTasted.takeIf { it > 0 } ?: System.currentTimeMillis(),
                                rating = oldNote.rating,
                                notes = oldNote.notes,
                                rebuy = oldNote.rebuy
                            )
                            tastingNoteDao.insertNote(newNote)
                            noteCount++
                            if (resolvedVintage != oldNote.vintage) {
                                Log.d("WineTracker", "Fixed vintage for note ${oldNote.noteId}: '${oldNote.vintage}' -> '$resolvedVintage'")
                            }
                        } catch (e: Exception) {
                            Log.e("DataImporter", "Error importing note", e)
                        }
                    }
                }
                Log.d("WineTracker", "Import finished. Imported $definitionCount definitions, $bottleCount bottles, $noteCount notes")
            }
        }
    }
}
