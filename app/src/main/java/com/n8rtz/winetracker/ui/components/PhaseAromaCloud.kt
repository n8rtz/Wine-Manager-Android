@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
    modifier: Modifier = Modifier,
    // Delete affordances are hidden by default: a long-press on any chip
    // enters delete mode (every chip shows its X) until the host reports
    // a tap elsewhere via [chipDeleteMode].
    chipDeleteMode: Boolean? = null,
    onChipDeleteModeChange: (Boolean) -> Unit = {}
) {
    var internalDeleteMode by remember { mutableStateOf(false) }
    val deleteMode = chipDeleteMode ?: internalDeleteMode
    fun setDeleteMode(value: Boolean) {
        internalDeleteMode = value
        onChipDeleteModeChange(value)
    }
    // An aroma picked for more than one phase is listed only once; the
    // list is intensity-sorted, so the kept chip is its largest instance.
    val sortedObservations = sortAromaObservationsByIntensity(observations)
        .distinctBy { it.descriptorId }
    val currentObservations = rememberUpdatedState(sortedObservations)
    val currentOnIntensityChange = rememberUpdatedState(onIntensityChange)
    val currentOnRemove = rememberUpdatedState(onRemove)

    // Chip bounds within this cloud, used to work out which chip sits
    // between the user's two fingers during a pinch.
    val chipBounds = remember { mutableStateMapOf<String, Rect>() }
    fun chipKey(observation: AromaObservation) = "${observation.phase.name}:${observation.descriptorId}"

    // While a pinch is in progress the chip order is frozen: the target
    // chip grows/shrinks in place and only re-sorts once the user lifts
    // their fingers, so chips never jump around mid-gesture.
    var pinchTargetKey by remember { mutableStateOf<String?>(null) }
    var frozenOrder by remember { mutableStateOf<List<AromaObservation>?>(null) }
    val displayedObservations = frozenOrder?.let { frozen ->
        val liveByKey = sortedObservations.associateBy { chipKey(it) }
        val frozenLive = frozen.mapNotNull { liveByKey[chipKey(it)] }
        val frozenKeys = frozen.map { chipKey(it) }.toSet()
        frozenLive + sortedObservations.filter { chipKey(it) !in frozenKeys }
    } ?: sortedObservations

    val pinchModifier = if (!allowIntensityAdjustment) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        // The chip to resize is the one between the two
                        // fingers: the chip under their midpoint, or the
                        // nearest chip to it. The fingers themselves do
                        // not need to be touching the chip.
                        if (pinchTargetKey == null) {
                            val centroid = Offset(
                                pressed.map { it.position.x }.average().toFloat(),
                                pressed.map { it.position.y }.average().toFloat()
                            )
                            val liveKeys = currentObservations.value.map { chipKey(it) }.toSet()
                            val liveBounds = chipBounds.entries.filter { it.key in liveKeys }
                            val target = liveBounds
                                .firstOrNull { it.value.contains(centroid) }
                                ?: liveBounds.minByOrNull { entry ->
                                    val rect = entry.value
                                    val dx = when {
                                        centroid.x < rect.left -> rect.left - centroid.x
                                        centroid.x > rect.right -> centroid.x - rect.right
                                        else -> 0f
                                    }
                                    val dy = when {
                                        centroid.y < rect.top -> rect.top - centroid.y
                                        centroid.y > rect.bottom -> centroid.y - rect.bottom
                                        else -> 0f
                                    }
                                    dx * dx + dy * dy
                                }
                            pinchTargetKey = target?.key
                            frozenOrder = currentObservations.value
                        }
                        val targetKey = pinchTargetKey
                        val zoom = event.calculateZoom()
                        if (targetKey != null && zoom != 1f) {
                            currentObservations.value
                                .firstOrNull { chipKey(it) == targetKey }
                                ?.let { target ->
                                    currentOnIntensityChange.value(
                                        target.descriptorId,
                                        target.phase,
                                        intensityAfterPinch(target.intensity, zoom)
                                    )
                                }
                        }
                        event.changes.forEach { it.consume() }
                    } else if (pressed.isEmpty()) {
                        // Gesture finished: unfreeze so chips re-sort by
                        // their final sizes.
                        pinchTargetKey = null
                        frozenOrder = null
                    }
                }
            }
        }
    }

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .then(pinchModifier),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        displayedObservations.forEach { observation ->
            key(observation.descriptorId, observation.phase, observation.displayOrder) {
                val info = AromaTaxonomy.describe(observation.descriptorId)
                val fontSize = aromaFontSize(observation.intensity)

                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(AromaWheelTheme.groupSelectedColor(info.groupId)),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, Color(AromaWheelTheme.groupSelectedColor(info.groupId))),
                    modifier = Modifier
                        .then(
                            if (readOnly) {
                                Modifier
                            } else {
                                Modifier.pointerInput(observation.descriptorId) {
                                    detectTapGestures(
                                        onLongPress = { setDeleteMode(true) }
                                    )
                                }
                            }
                        )
                        .onGloballyPositioned { coordinates ->
                            val origin = coordinates.positionInParent()
                            chipBounds[chipKey(observation)] = Rect(
                                origin.x, origin.y,
                                origin.x + coordinates.size.width,
                                origin.y + coordinates.size.height
                            )
                        }
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
                        if (!readOnly && deleteMode) {
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
