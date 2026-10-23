package com.yourapp.wine.fill

import com.yourapp.wine.ocr.MergedToken

/** No coordinates: the six editable Add Wine fields. */
enum class FieldId { PRODUCER, WINE_NAME, COUNTRY, REGION, SUBREGION, VINTAGE }

/** No coordinates: GREEN validates, YELLOW requires confirmation, RED must not be filled. */
enum class UiState { GREEN, YELLOW, RED }

/** Lexical evidence is separate from the engine's measured score; never a calibrated probability. */
data class SpellingSuggestion(val domain:String,val observed:String,val matchedForm:String,val proposed:String,
    val editDistance:Int,val editKind:String,val strength:String,val support:String)

/** Evidence boxes in sources use normalized rectified-label and original-capture coordinates. */
data class FieldCandidate(
    val field: FieldId,
    val value: String,
    val state: UiState,
    val reason: String,
    val sources: List<MergedToken>,
    val spelling:SpellingSuggestion? = null,
)

/** No image ownership: every field has an explicit state, including missing RED fields. */
data class FillResult(
    val fields: Map<FieldId, FieldCandidate>,
    val overallHint: String?,
    val alternatives:Map<FieldId,List<FieldCandidate>> = emptyMap(),
    val postProcessing:List<String> = emptyList(),
    val spellingSuggestions:List<SpellingSuggestion> = emptyList(),
    val geographyPath:List<com.yourapp.wine.kb.Place> = emptyList(),
    /** Only unresolved, supported rival assignments; not a catalogue/browser suggestion list. */
    val fallbackCandidates:Map<FieldId,List<FieldCandidate>> = emptyMap(),
    /** Meaningful blanks must never be turned into a forced choice. */
    val intentionalBlankFields:Set<FieldId> = emptySet(),
)

/** No coordinates: global conditions can only downgrade otherwise validated suggestions. */
data class ConfidenceContext(
    val labelFound: Boolean = true,
    val deadlineReached: Boolean = false,
    val weakQuality: Boolean = false,
    val hint: String? = null,
)
