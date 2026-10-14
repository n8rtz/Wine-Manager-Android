package com.yourapp.wine.fill

import com.yourapp.wine.ocr.MergedToken

/** No coordinates: the six editable Add Wine fields. */
enum class FieldId { PRODUCER, WINE_NAME, COUNTRY, REGION, SUBREGION, VINTAGE }

/** No coordinates: GREEN validates, YELLOW requires confirmation, RED must not be filled. */
enum class UiState { GREEN, YELLOW, RED }

/** Evidence boxes in sources use normalized rectified-label and original-capture coordinates. */
data class FieldCandidate(
    val field: FieldId,
    val value: String,
    val state: UiState,
    val reason: String,
    val sources: List<MergedToken>,
)

/** No image ownership: every field has an explicit state, including missing RED fields. */
data class FillResult(
    val fields: Map<FieldId, FieldCandidate>,
    val overallHint: String?,
)

/** No coordinates: global conditions can only downgrade otherwise validated suggestions. */
data class ConfidenceContext(
    val labelFound: Boolean = true,
    val deadlineReached: Boolean = false,
    val weakQuality: Boolean = false,
    val hint: String? = null,
)
