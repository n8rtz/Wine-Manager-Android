package com.yourapp.wine.fill

import com.yourapp.wine.extract.ExtractionResult
import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.kb.Place
import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.merge.TextNorm

class Validator(private val kb: WineKnowledgeBase, private val hierarchy: PlaceHierarchy = PlaceHierarchy(kb)) {
    private val geo = listOf(FieldId.COUNTRY, FieldId.REGION, FieldId.SUBREGION)

    /** Validates candidates with normalized rectified evidence; preserves explicit conflicting values. */
    fun validate(extracted: ExtractionResult, currentYear: Int): Map<FieldId, FieldCandidate> {
        val geography = hierarchy.snapshot()
        fun resolve(field: FieldId, value: String): Place? = geography.byName[TextNorm.normalize(value)]
            .orEmpty().filter { place -> when (field) {
                FieldId.COUNTRY -> place.type == "country"
                FieldId.REGION -> place.type == "region"
                FieldId.SUBREGION -> place.type in setOf("subregion", "appellation")
                else -> false
            } }.singleOrNull()
        val fields = FieldId.entries.associateWith { field ->
            extracted.candidates[field]?.firstOrNull() ?: FieldCandidate(field, "", UiState.RED, "No readable candidate; retake or enter manually", emptyList())
        }.toMutableMap()
        for ((field, candidates) in extracted.candidates) {
            val selected = fields.getValue(field)
            val conflict = candidates.drop(1).any { alternative ->
                if (TextNorm.normalize(alternative.value) == TextNorm.normalize(selected.value)) false
                else if (field !in geo) true
                else {
                    val first = resolve(field, selected.value)
                    val other = resolve(field, alternative.value)
                    first == null || other == null || !geography.compatible(first, other)
                }
            }
            if (conflict) fields[field] = selected.copy(state = UiState.YELLOW,
                reason = selected.reason + "; conflicting label candidates: " + candidates.drop(1).joinToString { it.value })
        }
        for (field in listOf(FieldId.PRODUCER, FieldId.WINE_NAME)) {
            val candidate = fields.getValue(field)
            if (candidate.state != UiState.RED && !MetadataRules.isIdentity(candidate.value)) {
                fields[field] = candidate.copy(value = "", state = UiState.RED, reason = "Packaging/alcohol/barcode is not a wine identity")
            }
        }
        val vintage = fields.getValue(FieldId.VINTAGE)
        if (vintage.state != UiState.RED && (vintage.value.toIntOrNull() !in 1900..currentYear)) {
            fields[FieldId.VINTAGE] = vintage.copy(value = "", state = UiState.RED, reason = "Vintage outside 1900..$currentYear")
        }
        val selectedPlaces = geo.mapNotNull { field -> resolve(field, fields.getValue(field).value)?.let { field to it } }
        val conflict = selectedPlaces.any { (_, place) ->
            selectedPlaces.any { (_, other) -> !geography.compatible(place, other) }
        } || extracted.placeClaims.any { a -> extracted.placeClaims.any { b -> !geography.compatible(a.place, b.place) } }
        if (conflict) geo.forEach { field ->
            val c = fields.getValue(field)
            if (c.state != UiState.RED) fields[field] = c.copy(state = UiState.YELLOW,
                reason = c.reason + "; hierarchy conflict — confirm country/region/subregion, no silent correction")
        }
        // Ambiguous same-name places and names used for the wrong geographic level are not validated.
        geo.forEach { field ->
            val c = fields.getValue(field)
            if (c.state == UiState.GREEN && resolve(field, c.value) == null) {
                fields[field] = c.copy(state = UiState.YELLOW, reason = c.reason + "; geography unknown, ambiguous or wrong level in local KB")
            }
        }
        val producer = fields.getValue(FieldId.PRODUCER)
        val knownProducer = if (producer.state != UiState.RED) kb.findProducer(producer.value)
            .firstOrNull { it.editDistance == 0 }?.producer else null
        if (knownProducer == null && producer.state == UiState.GREEN) {
            fields[FieldId.PRODUCER] = producer.copy(state = UiState.YELLOW, reason = producer.reason + "; producer not in local KB")
        }
        if (knownProducer != null && selectedPlaces.any { (_, p) -> p.countryCode != knownProducer.countryCode ||
                (p.type != "country" && knownProducer.regionId != null &&
                    geography.ancestors(p).none { it.id == knownProducer.regionId }) }) {
            fields[FieldId.PRODUCER] = producer.copy(state = UiState.YELLOW, reason = producer.reason + "; producer KB origin conflicts with label")
            geo.forEach { field ->
                val c = fields.getValue(field)
                if (c.state != UiState.RED) fields[field] = c.copy(state = UiState.YELLOW, reason = c.reason + "; producer origin conflict")
            }
        }
        val name = fields.getValue(FieldId.WINE_NAME)
        if (name.state == UiState.GREEN && fields.getValue(FieldId.PRODUCER).state != UiState.GREEN) {
            fields[FieldId.WINE_NAME] = name.copy(state = UiState.YELLOW, reason = name.reason + "; producer identity requires confirmation")
        }
        return fields
    }
}
