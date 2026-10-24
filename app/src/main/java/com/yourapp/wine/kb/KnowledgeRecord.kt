package com.yourapp.wine.kb

import com.yourapp.wine.merge.TextNorm

/** No image coordinates: one validated interchange row, emitted without retaining the whole dump. */
sealed interface KnowledgeRecord {
    data class PlaceRow(val value: Place) : KnowledgeRecord
    data class ProducerRow(val value: Producer) : KnowledgeRecord
    data class AliasRow(val producerId: Long, val aliasNorm: String) : KnowledgeRecord
    data class CuveeRow(val value: Cuvee) : KnowledgeRecord
}

internal object KnowledgeRecords {
    private val positiveId = Regex("[0-9]{1,19}")
    private val isoCode = Regex("[A-Z]{2}")
    private val placeTypes = setOf("country", "region", "subregion", "appellation")
    const val MAX_TEXT_CHARS = 512

    fun id(value: String?, field: String): Long {
        require(value != null && positiveId.matches(value)) { "Expected positive integer $field" }
        return requireNotNull(value.toLongOrNull()) { "Invalid/overflowing $field" }.also {
            require(it > 0) { "Expected positive $field" }
        }
    }

    fun optionalId(value: String?, field: String): Long? =
        value?.takeIf { it.isNotBlank() }?.let { id(it, field) }

    fun text(value: String?, field: String): String = requireNotNull(value) { "Missing $field" }.also {
        require(it.isNotBlank() && it.length <= MAX_TEXT_CHARS && it.none { c -> c.isISOControl() }) {
            "Invalid $field (nonempty, single-line, at most $MAX_TEXT_CHARS characters)"
        }
    }

    fun checked(record: KnowledgeRecord): KnowledgeRecord = record.also {
        when (it) {
            is KnowledgeRecord.PlaceRow -> with(it.value) {
                require(id > 0 && parentId != id && (parentId == null || parentId > 0)) { "Invalid place id/parent" }
                text(name, "place.name")
                require(type in placeTypes && isoCode.matches(this.countryCode)) { "Invalid place type/country" }
                require(type != "country" || parentId == null) { "Country cannot have a parent" }
            }
            is KnowledgeRecord.ProducerRow -> with(it.value) {
                require(id > 0 && (regionId == null || regionId > 0)) { "Invalid producer id/region" }
                text(name, "producer.name")
                require(isoCode.matches(this.countryCode)) { "Invalid producer country" }
            }
            is KnowledgeRecord.AliasRow -> {
                require(it.producerId > 0) { "Invalid alias producer_id" }
                text(it.aliasNorm, "alias_norm")
                require(TextNorm.normalize(it.aliasNorm).isNotEmpty()) { "Empty normalized alias" }
            }
            is KnowledgeRecord.CuveeRow -> {
                require(it.value.producerId > 0) { "Invalid cuvee producer_id" }
                text(it.value.name, "cuvee.name")
            }
        }
    }
}
