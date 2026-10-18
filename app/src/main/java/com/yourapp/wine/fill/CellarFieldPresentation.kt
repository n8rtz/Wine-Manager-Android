package com.yourapp.wine.fill

import com.yourapp.wine.merge.TextNorm

/** No coordinate changes: adapt validated scanner geography to the cellar's state-as-country convention. */
object CellarFieldPresentation {
    private val states = ("Alabama|Alaska|Arizona|Arkansas|California|Colorado|Connecticut|Delaware|Florida|Georgia|Hawaii|Idaho|Illinois|Indiana|Iowa|Kansas|Kentucky|Louisiana|Maine|Maryland|Massachusetts|Michigan|Minnesota|Mississippi|Missouri|Montana|Nebraska|Nevada|New Hampshire|New Jersey|New Mexico|New York|North Carolina|North Dakota|Ohio|Oklahoma|Oregon|Pennsylvania|Rhode Island|South Carolina|South Dakota|Tennessee|Texas|Utah|Vermont|Virginia|Washington|West Virginia|Wisconsin|Wyoming").split('|').map(TextNorm::normalize).toSet()

    /** Validated fields in/out: never maps contradictory/non-US geography or manufactures a missing state. */
    fun apply(result: FillResult): FillResult {
        val fields = result.fields.toMutableMap()
        val producer = fields.getValue(FieldId.PRODUCER)
        if (producer.state != UiState.RED) {
            val short = producer.value.replace(Regex("(?i)\\s+winery$"), "").trim()
            if (short.isNotBlank() && short != producer.value) fields[FieldId.PRODUCER] = producer.copy(value = short,
                reason = producer.reason + "; generic Winery suffix omitted for display")
        }
        val country = fields.getValue(FieldId.COUNTRY)
        val state = fields.getValue(FieldId.REGION)
        if (TextNorm.normalize(country.value) == "united states" && country.state != UiState.RED &&
            state.state != UiState.RED && TextNorm.normalize(state.value) in states &&
            listOf(country,state).none { it.reason.contains("conflict",ignoreCase=true) }) {
            fields[FieldId.COUNTRY] = state.copy(field = FieldId.COUNTRY,
                state = if (country.state == UiState.YELLOW || state.state == UiState.YELLOW) UiState.YELLOW else UiState.GREEN,
                reason = state.reason + "; U.S. state displayed in Country by cellar convention")
            fields[FieldId.REGION] = FieldCandidate(FieldId.REGION,"",UiState.RED,
                "State moved to Country; no separate regional candidate supplied",emptyList())
        }
        return result.copy(fields = fields)
    }
}
