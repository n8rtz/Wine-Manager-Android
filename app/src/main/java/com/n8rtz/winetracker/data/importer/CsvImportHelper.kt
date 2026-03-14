package com.n8rtz.winetracker.data.importer

import android.content.Context
import android.util.Log
import com.n8rtz.winetracker.data.local.BottleStatus
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object CsvImportHelper {
    suspend fun importWines(context: Context, database: WineDatabase) = withContext(Dispatchers.IO) {
        val wineDao = database.wineDao()
        val inventoryDao = database.inventoryDao()

        val fileName = "WineTracker_Backup.csv"

        try {
            context.assets.open(fileName).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    // Map old ID (from CSV) to (Maker, Name)
                    val idToDefinition = mutableMapOf<Long, Pair<String, String>>()
                    var parsingDefinitions = false
                    var parsingBottles = false

                    reader.lineSequence().forEach { line ->
                        if (line.startsWith("--- Definitions ---")) {
                            parsingDefinitions = true
                            parsingBottles = false
                            return@forEach
                        }
                        if (line.startsWith("--- Bottles ---")) {
                            parsingDefinitions = false
                            parsingBottles = true
                            return@forEach
                        }

                        if (parsingDefinitions && !line.startsWith("ID,")) {
                            val parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                            if (parts.size >= 7) {
                                val oldId = parts[0].toLongOrNull() ?: return@forEach
                                val maker = parts[1].replace("\"", "").trim()
                                val name = parts[2].replace("\"", "").trim()
                                val type = parts[3].replace("\"", "").trim()
                                val country = parts[4].replace("\"", "").trim()
                                val region = parts[5].replace("\"", "").trim()

                                idToDefinition[oldId] = Pair(maker, name)
                                
                                if (wineDao.getDefinitionByDetails(maker, name) == null) {
                                    wineDao.insertDefinition(
                                        WineDefinition(
                                            maker = maker,
                                            name = name,
                                            type = type,
                                            country = country,
                                            region = region
                                        )
                                    )
                                }
                            }
                        } else if (parsingBottles && !line.startsWith("ID,")) {
                            val parts = line.split(",")
                            if (parts.size >= 7) {
                                val oldDefId = parts[1].toLongOrNull() ?: return@forEach
                                val vintage = parts[2].replace("\"", "").trim()
                                val qty = parts[3].toIntOrNull() ?: 0
                                val price = parts[4].toDoubleOrNull()
                                val statusStr = parts[6].replace("\"", "").trim()
                                val status = if (statusStr == "IN_CELLAR") BottleStatus.IN_CELLAR else BottleStatus.ARCHIVED

                                // Find current DB ID
                                val (maker, name) = idToDefinition[oldDefId] ?: return@forEach
                                val definition = wineDao.getDefinitionByDetails(maker, name)

                                if (definition != null && (qty > 0 || status == BottleStatus.ARCHIVED)) {
                                    inventoryDao.insertBottle(
                                        WineBottle(
                                            definitionId = definition.definitionId,
                                            vintage = vintage,
                                            quantity = qty,
                                            price = price,
                                            qtyType = null,
                                            location = null,
                                            status = status
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WineTracker", "Error importing backup: ${e.message}", e)
        }
    }
}
