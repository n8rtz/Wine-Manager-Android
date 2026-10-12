package com.n8rtz.winetracker.data.importer

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonDeserializer
import com.n8rtz.winetracker.data.local.BottleStatus
import com.n8rtz.winetracker.data.local.ConsumptionEvent
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.normalizeVintage
import com.n8rtz.winetracker.image.WineImageFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/** Restores a complete JSON backup atomically; callers must ask before replacing local data. */
class DataImporter(
    private val context: Context,
    private val database: WineDatabase
) {
    suspend fun standardImport(uri: Uri) = withContext(Dispatchers.IO) {
        val backup = context.contentResolver.openInputStream(uri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                backupGson().fromJson(reader, BackupData::class.java)
            }
        } ?: throw IllegalArgumentException("The selected file could not be opened.")

        requireNotNull(backup) { "The selected file is not a Wine Manager JSON backup." }
        restoreBackup(backup)
    }

    private fun backupGson() = GsonBuilder()
        .registerTypeAdapter(java.lang.Long::class.java, longDateDeserializer())
        .registerTypeAdapter(java.lang.Long.TYPE, longDateDeserializer())
        .create()

    private fun longDateDeserializer() = JsonDeserializer<Long> { json, _, _ ->
        if (json == null || json.isJsonNull) return@JsonDeserializer null
        val primitive = json.asJsonPrimitive
        if (primitive.isNumber) return@JsonDeserializer primitive.asLong

        val value = primitive.asString.trim()
        val slashDate = Regex("^\\d{1,2}/\\d{1,2}/(\\d{2}|\\d{4})$").matchEntire(value)
        val formats = buildList {
            if (slashDate != null) add(if (slashDate.groupValues[1].length == 2) "M/d/yy" else "M/d/yyyy")
            addAll(listOf("yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX"))
        }
        val parsed = formats.firstNotNullOfOrNull { pattern ->
            val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            val position = ParsePosition(0)
            parser.parse(value, position)?.takeIf { position.index == value.length }
        }
        parsed?.time ?: throw JsonParseException("Unrecognized date value '$value'.")
    }

    private suspend fun restoreBackup(source: BackupData) {
        val definitions = source.definitions
        val sourceBottles = source.bottles
        val notes = source.notes
        val ratings = source.ratings
        val eventsFromBackup = source.consumptionEvents
        val imageAssets = source.imageAssets.orEmpty()

        require(definitions.map { it.definitionId }.distinct().size == definitions.size) {
            "The backup contains duplicate wine definition IDs."
        }
        require(definitions.all { it.definitionId > 0L && it.maker.isNotBlank() && it.name.isNotBlank() }) {
            "The backup contains a wine without a valid ID, producer, or name."
        }
        require(definitions.map { it.maker.trim().lowercase() to it.name.trim().lowercase() }.distinct().size == definitions.size) {
            "The backup contains duplicate wine definitions."
        }
        val definitionIds = definitions.mapTo(mutableSetOf()) { it.definitionId }
        require(imageAssets.map { it.definitionId }.distinct().size == imageAssets.size) {
            "The backup contains duplicate wine-image metadata rows."
        }
        require(imageAssets.all { asset ->
            asset.definitionId in definitionIds &&
                asset.sourceName == OPEN_FOOD_FACTS_SOURCE &&
                asset.productTitle.isNotBlank() &&
                isOpenFoodFactsUrl(asset.sourceUrl) &&
                (asset.sourcePageUrl == null || isOpenFoodFactsUrl(asset.sourcePageUrl)) &&
                asset.kind in setOf("BOTTLE", "LABEL") &&
                asset.width > 0 && asset.height > 0 &&
                asset.sizeBytes in 1..MAX_IMAGE_BYTES &&
                runCatching { asset.sha256.matches(Regex("[0-9a-fA-F]{64}")) }.getOrDefault(false)
        }) {
            "The backup contains invalid or unsupported wine-image provenance metadata."
        }
        require(sourceBottles.map { it.bottleId }.distinct().size == sourceBottles.size) {
            "The backup contains duplicate bottle IDs."
        }
        require(sourceBottles.all {
            it.bottleId > 0L && it.definitionId in definitionIds &&
                (it.status == BottleStatus.IN_CELLAR || it.status == BottleStatus.ARCHIVED)
        }) {
            "The backup contains a bottle with a missing wine or valid inventory status."
        }
        require(notes.map { it.noteId }.distinct().size == notes.size) {
            "The backup contains duplicate tasting-note IDs."
        }
        require(notes.mapNotNull { it.importKey }.distinct().size == notes.count { it.importKey != null }) {
            "The backup contains duplicate tasting-note import keys."
        }
        require(ratings.map { it.id }.distinct().size == ratings.size) {
            "The backup contains duplicate rating IDs."
        }
        require(eventsFromBackup.mapNotNull { it.importKey }.distinct().size == eventsFromBackup.count { it.importKey != null }) {
            "The backup contains duplicate consumption-event import keys."
        }
        require(notes.all { it.noteId > 0L && it.definitionId in definitionIds && it.dateTasted > 0L }) {
            "A tasting note is missing its wine, ID, or recorded date. The existing database was not changed."
        }
        require(ratings.all { it.wineId in definitionIds }) {
            "The backup contains a rating without a matching wine definition."
        }
        require(eventsFromBackup.all { it.definitionId in definitionIds && it.quantity > 0 }) {
            "The backup contains a consumption event with invalid wine or quantity data."
        }

        // A pre-event backup can contain multiple archived rows for the same wine/vintage.
        // Convert each archived row into a history event instead of violating the inventory
        // uniqueness index or dropping additional consumption records.
        val notesByBottleId = notes.filter { it.bottleId != null }.groupBy { it.bottleId }
        val archivedBottlesAlreadyRepresented = eventsFromBackup.mapNotNull { it.bottleId }.toSet()
        val legacyArchiveEvents = sourceBottles
            .filter { it.status == BottleStatus.ARCHIVED && it.bottleId !in archivedBottlesAlreadyRepresented }
            .map { archivedBottle ->
                val linkedNotes = notesByBottleId[archivedBottle.bottleId].orEmpty()
                ConsumptionEvent(
                    definitionId = archivedBottle.definitionId,
                    bottleId = archivedBottle.bottleId,
                    vintage = normalizeVintage(archivedBottle.vintage),
                    consumedAt = archivedBottle.dateConsumed,
                    // Archived legacy rows represent at least one consumed item; zero was remaining stock.
                    quantity = archivedBottle.quantity.coerceAtLeast(1),
                    price = archivedBottle.price,
                    noteId = linkedNotes.singleOrNull()?.noteId,
                    importKey = "json-archived-bottle:${archivedBottle.bottleId}"
                )
            }
        val activeBottles = sourceBottles
            .filter { it.status == BottleStatus.IN_CELLAR }
            .map { it.copy(vintage = normalizeVintage(it.vintage)) }
        require(activeBottles.map { Triple(it.definitionId, it.vintage, it.status) }.distinct().size == activeBottles.size) {
            "The backup contains duplicate active cellar rows for the same wine and vintage."
        }
        val restoredNotes = notes.map { oldNote ->
            val directBottleVintage = oldNote.bottleId?.let { bottleId ->
                sourceBottles.firstOrNull { it.bottleId == bottleId }?.vintage
            }
            oldNote.copy(
                vintage = normalizeVintage(oldNote.vintage?.takeIf { it.isNotBlank() } ?: directBottleVintage),
                valueScore = oldNote.valueScore?.takeIf { it in 1..10 }
            )
        }
        val restoredEvents = (eventsFromBackup + legacyArchiveEvents).map { event ->
            event.copy(eventId = 0L, vintage = normalizeVintage(event.vintage))
        }
        require(restoredEvents.mapNotNull { it.importKey }.distinct().size == restoredEvents.count { it.importKey != null }) {
            "The backup contains a consumption-event key that conflicts with a legacy archived bottle."
        }

        database.withTransaction {
            database.consumptionEventDao().deleteAllEvents()
            database.ratingDao().deleteAllRatings()
            database.tastingNoteDao().deleteAllNotes()
            database.inventoryDao().deleteAllBottles()
            database.wineImageAssetDao().deleteAll()
            database.wineDao().deleteAllDefinitions()

            if (definitions.isNotEmpty()) database.wineDao().insertDefinitions(definitions)
            val imageAssetDao = database.wineImageAssetDao()
            imageAssets.forEach { asset -> imageAssetDao.upsert(asset.copy(relativePath = null)) }
            if (activeBottles.isNotEmpty()) database.inventoryDao().insertBottles(activeBottles)
            if (restoredNotes.isNotEmpty()) database.tastingNoteDao().insertNotes(restoredNotes)
            if (ratings.isNotEmpty()) database.ratingDao().insertRatings(ratings)
            if (restoredEvents.isNotEmpty()) database.consumptionEventDao().insertEvents(restoredEvents)
        }
        // Backups carry provenance and source URLs, not app-private bytes or paths.
        runCatching { WineImageFileStore(context).clearStoredImages() }
    }

    private fun isOpenFoodFactsUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching false
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (host == "openfoodfacts.org" || host.endsWith(".openfoodfacts.org"))
    }.getOrDefault(false)

    private companion object {
        const val OPEN_FOOD_FACTS_SOURCE = "Open Food Facts"
        const val MAX_IMAGE_BYTES = 500L * 1024L
    }
}
