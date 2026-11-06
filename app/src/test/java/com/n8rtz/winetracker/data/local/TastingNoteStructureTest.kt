package com.n8rtz.winetracker.data.local

import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import com.n8rtz.winetracker.aromawheel.MAX_SELECTED_DESCRIPTORS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TastingNoteStructureTest {

    @Test
    fun hsvColorConvertsPureRedToArgb() {
        assertEquals(0xFFFF0000.toInt(), HsvColor(0f, 1f, 1f).toArgb())
    }

    @Test
    fun capMatchesWheelSelectionLimit() {
        assertEquals(MAX_SELECTED_DESCRIPTORS, MAX_DISTINCT_AROMA_IDS)
    }

    @Test
    fun normalizeLegacyDataPreservesFlatAromasWithoutInference() {
        val normalized = normalizeSensoryData(SensoryData(aromas = listOf("legacy-id")))
        assertEquals(listOf("legacy-id"), normalized.aromas)
        assertTrue(normalized.aromaObservations.isEmpty())
        assertNull(normalized.colorHsv)
        assertEquals(0.5f, normalized.entryTightOpen!!, 0.0001f)
    }

    @Test
    fun withDerivedAromasPreservesLegacyFlatAromasWhenNoObservations() {
        val data = SensoryData(aromas = listOf("legacy-id"))
        assertEquals(listOf("legacy-id"), withDerivedAromas(data).aromas)
    }

    @Test
    fun derivedFlatAromasUseDistinctIdsAcrossPhases() {
        val data = SensoryData(
            aromaObservations = listOf(
                AromaObservation("id-a", TastingPhase.AROMA_BEFORE_TASTING, 0.2f, 0),
                AromaObservation("id-a", TastingPhase.FINISH, 0.8f, 1),
                AromaObservation("id-b", TastingPhase.ENTRY, null, 2)
            )
        )
        val derived = withDerivedAromas(data)
        assertEquals(listOf("id-a", "id-b"), derived.aromas)
        assertEquals(listOf("id-a", "id-b"), toFlatAromaIds(derived.aromaObservations))
    }

    @Test
    fun addRejectsDuplicateKeyAndDistinctIdLimit() {
        val first = addAromaObservation(SensoryData(), "id-a", TastingPhase.ENTRY).getOrThrow()
        val duplicate = addAromaObservation(first, "id-a", TastingPhase.ENTRY)
        assertTrue(duplicate.isFailure)
        assertEquals(
            AromaEditReason.DUPLICATE,
            (duplicate.exceptionOrNull() as AromaEditException).reason
        )

        var state = SensoryData()
        repeat(MAX_DISTINCT_AROMA_IDS) { index ->
            state = addAromaObservation(state, "id-$index", TastingPhase.ENTRY).getOrThrow()
        }
        val capped = addAromaObservation(state, "id-extra", TastingPhase.FINISH)
        assertTrue(capped.isFailure)
        assertEquals(
            AromaEditReason.LIMIT,
            (capped.exceptionOrNull() as AromaEditException).reason
        )
    }

    @Test
    fun sameDescriptorCanBeAssignedToMultiplePhasesIndependently() {
        val first = addAromaObservation(
            SensoryData(),
            "id-a",
            TastingPhase.AROMA_BEFORE_TASTING
        ).getOrThrow()
        val both = addAromaObservation(first, "id-a", TastingPhase.FINISH).getOrThrow()

        assertEquals(listOf("id-a"), both.aromas)
        assertEquals(2, both.aromaObservations.size)
        assertEquals(
            setOf(TastingPhase.AROMA_BEFORE_TASTING, TastingPhase.FINISH),
            both.aromaObservations.map { it.phase }.toSet()
        )
        assertTrue(validateForSave(both).isEmpty())

        val updated = setAromaIntensity(
            both,
            "id-a",
            TastingPhase.AROMA_BEFORE_TASTING,
            0.3f
        )
        val beforeAromaIntensity = updated.aromaObservations
            .single { it.phase == TastingPhase.AROMA_BEFORE_TASTING }
            .intensity!!
        assertEquals(0.3f, beforeAromaIntensity, 0.0001f)
        assertNull(updated.aromaObservations.single { it.phase == TastingPhase.FINISH }.intensity)

        val removed = removeAromaObservation(updated, "id-a", TastingPhase.AROMA_BEFORE_TASTING)
        assertEquals(listOf(TastingPhase.FINISH), removed.aromaObservations.map { it.phase })
        assertEquals(listOf("id-a"), removed.aromas)
    }

    @Test
    fun intensityIsContinuousPerPhaseAndRejectsOutOfRange() {
        val initial = SensoryData(
            aromaObservations = listOf(
                AromaObservation("id-a", TastingPhase.ENTRY, null, 0),
                AromaObservation("id-b", TastingPhase.ENTRY, null, 1)
            )
        )
        val updated = setAromaIntensity(initial, "id-a", TastingPhase.ENTRY, 0.37f)
        assertEquals(0.37f, updated.aromaObservations[0].intensity!!, 0.0001f)
        assertEquals(listOf("id-a", "id-b"), updated.aromaObservations.map { it.descriptorId })

        val invalid = setAromaIntensity(updated, "id-a", TastingPhase.ENTRY, 1.2f)
        assertEquals(0.37f, invalid.aromaObservations[0].intensity!!, 0.0001f)
    }

    @Test
    fun pinchContinuouslyResizesAromaWithoutVisibleStepValues() {
        val initial = intensityAfterPinch(null, 1f)
        val larger = intensityAfterPinch(initial, 1.4f)
        val smaller = intensityAfterPinch(initial, 0.7f)
        assertTrue(larger > initial)
        assertTrue(smaller < initial)
        assertEquals(1f, intensityAfterPinch(0.95f, 2f), 0f)
        assertEquals(0f, intensityAfterPinch(0.05f, 0.5f), 0f)
        assertEquals(0.5f, visualAromaIntensity(Float.NaN), 0f)
    }

    @Test
    fun wordCloudSortsLargestAromasFirstAndKeepsTiesStable() {
        val sorted = sortAromaObservationsByIntensity(
            listOf(
                AromaObservation("small", TastingPhase.ENTRY, 0.2f, 0),
                AromaObservation("tie-later", TastingPhase.ENTRY, 0.7f, 3),
                AromaObservation("largest", TastingPhase.ENTRY, 0.9f, 2),
                AromaObservation("tie-first", TastingPhase.ENTRY, 0.7f, 1)
            )
        )
        assertEquals(listOf("largest", "tie-first", "tie-later", "small"), sorted.map { it.descriptorId })
    }

    @Test
    fun reorderChangesOnlyTheRequestedPhase() {
        val data = SensoryData(
            aromaObservations = listOf(
                AromaObservation("a", TastingPhase.ENTRY, null, 0),
                AromaObservation("b", TastingPhase.ENTRY, null, 1),
                AromaObservation("c", TastingPhase.FINISH, null, 2)
            )
        )
        val reordered = reorderAromaChips(data, TastingPhase.ENTRY, 0, 1)
        assertEquals(
            listOf("b", "a"),
            groupByPhase(reordered.aromaObservations)[TastingPhase.ENTRY]!!.map { it.descriptorId }
        )
        assertEquals(
            listOf("c"),
            groupByPhase(reordered.aromaObservations)[TastingPhase.FINISH]!!.map { it.descriptorId }
        )
    }

    @Test
    fun validationReportsColorEntryIntensityDuplicateAndLimitErrors() {
        val oversized = (0 until MAX_DISTINCT_AROMA_IDS + 1).map { index ->
            AromaObservation("id-$index", TastingPhase.ENTRY, 1.2f, index)
        }
        val bad = SensoryData(
            colorHsv = HsvColor(400f, 0f, 1f),
            entryTightOpen = 2f,
            aromaObservations = oversized + AromaObservation("id-0", TastingPhase.ENTRY, null, 99)
        )
        val errors = validateForSave(bad)
        assertTrue(SensoryValidationError.COLOR_RANGE in errors)
        assertTrue(SensoryValidationError.ENTRY_RANGE in errors)
        assertTrue(SensoryValidationError.INTENSITY_RANGE in errors)
        assertTrue(SensoryValidationError.DUPLICATE_AROMA in errors)
        assertTrue(SensoryValidationError.AROMA_LIMIT in errors)
    }

    @Test
    fun aromaQuerySearchesAllPhasesByDefaultAndNarrowsOptionally() {
        val descriptor = AromaTaxonomy.leaves.first()
        val label = AromaTaxonomy.describe(descriptor.id).label
        val observations = listOf(
            AromaObservation(descriptor.id, TastingPhase.FINISH, null, 0)
        )
        assertTrue(matchesAromaQuery(observations, emptyList(), label))
        assertTrue(matchesAromaQuery(observations, emptyList(), "", null))
        assertTrue(matchesAromaQuery(observations, emptyList(), "", TastingPhase.FINISH))
        assertFalse(matchesAromaQuery(observations, emptyList(), "", TastingPhase.ENTRY))
        assertFalse(matchesAromaQuery(observations, emptyList(), label, TastingPhase.ENTRY))
        assertTrue(matchesAromaQuery(emptyList(), listOf("legacy-id"), "legacy-id"))
    }

    @Test
    fun noteColorOverridesDefinitionDefault() {
        val inherited = HsvColor(10f, 0.2f, 0.8f)
        val override = HsvColor(200f, 0.4f, 0.6f)
        assertEquals(inherited, resolveEffectiveColor(null, inherited))
        assertEquals(override, resolveEffectiveColor(override, inherited))
    }
}
