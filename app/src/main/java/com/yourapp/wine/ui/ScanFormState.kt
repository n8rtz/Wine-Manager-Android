package com.yourapp.wine.ui

import com.yourapp.wine.fill.*
import com.yourapp.wine.merge.TextNorm

/** No image ownership: a user's edits are protected from subsequent scanner suggestions. */
data class ReviewedField(val value: String = "", val state: UiState? = null, val confirmed: Boolean = true,
    val edited: Boolean = false, val reason: String = "", val choices:List<ScanChoicePolicy.Choice> = emptyList(),
    val intentionallyBlank:Boolean=false, val rejectedValues:Set<String> = emptySet(), val clearedByUser:Boolean=false)

data class ScanFormState(val fields: Map<FieldId, ReviewedField> = FieldId.entries.associateWith { ReviewedField() }) {
    val hasPendingConfirmation: Boolean get() = fields.values.any { !it.confirmed && it.value.isNotBlank() }

    /** No coordinates: apply non-RED suggestions only; never overwrite any field the user edited (including GREEN). */
    fun apply(result: FillResult, currentValues: Map<FieldId, String>, currentYear:Int=java.time.Year.now().value): ScanFormState {
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
                previous.clearedByUser -> previous.copy(value="", choices=ScanChoicePolicy.choices(field,result,
                    currentValues + (field to ""),currentYear,previous.rejectedValues,afterExplicitClear=true))
                previous.edited -> previous
                candidate.state == UiState.RED -> ReviewedField(state = UiState.RED, reason = candidate.reason)
                else -> ReviewedField(candidate.value, candidate.state, candidate.state == UiState.GREEN, false, candidate.reason)
            }
            if (field in related && next.state == UiState.GREEN) next.copy(state = UiState.YELLOW, confirmed = false,
                reason = next.reason + "; kept edited ${conflicts.joinToString()} differs — confirm associated fields") else next
        }
        val values=updated.mapValues { it.value.value }
        return copy(fields = updated.mapValues { (field,review) ->
            if(review.clearedByUser) review
            else if(review.edited || review.value.isNotBlank() || field in related) review.copy(choices=emptyList())
            else review.copy(choices=ScanChoicePolicy.choices(field,result,values,currentYear),intentionallyBlank=field in result.intentionalBlankFields)
        })
    }

    /** Manual changes are protected on later scans. */
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
            if(id in related && review.choices.isNotEmpty()) review.copy(choices=emptyList())
            else if (id in related && review.state == UiState.GREEN) review.copy(state = UiState.YELLOW, confirmed = false,
                reason = review.reason + "; $field edited — confirm associated hierarchy/name") else review
        })
    }

    /** Clear the visible suggestion, remember its rejection, and expose only supported alternatives. */
    fun clearSuggestion(field:FieldId,result:FillResult?,currentValues:Map<FieldId,String>,currentYear:Int):ScanFormState {
        val previous=fields.getValue(field)
        val rejected=(previous.rejectedValues + (currentValues[field] ?: previous.value).takeIf { it.isNotBlank() }.orEmpty())
        val choices=result?.let { ScanChoicePolicy.choices(field,it,currentValues+(field to ""),currentYear,rejected,afterExplicitClear=true) }.orEmpty()
        val cleared=ReviewedField(value="",state=UiState.RED,confirmed=true,edited=true,
            reason=if(choices.isEmpty()) "Cleared by user — enter manually or retake" else "Cleared by user — choose an OCR alternative or enter manually",
            choices=choices,rejectedValues=rejected,clearedByUser=true)
        return copy(fields=fields+(field to cleared))
    }

    /** Selecting an offered choice is an explicit user edit, not algorithmic validation. */
    fun selectChoice(field:FieldId,value:String):ScanFormState {
        val current=fields.getValue(field)
        if(current.value.isNotBlank() || (current.edited && !current.clearedByUser) || current.choices.none { it.value==value }) return this
        val edited=edit(field,value)
        return edited.copy(fields=edited.fields+(field to edited.fields.getValue(field).copy(
            state=UiState.YELLOW,confirmed=true,reason="User selected OCR alternative — kept on retakes",choices=emptyList())))
    }
    fun dismissChoices(field:FieldId):ScanFormState {
        if(fields.getValue(field).value.isNotBlank()) return this
        return edit(field,"").let { it.copy(fields=it.fields+(field to it.fields.getValue(field).copy(
            reason="User kept this field blank — kept on retakes",intentionallyBlank=true))) }
    }

    /** No coordinates: one tap acknowledges a populated YELLOW field without pretending a KB match occurred. */
    fun confirm(field: FieldId): ScanFormState {
        val current = fields.getValue(field)
        if (current.state != UiState.YELLOW || current.value.isBlank()) return this
        return copy(fields = fields + (field to current.copy(confirmed = true, reason = current.reason + "; user confirmed")))
    }
    private companion object { val GEOGRAPHY = setOf(FieldId.COUNTRY, FieldId.REGION, FieldId.SUBREGION) }
}
