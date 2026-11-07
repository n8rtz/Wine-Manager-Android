package com.n8rtz.winetracker.data.local

import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import kotlin.math.ln

const val MAX_DISTINCT_AROMA_IDS = 24

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

/** Clamp malformed legacy values for display without mutating the stored observation. */
fun visualAromaIntensity(intensity: Float?): Float {
    val value = intensity ?: 0.5f
    return if (value.isNaN() || value.isInfinite()) 0.5f else value.coerceIn(0f, 1f)
}

/** Visual size is adjusted continuously by pinching; no numeric value is needed on the chip. */
fun intensityAfterPinch(intensity: Float?, zoom: Float): Float {
    val current = visualAromaIntensity(intensity)
    val safeZoom = zoom.takeIf { it > 0f && !it.isNaN() && !it.isInfinite() } ?: 1f
    val sizeDelta = ln(safeZoom.toDouble()).toFloat() * 0.35f
    return (current + sizeDelta).coerceIn(0f, 1f)
}

/** The most prominent aromas are laid out first, so the largest words sit at the top. */
fun sortAromaObservationsByIntensity(observations: List<AromaObservation>): List<AromaObservation> =
    observations.sortedWith(
        compareByDescending<AromaObservation> { visualAromaIntensity(it.intensity) }
            .thenBy { it.displayOrder }
    )

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
