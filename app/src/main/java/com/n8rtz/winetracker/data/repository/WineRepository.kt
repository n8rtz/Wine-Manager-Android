package com.n8rtz.winetracker.data.repository

import android.content.Context
import android.net.Uri
import com.n8rtz.winetracker.network.WineImageFetcher
import com.n8rtz.winetracker.data.local.BottleStatus
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
import kotlinx.coroutines.flow.Flow

class WineRepository(
    private val wineDao: WineDao,
    private val inventoryDao: InventoryDao,
    private val tastingNoteDao: TastingNoteDao,
    private val ratingDao: RatingDao,
    val database: WineDatabase
) {
    private val imageFetcher = WineImageFetcher()
    val activeInventory: Flow<List<WineBottleWithDefinition>> = inventoryDao.getActiveInventory()
    val archivedInventory: Flow<List<WineBottleWithDefinition>> = inventoryDao.getArchivedBottles()
    val tastingHistory: Flow<List<TastingNoteWithDefinition>> = tastingNoteDao.getTastingHistory()
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
        price: Double
    ) {
        val existingDefinition = wineDao.getDefinitionByDetails(maker, name)

        val imageUrl = if (existingDefinition?.imageUrl == null) {
            imageFetcher.fetchImageUrl(maker, name, vintage)
        } else {
            existingDefinition.imageUrl
        }

        val definitionId = if (existingDefinition == null) {
            wineDao.insertDefinition(
                WineDefinition(
                    maker = maker,
                    name = name,
                    type = type,
                    country = country,
                    region = region,
                    subregion = subregion,
                    imageUrl = imageUrl
                )
            )
        } else {
            wineDao.updateDefinition(
                existingDefinition.copy(
                    type = type,
                    country = country,
                    region = region,
                    subregion = subregion,
                    imageUrl = imageUrl ?: existingDefinition.imageUrl
                )
            )
            existingDefinition.definitionId
        }

        val existingBottle = inventoryDao.getBottleInCellar(definitionId, vintage)
        if (existingBottle != null) {
            inventoryDao.updateBottle(
                existingBottle.copy(quantity = existingBottle.quantity + quantity)
            )
        } else {
            inventoryDao.insertBottle(
                WineBottle(
                    definitionId = definitionId,
                    vintage = vintage,
                    quantity = quantity,
                    price = price,
                    qtyType = "Bottle",
                    location = null
                )
            )
        }
    }

    suspend fun updateWineDefinition(definition: WineDefinition) {
        wineDao.updateDefinition(definition)
    }

    suspend fun consumeBottle(
        bottleId: Long,
        rating: Double?,
        notes: String?,
        rebuy: Boolean,
        consumptionDate: Long = System.currentTimeMillis()
    ) {
        val bottle = inventoryDao.getBottleById(bottleId) ?: return

        if (bottle.quantity > 1) {
            inventoryDao.updateBottle(bottle.copy(quantity = bottle.quantity - 1))
        } else {
            inventoryDao.updateBottle(bottle.copy(quantity = 0, status = BottleStatus.ARCHIVED))
        }

        if (rating != null) {
            tastingNoteDao.insertNote(
                TastingNote(
                    definitionId = bottle.definitionId,
                    vintage = bottle.vintage,
                    dateTasted = consumptionDate,
                    rating = rating,
                    notes = notes,
                    rebuy = rebuy,
                    initialRating = rating.toInt()
                )
            )
        }
    }

    suspend fun undoConsumeBottle(bottleId: Long, removeLastNote: Boolean) {
        val bottle = inventoryDao.getBottleById(bottleId) ?: return

        inventoryDao.updateBottle(
            bottle.copy(
                quantity = bottle.quantity + 1,
                status = BottleStatus.IN_CELLAR
            )
        )

        if (removeLastNote) {
            tastingNoteDao.deleteLastNoteForWine(bottle.definitionId, bottle.vintage)
        }
    }

    suspend fun deleteWine(bottleId: Long) {
        inventoryDao.deleteBottle(bottleId)
    }
}
