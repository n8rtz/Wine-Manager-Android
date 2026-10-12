package com.n8rtz.winetracker.data.importer

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.n8rtz.winetracker.data.local.ConsumptionEvent
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.WineDatabase
import com.n8rtz.winetracker.data.local.WineDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

enum class LegacyCsvImportType { TASTING_NOTES, CONSUMPTION_HISTORY }

data class LegacyCsvImportResult(val imported: Int, val alreadyPresent: Int, val skippedInvalid: Int = 0)

object CsvImportHelper {
    suspend fun importTastingNotes(
        context: Context,
        database: WineDatabase,
        uri: Uri
    ): LegacyCsvImportResult = withContext(Dispatchers.IO) {
        val rows = readRows(context, uri)
        require(rows.isNotEmpty()) { "The selected CSV file is empty." }
        val header = headerIndex(rows.first())
        require(header.containsKey("date") && header.containsKey("type") &&
            header.containsKey("country") && header.containsKey("name") &&
            header.containsKey("rating")) {
            "This file is not a legacy tasting-notes CSV. Choose Wines - Tasting Notes.csv."
        }

        database.withTransaction {
            val definitions = loadDefinitionIndex(database)
            val duplicateOccurrences = mutableMapOf<String, Int>()
            var imported = 0
            var alreadyPresent = 0
            var skippedInvalid = 0

            rows.drop(1).forEachIndexed { rowIndex, row ->
                if (listOf("date", "type", "country", "name", "rating", "rec", "notes")
                        .none { cell(row, header, it).isNotBlank() }) return@forEachIndexed
                val dateText = cell(row, header, "date")
                val type = cell(row, header, "type")
                val country = cell(row, header, "country")
                val identity = runCatching {
                    parseWineIdentity(cell(row, header, "name"), definitions, rowIndex + 2)
                }.getOrNull()
                val ratingText = cell(row, header, "rating")
                val noteText = header["notes"]?.let { row.getOrNull(it).orEmpty().trim() }.orEmpty()
                val recommended = header["rec"]?.let { row.getOrNull(it).orEmpty().trim() }.orEmpty()
                val date = runCatching { parseDate(dateText, rowIndex + 2) }.getOrNull()
                val rating = if (ratingText.isBlank()) 0.0 else ratingText.toDoubleOrNull()
                if (date == null || rating == null || identity == null) {
                    skippedInvalid++
                    return@forEachIndexed
                }
                val (maker, wineName, vintage) = identity
                val definition = findOrCreateDefinition(database, definitions, maker, wineName, type, country)
                val canonicalRow = listOf(dateText, type, country, maker, wineName, vintage, ratingText, recommended, noteText)
                    .joinToString("\u001f") { it.trim() }
                val occurrence = duplicateOccurrences.getOrDefault(canonicalRow, 0)
                duplicateOccurrences[canonicalRow] = occurrence + 1
                val importKey = "legacy-note:${sha256("$canonicalRow\u001f$occurrence")}"

                if (database.tastingNoteDao().getNoteByImportKey(importKey) != null) {
                    alreadyPresent++
                } else {
                    val noteId = database.tastingNoteDao().insertNote(
                        TastingNote(
                            definitionId = definition.definitionId,
                            vintage = vintage,
                            dateTasted = date,
                            rating = rating,
                            notes = noteText.takeIf { it.isNotBlank() },
                            rebuy = parseBoolean(recommended),
                            recommendedBy = recommended.takeIf { it.isNotBlank() && !parseBoolean(it) },
                            importKey = importKey
                        )
                    )
                    if (noteId > 0L) imported++ else alreadyPresent++
                }
            }
            LegacyCsvImportResult(imported, alreadyPresent, skippedInvalid)
        }
    }

