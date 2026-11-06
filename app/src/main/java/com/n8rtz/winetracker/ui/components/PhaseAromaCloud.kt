@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import com.n8rtz.winetracker.aromawheel.AromaWheelTheme
import com.n8rtz.winetracker.data.local.AromaObservation
import com.n8rtz.winetracker.data.local.TastingPhase

internal fun scaleForIntensity(intensity: Float?): Float =
    (0.75f + (intensity ?: 0.5f) * 0.9f).coerceIn(0.75f, 1.65f)

@Composable
fun PhaseAromaCloud(
    phase: TastingPhase,
    observations: List<AromaObservation>,
    onIntensityChange: (String, TastingPhase, Float?) -> Unit,
    onReorder: (TastingPhase, Int, Int) -> Unit,
    onRemove: (String, TastingPhase) -> Unit,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val currentObservations by rememberUpdatedState(observations)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnIntensityChange by rememberUpdatedState(onIntensityChange)
    val currentOnRemove by rememberUpdatedState(onRemove)
    val widthAt = { index: Int ->
        val observation = currentObservations.getOrNull(index)
        if (observation == null) 1 else {
            val label = AromaTaxonomy.describe(observation.descriptorId).label
            with(density) {
                (label.length * 10f * scaleForIntensity(observation.intensity) + 48f)
                    .dp.toPx().toInt().coerceAtLeast(1)
            }
        }
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        observations.forEachIndexed { index, observation ->
            val info = AromaTaxonomy.describe(observation.descriptorId)
            val shown = observation.intensity ?: 0.5f
            val scale = scaleForIntensity(observation.intensity)
            val currentShown by rememberUpdatedState(shown)
            val currentId by rememberUpdatedState(observation.descriptorId)
            var dragX by remember(observation.descriptorId, phase) { mutableStateOf(0f) }

            val gestureModifier = if (readOnly) {
                Modifier
            } else {
                Modifier
                    .pointerInput(currentId, phase) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) {
                                currentOnIntensityChange(
                                    currentId,
                                    phase,
                                    (currentShown * zoom).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                    .pointerInput(
                        currentId,
                        phase,
                        index,
                        observations.joinToString { "${it.descriptorId}:${it.intensity}" }
                    ) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragX = 0f },
                            onDrag = { change, dragAmount ->
                                dragX += dragAmount.x
                                change.consume()
                            },
                            onDragEnd = {
                                val count = currentObservations.size
                                val target = aromaChipDragTarget(index, dragX, count) { widthAt(it) }.first
                                if (target != index) currentOnReorder(phase, index, target)
                                dragX = 0f
                            },
                            onDragCancel = { dragX = 0f }
                        )
                    }
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = Color(AromaWheelTheme.groupColor(info.groupId)).copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .then(gestureModifier)
                    .semantics { contentDescription = "${info.label} %.1f".format(shown) }
            ) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!readOnly) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .semantics { contentDescription = "Reorder ${info.label}" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("≡", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        info.label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(92.dp).padding(horizontal = 4.dp)
                    )
                    if (readOnly) {
                        Text("%.1f".format(shown), style = MaterialTheme.typography.labelSmall)
                    } else {
                        IconButton(
                            onClick = {
                                currentOnIntensityChange(currentId, phase, (currentShown - 0.1f).coerceIn(0f, 1f))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Less", modifier = Modifier.size(14.dp))
                        }
                        Text("%.1f".format(shown), style = MaterialTheme.typography.labelSmall)
                        IconButton(
                            onClick = {
                                currentOnIntensityChange(currentId, phase, (currentShown + 0.1f).coerceIn(0f, 1f))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "More", modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = { currentOnRemove(currentId, phase) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}
