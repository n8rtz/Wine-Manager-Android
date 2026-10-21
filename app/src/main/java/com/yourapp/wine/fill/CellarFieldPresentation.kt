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
        if (TextNorm.normalize(country.value) == "united states" && state.state == UiState.RED) {
            fields[FieldId.COUNTRY] = country.copy(value = "", state = UiState.RED,
                reason = country.reason + "; U.S. origin known but state not determined; enter state for cellar Country")
        }
        val sub=fields.getValue(FieldId.SUBREGION)
        if(sub.state!=UiState.RED && TextNorm.normalize(sub.value) in setOf("brunello di montalcino","rosso di montalcino")) {
            fields[FieldId.SUBREGION]=sub.copy(value="Montalcino",reason=sub.reason+"; cellar locality for the printed Montalcino appellation")
        }
        val geographyConflicts=listOf(FieldId.COUNTRY,FieldId.REGION,FieldId.SUBREGION).any {
            fields.getValue(it).reason.contains("conflict",ignoreCase=true)
        }
        val currentSub=fields.getValue(FieldId.SUBREGION)
        // Use an actual ancestor when available (e.g. Oakville -> Napa Valley), never a guessed parent.
        val valley=result.geographyPath.firstOrNull { isValley(it.name) }
        if(!geographyConflicts && currentSub.state!=UiState.RED && currentSub.value.isNotBlank() && valley!=null) {
            fields[FieldId.REGION]=currentSub.copy(field=FieldId.REGION,value=valley.name,
                reason=currentSub.reason+"; valley-level Region from the local hierarchy")
            if(TextNorm.normalize(currentSub.value)==TextNorm.normalize(valley.name))
                fields[FieldId.SUBREGION]=FieldCandidate(FieldId.SUBREGION,"",UiState.RED,"Valley displayed in Region; no finer locality read",emptyList())
        } else if(!geographyConflicts && currentSub.state!=UiState.RED && isValley(currentSub.value)) {
            fields[FieldId.REGION]=currentSub.copy(field=FieldId.REGION,reason=currentSub.reason+"; valley-level Region")
            fields[FieldId.SUBREGION]=FieldCandidate(FieldId.SUBREGION,"",UiState.RED,"Valley moved to Region",emptyList())
        }
        // Global presentation invariant: retain a known locality by promotion, never invent its parent.
        val region=fields.getValue(FieldId.REGION)
        val remaining=fields.getValue(FieldId.SUBREGION)
        if((region.state==UiState.RED || region.value.isBlank()) && remaining.state!=UiState.RED && remaining.value.isNotBlank()) {
            if(!geographyConflicts) fields[FieldId.REGION]=remaining.copy(field=FieldId.REGION,state=UiState.YELLOW,
                reason=remaining.reason+"; no parent Region available; locality promoted for confirmation")
            fields[FieldId.SUBREGION]=FieldCandidate(FieldId.SUBREGION,"",UiState.RED,
                "Subregion requires a Region; no unsupported parent supplied",emptyList())
        }
        return result.copy(fields = fields)
    }
    private fun isValley(name:String):Boolean = Regex("\\b(valley|valle|vallee)\\b").containsMatchIn(TextNorm.normalize(name))
}