    suspend fun importConsumptionHistory(
        context: Context,
        database: WineDatabase,
        uri: Uri
    ): LegacyCsvImportResult = withContext(Dispatchers.IO) {
        val rows = readRows(context, uri)
        require(rows.isNotEmpty()) { "The selected CSV file is empty." }
        val header = headerIndex(rows.first())
        require(header.containsKey("date") && header.containsKey("type") &&
            header.containsKey("country") && header.containsKey("maker") &&
            header.containsKey("name & vintage") && header.containsKey("qty")) {
            "This file is not a legacy consumption-history CSV. Choose Wines - Log (Out).csv."
        }

        database.withTransaction {
            val definitions = loadDefinitionIndex(database)
            val duplicateOccurrences = mutableMapOf<String, Int>()
            var imported = 0
            var alreadyPresent = 0
            var skippedInvalid = 0

            rows.drop(1).forEachIndexed { rowIndex, row ->
                if (listOf("date", "type", "country", "maker", "name & vintage", "qty")
                        .none { cell(row, header, it).isNotBlank() }) return@forEachIndexed
                val dateText = cell(row, header, "date")
                val type = cell(row, header, "type")
                val country = cell(row, header, "country")
                val maker = cell(row, header, "maker").trim()
                val identity = runCatching {
                    parseVintage(cell(row, header, "name & vintage"), rowIndex + 2)
                }.getOrNull()
                val quantity = cell(row, header, "qty").toIntOrNull()
                val date = runCatching { parseDate(dateText, rowIndex + 2) }.getOrNull()
                if (identity == null || quantity == null || quantity <= 0 || maker.isBlank() || date == null) {
                    skippedInvalid++
                    return@forEachIndexed
                }
                val (parsedName, vintage) = identity
                val definition = findOrCreateDefinition(database, definitions, maker, parsedName, type, country)
                val canonicalRow = listOf(dateText, type, country, maker, parsedName, vintage, quantity.toString())
                    .joinToString("\u001f") { it.trim() }
                val occurrence = duplicateOccurrences.getOrDefault(canonicalRow, 0)
                duplicateOccurrences[canonicalRow] = occurrence + 1
                val importKey = "legacy-consumption:${sha256("$canonicalRow\u001f$occurrence")}"

                if (database.consumptionEventDao().getEventByImportKey(importKey) != null) {
                    alreadyPresent++
                } else {
                    val eventId = database.consumptionEventDao().insertEvent(
                        ConsumptionEvent(
                            definitionId = definition.definitionId,
                            vintage = vintage,
                            consumedAt = date,
                            quantity = quantity,
                            importKey = importKey
                        )
                    )
                    if (eventId > 0L) imported++ else alreadyPresent++
                }
            }
            LegacyCsvImportResult(imported, alreadyPresent, skippedInvalid)
        }
    }

    private fun readRows(context: Context, uri: Uri): List<List<String>> {
        val content = context.contentResolver.openInputStream(uri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { it.readText() }
        } ?: throw IllegalArgumentException("The selected CSV file could not be opened.")
        return parseCsv(content.removePrefix("\uFEFF"))
    }

