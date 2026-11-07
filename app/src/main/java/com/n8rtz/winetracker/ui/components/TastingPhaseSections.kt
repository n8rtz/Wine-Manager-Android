@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.HsvColor
import com.n8rtz.winetracker.data.local.SensoryData
import com.n8rtz.winetracker.data.local.TastingPhase
import com.n8rtz.winetracker.data.local.groupByPhase
import com.n8rtz.winetracker.data.local.normalizeSensoryData
import com.n8rtz.winetracker.data.local.removeAromaObservation
import com.n8rtz.winetracker.data.local.resolveEffectiveColor
import com.n8rtz.winetracker.data.local.setAromaIntensity
import com.n8rtz.winetracker.data.local.sortAromaObservationsByIntensity

private fun traitTitlesFor(phase: TastingPhase): List<String> = when (phase) {
    TastingPhase.AROMA_BEFORE_TASTING -> listOf("Aroma")
    TastingPhase.ENTRY -> listOf("Sweetness")
    TastingPhase.MID_PALATE -> listOf(
        "Acidity", "Tannins / grip", "Body", "Balance", "Concentration", "Complexity"
    )
    TastingPhase.FINISH -> listOf("Finish", "Quality")
}

@Composable
fun SectionedSensoryFields(
    data: SensoryData,
    definitionColor: HsvColor?,
    legacyAromasUnresolved: Boolean,
    onDataChange: (SensoryData) -> Unit,
    onOpenWheel: () -> Unit,
    onAssignLegacy: () -> Unit,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val normalized = normalizeSensoryData(data)
    val traits = sensoryTraitControls(normalized, onDataChange)
    val byPhase = groupByPhase(normalized.aromaObservations)
    val selectedAromaIds = (
        sortAromaObservationsByIntensity(normalized.aromaObservations).map { it.descriptorId } +
            normalized.aromas
        ).distinct()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TastingPhase.values().forEach { phase ->
            Text(phase.sectionLabel, style = MaterialTheme.typography.titleSmall)
            when (phase) {
                TastingPhase.AROMA_BEFORE_TASTING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        NoteColorField(
                            definitionColor = definitionColor,
                            noteColor = normalized.colorHsv,
                            onNoteColorChange = { onDataChange(normalized.copy(colorHsv = it)) },
                            readOnly = readOnly,
                            inline = true
                        )
                        AromaWheelLauncherCard(
                            selectedIds = selectedAromaIds,
                            onClick = onOpenWheel,
                            enabled = !readOnly,
                            compact = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (legacyAromasUnresolved && !readOnly) {
                        TextButton(onClick = onAssignLegacy) { Text("Assign") }
                    }
                }
                TastingPhase.ENTRY -> {
                    EntryScaleSlider(data = normalized, onDataChange = onDataChange, enabled = !readOnly)
                }
                else -> Unit
            }
            traits.filter { it.title in traitTitlesFor(phase) }.forEach { trait ->
                SensoryTraitSlider(trait, enabled = !readOnly)
            }
            PhaseAromaCloud(
                phase = phase,
                observations = byPhase[phase].orEmpty(),
                onIntensityChange = { id, phaseValue, intensity ->
                    onDataChange(setAromaIntensity(normalized, id, phaseValue, intensity))
                },
                onRemove = { id, phaseValue ->
                    onDataChange(removeAromaObservation(normalized, id, phaseValue))
                },
                readOnly = readOnly
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun entryTightOpenLabel(value: Float): String =
    if (value < 0.5f) "Tight" else "Open"

@Composable
private fun EntryScaleSlider(
    data: SensoryData,
    onDataChange: (SensoryData) -> Unit,
    enabled: Boolean = true
) {
    val value = data.entryTightOpen ?: 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Entry",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                entryTightOpenLabel(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Tight",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Open",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        GradientSensorySlider(
            value = value,
            valueRange = 0f..1f,
            steps = 0,
            gradientColors = gradientColorsFor("Body"),
            enabled = enabled,
            onValueChange = { onDataChange(data.copy(entryTightOpen = it)) }
        )
    }
}

@Composable
fun CommonFaultsFields(
    data: SensoryData,
    onChange: (SensoryData) -> Unit,
    readOnly: Boolean = false
) {
    val normalized = normalizeSensoryData(data)
    Text("Common faults", style = MaterialTheme.typography.titleSmall)
    val flaws = listOf(
        "Cork taint (TCA)", "Oxidation", "Volatile acidity",
        "Brettanomyces", "Reduction / sulfur", "Heat damage"
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        flaws.forEach { flaw ->
            val selected = flaw in normalized.flaws
            FilterChip(
                selected = selected,
                enabled = !readOnly,
                onClick = {
                    if (!readOnly) {
                        val updated = if (selected) normalized.flaws - flaw else normalized.flaws + flaw
                        onChange(normalized.copy(flaws = updated))
                    }
                },
                label = { Text(flaw) }
            )
        }
    }
}

@Composable
fun StructuredNoteSummary(
    sensoryData: SensoryData?,
    definitionColor: HsvColor?,
    modifier: Modifier = Modifier
) {
    val data = normalizeSensoryData(sensoryData)
    val effectiveColor = resolveEffectiveColor(data.colorHsv, definitionColor)
    val traits = sensoryTraitControls(data) {}
    val byPhase = groupByPhase(data.aromaObservations)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        effectiveColor?.let { color ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Color", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(color.toArgb()))
                )
            }
        }
        TastingPhase.values().forEach { phase ->
            Text(phase.sectionLabel, style = MaterialTheme.typography.titleSmall)
            if (phase == TastingPhase.ENTRY) {
                Text(
                    "Entry: ${entryTightOpenLabel(data.entryTightOpen ?: 0.5f)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            traitTitlesFor(phase).forEach { title ->
                traits.firstOrNull { it.title == title }?.let { trait ->
                    val index = trait.value.coerceIn(0, trait.descriptors.lastIndex)
                    Text(
                        "${trait.title}: ${trait.descriptors[index]}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            PhaseAromaCloud(
                phase = phase,
                observations = byPhase[phase].orEmpty(),
                onIntensityChange = { _, _, _ -> },
                onRemove = { _, _ -> },
                readOnly = true
            )
        }
    }
}
