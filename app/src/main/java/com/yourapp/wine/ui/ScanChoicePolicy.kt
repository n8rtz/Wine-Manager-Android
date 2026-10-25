package com.yourapp.wine.ui

import com.yourapp.wine.fill.*
import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.merge.TextNorm

/** Evidence-backed OCR choices only; never invent catalog values or infer child-place readings. */
object ScanChoicePolicy {
    data class Choice(val value:String,val reason:String)

    /**
     * Ordinary unresolved fields retain the conservative fallback policy. After an explicit clear,
     * expose the ranked field alternatives (minus rejected/current values) so the user can choose.
     */
    fun choices(field:FieldId,result:FillResult,values:Map<FieldId,String>,currentYear:Int,
        rejectedValues:Set<String> = emptySet(), afterExplicitClear:Boolean = false):List<Choice> {
        if(values[field].orEmpty().isNotBlank() || field in result.intentionalBlankFields) return emptyList()
        val selected=result.fields.getValue(field)
        if(!afterExplicitClear && selected.state!=UiState.RED && selected.value.isNotBlank()) return emptyList()
        val sourceCandidates = if(afterExplicitClear)
            result.alternatives[field].orEmpty() + result.fallbackCandidates[field].orEmpty()
        else result.fallbackCandidates[field].orEmpty()
        val excluded=(rejectedValues + (if(afterExplicitClear) "" else selected.value)).map(::key).filter(String::isNotEmpty).toSet()
        val candidates=sourceCandidates.filter { c ->
            key(c.value) !in excluded && c.field==field && c.state!=UiState.RED && c.value.isNotBlank() && c.value.length<=160 &&
                c.sources.any { it.members.isNotEmpty() } && when(field) {
                    FieldId.PRODUCER,FieldId.WINE_NAME -> MetadataRules.isIdentity(c.value) &&
                        c.sources.any { token -> token.members.any { MetadataRules.isIdentity(it.textRaw) } }
                    FieldId.VINTAGE -> c.value.toIntOrNull() in 1900..currentYear && c.sources.any { token ->
                        token.members.any { r -> Regex("(?<![0-9])${Regex.escape(c.value)}(?![0-9])").containsMatchIn(r.textRaw) && !MetadataRules.isBoilerplate(r.textRaw) }
                    }
                    else -> geographicEvidence(field,c,result,values)
                }
        }.distinctBy { key(it.value) }
        // Once the user explicitly rejected the selected text, even one remaining supported rival is useful.
        val minimum=if(afterExplicitClear) 1 else 2
        return if(candidates.size<minimum) emptyList() else candidates.take(3).map { Choice(it.value,it.reason) }
    }

    private fun geographicEvidence(field:FieldId,c:FieldCandidate,result:FillResult,values:Map<FieldId,String>):Boolean {
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
        return c.sources.any { token -> token.members.any { key(it.textRaw)==key(place.name) } }
    }
    private fun key(text:String)=TextNorm.normalize(text).filter(Char::isLetterOrDigit)
}