    private fun headerIndex(row: List<String>): Map<String, Int> = row
        .mapIndexedNotNull { index, value ->
            value.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT)
                .takeIf { it.isNotBlank() }?.let { it to index }
        }
        .toMap()

    private fun cell(row: List<String>, header: Map<String, Int>, name: String): String {
        val index = header[name] ?: return ""
        return row.getOrNull(index).orEmpty().trim()
    }

    private fun loadDefinitionIndex(database: WineDatabase): MutableMap<String, WineDefinition> =
        database.wineDao().getAllDefinitionsOnce().associateByTo(mutableMapOf()) {
            definitionKey(it.maker, it.name)
        }

    private suspend fun findOrCreateDefinition(
        database: WineDatabase,
        definitions: MutableMap<String, WineDefinition>,
        maker: String,
        name: String,
        type: String,
        country: String
    ): WineDefinition {
        require(maker.isNotBlank() && name.isNotBlank()) { "A CSV row is missing the producer or wine name." }
        val key = definitionKey(maker, name)
        definitions[key]?.let { return it }

        val candidate = WineDefinition(
            maker = maker,
            name = name,
            type = type.ifBlank { "Unknown" },
            country = country.ifBlank { "Unknown" },
            region = null
        )
        val newId = database.wineDao().insertDefinition(candidate)
        val definition = if (newId > 0L) candidate.copy(definitionId = newId) else
            database.wineDao().getDefinitionByDetails(maker, name)
                ?: throw IllegalStateException("Could not create a wine definition for '$maker - $name'.")
        definitions[key] = definition
        return definition
    }

    private fun definitionKey(maker: String, name: String): String =
        "${maker.trim().lowercase(Locale.ROOT)}\u001f${name.trim().lowercase(Locale.ROOT)}"

    private fun parseWineIdentity(
        value: String,
        definitions: Map<String, WineDefinition>,
        rowNumber: Int
    ): Triple<String, String, String>? {
        val separator = value.indexOf(" - ")
        if (separator > 0) {
            val maker = value.substring(0, separator).trim()
            val (name, vintage) = parseVintage(value.substring(separator + 3), rowNumber)
            return Triple(maker, name, vintage).takeIf { maker.isNotBlank() && name.isNotBlank() }
        }

        // Some legacy rows contain only the combined producer/name. Accept these only when an
        // existing catalog definition matches the text exactly; do not guess a producer split.
        val (combinedName, vintage) = parseVintage(value, rowNumber)
        val exactMatches = definitions.values.filter { definition ->
            "${definition.maker} ${definition.name}".trim().replace(Regex("\\s+"), " ")
                .equals(combinedName.replace(Regex("\\s+"), " "), ignoreCase = true)
        }
        return exactMatches.singleOrNull()?.let { Triple(it.maker, it.name, vintage) }
    }

    private fun parseVintage(value: String, rowNumber: Int): Pair<String, String> {
        val trimmed = value.trim()
        val match = Regex("^(.*?)\\s*(?:\\((\\d{4}|NV)\\)|(\\d{4}|NV))?$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?: throw IllegalArgumentException("Could not read the wine name on CSV row $rowNumber.")
        val name = match.groupValues[1].trim()
        val rawVintage = match.groupValues[2].ifBlank { match.groupValues[3] }
        val vintage = if (rawVintage.equals("NV", ignoreCase = true)) "NV" else rawVintage.ifBlank { "NV" }
        require(name.isNotBlank()) { "Missing wine name on CSV row $rowNumber." }
        return name to vintage
    }

    private fun parseDate(value: String, rowNumber: Int): Long {
        val trimmed = value.trim()
        val slashDate = Regex("^\\d{1,2}/\\d{1,2}/(\\d{2}|\\d{4})$").matchEntire(trimmed)
        val formats = when {
            slashDate != null -> listOf(if (slashDate.groupValues[1].length == 2) "M/d/yy" else "M/d/yyyy")
            trimmed.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) -> listOf("yyyy-MM-dd")
            else -> emptyList()
        }
        val parsed = formats.firstNotNullOfOrNull { pattern ->
            val formatter = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            val position = ParsePosition(0)
            formatter.parse(trimmed, position)?.takeIf { position.index == trimmed.length }
        }
        return parsed?.time ?: throw IllegalArgumentException("Invalid date on CSV row $rowNumber: '$value'.")
    }

    private fun parseBoolean(value: String): Boolean = when (value.trim().lowercase(Locale.ROOT)) {
        "true", "yes", "y", "1", "recommended", "rebuy" -> true
        else -> false
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    /** CSV reader supports escaped quotes, embedded commas, and embedded newlines. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row += field.toString()
                    field.setLength(0)
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += field.toString()
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows += row.toList()
                    row.clear()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotBlank() }) rows += row.toList()
        }
        require(!quoted) { "The CSV file ends inside a quoted field." }
        return rows
    }
}
