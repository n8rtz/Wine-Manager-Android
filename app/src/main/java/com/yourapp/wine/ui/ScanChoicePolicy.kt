package com.yourapp.wine.ui

import com.yourapp.wine.fill.*
import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.merge.TextNorm

/** Fallback only: no scoring changes, no child-place enumeration, no guesses from residual text. */
object ScanChoicePolicy {
    data class Choice(val value:String,val reason:String)
    fun choices(field:FieldId,result:FillResult,values:Map<FieldId,String>,currentYear:Int):List<Choice> {
        if(values[field].orEmpty().isNotBlank() || field in result.intentionalBlankFields) return emptyList()
        val selected=result.fields.getValue(field)
        if(selected.state!=UiState.RED && selected.value.isNotBlank()) return emptyList()
        val candidates=result.fallbackCandidates[field].orEmpty().filter { c ->
            c.field==field && c.state!=UiState.RED && c.value.isNotBlank() && c.value.length<=160 &&
                c.sources.any { it.members.isNotEmpty() } && when(field) {
                    FieldId.PRODUCER,FieldId.WINE_NAME -> MetadataRules.isIdentity(c.value) &&
                        c.sources.any { token -> token.members.any { MetadataRules.isIdentity(it.textRaw) } }
                    FieldId.VINTAGE -> c.value.toIntOrNull() in 1900..currentYear && c.sources.any { token ->
                        token.members.any { r -> Regex("(?<![0-9])${Regex.escape(c.value)}(?![0-9])").containsMatchIn(r.textRaw) && !MetadataRules.isBoilerplate(r.textRaw) }
                    }
                    else -> geographicEvidence(field,c,result,values)
                }
        }.distinctBy { key(it.value) }
        // A single weak/unsupported contender is not a reason to force a choice or fill a field.
        return if(candidates.size<2) emptyList() else candidates.take(3).map { Choice(it.value,it.reason) }
    }
    private fun geographicEvidence(field:FieldId,c:FieldCandidate,result:FillResult,values:Map<FieldId,String>):Boolean {
        // Future geography ambiguity producers must supply a verified path as well as explicit label support.
        val path=result.geographyPath
        val place=path.singleOrNull { key(it.name)==key(c.value) && when(field) {
            FieldId.COUNTRY -> it.type=="country"
            FieldId.REGION -> it.type=="region"
            FieldId.SUBREGION -> it.type in setOf("subregion","appellation")
            else -> false
        } } ?: return false
        fun selectedParent(parent:FieldId,type:String)=values[parent].orEmpty().isNotBlank() &&
            path.any { it.type==type && key(it.name)==key(values.getValue(parent)) }
        if(field==FieldId.REGION && !selectedParent(FieldId.COUNTRY,"country")) return false
        if(field==FieldId.SUBREGION && (!selectedParent(FieldId.COUNTRY,"country") || !selectedParent(FieldId.REGION,"region"))) return false
        // An ancestor/region reading does not supply evidence for its children. No substring inference.
        return c.sources.any { token -> token.members.any { key(it.textRaw)==key(place.name) } }
    }
    private fun key(text:String)=TextNorm.normalize(text).filter(Char::isLetterOrDigit)
}
