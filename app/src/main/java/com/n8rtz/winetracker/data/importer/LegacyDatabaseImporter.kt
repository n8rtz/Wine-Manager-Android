package com.n8rtz.winetracker.data.importer

import android.content.Context
import android.util.Log
import com.n8rtz.winetracker.data.local.BottleStatus
import com.n8rtz.winetracker.data.local.InventoryDao
import com.n8rtz.winetracker.data.local.TastingNoteDao
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineDao
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object LegacyDatabaseImporter {
    suspend fun importLogFile(context: Context, database: WineDatabase) = withContext(Dispatchers.IO) {
        val wineDao = database.wineDao()
        val inventoryDao = database.inventoryDao()
        val tastingDao = database.tastingNoteDao()

        // 1. Import "Log (In)" as IN_CELLAR bottles
        importCsvToDatabase(context, "Wines - Log (In).csv", wineDao, inventoryDao, tastingDao, BottleStatus.IN_CELLAR)
        
        // 2. Import "Log (Out)" as ARCHIVED bottles (History)
        importCsvToDatabase(context, "Wines - Log (Out).csv", wineDao, inventoryDao, tastingDao, BottleStatus.ARCHIVED)
    }

    private suspend fun importCsvToDatabase(
        context: Context,
        fileName: String,
        wineDao: WineDao,
        inventoryDao: InventoryDao,
        tastingDao: TastingNoteDao,
        status: BottleStatus
    ) {
        context.assets.open(fileName).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readLine() // Skip header

                reader.lineSequence().forEach { line ->
                    val columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                        .map { it.replace("\"", "").trim() }
                    
                    if (columns.size >= 10) {
                        try {
                            val type = columns[1]
                            val maker = columns[2]
                            val name = columns[3]
                            val vintage = columns[4]
                            val country = columns[5]
                            val region = columns[6]
                            val quantity = columns[8].toIntOrNull() ?: 0
                            val priceString = columns[9].replace("$", "").replace(",", "")
                            val price = priceString.toDoubleOrNull()
                            val dateAdded = columns[0]

                            // Get or Create WineDefinition
                            var definition = wineDao.getDefinitionByDetails(maker, name)
                            if (definition == null) {
                                val newDef = WineDefinition(
                                    maker = maker,
                                    name = name,
                                    type = type,
                                    country = country,
                                    region = region
                                )
                                wineDao.insertDefinition(newDef)
                                definition = wineDao.getDefinitionByDetails(maker, name)
                            }

                            // Insert WineBottle with specific status
                            if (definition != null) {
                                val bottle = WineBottle(
                                    definitionId = definition.definitionId,
                                    vintage = vintage,
                                    quantity = if (status == BottleStatus.IN_CELLAR) quantity else 0,
                                    price = price,
                                    qtyType = null,
                                    location = null,
                                    status = status,
                                    dateAdded = dateAdded
                                )
                                inventoryDao.insertBottle(bottle)
                                Log.d("WineTracker", "Success: Imported $name by $maker as $status")
                            }
                        } catch (e: Exception) {
                            Log.e("WineTracker", "Error importing row from $fileName: $line", e)
                        }
                    }
                }
            }
        }
    }
}
