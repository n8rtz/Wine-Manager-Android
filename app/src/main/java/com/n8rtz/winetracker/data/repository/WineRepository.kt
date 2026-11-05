package com.n8rtz.winetracker.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.n8rtz.winetracker.data.local.BottleStatus
import com.n8rtz.winetracker.data.local.ConsumptionEvent
import com.n8rtz.winetracker.data.local.ConsumptionEventWithDefinition
import com.n8rtz.winetracker.data.local.InventoryDao
import com.n8rtz.winetracker.data.local.RatingDao
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.TastingNoteDao
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.local.WineDao
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.normalizeVintage
import kotlinx.coroutines.flow.Flow

class WineRepository(
    private val wineDao: WineDao,
    private val inventoryDao: InventoryDao,
    private val tastingNoteDao: TastingNoteDao,
    private val ratingDao: RatingDao,
    val database: WineDatabase
) {
    val activeInventory: Flow<List<WineBottleWithDefinition>> = inventoryDao.getActiveInventory()
    val archivedInventory: Flow<List<WineBottleWithDefinition>> = inventoryDao.getArchivedBottles()
    val tastingHistory: Flow<List<TastingNoteWithDefinition>> = tastingNoteDao.getTastingHistory()
    val consumptionHistory: Flow<List<ConsumptionEventWithDefinition>> = database.consumptionEventDao().getConsumptionHistory()
    val allDefinitions: Flow<List<WineDefinition>> = wineDao.getAllDefinitions()

    companion object {
        val locationData = mapOf(
            "Argentina" to mapOf("Mendoza" to listOf("Lujan de Cuyo", "Uco Valley", "Maipu"), "Salta" to listOf("Cafayate"), "Patagonia" to listOf("Rio Negro", "Neuquen")),
            "Australia" to mapOf("South Australia" to listOf("Barossa Valley", "McLaren Vale", "Clare Valley", "Coonawarra", "Eden Valley"), "Western Australia" to listOf("Margaret River"), "Victoria" to listOf("Yarra Valley", "Mornington Peninsula"), "New South Wales" to listOf("Hunter Valley")),
            "California" to mapOf("North Coast" to listOf("Napa Valley", "Sonoma Coast", "Alexander Valley", "Russian River Valley", "Oakville", "Rutherford", "Stags Leap District", "Howell Mountain", "Los Carneros"), "Central Coast" to listOf("Paso Robles", "Santa Barbara", "Santa Ynez Valley", "Santa Lucia Highlands"), "Central Valley" to listOf("Lodi")),
            "Chile" to mapOf("Central Valley" to listOf("Maipo Valley", "Colchagua Valley", "Cachapoal", "Curico"), "Aconcagua" to listOf("Casablanca Valley", "San Antonio Valley")),
            "France" to mapOf(
                "Bordeaux" to listOf("Pauillac", "Margaux", "St. Emilion", "Pomerol", "Graves", "Saint-Estephe", "Saint-Julien", "Pessac-Leognan", "Sauternes", "Barsac", "Medoc", "Haut-Medoc", "Fronsac"),
                "Burgundy" to listOf("Chablis", "Cote de Nuits", "Cote de Beaune", "Gevrey-Chambertin", "Chambolle-Musigny", "Vosne-Romanee", "Meursault", "Puligny-Montrachet", "Pouilly-Fuisse", "Macon"),
                "Rhone" to listOf("Chateauneuf-du-Pape", "Cote Rotie", "Hermitage", "Crozes-Hermitage", "Gigondas", "Condrieu"),
                "Champagne" to listOf("Montagne de Reims", "Cote des Blancs", "Vallee de la Marne"),
                "Loire" to listOf("Sancerre", "Pouilly-Fume", "Vouvray", "Chinon")
            ),
            "Georgia" to mapOf("Kakheti" to listOf("Tsinandali", "Mukuzani", "Kindzmarauli"), "Imereti" to listOf()),
            "Germany" to mapOf("Mosel" to listOf("Bernkastel", "Piesport", "Wehlen"), "Rheingau" to listOf("Rudesheim", "Johannisberg"), "Rheinhessen" to listOf(), "Pfalz" to listOf()),
            "Greece" to mapOf("Macedonia" to listOf("Naoussa"), "Peloponnese" to listOf("Nemea", "Mantineia"), "Aegean Islands" to listOf("Santorini")),
            "Italy" to mapOf(
                "Piedmont" to listOf("Barolo", "Barbaresco", "Asti", "Alba", "Gavi", "Roero"),
                "Tuscany" to listOf("Chianti Classico", "Brunello di Montalcino", "Bolgheri", "Rosso di Montalcino", "Vino Nobile di Montepulciano"),
                "Veneto" to listOf("Amarone della Valpolicella", "Prosecco", "Valpolicella Ripasso", "Soave"),
                "Sicily" to listOf("Etna", "Vittoria")
            ),
            "New Zealand" to mapOf("Marlborough" to listOf("Wairau Valley", "Awatere Valley"), "Central Otago" to listOf("Bannockburn", "Gibbston"), "Hawke's Bay" to listOf("Gimblett Gravels"), "Martinborough" to listOf()),
            "Oregon" to mapOf("Willamette Valley" to listOf("Dundee Hills", "Eola-Amity Hills", "Chehalem Mountains", "Ribbon Ridge", "Yamhill-Carlton"), "Southern Oregon" to listOf("Rogue Valley", "Applegate Valley")),
            "Portugal" to mapOf("Douro" to listOf("Cima Corgo", "Baixo Corgo"), "Alentejo" to listOf(), "Minho" to listOf("Vinho Verde"), "Dao" to listOf()),
            "South Africa" to mapOf("Coastal Region" to listOf("Stellenbosch", "Franschhoek", "Paarl", "Swartland", "Constantia"), "Cape South Coast" to listOf("Walker Bay", "Hemel-en-Aarde")),
            "Spain" to mapOf("Rioja" to listOf("Rioja Alta", "Rioja Alavesa", "Rioja Oriental"), "Castilla y Leon" to listOf("Ribera del Duero", "Rueda", "Toro"), "Catalonia" to listOf("Priorat", "Penedes"), "Galicia" to listOf("Rias Baixas")),
            "Washington" to mapOf("Columbia Valley" to listOf("Walla Walla Valley", "Yakima Valley", "Red Mountain", "Horse Heaven Hills", "Wahluke Slope"))
        )
    }

    suspend fun addWine(
        maker: String,
        name: String,
        vintage: String,
        type: String,
        country: String,
        region: String?,
        subregion: String?,
        quantity: Int,
        price: Double?,
        drinkingWindowStart: Int? = null,
        drinkingWindowEnd: Int? = null
    ) {
        require(drinkingWindowStart == null || drinkingWindowEnd == null || drinkingWindowStart <= drinkingWindowEnd) {
            "Drinking window start year cannot be later than its end year."
        }
        val normalizedVintage = normalizeVintage(vintage)
        val existingDefinition = wineDao.getDefinitionByDetails(maker, name)

        // No automatic image lookup: images come from the reviewed Open Food Facts import (Profile).
        val imageUrl = existingDefinition?.imageUrl

        val definitionId = if (existingDefinition == null) {
            wineDao.insertDefinition(
                WineDefinition(
                    maker = maker,
                    name = name,
                    type = type,
                    country = country,
                    region = region,
                    subregion = subregion,
                    imageUrl = imageUrl,
                    drinkingWindowStart = drinkingWindowStart,
                    drinkingWindowEnd = drinkingWindowEnd
                )
            )
        } else {
            wineDao.updateDefinition(
                existingDefinition.copy(
                    type = type,
                    country = country,
                    region = region,
                    subregion = subregion,
                    imageUrl = imageUrl ?: existingDefinition.imageUrl,
                    drinkingWindowStart = drinkingWindowStart ?: existingDefinition.drinkingWindowStart,
                    drinkingWindowEnd = drinkingWindowEnd ?: existingDefinition.drinkingWindowEnd
                )
            )
            existingDefinition.definitionId
        }

        val existingBottle = inventoryDao.getBottleInCellar(definitionId, normalizedVintage)
        if (existingBottle != null) {
            inventoryDao.updateBottle(
                existingBottle.copy(quantity = existingBottle.quantity + quantity)
            )
        } else {
            inventoryDao.insertBottle(
                WineBottle(
                    definitionId = definitionId,
                    vintage = normalizedVintage,
                    quantity = quantity,
                    price = price,
                    qtyType = "Bottle",
                    location = null,
                    dateAdded = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                )
            )
        }
    }

    suspend fun updateWineDefinition(definition: WineDefinition) {
        wineDao.updateDefinition(definition)
    }

    suspend fun updateWine(definition: WineDefinition, bottle: WineBottle) {
        database.withTransaction {
            wineDao.updateDefinition(definition)
            inventoryDao.updateBottle(bottle)
        }
    }

    fun getTastingNotesForWine(definitionId: Long, vintage: String): Flow<List<TastingNote>> =
        tastingNoteDao.getTastingNotesForWine(definitionId, vintage)

    suspend fun getTastingNoteById(noteId: Long): TastingNote? = tastingNoteDao.getNoteById(noteId)

    suspend fun saveTastingNote(note: TastingNote, linkedEventId: Long? = null): Long = database.withTransaction {
        val savedId = if (note.noteId == 0L) {
            tastingNoteDao.insertNote(note)
        } else {
            tastingNoteDao.updateNote(note)
            note.noteId
        }
        if (savedId > 0L && linkedEventId != null) {
            check(database.consumptionEventDao().attachNote(linkedEventId, savedId) == 1) {
                "The consumption event no longer exists."
            }
        }
        savedId
    }

    suspend fun saveManualTastingNote(
        note: TastingNote,
        maker: String,
        name: String,
        country: String,
        region: String?,
        subregion: String?
    ): Long = database.withTransaction {
        val normalizedMaker = maker.trim()
        val normalizedName = name.trim()
        val normalizedCountry = country.trim().takeIf { it.isNotEmpty() }
        val normalizedRegion = region?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedSubregion = subregion?.trim()?.takeIf { it.isNotEmpty() }
        val existingDefinition = wineDao.getDefinitionByDetails(normalizedMaker, normalizedName)
        val definition = existingDefinition ?: run {
            val definitionId = wineDao.insertDefinition(
                WineDefinition(
                    maker = normalizedMaker,
                    name = normalizedName,
                    type = "Unknown",
                    country = normalizedCountry ?: "Unknown",
                    region = normalizedRegion,
                    subregion = normalizedSubregion
                )
            )
            (if (definitionId > 0L) wineDao.getDefinitionById(definitionId) else null)
                ?: wineDao.getDefinitionByDetails(normalizedMaker, normalizedName)
                ?: error("Unable to create the manual wine entry.")
        }
        val definitionWithLocation = definition.copy(
            country = if (definition.country.isBlank() || definition.country.equals("Unknown", ignoreCase = true)) {
                normalizedCountry ?: definition.country
            } else {
                definition.country
            },
            region = if (definition.region.isNullOrBlank() || definition.region.orEmpty().equals("Unknown", ignoreCase = true)) {
                normalizedRegion ?: definition.region
            } else {
                definition.region
            },
            subregion = if (definition.subregion.isNullOrBlank() || definition.subregion.orEmpty().equals("Unknown", ignoreCase = true)) {
                normalizedSubregion ?: definition.subregion
            } else {
                definition.subregion
            }
        )
        if (definitionWithLocation != definition) {
            wineDao.updateDefinition(definitionWithLocation)
        }
        val manualNote = note.copy(
            definitionId = definitionWithLocation.definitionId,
            vintage = normalizeVintage(note.vintage),
            bottleId = null
        )

        if (manualNote.noteId == 0L) {
            tastingNoteDao.insertNote(manualNote)
        } else {
            tastingNoteDao.updateNote(manualNote)
            manualNote.noteId
        }
    }

    suspend fun deleteTastingNote(noteId: Long) {
        database.withTransaction {
            database.consumptionEventDao().clearNoteReference(noteId)
            tastingNoteDao.deleteNoteById(noteId)
        }
    }

    suspend fun getAllPricesForDefinitions(definitionIds: List<Long>): List<com.n8rtz.winetracker.data.local.PriceLookupResult> {
        return inventoryDao.getAllPricesForDefinitions(definitionIds)
    }


    suspend fun consumeBottle(
        bottleId: Long,
        rating: Double?,
        notes: String?,
        rebuy: Boolean,
        consumptionDate: Long = System.currentTimeMillis(),
        quantity: Int = 1
    ): Long = database.withTransaction {
        val bottle = inventoryDao.getBottleById(bottleId) ?: return@withTransaction 0L
        if (bottle.status != BottleStatus.IN_CELLAR || bottle.quantity <= 0) {
            return@withTransaction 0L
        }
        val consumedQuantity = quantity.coerceIn(1, bottle.quantity)

        val noteId = if (rating != null || !notes.isNullOrBlank() || rebuy) {
            tastingNoteDao.insertNote(
                TastingNote(
                    definitionId = bottle.definitionId,
                    vintage = bottle.vintage,
                    dateTasted = consumptionDate,
                    rating = rating ?: 0.0,
                    notes = notes?.takeIf { it.isNotBlank() },
                    rebuy = rebuy,
                    initialRating = rating?.toInt(),
                    bottleId = bottleId
                )
            ).takeIf { it > 0L }
        } else null

        val eventId = database.consumptionEventDao().insertEvent(
            ConsumptionEvent(
                definitionId = bottle.definitionId,
                bottleId = bottleId,
                vintage = bottle.vintage,
                consumedAt = consumptionDate,
                quantity = consumedQuantity,
                price = bottle.price,
                noteId = noteId
            )
        )

        // Keep the unique (definitionId, vintage, status) inventory row in the cellar.
        // A zero quantity row is hidden from active inventory and can be reused when restocked.
        inventoryDao.updateBottle(
            bottle.copy(
                quantity = bottle.quantity - consumedQuantity,
                status = BottleStatus.IN_CELLAR,
                dateConsumed = null
            )
        )
        eventId
    }

    /**
     * Puts a consumed bottle back and removes its history entry.
     * [keepNote] = false (the snackbar UNDO right after consuming) also deletes the tasting note that
     * consuming created. History's "Restore to cellar" passes true so a note the owner may have written
     * or edited since is never deleted along with the log entry.
     */
    suspend fun undoConsumeBottle(eventId: Long, keepNote: Boolean = false) {
        database.withTransaction {
            val event = database.consumptionEventDao().getEventById(eventId) ?: return@withTransaction
            val bottle = event.bottleId?.let { inventoryDao.getBottleById(it) }
            if (bottle != null) {
                inventoryDao.updateBottle(
                    bottle.copy(
                        quantity = bottle.quantity + event.quantity,
                        status = BottleStatus.IN_CELLAR,
                        dateConsumed = null
                    )
                )
            } else {
                val matchingCellarRow = inventoryDao.getBottleInCellar(event.definitionId, event.vintage)
                if (matchingCellarRow != null) {
                    inventoryDao.updateBottle(
                        matchingCellarRow.copy(quantity = matchingCellarRow.quantity + event.quantity)
                    )
                } else {
                    // Restore this exact event's bottle count if its original cellar row
                    // was explicitly removed after the consumption.
                    inventoryDao.insertBottle(
                        WineBottle(
                            bottleId = event.bottleId ?: 0L,
                            definitionId = event.definitionId,
                            vintage = event.vintage,
                            quantity = event.quantity,
                            price = event.price,
                            qtyType = "Bottle",
                            location = null,
                            status = BottleStatus.IN_CELLAR
                        )
                    )
                }
            }
            if (!keepNote) event.noteId?.let { tastingNoteDao.deleteNoteById(it) }
            database.consumptionEventDao().deleteEventById(eventId)
        }
    }

    /** Removes one history entry only. The cellar is not changed and any tasting note is kept. */
    suspend fun deleteConsumptionEvent(eventId: Long) {
        database.consumptionEventDao().deleteEventById(eventId)
    }

    suspend fun deleteWine(bottleId: Long) {
        inventoryDao.deleteBottle(bottleId)
    }

    suspend fun clearAllTables() {
        database.clearAllTables()
    }
}
