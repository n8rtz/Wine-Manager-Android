package com.n8rtz.winetracker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class DataImporter(
    private val context: Context,
    private val database: WineDatabase
) {
    suspend fun performThreeWayImport() = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.clearAllTables()

            val wineDao = database.wineDao()
            val inventoryDao = database.inventoryDao()

            // 1. Phase 1: The Catalog (Wines - Log (In).csv)
            context.assets.open("Wines - Log (In).csv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readLine() // Skip header
                    reader.lineSequence().forEach { line ->
                        val columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                            .map { it.replace("\"", "").trim() }
                        
                        if (columns.size >= 7) { 
                            val maker = columns[2]
                            val name = columns[3]
                            val type = columns[1]
                            val country = if (columns[5] == "null") null else columns[5]
                            val region = if (columns[6] == "null") null else columns[6]

                            if (wineDao.getDefinitionByDetails(maker, name) == null) {
                                wineDao.insertDefinition(
                                    WineDefinition(
                                        maker = maker,
                                        name = name,
                                        type = type,
                                        country = country ?: "",
                                        region = region
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 2. Phase 2: Active Inventory (Wines - Cellar.csv)
            context.assets.open("Wines - Cellar.csv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    repeat(27) { reader.readLine() }

                    reader.lineSequence().forEach { line ->
                        val columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                            .map { it.replace("\"", "").trim() }
                        
                        if (columns.size >= 9) {
                            val qty = columns[0].toIntOrNull() ?: 0
                            val maker = columns[2]
                            val name = columns[3]
                            val vintage = columns[4]
                            val price = columns[8].replace("$", "").replace(",", "").toDoubleOrNull() ?: 0.0

                            val def = wineDao.getDefinitionByDetails(maker, name)
                            if (def != null) {
                                inventoryDao.insertBottle(
                                    WineBottle(
                                        definitionId = def.definitionId,
                                        vintage = vintage,
                                        quantity = qty,
                                        price = price,
                                        qtyType = "Bottle",
                                        location = null,
                                        status = BottleStatus.IN_CELLAR,
                                        dateAdded = System.currentTimeMillis().toString()
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 3. Phase 3: History (Wines - Log (Out).csv)
            context.assets.open("Wines - Log (Out).csv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readLine()
                    reader.lineSequence().forEach { line ->
                        val columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                            .map { it.replace("\"", "").trim() }
                        
                        if (columns.size >= 5) {
                            val dateAdded = columns[0]
                            val maker = columns[3]
                            val nameAndVintage = columns[4]
                            
                            Log.d("WineTracker", "Imported History: $nameAndVintage")

                            val name = nameAndVintage.substringBefore("(").trim()
                            val vintage = nameAndVintage.substringAfter("(").substringBefore(")").trim()

                            val def = wineDao.getDefinitionByDetails(maker, name)
                            if (def != null) {
                                inventoryDao.insertBottle(
                                    WineBottle(
                                        definitionId = def.definitionId,
                                        vintage = vintage,
                                        quantity = 0,
                                        price = 0.0,
                                        qtyType = null,
                                        location = null,
                                        status = BottleStatus.ARCHIVED,
                                        dateAdded = dateAdded
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun standardImport(uri: Uri) = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val lines = reader.readLines()
                if (lines.isEmpty()) return@use

                val wineDao = database.wineDao()
                val inventoryDao = database.inventoryDao()

                // Maps file-specific ID -> DB generated ID
                val idMapping = mutableMapOf<Long, Long>()

                database.withTransaction {
                    var currentSection = ""
                    
                    lines.forEach { line ->
                        if (line.startsWith("---")) {
                            currentSection = line.trim()
                            return@forEach
                        }
                        if (line.isBlank() || line.startsWith("ID,")) return@forEach

                        val columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                            .map { it.replace("\"", "").trim() }
                        
                        try {
                            when (currentSection) {
                                "--- Definitions ---" -> {
                                    // File format assumed: ID,Maker,Name,Type,Country,Region,Subregion
                                    if (columns.size >= 7) {
                                        val oldId = columns[0].toLongOrNull() ?: return@forEach
                                        val maker = columns[1]
                                        val name = columns[2]
                                        val type = columns[3]
                                        val country = columns[4]
                                        val region = if (columns[5] == "null") null else columns[5]
                                        val subregion = if (columns[6] == "null") null else columns[6]

                                        val existing = wineDao.getDefinitionByDetails(maker, name)
                                        if (existing != null) {
                                            idMapping[oldId] = existing.definitionId
                                        } else {
                                            val newId = wineDao.insertDefinition(
                                                WineDefinition(
                                                    maker = maker,
                                                    name = name,
                                                    type = type,
                                                    country = country,
                                                    region = region,
                                                    subregion = subregion
                                                )
                                            )
                                            idMapping[oldId] = newId
                                        }
                                    }
                                }
                                "--- Bottles ---" -> {
                                    // File format: ID,DefinitionID,Vintage,Quantity,Price,Location,Status
                                    if (columns.size >= 7) {
                                        val fileDefId = columns[1].toLongOrNull() ?: return@forEach
                                        val mappedDefinitionId = idMapping[fileDefId] ?: return@forEach
                                        
                                        val vintage = columns[2]
                                        val qty = columns[3].toIntOrNull() ?: 0
                                        val price = columns[4].toDoubleOrNull() ?: 0.0
                                        val location = columns[5]
                                        val statusStr = columns[6]
                                        val status = try { BottleStatus.valueOf(statusStr) } catch(e: Exception) { BottleStatus.IN_CELLAR }

                                        inventoryDao.insertBottle(
                                            WineBottle(
                                                definitionId = mappedDefinitionId,
                                                vintage = vintage,
                                                quantity = qty,
                                                price = price,
                                                qtyType = "Bottle",
                                                location = if(location == "null") null else location,
                                                status = status,
                                                dateAdded = System.currentTimeMillis().toString()
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("DataImporter", "Error parsing line: $line", e)
                        }
                    }
                }
            }
        }
    }
}
