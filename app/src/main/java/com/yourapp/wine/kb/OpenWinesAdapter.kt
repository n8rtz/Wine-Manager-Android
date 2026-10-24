package com.yourapp.wine.kb

import java.io.InputStream

/** Adapter for the stated interchange schema, not a claim that an arbitrary OpenWines dump is compatible. */
object OpenWinesAdapter {
    const val MAX_BYTES = 80 * 1024 * 1024
    const val MAX_SNAPSHOT_RECORDS = 10_000

    /** No image coordinates: streams validated UTF-8 rows to a sink; the caller retains ownership of input. */
    fun stream(input: InputStream, emit: (KnowledgeRecord) -> Unit) {
        ImportReaders.utf8(input, MAX_BYTES.toLong()).use { reader ->
            var first = reader.read()
            if (first == '\uFEFF'.code) first = reader.read()
            while (first >= 0 && first.toChar().isWhitespace()) first = reader.read()
            require(first >= 0) { "Empty KB interchange" }
            reader.unread(first)
            if (first == '{'.code) JsonKnowledgeRecords.read(reader, emit)
            else CsvKnowledgeRecords.read(reader, emit)
        }
    }

    /** No image coordinates: small test/tool convenience only; the production database importer uses stream(). */
    fun read(input: InputStream): KnowledgeSnapshot {
        val places = linkedMapOf<Long, Place>()
        val producers = linkedMapOf<Long, Producer>()
        val aliases = mutableMapOf<Long, MutableList<String>>()
        val cuvees = mutableListOf<Cuvee>()
        var count = 0
        stream(input) { record ->
            require(++count <= MAX_SNAPSHOT_RECORDS) { "Use the streaming DB importer for more than $MAX_SNAPSHOT_RECORDS records" }
            when (record) {
                is KnowledgeRecord.PlaceRow -> require(places.put(record.value.id, record.value) == null) { "Duplicate place ids" }
                is KnowledgeRecord.ProducerRow -> require(producers.put(record.value.id, record.value) == null) { "Duplicate producer ids" }
                is KnowledgeRecord.AliasRow -> aliases.getOrPut(record.producerId) { mutableListOf() } += record.aliasNorm
                is KnowledgeRecord.CuveeRow -> cuvees += record.value
            }
        }
        require(aliases.keys.all { it in producers }) { "Alias must reference a producer in the import" }
        return KnowledgeSnapshot(places.values.toList(), producers.values.map { it.copy(aliases = aliases[it.id].orEmpty()) }, cuvees)
    }
}
