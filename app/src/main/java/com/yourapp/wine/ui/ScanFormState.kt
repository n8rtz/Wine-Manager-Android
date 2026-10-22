package com.yourapp.wine.ui

import com.yourapp.wine.fill.*
import com.yourapp.wine.merge.TextNorm

/** No image ownership: a user's edits are protected from subsequent scanner suggestions. */
data class ReviewedField(val value: String = "", val state: UiState? = null, val confirmed: Boolean = true,
    val edited: Boolean = false, val reason: String = "")

data class ScanFormState(val fields: Map<FieldId, ReviewedField> = FieldId.entries.associateWith { ReviewedField() }) {
    val hasPendingConfirmation: Boolean get() = fields.values.any { !it.confirmed && it.value.isNotBlank() }

    /** No coordinates: apply non-RED suggestions only; never overwrite any field the user edited (including GREEN). */
    fun apply(result: FillResult, currentValues: Map<FieldId, String>): ScanFormState {
        val conflicts = FieldId.entries.filter { field ->
            val previous = fields.getValue(field)
            val incoming = result.fields.getValue(field)
            previous.edited && incoming.state != UiState.RED &&
                TextNorm.normalize(currentValues[field] ?: previous.value) != TextNorm.normalize(incoming.value)
        }.toSet()
        val related = mutableSetOf<FieldId>()
        if (conflicts.any { it in GEOGRAPHY }) related += GEOGRAPHY
        if (FieldId.PRODUCER in conflicts) related += GEOGRAPHY + FieldId.WINE_NAME
        val updated = FieldId.entries.associateWith { field ->
            val previous = fields.getValue(field).copy(value = currentValues[field] ?: fields.getValue(field).value)
            val candidate = result.fields.getValue(field)
            val next = when {
                previous.edited -> previous
                // A rejected retake must not leave old automatic text that can be saved as a new reading.
                candidate.state == UiState.RED -> ReviewedField(state = UiState.RED, reason = candidate.reason)
                else -> ReviewedField(candidate.value, candidate.state, candidate.state == UiState.GREEN, false, candidate.reason)
            }
            if (field in related && next.state == UiState.GREEN) next.copy(state = UiState.YELLOW, confirmed = false,
                reason = next.reason + "; kept edited ${conflicts.joinToString()} differs — confirm associated fields") else next
        }
        return copy(fields = updated)
    }

    /** No coordinates: manual changes are protected; changing origin/producer invalidates related GREEN validation. */
    fun edit(field: FieldId, value: String): ScanFormState {
        val previous = fields.getValue(field)
        val equivalent = TextNorm.normalize(previous.value) == TextNorm.normalize(value)
        val edited = ReviewedField(value,
            if (value.isBlank()) UiState.RED else if (previous.state == UiState.GREEN && equivalent) UiState.GREEN else UiState.YELLOW,
            true, true, if (value.isBlank()) "Enter manually or retake" else "Manually entered/edited — kept on retakes")
        val related = if (equivalent) emptySet() else when (field) {
            FieldId.PRODUCER -> GEOGRAPHY + FieldId.WINE_NAME
            in GEOGRAPHY -> GEOGRAPHY - field
            else -> emptySet()
        }
        return copy(fields = (fields + (field to edited)).mapValues { (id, review) ->
            if (id in related && review.state == UiState.GREEN) review.copy(state = UiState.YELLOW, confirmed = false,
                reason = review.reason + "; $field edited — confirm associated hierarchy/name") else review
        })
    }

    /** No coordinates: one tap acknowledges a populated YELLOW field without pretending a KB match occurred. */
    fun confirm(field: FieldId): ScanFormState {
        val current = fields.getValue(field)
        if (current.state != UiState.YELLOW || current.value.isBlank()) return this
        return copy(fields = fields + (field to current.copy(confirmed = true, reason = current.reason + "; user confirmed")))
    }
    private companion object { val GEOGRAPHY = setOf(FieldId.COUNTRY, FieldId.REGION, FieldId.SUBREGION) }
}
