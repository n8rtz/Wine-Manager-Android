@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
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
    modifier: Modifier = Modifier,
    // Reports a chip deletion (label + restore action) so the host screen
    // can show a bottom undo popup.
    onAromaRemoved: ((String, () -> Unit) -> Unit)? = null
) {
    val normalized = normalizeSensoryData(data)
    val traits = sensoryTraitControls(normalized, onDataChange)
    val selectedAromaIds = (
        sortAromaObservationsByIntensity(normalized.aromaObservations).map { it.descriptorId } +
            normalized.aromas
        ).distinct()
    var colorFieldWidthPx by remember { mutableIntStateOf(0) }
    var colorFieldHeightPx by remember { mutableIntStateOf(0) }
    var aromaHeaderHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val swatchCardInset = 8.dp
    val swatchCardInsetPx = with(density) { swatchCardInset.roundToPx() }
    val aromaSummaryStartPx = if (selectedAromaIds.isEmpty()) {
        aromaHeaderHeightPx
    } else {
        maxOf(aromaHeaderHeightPx, colorFieldHeightPx + swatchCardInsetPx)
    }
    val aromaSummaryGap = with(density) {
        (aromaSummaryStartPx - aromaHeaderHeightPx).coerceAtLeast(0).toDp()
    }
    val aromaCardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TastingPhase.values().forEach { phase ->
            Text(phase.sectionLabel, style = MaterialTheme.typography.titleSmall)
            when (phase) {
                TastingPhase.AROMA_BEFORE_TASTING -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                if (colorFieldWidthPx > 0 && aromaHeaderHeightPx > 0) {
                                    val stepX = colorFieldWidthPx.toFloat() + swatchCardInset.toPx()
                                    val stepY = aromaSummaryStartPx.toFloat()
                                    if (stepX < size.width && stepY <= size.height) {
                                        if (selectedAromaIds.isEmpty()) {
                                            val headerWidth = size.width - stepX
                                            val radius = minOf(
                                                16.dp.toPx(),
                                                headerWidth / 2f,
                                                stepY / 2f
                                            )
                                            drawRoundRect(
                                                color = aromaCardColor,
                                                topLeft = Offset(stepX, 0f),
                                                size = Size(headerWidth, stepY),
                                                cornerRadius = CornerRadius(radius)
                                            )
                                        } else {
                                            drawPath(
                                                path = roundedSteppedCardPath(
                                                    width = size.width,
                                                    height = size.height,
                                                    stepX = stepX,
                                                    stepY = stepY,
                                                    radius = 16.dp.toPx()
                                                ),
                                                color = aromaCardColor
                                            )
                                        }
                                    }
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { aromaHeaderHeightPx = it.height },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            NoteColorField(
                                definitionColor = definitionColor,
                                noteColor = normalized.colorHsv,
                                onNoteColorChange = { onDataChange(normalized.copy(colorHsv = it)) },
                                modifier = Modifier.onSizeChanged {
                                    colorFieldWidthPx = it.width
                                    colorFieldHeightPx = it.height
                                },
                                readOnly = readOnly,
                                inline = true
                            )
                            AromaWheelLauncherHeader(
                                selectedIds = selectedAromaIds,
                                onClick = onOpenWheel,
                                enabled = !readOnly,
                                compact = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (selectedAromaIds.isNotEmpty()) {
                            Spacer(Modifier.height(aromaSummaryGap))
                        }
                        if (normalized.aromaObservations.isNotEmpty()) {
                            // The single listing of selected aromas in the
                            // note: resizable/removable chips under the
                            // aroma wheel (not repeated per phase below).
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                AromaObservationCloud(
                                    observations = normalized.aromaObservations,
                                    onIntensityChange = { id, phaseValue, intensity ->
                                        onDataChange(setAromaIntensity(normalized, id, phaseValue, intensity))
                                    },
                                    onRemove = { id, phaseValue ->
                                        val before = normalized
                                        onDataChange(removeAromaObservation(normalized, id, phaseValue))
                                        onAromaRemoved?.invoke(
                                            com.n8rtz.winetracker.aromawheel.AromaTaxonomy.describe(id).label
                                        ) { onDataChange(before) }
                                    },
                                    readOnly = readOnly
                                )
                            }
                        } else {
                            AromaWheelSummaryPanel(
                                selectedIds = selectedAromaIds,
                                onClick = onOpenWheel,
                                enabled = !readOnly,
                                compact = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
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
            // Aromas are listed only once, with the aroma wheel above;
            // the per-phase sections below carry just their sliders.
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun roundedSteppedCardPath(
    width: Float,
    height: Float,
    stepX: Float,
    stepY: Float,
    radius: Float
): Path {
    val x = stepX.coerceIn(0f, width)
    val y = stepY.coerceIn(0f, height)
    val cornerRadius = minOf(
        radius,
        x / 2f,
        (width - x) / 2f,
        y / 2f,
        (height - y) / 2f
    )
    val corners = listOf(
        Offset(x, 0f),
        Offset(width, 0f),
        Offset(width, height),
        Offset(0f, height),
        Offset(0f, y),
        Offset(x, y)
    )
    val path = Path()
    corners.forEachIndexed { index, corner ->
        val previous = corners[(index - 1 + corners.size) % corners.size]
        val next = corners[(index + 1) % corners.size]
        val incomingX = previous.x - corner.x
        val incomingY = previous.y - corner.y
        val outgoingX = next.x - corner.x
        val outgoingY = next.y - corner.y
        val incomingLength = sqrt(incomingX * incomingX + incomingY * incomingY)
        val outgoingLength = sqrt(outgoingX * outgoingX + outgoingY * outgoingY)
        val before = Offset(
            corner.x + incomingX / incomingLength * cornerRadius,
            corner.y + incomingY / incomingLength * cornerRadius
        )
        val after = Offset(
            corner.x + outgoingX / outgoingLength * cornerRadius,
            corner.y + outgoingY / outgoingLength * cornerRadius
        )
        if (index == 0) path.moveTo(before.x, before.y) else path.lineTo(before.x, before.y)
        path.quadraticTo(corner.x, corner.y, after.x, after.y)
    }
    path.close()
    return path
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
