@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import com.n8rtz.winetracker.aromawheel.AromaWheelTheme
import com.n8rtz.winetracker.data.local.AromaObservation
import com.n8rtz.winetracker.data.local.TastingPhase
import com.n8rtz.winetracker.data.local.intensityAfterPinch
import com.n8rtz.winetracker.data.local.sortAromaObservationsByIntensity
import com.n8rtz.winetracker.data.local.visualAromaIntensity

/**
 * Chip text spans 10sp (intensity 0) to 32sp (intensity 1); the default
 * intensity of 0.5 renders at 21sp, so chips can shrink noticeably below
 * the default size as well as grow above it.
 */
private fun aromaFontSize(intensity: Float?): androidx.compose.ui.unit.TextUnit =
    (10f + visualAromaIntensity(intensity) * 22f).sp

/**
 * Bottom popup shown after an aroma chip is deleted, offering Undo.
 * Shared by the aroma wheel dialogs and the tasting-note editor.
 */
@Composable
fun AromaRemovalUndoBar(
    removedLabel: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Removed $removedLabel",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUndo) {
                Text(
                    "Undo",
                    color = MaterialTheme.colorScheme.inversePrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * All selected aromas as one cloud of resizable chips, regardless of phase.
 * This is the single place aromas are listed in a tasting note (below the
 * aroma wheel); each chip still knows its own phase for resize/remove.
 */
@Composable
fun AromaObservationCloud(
    observations: List<AromaObservation>,
    onIntensityChange: (String, TastingPhase, Float?) -> Unit,
    onRemove: (String, TastingPhase) -> Unit,
    readOnly: Boolean = false,
    allowIntensityAdjustment: Boolean = !readOnly,
    modifier: Modifier = Modifier
) {
    val sortedObservations = sortAromaObservationsByIntensity(observations)
    val currentOnIntensityChange = rememberUpdatedState(onIntensityChange)
    val currentOnRemove = rememberUpdatedState(onRemove)

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        sortedObservations.forEach { observation ->
            key(observation.descriptorId, observation.phase, observation.displayOrder) {
                val info = AromaTaxonomy.describe(observation.descriptorId)
                val currentIntensity = rememberUpdatedState(observation.intensity)
                val fontSize = aromaFontSize(observation.intensity)
                val zoomModifier = if (!allowIntensityAdjustment) {
                    Modifier
                } else {
                    Modifier.pointerInput(observation.descriptorId, observation.phase) {
                        // Angle-independent pinch: zoom is the ratio of the
                        // distance between the two pointers (calculateZoom),
                        // so vertical, horizontal and diagonal pinches all
                        // resize equally. Two-pointer changes are consumed
                        // on the initial pass so a surrounding scroll view
                        // cannot steal a vertical pinch.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        currentOnIntensityChange.value(
                                            observation.descriptorId,
                                            observation.phase,
                                            intensityAfterPinch(currentIntensity.value, zoom)
                                        )
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(AromaWheelTheme.groupColor(info.groupId)).copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .then(zoomModifier)
                        .semantics {
                            contentDescription = if (!allowIntensityAdjustment) {
                                info.label
                            } else {
                                "${info.label}, pinch to resize"
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = info.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = fontSize),
                            fontWeight = if (visualAromaIntensity(observation.intensity) >= 0.7f) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                        if (!readOnly) {
                            IconButton(
                                onClick = { currentOnRemove.value(observation.descriptorId, observation.phase) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove ${info.label}",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhaseAromaCloud(
    phase: TastingPhase,
    observations: List<AromaObservation>,
    onIntensityChange: (String, TastingPhase, Float?) -> Unit,
    onRemove: (String, TastingPhase) -> Unit,
    readOnly: Boolean = false,
    allowIntensityAdjustment: Boolean = !readOnly,
    modifier: Modifier = Modifier
) {
    AromaObservationCloud(
        observations = observations.filter { it.phase == phase },
        onIntensityChange = onIntensityChange,
        onRemove = onRemove,
        readOnly = readOnly,
        allowIntensityAdjustment = allowIntensityAdjustment,
        modifier = modifier
    )
}
