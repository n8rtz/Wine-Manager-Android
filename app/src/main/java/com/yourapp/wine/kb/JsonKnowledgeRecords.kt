package com.yourapp.wine.kb

import android.util.JsonReader
import android.util.JsonToken
import com.yourapp.wine.merge.TextNorm
import java.io.Reader

/** Real Android streaming JSON parser, available since API11; only the stated flat interchange is accepted. */
internal object JsonKnowledgeRecords {
    private data class Value(val token: JsonToken, val text: String?)

    fun read(input: Reader, emit: (KnowledgeRecord) -> Unit) {
        JsonReader(ImportReaders.jsonEnvelope(input)).use { reader ->
            reader.isLenient = false
            val seen = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val group = reader.nextName()
                require(seen.add(group)) { "Duplicate JSON group: $group" }
                if (group == "schema") {
                    require(reader.peek() == JsonToken.STRING && reader.nextString() == "wine-kb/v1") {
                        "Expected schema wine-kb/v1; adapt the upstream dump first"
                    }
                } else {
                    require(group in setOf("places", "producers", "producer_aliases", "cuvees")) {
                        "Unexpected JSON group $group; adapt the upstream dump first"
                    }
                    reader.beginArray()
                    while (reader.hasNext()) emit(row(reader, group))
                    reader.endArray()
                }
            }
            reader.endObject()
            require("schema" in seen && reader.peek() == JsonToken.END_DOCUMENT) { "Missing schema or trailing JSON data" }
        }
    }

    private fun row(reader: JsonReader, group: String): KnowledgeRecord {
        val values = mutableMapOf<String, Value>()
        reader.beginObject()
        while (reader.hasNext()) {
            require(values.size < 12) { "Too many fields in $group record; adapt the upstream dump first" }
            val name = reader.nextName()
            require(name !in values) { "Duplicate JSON field $name" }
            values[name] = when (val token = reader.peek()) {
                JsonToken.NULL -> { reader.nextNull(); Value(token, null) }
                JsonToken.STRING, JsonToken.NUMBER -> Value(token, reader.nextString())
                else -> error("Expected scalar JSON field $group.$name")
            }
        }
        reader.endObject()
        fun text(name: String): String {
            val value = requireNotNull(values[name]) { "Missing $group.$name" }
            require(value.token == JsonToken.STRING) { "Expected string $group.$name" }
            return KnowledgeRecords.text(value.text, "$group.$name")
        }
        fun id(name: String): Long = KnowledgeRecords.id(values[name]?.text, "$group.$name")
        fun optionalId(name: String): Long? {
            val value = values[name] ?: return null
            return if (value.token == JsonToken.NULL) null else id(name)
        }
        val record = when (group) {
            "places" -> KnowledgeRecord.PlaceRow(Place(id("id"), text("name"), text("type"), optionalId("parent_id"), text("country_code")))
            "producers" -> KnowledgeRecord.ProducerRow(Producer(id("id"), text("name"), text("country_code"), optionalId("region_id")))
            "producer_aliases" -> KnowledgeRecord.AliasRow(id("producer_id"), TextNorm.normalize(text("alias_norm")))
            else -> KnowledgeRecord.CuveeRow(Cuvee(id("producer_id"), text("name")))
        }
        return KnowledgeRecords.checked(record)
    }
}
