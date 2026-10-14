package com.yourapp.wine.kb

import com.yourapp.wine.merge.TextNorm
import java.io.Reader

/** Strict single-line CSV adapter; each row is released immediately after the sink accepts it. */
internal object CsvKnowledgeRecords {
    val columns = listOf("record_type", "id", "name", "type", "parent_id", "country_code", "region_id", "producer_id", "alias_norm")

    fun read(reader: Reader, emit: (KnowledgeRecord) -> Unit) {
        val header = ImportReaders.line(reader) ?: error("Empty KB CSV")
        require(split(header) == columns) { "Expected columns ${columns.joinToString()}; adapt the OpenWines dump first" }
        while (true) {
            val line = ImportReaders.line(reader) ?: break
            if (line.isBlank()) continue
            val c = split(line)
            require(c.size == columns.size) { "Wrong CSV column count; multiline fields are not supported" }
            val record = when (c[0]) {
                "place" -> KnowledgeRecord.PlaceRow(Place(KnowledgeRecords.id(c[1], "id"), c[2], c[3],
                    KnowledgeRecords.optionalId(c[4], "parent_id"), c[5]))
                "producer" -> KnowledgeRecord.ProducerRow(Producer(KnowledgeRecords.id(c[1], "id"), c[2], c[5],
                    KnowledgeRecords.optionalId(c[6], "region_id")))
                "alias" -> KnowledgeRecord.AliasRow(KnowledgeRecords.id(c[7], "producer_id"), TextNorm.normalize(KnowledgeRecords.text(c[8], "alias_norm")))
                "cuvee" -> KnowledgeRecord.CuveeRow(Cuvee(KnowledgeRecords.id(c[7], "producer_id"), c[2]))
                else -> error("Unknown record_type: ${c[0]}")
            }
            emit(KnowledgeRecords.checked(record))
        }
    }

    private fun split(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> { cell.append('"'); index++ }
                char == '"' && quoted -> { quoted = false; closedQuote = true }
                char == '"' -> { require(cell.isEmpty() && !closedQuote) { "Misplaced CSV quote" }; quoted = true }
                char == ',' && !quoted -> { cells += cell.toString(); cell.setLength(0); closedQuote = false }
                else -> { require(!closedQuote) { "Data after a closed CSV quote" }; cell.append(char) }
            }
            index++
        }
        require(!quoted) { "Unclosed CSV quote / unsupported multiline field" }
        cells += cell.toString()
        return cells
    }
}
