package com.n8rtz.winetracker.data.local

import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stable W-061 phase IDs. Labels are display-only and must never be persisted as IDs. */
enum class TastingPhase(val label: String) {
    AROMA_BEFORE_TASTING("Aroma (before tasting)"),
    ENTRY("Entry"),
    MID_PALATE("Mid-palate"),
    FINISH("Finish");

    val sectionLabel: String
        get() = when (this) {
            AROMA_BEFORE_TASTING -> "Visual / aroma"
            ENTRY -> "Entry"
            MID_PALATE -> "Mid-palate"
            FINISH -> "Finish"
        }
}

const val MAX_DISTINCT_AROMA_IDS = 24

/** One opaque HSV color. No ARGB or palette ID is stored. */
data class HsvColor(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val value: Float = 1f
) {
    fun isValid(): Boolean =
        hue in 0f..360f && saturation in 0f..1f && value in 0f..1f &&
            !hue.isNaN() && !saturation.isNaN() && !value.isNaN() &&
            !hue.isInfinite() && !saturation.isInfinite() && !value.isInfinite()

    /** Pure JVM HSV -> ARGB conversion so UI code needs no Android graphics class. */
    fun toArgb(): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val c = value * saturation
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = value - c
        val sector = (h / 60f).toInt() % 6
        val (r, g, b) = when (sector) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255f).roundToInt().coerceIn(0, 255)
        val gi = ((g + m) * 255f).roundToInt().coerceIn(0, 255)
        val bi = ((b + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

/** One descriptor observed in one phase. `displayOrder` is independent of `intensity`. */
data class AromaObservation(
    val descriptorId: String = "",
    val phase: TastingPhase = TastingPhase.AROMA_BEFORE_TASTING,
    val intensity: Float? = null,
    val displayOrder: Int = 0
)

enum class AromaEditReason { DUPLICATE, LIMIT }

class AromaEditException(val reason: AromaEditReason) : IllegalArgumentException(reason.name)

enum class SensoryValidationError {
    COLOR_RANGE,
    ENTRY_RANGE,
    INTENSITY_RANGE,
    EMPTY_DESCRIPTOR_ID,
    DUPLICATE_AROMA,
    AROMA_LIMIT
}

/** Normalize Gson/legacy gaps without inferring phase, color or intensity. */
fun normalizeSensoryData(raw: SensoryData?): SensoryData {
    if (raw == null) return SensoryData()
    val observations = raw.aromaObservations.orEmpty().sortedBy { it.displayOrder }
    val derivedAromas = if (observations.isEmpty()) {
        raw.aromas.orEmpty()
    } else {
        toFlatAromaIds(observations)
    }
    return raw.copy(
        flaws = raw.flaws.orEmpty(),
        aromas = derivedAromas,
        colorHsv = raw.colorHsv,
        entryTightOpen = raw.entryTightOpen ?: 0.5f,
        aromaObservations = observations
    )
}

fun resolveEffectiveColor(noteColor: HsvColor?, definitionColor: HsvColor?): HsvColor? =
    noteColor ?: definitionColor

fun groupByPhase(observations: List<AromaObservation>): Map<TastingPhase, List<AromaObservation>> =
    TastingPhase.values().associateWith { phase ->
        observations.filter { it.phase == phase }.sortedBy { it.displayOrder }
    }

fun toFlatAromaIds(observations: List<AromaObservation>): List<String> =
    observations.sortedBy { it.displayOrder }.map { it.descriptorId }.distinct()

fun withDerivedAromas(state: SensoryData): SensoryData {
    val normalized = normalizeSensoryData(state)
    return normalized.copy(
        aromas = if (normalized.aromaObservations.isEmpty()) {
            normalized.aromas
        } else {
            toFlatAromaIds(normalized.aromaObservations)
        }
    )
}

private fun nextDisplayOrder(observations: List<AromaObservation>): Int =
    (observations.maxOfOrNull { it.displayOrder } ?: -1) + 1

fun addAromaObservation(
    state: SensoryData,
    descriptorId: String,
    phase: TastingPhase
): Result<SensoryData> = runCatching {
    val normalized = normalizeSensoryData(state)
    if (descriptorId.isBlank()) throw AromaEditException(AromaEditReason.DUPLICATE)
    val duplicate = normalized.aromaObservations.any {
        it.descriptorId == descriptorId && it.phase == phase
    }
    if (duplicate) throw AromaEditException(AromaEditReason.DUPLICATE)
    val distinctIds = (normalized.aromaObservations.map { it.descriptorId } + descriptorId).distinct()
    if (distinctIds.size > MAX_DISTINCT_AROMA_IDS) throw AromaEditException(AromaEditReason.LIMIT)
    val observation = AromaObservation(
        descriptorId = descriptorId,
        phase = phase,
        intensity = null,
        displayOrder = nextDisplayOrder(normalized.aromaObservations)
    )
    withDerivedAromas(normalized.copy(aromaObservations = normalized.aromaObservations + observation))
}

fun removeAromaObservation(
    state: SensoryData,
    descriptorId: String,
    phase: TastingPhase
): SensoryData {
    val normalized = normalizeSensoryData(state)
    val remaining = normalized.aromaObservations.filterNot {
        it.descriptorId == descriptorId && it.phase == phase
    }
    return if (remaining.isEmpty() && normalized.aromas.isNotEmpty()) {
        normalized.copy(aromaObservations = remaining)
    } else {
        withDerivedAromas(normalized.copy(aromaObservations = remaining))
    }
}

fun toggleAromaObservation(
    state: SensoryData,
    descriptorId: String,
    phase: TastingPhase
): Result<SensoryData> {
    val normalized = normalizeSensoryData(state)
    val exists = normalized.aromaObservations.any {
        it.descriptorId == descriptorId && it.phase == phase
    }
    return if (exists) {
        Result.success(removeAromaObservation(normalized, descriptorId, phase))
    } else {
        addAromaObservation(normalized, descriptorId, phase)
    }
}

fun setAromaIntensity(
    state: SensoryData,
    descriptorId: String,
    phase: TastingPhase,
    intensity: Float?
): SensoryData {
    if (intensity != null && (intensity < 0f || intensity > 1f || intensity.isNaN() || intensity.isInfinite())) {
        return state
    }
    val normalized = normalizeSensoryData(state)
    val updated = normalized.aromaObservations.map { observation ->
        if (observation.descriptorId == descriptorId && observation.phase == phase) {
            observation.copy(intensity = intensity)
        } else {
            observation
        }
    }
    return withDerivedAromas(normalized.copy(aromaObservations = updated))
}

fun reorderAromaChips(
    state: SensoryData,
    phase: TastingPhase,
    fromIndex: Int,
    toIndex: Int
): SensoryData {
    val normalized = normalizeSensoryData(state)
    val inPhase = normalized.aromaObservations.filter { it.phase == phase }.sortedBy { it.displayOrder }
    if (fromIndex !in inPhase.indices || toIndex !in inPhase.indices || fromIndex == toIndex) return normalized
    val reorderedIds = inPhase.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }.map { it.descriptorId }
    val rankOf = { observation: AromaObservation ->
        if (observation.phase == phase) {
            reorderedIds.indexOf(observation.descriptorId).takeIf { it >= 0 } ?: Int.MAX_VALUE
        } else {
            observation.displayOrder
        }
    }
    val comparator = java.util.Comparator<AromaObservation> { a, b ->
        val byPhase = a.phase.ordinal.compareTo(b.phase.ordinal)
        if (byPhase != 0) byPhase else rankOf(a).compareTo(rankOf(b))
    }
    val sorted = normalized.aromaObservations.sortedWith(comparator)
    val reindexed = sorted.mapIndexed { index, observation -> observation.copy(displayOrder = index) }
    return withDerivedAromas(normalized.copy(aromaObservations = reindexed))
}

fun validateForSave(state: SensoryData): List<SensoryValidationError> {
    val normalized = normalizeSensoryData(state)
    val errors = mutableListOf<SensoryValidationError>()
    normalized.colorHsv?.let { if (!it.isValid()) errors += SensoryValidationError.COLOR_RANGE }
    val entry = normalized.entryTightOpen ?: 0.5f
    if (entry < 0f || entry > 1f || entry.isNaN() || entry.isInfinite()) {
        errors += SensoryValidationError.ENTRY_RANGE
    }
    if (normalized.aromaObservations.any { it.descriptorId.isBlank() }) {
        errors += SensoryValidationError.EMPTY_DESCRIPTOR_ID
    }
    if (normalized.aromaObservations.any {
            val value = it.intensity
            value != null && (value < 0f || value > 1f || value.isNaN() || value.isInfinite())
        }) {
        errors += SensoryValidationError.INTENSITY_RANGE
    }
    val keys = normalized.aromaObservations.map { it.descriptorId to it.phase }
    if (keys.size != keys.distinct().size) errors += SensoryValidationError.DUPLICATE_AROMA
    if (keys.map { it.first }.distinct().size > MAX_DISTINCT_AROMA_IDS) {
        errors += SensoryValidationError.AROMA_LIMIT
    }
    return errors.distinct()
}

/** W-053 contract: all phases by default, optional phase narrowing. */
fun matchesAromaQuery(
    observations: List<AromaObservation>,
    legacyAromaIds: List<String>,
    query: String,
    phase: TastingPhase? = null
): Boolean {
    val trimmed = query.trim()
    val candidateIds = if (phase == null) {
        observations.map { it.descriptorId } + legacyAromaIds
    } else {
        observations.filter { it.phase == phase }.map { it.descriptorId }
    }
    if (trimmed.isEmpty()) return phase == null || candidateIds.isNotEmpty()
    return candidateIds.distinct().any { id ->
        id.contains(trimmed, ignoreCase = true) ||
            AromaTaxonomy.describe(id).label.contains(trimmed, ignoreCase = true)
    }
}
