@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private fun aromaFontSize(intensity: Float?): androidx.compose.ui.unit.TextUnit =
    (14f + visualAromaIntensity(intensity) * 14f).sp

@Composable
fun PhaseAromaCloud(
    phase: TastingPhase,
    observations: List<AromaObservation>,
    onIntensityChange: (String, TastingPhase, Float?) -> Unit,
    onRemove: (String, TastingPhase) -> Unit,
    readOnly: Boolean = false,
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
            key(observation.descriptorId, observation.displayOrder) {
                val info = AromaTaxonomy.describe(observation.descriptorId)
                val currentIntensity = rememberUpdatedState(observation.intensity)
                val fontSize = aromaFontSize(observation.intensity)
                val zoomModifier = if (readOnly) {
                    Modifier
                } else {
                    Modifier.pointerInput(observation.descriptorId, phase) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) {
                                currentOnIntensityChange.value(
                                    observation.descriptorId,
                                    phase,
                                    intensityAfterPinch(currentIntensity.value, zoom)
                                )
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
                            contentDescription = if (readOnly) {
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
                                onClick = { currentOnRemove.value(observation.descriptorId, phase) },
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
