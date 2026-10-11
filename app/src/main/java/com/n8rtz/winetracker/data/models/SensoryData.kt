package com.n8rtz.winetracker.data.models

/**
 * Data model for storing detailed sensory evaluation data.
 * This is serialized into a JSON string in the TastingNote entity.
 */
data class SensoryData(
    val clarity: String? = null,
    val colorType: String? = null,
    val colorHue: String? = null,
    val intensity: Float? = null,
    val noseIntensity: Float? = null,
    val noseComplexity: Float? = null,
    val aromas: List<String> = emptyList(),
    val sweetness: Float? = null,
    val acidity: Float? = null,
    val tannin: Float? = null,
    val alcohol: Float? = null,
    val body: Float? = null,
    val finishLength: Float? = null,
    val flaws: List<String> = emptyList(),
    val overallQuality: String? = null,
    val overallScore: Int? = null
)
