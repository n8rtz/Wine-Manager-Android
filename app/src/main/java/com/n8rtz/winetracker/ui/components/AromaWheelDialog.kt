@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.n8rtz.winetracker.aromawheel.AromaSelection
import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import com.n8rtz.winetracker.aromawheel.WheelLayout
import com.n8rtz.winetracker.aromawheel.AromaWheel
import com.n8rtz.winetracker.aromawheel.AromaWheelTheme
import com.n8rtz.winetracker.aromawheel.MAX_SELECTED_DESCRIPTORS
import com.n8rtz.winetracker.aromawheel.TaxNode
import kotlinx.coroutines.delay
import com.n8rtz.winetracker.data.local.AromaEditException
import com.n8rtz.winetracker.data.local.AromaEditReason
import com.n8rtz.winetracker.data.local.AromaObservation
import com.n8rtz.winetracker.data.local.SensoryData
import com.n8rtz.winetracker.data.local.TastingPhase
import com.n8rtz.winetracker.data.local.moveAromaObservation
import com.n8rtz.winetracker.data.local.normalizeSensoryData
import com.n8rtz.winetracker.data.local.toFlatAromaIds
import com.n8rtz.winetracker.data.local.toggleAromaObservation

/** Header card for the live tasting-note launcher. */
@Composable
fun AromaWheelLauncherHeader(
    selectedIds: List<String>,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val glyphSize = if (compact) 36.dp else 44.dp
    val titleStyle = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
    val arrowSize = if (compact) 20.dp else 24.dp

    Box(
        modifier = modifier
            .clickable(enabled = enabled, onClickLabel = "Open the aroma wheel", onClick = onClick)
            .padding(
                start = if (compact) 8.dp else 14.dp,
                end = if (compact) 8.dp else 8.dp,
                top = if (compact) 8.dp else 10.dp,
                bottom = if (compact) 8.dp else 10.dp
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AromaWheelGlyph(
                selectedIds = selectedIds.toSet(),
                modifier = Modifier
                    .size(glyphSize)
                    .semantics { contentDescription = "Aroma wheel selection overview" }
            )
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Text(
                "Aroma wheel",
                style = titleStyle,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(arrowSize)
            )
        }
    }
}

/** Full-width selected-aroma panel, which can sit below the narrower launcher header. */
@Composable
fun AromaWheelSummaryPanel(
    selectedIds: List<String>,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (selectedIds.isEmpty()) return

    Box(
        modifier = modifier
            .clickable(enabled = enabled, onClickLabel = "Open the aroma wheel", onClick = onClick)
            .padding(
                start = if (compact) 8.dp else 14.dp,
                end = if (compact) 8.dp else 8.dp,
                top = 8.dp,
                bottom = if (compact) 8.dp else 10.dp
            )
    ) {
        AromaSummaryChipRow(
            ids = selectedIds,
            modifier = Modifier.fillMaxWidth(),
            compact = compact
        )
    }
}

/** Convenience card for places where the header and summary share one width. */
@Composable
fun AromaWheelLauncherCard(
    selectedIds: List<String>,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        AromaWheelLauncherHeader(
            selectedIds = selectedIds,
            onClick = onClick,
            enabled = enabled,
            compact = compact,
            modifier = Modifier.fillMaxWidth()
        )
        AromaWheelSummaryPanel(
            selectedIds = selectedIds,
            onClick = onClick,
            enabled = enabled,
            compact = compact,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AromaSummaryChipRow(
    ids: List<String>,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ids.distinct().forEach { id ->
            key(id) {
                val info = AromaTaxonomy.describe(id)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(AromaWheelTheme.groupSelectedColor(info.groupId)),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, Color(AromaWheelTheme.groupSelectedColor(info.groupId)))
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (compact) 8.dp else 10.dp,
                            vertical = 5.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(AromaWheelTheme.groupColor(info.groupId)))
                        )
                        Text(
                            info.label,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Drag positions are in the wrapping container's coordinates, not a single row. */
private class AromaChipDragState {
    var id by mutableStateOf<String?>(null)
    var position by mutableStateOf(Offset.Zero)
    val bounds = mutableStateMapOf<String, Rect>()

    fun end() {
        id = null
        position = Offset.Zero
    }
}

/** Wrapping removable chips; long-press and drag across rows to reorder. */
@Composable
fun ReorderableAromaChipRow(
    ids: List<String>,
    onReorder: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val drag = remember { AromaChipDragState() }
    val currentIds by rememberUpdatedState(ids)
    val currentOnReorder by rememberUpdatedState(onReorder)
    FlowRow(
        modifier = modifier.semantics {
            contentDescription =
                "Selected aromas. Long-press a chip and drag to arrange them by prevalence."
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ids.forEach { id ->
            key(id) {
                val info = AromaTaxonomy.describe(id)
                val dragging = drag.id == id
                val offset = if (dragging) {
                    drag.position - (drag.bounds[id]?.center ?: drag.position)
                } else Offset.Zero
                AromaChipPill(
                    label = info.label,
                    groupColor = Color(AromaWheelTheme.groupColor(info.groupId)),
                    groupSelected = Color(AromaWheelTheme.groupSelectedColor(info.groupId)),
                    dragging = dragging,
                    dragOffsetPx = offset.x,
                    dragOffsetYPx = offset.y,
                    onRemove = { onRemove(id) },
                    modifier = Modifier
                        .onGloballyPositioned { coordinates ->
                            val origin = coordinates.positionInParent()
                            drag.bounds[id] = Rect(
                                origin.x, origin.y,
                                origin.x + coordinates.size.width,
                                origin.y + coordinates.size.height
                            )
                        }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    drag.id = id
                                    drag.position = drag.bounds[id]?.center ?: Offset.Zero
                                    Log.d("WineTracker", "Started wrapping aroma chip drag: $id")
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (drag.id != id) return@detectDragGesturesAfterLongPress
                                    drag.position += amount
                                    val targetId = currentIds.firstOrNull { candidate ->
                                        candidate != id && drag.bounds[candidate]?.contains(drag.position) == true
                                    }
                                    val from = currentIds.indexOf(id)
                                    val target = currentIds.indexOf(targetId)
                                    if (from >= 0 && target >= 0) {
                                        currentOnReorder(currentIds.toMutableList().apply {
                                            add(target, removeAt(from))
                                        })
                                        Log.d("WineTracker", "Reordered aroma chip $from -> $target")
                                    }
                                },
                                onDragEnd = { drag.end() },
                                onDragCancel = { drag.end() }
                            )
                        }
                )
            }
        }
    }
}

/**
 * One aroma pill: group-coloured dot, label, and a ✕ remove affordance.
 * While [dragging], it lifts (scale + shadow) and follows [dragOffsetPx].
 */
@Composable
private fun AromaChipPill(
    label: String,
    groupColor: Color,
    groupSelected: Color = Color.Unspecified,
    dragging: Boolean,
    dragOffsetPx: Float,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    dragOffsetYPx: Float = 0f,
    showRemove: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (groupSelected == Color.Unspecified) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f) else groupSelected,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (groupSelected == Color.Unspecified) groupColor else groupSelected),
        shadowElevation = if (dragging) 6.dp else 2.dp,
        modifier = modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationX = dragOffsetPx
                translationY = dragOffsetYPx
                val lift = if (dragging) 1.05f else 1f
                scaleX = lift
                scaleY = lift
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(groupColor)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp)
            )
            if (showRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onRemove)
                        .padding(3.dp)
                        .size(14.dp)
                )
            }
        }
    }
}

/**
 * Miniature aroma wheel: one arc per group in the theme's real group colours.
 * Groups containing selected descriptors are fully saturated; the rest stay
 * neutral, so the glyph previews at a glance which parts of the wheel are
 * already tagged. A centre dot finishes the wheel look.
 */
@Composable
fun AromaWheelGlyph(
    selectedIds: Set<String>,
    modifier: Modifier = Modifier
) {
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val strokeWidth = r * 0.34f
        val midRadius = r * 0.78f
        val insetBy = (r - midRadius - strokeWidth / 2f).coerceAtLeast(0f)
        inset(insetBy, insetBy, insetBy, insetBy) {
            AromaTaxonomy.groups.forEach { group ->
                val span = WheelLayout.spanOf(group.id)
                val active = AromaTaxonomy.countSelectedUnder(group, selectedIds) > 0
                drawArc(
                    color = if (active) {
                        Color(AromaWheelTheme.groupColor(group.id))
                    } else {
                        inactive
                    },
                    startAngle = span.a0 - 90f + 0.8f,
                    sweepAngle = (span.sweep - 1.6f).coerceAtLeast(0.5f),
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
            }
        }
        drawCircle(
            color = Color(AromaWheelTheme.centerRed),
            radius = r * 0.13f
        )
    }
}

/**
 * "Aroma descriptors" entry point shown in the tasting-note editor's sensory
 * section: a removable-chip summary of the current selection plus a button that
 * opens the full [AromaWheelDialog].
 */
@Composable
fun AromaDescriptorSection(
    selectedIds: List<String>,
    onChange: (List<String>) -> Unit
) {
    var showWheel by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Aroma descriptors",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (selectedIds.isNotEmpty()) {
                Text(
                    "${selectedIds.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { onChange(emptyList()) },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Clear")
                }
            }
            TextButton(onClick = { showWheel = true }) {
                Icon(
                    Icons.Default.DonutLarge,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Browse wheel")
            }
        }
        if (selectedIds.isEmpty()) {
            Text(
                "Tag aromas such as cherry, vanilla, or citrus from the aroma wheel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                selectedIds.forEach { id ->
                    val info = AromaTaxonomy.describe(id)
                    FilterChip(
                        selected = true,
                        onClick = { onChange(AromaSelection.toggle(selectedIds, id).ids) },
                        label = { Text(info.label) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(AromaWheelTheme.groupColor(info.groupId)))
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove ${info.label}",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
    if (showWheel) {
        AromaWheelDialog(
            initiallySelectedIds = selectedIds,
            onConfirm = {
                onChange(it)
                showWheel = false
            },
            onDismiss = { showWheel = false }
        )
    }
}

/**
 * Full-screen aroma wheel: the zoomable concentric descriptor wheel
 * ([AromaWheel], pinch to zoom / drag to pan / tap a descriptor to toggle it)
 * paired with an equivalent list + search mode over the same taxonomy and
 * selection state, as required for TalkBack users and small screens.
 */
/** Shown when backing out of an aroma wheel with unsaved changes. */
@Composable
private fun UnsavedAromaChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onStay: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("Unsaved changes") },
        text = { Text("You have unsaved changes to the aroma wheel. Save them before leaving?") },
        confirmButton = {
            TextButton(onClick = onSave) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Don't save") }
                TextButton(onClick = onStay) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun AromaWheelDialog(
    initiallySelectedIds: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember {
        mutableStateListOf<String>().apply { addAll(initiallySelectedIds) }
    }
    var showListMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var notice by remember { mutableStateOf<String?>(null) }
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    var removedChipLabel by remember { mutableStateOf<String?>(null) }
    var removedChipSnapshot by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(removedChipSnapshot) {
        if (removedChipSnapshot != null) {
            delay(4000)
            removedChipSnapshot = null
            removedChipLabel = null
        }
    }
    val hasUnsavedChanges = selected.toSet() != initiallySelectedIds.toSet()
    fun requestDismiss() {
        if (hasUnsavedChanges) showUnsavedChangesDialog = true else onDismiss()
    }
    BackHandler { requestDismiss() }

    fun applyToggle(id: String) {
        val result = AromaSelection.toggle(selected.toList(), id)
        if (result.changed) {
            selected.clear()
            selected.addAll(result.ids)
            Log.d("WineTracker", "Aroma wheel: ${result.ids.size} descriptors selected")
        } else {
            notice = when (result.reason) {
                AromaSelection.Reason.LIMIT ->
                    "Limit of $MAX_SELECTED_DESCRIPTORS descriptors reached"
                AromaSelection.Reason.UNKNOWN ->
                    "That descriptor is no longer in the aroma wheel"
                else -> null
            }
        }
    }

    fun removeChipWithUndo(id: String) {
        val snapshot = selected.toList()
        applyToggle(id)
        if (id !in selected) {
            removedChipSnapshot = snapshot
            removedChipLabel = AromaTaxonomy.describe(id).label
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2600)
            notice = null
        }
    }

    if (showUnsavedChangesDialog) {
        UnsavedAromaChangesDialog(
            onSave = {
                showUnsavedChangesDialog = false
                onConfirm(selected.toList())
            },
            onDiscard = {
                showUnsavedChangesDialog = false
                onDismiss()
            },
            onStay = { showUnsavedChangesDialog = false }
        )
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::requestDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Aroma wheel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${selected.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showListMode = !showListMode },
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (showListMode) "Switch to wheel view" else "Switch to list view"
                        }
                    ) {
                        Icon(
                            if (showListMode) Icons.Default.DonutLarge else Icons.AutoMirrored.Filled.List,
                            contentDescription = null
                        )
                    }
                    TextButton(onClick = { onConfirm(selected.toList()) }) {
                        Text("Done")
                    }
                }

                if (notice != null) {
                    Text(
                        notice!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                Crossfade(targetState = showListMode, label = "AromaWheelMode") { listMode ->
                    if (listMode) {
                        AromaListContent(
                            selectedIds = selected.toList(),
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            expandedGroups = expandedGroups,
                            onToggleLeaf = { id -> applyToggle(id) }
                        )
                    } else {
                        // Read the list contents directly (not as a remember key): SnapshotStateList uses
                        // identity equals, so remember(selected) would cache the initial set forever and
                        // the wheel would never redraw its selection. Reading it here subscribes this
                        // scope to list changes, so a new Set is produced on every selection change.
                        val selectedSet: Set<String> = selected.toSet()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            AromaWheel(
                                selectedIds = selectedSet,
                                onToggle = { id -> applyToggle(id) },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (selected.isNotEmpty()) {
                                SelectedAromasOverlay(
                                    selectedIds = selected.toList(),
                                    onRemove = { id -> removeChipWithUndo(id) },
                                    onClear = { selected.clear() },
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
                if (removedChipSnapshot != null && removedChipLabel != null) {
                    AromaRemovalUndoBar(
                        removedLabel = removedChipLabel!!,
                        onUndo = {
                            selected.clear()
                            selected.addAll(removedChipSnapshot!!)
                            removedChipSnapshot = null
                            removedChipLabel = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedAromasOverlay(
    selectedIds: List<String>,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    // Phase reassignment (phase wheel dialog only): long-press a chip and
    // drag it up to a phase chip; the drop reports the pointer's root
    // coordinates so the caller can resolve which phase it landed on.
    onDropChipAt: ((String, Offset) -> Unit)? = null,
    onChipDragChange: ((String?, Offset) -> Unit)? = null,
    locked: Boolean = false
) {
    val orderedIds = selectedIds
    var draggingChipId by remember { mutableStateOf<String?>(null) }
    var chipDragOffset by remember { mutableStateOf(Offset.Zero) }
    var chipDragPointerRoot by remember { mutableStateOf(Offset.Zero) }
    val chipBoundsRoot = remember { mutableStateMapOf<String, Rect>() }
    val chipDragModifier: (String) -> Modifier = { id ->
        if (onDropChipAt == null) {
            Modifier
        } else {
            Modifier
                .onGloballyPositioned { coordinates ->
                    val origin = coordinates.positionInRoot()
                    chipBoundsRoot[id] = Rect(
                        origin.x, origin.y,
                        origin.x + coordinates.size.width,
                        origin.y + coordinates.size.height
                    )
                }
                .pointerInput(id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            draggingChipId = id
                            chipDragOffset = Offset.Zero
                            chipDragPointerRoot =
                                (chipBoundsRoot[id]?.topLeft ?: Offset.Zero) + start
                            onChipDragChange?.invoke(id, chipDragPointerRoot)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (draggingChipId != id) return@detectDragGesturesAfterLongPress
                            chipDragOffset += amount
                            chipDragPointerRoot += amount
                            onChipDragChange?.invoke(id, chipDragPointerRoot)
                        },
                        onDragEnd = {
                            if (draggingChipId == id) {
                                onDropChipAt(id, chipDragPointerRoot)
                            }
                            draggingChipId = null
                            chipDragOffset = Offset.Zero
                            onChipDragChange?.invoke(null, chipDragPointerRoot)
                        },
                        onDragCancel = {
                            draggingChipId = null
                            chipDragOffset = Offset.Zero
                            onChipDragChange?.invoke(null, chipDragPointerRoot)
                        }
                    )
                }
        }
    }
    // Collapsed by default to a single line -- the first two chips plus a
    // "+N" chip -- so a big selection never covers the wheel. Tapping "+N"
    // expands the full wrapping list (capped and scrollable); tapping
    // "Show less" collapses it again.
    var expanded by remember { mutableStateOf(false) }
    val showExpanded = expanded && orderedIds.size > 2
    if (!showExpanded && orderedIds.size > 2) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            orderedIds.take(2).forEach { id ->
                val info = AromaTaxonomy.describe(id)
                val dragging = draggingChipId == id
                AromaChipPill(
                    label = info.label,
                    groupColor = Color(AromaWheelTheme.groupColor(info.groupId)),
                    groupSelected = Color(AromaWheelTheme.groupSelectedColor(info.groupId)),
                    dragging = dragging,
                    dragOffsetPx = if (dragging) chipDragOffset.x else 0f,
                    dragOffsetYPx = if (dragging) chipDragOffset.y else 0f,
                    onRemove = { onRemove(id) },
                    showRemove = !locked,
                    modifier = Modifier.weight(1f, fill = false).then(chipDragModifier(id))
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 2.dp,
                modifier = Modifier.clickable { expanded = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "+${orderedIds.size - 2}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = "Show all selected aromas",
                        modifier = Modifier.padding(start = 2.dp).size(16.dp)
                    )
                }
            }
        }
        return
    }
    // Expanded: every chip is shown at once, however much of the wheel it
    // covers -- no height cap and no scrolling.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        orderedIds.forEach { id ->
            val info = AromaTaxonomy.describe(id)
            val dragging = draggingChipId == id
            AromaChipPill(
                label = info.label,
                groupColor = Color(AromaWheelTheme.groupColor(info.groupId)),
                groupSelected = Color(AromaWheelTheme.groupSelectedColor(info.groupId)),
                dragging = dragging,
                dragOffsetPx = if (dragging) chipDragOffset.x else 0f,
                dragOffsetYPx = if (dragging) chipDragOffset.y else 0f,
                onRemove = { onRemove(id) },
                showRemove = !locked,
                modifier = chipDragModifier(id)
            )
        }
        if (!locked) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.primary,
                shadowElevation = 2.dp,
                modifier = Modifier.clickable(onClick = onClear)
            ) {
                Text(
                    "Clear all",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
        if (orderedIds.size > 2) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.primary,
                shadowElevation = 2.dp,
                modifier = Modifier.clickable { expanded = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Show less",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(
                        Icons.Default.ExpandLess,
                        contentDescription = "Collapse selected aromas",
                        modifier = Modifier.padding(start = 2.dp).size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AromaListContent(
    selectedIds: List<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    expandedGroups: MutableMap<String, Boolean>,
    onToggleLeaf: (String) -> Unit
) {
    val selectedSet = selectedIds.toSet()
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search", maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        val trimmedQuery = query.trim()
        val hits = remember(trimmedQuery) { AromaTaxonomy.search(trimmedQuery) }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (trimmedQuery.isEmpty()) {
                AromaTaxonomy.families.forEach { family ->
                    val familySelected =
                        AromaTaxonomy.countSelectedUnder(family, selectedSet)
                    item(key = "${family.id}-header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Color(
                                            AromaWheelTheme.groupColor(
                                                (family.children.firstOrNull() as? TaxNode)?.id
                                            )
                                        )
                                    )
                            )
                            Text(
                                family.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp)
                            )
                            if (familySelected > 0) {
                                Text(
                                    "$familySelected selected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    family.children.forEach { group ->
                        val groupSelected =
                            AromaTaxonomy.countSelectedUnder(group, selectedSet)
                        val isOpen = expandedGroups[group.id]
                            ?: (groupSelected > 0)
                        item(key = "${group.id}-header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .clickable { expandedGroups[group.id] = !isOpen }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isOpen) {
                                        Icons.Default.ExpandLess
                                    } else {
                                        Icons.Default.ExpandMore
                                    },
                                    contentDescription = if (isOpen) {
                                        "Collapse ${group.label}"
                                    } else {
                                        "Expand ${group.label}"
                                    }
                                )
                                Text(
                                    group.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                )
                                if (groupSelected > 0) {
                                    Text(
                                        "$groupSelected",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (isOpen) {
                            items(group.children, key = { it.id }) { leaf ->
                                AromaListLeafRow(
                                    leaf = leaf,
                                    checked = leaf.id in selectedSet,
                                    onToggle = { onToggleLeaf(leaf.id) }
                                )
                            }
                        }
                    }
                }
            } else {
                if (hits.isEmpty()) {
                    item(key = "no-results") {
                        Text(
                            "No aromas match \"$trimmedQuery\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(hits, key = { it.leaf.id }) { hit ->
                        AromaListLeafRow(
                            leaf = hit.leaf,
                            checked = hit.leaf.id in selectedSet,
                            onToggle = { onToggleLeaf(hit.leaf.id) },
                            pathLabels = hit.pathLabels
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AromaListLeafRow(
    leaf: TaxNode,
    checked: Boolean,
    onToggle: () -> Unit,
    pathLabels: List<String>? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable { onToggle() }
            .padding(start = 32.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() }
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    Color(
                        AromaWheelTheme.groupColor(AromaTaxonomy.describe(leaf.id).groupId)
                    )
                )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Text(
                leaf.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (pathLabels != null && pathLabels.isNotEmpty()) {
                Text(
                    pathLabels.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PhaseAromaWheelDialog(
    initialObservations: List<AromaObservation>,
    initialPhase: TastingPhase,
    onConfirm: (TastingPhase, List<AromaObservation>) -> Unit,
    onDismiss: () -> Unit,
    readOnly: Boolean = false
) {
    val observations = remember {
        mutableStateListOf<AromaObservation>().apply {
            addAll(
                normalizeSensoryData(SensoryData(aromaObservations = initialObservations))
                    .aromaObservations
            )
        }
    }
    var activePhase by remember { mutableStateOf(initialPhase) }
    var showListMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var notice by remember { mutableStateOf<String?>(null) }
    var removedChipLabel by remember { mutableStateOf<String?>(null) }
    var removedChipSnapshot by remember { mutableStateOf<List<AromaObservation>?>(null) }
    LaunchedEffect(removedChipSnapshot) {
        if (removedChipSnapshot != null) {
            delay(4000)
            removedChipSnapshot = null
            removedChipLabel = null
        }
    }
    val initialObservationSnapshot = remember { observations.toList() }
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    val hasUnsavedChanges = observations.toList() != initialObservationSnapshot
    fun requestDismiss() {
        if (!readOnly && hasUnsavedChanges) showUnsavedChangesDialog = true else onDismiss()
    }
    BackHandler { requestDismiss() }

    fun applyToggle(id: String) {
        if (readOnly) return
        val result = toggleAromaObservation(
            SensoryData(aromaObservations = observations.toList()),
            id,
            activePhase
        )
        result.onSuccess { updated ->
            observations.clear()
            observations.addAll(updated.aromaObservations)
        }.onFailure { error ->
            notice = when ((error as? AromaEditException)?.reason) {
                AromaEditReason.LIMIT -> "Limit of $MAX_SELECTED_DESCRIPTORS descriptors reached"
                AromaEditReason.DUPLICATE -> "Already selected"
                else -> null
            }
        }
    }

    fun clearActivePhase() {
        observations.removeAll { it.phase == activePhase }
    }

    fun removeChipWithUndo(id: String) {
        val snapshot = observations.toList()
        applyToggle(id)
        if (snapshot.any { it.descriptorId == id && it.phase == activePhase } &&
            observations.none { it.descriptorId == id && it.phase == activePhase }
        ) {
            removedChipSnapshot = snapshot
            removedChipLabel = AromaTaxonomy.describe(id).label
        }
    }

    // Dragging a selected chip up to the phase bar reassigns it. The phase
    // chips register their root bounds; the hovered one highlights mid-drag.
    val phaseChipBounds = remember { mutableStateMapOf<TastingPhase, Rect>() }
    var chipDragId by remember { mutableStateOf<String?>(null) }
    var chipDragPointerRoot by remember { mutableStateOf(Offset.Zero) }
    val hoveredPhase = if (chipDragId != null) {
        phaseChipBounds.entries.firstOrNull { it.value.contains(chipDragPointerRoot) }?.key
    } else {
        null
    }

    fun moveChipToPhaseAt(id: String, pointerRoot: Offset) {
        if (readOnly) return
        val target = phaseChipBounds.entries
            .firstOrNull { it.value.contains(pointerRoot) }?.key ?: return
        if (target == activePhase) return
        val result = moveAromaObservation(
            SensoryData(aromaObservations = observations.toList()),
            id,
            activePhase,
            target
        )
        result.onSuccess { updated ->
            observations.clear()
            observations.addAll(updated.aromaObservations)
        }.onFailure { error ->
            notice = when ((error as? AromaEditException)?.reason) {
                AromaEditReason.DUPLICATE -> "Already in ${target.label}"
                else -> null
            }
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2600)
            notice = null
        }
    }

    val activeIds = observations
        .filter { it.phase == activePhase }
        .sortedBy { it.displayOrder }
        .map { it.descriptorId }
    val distinctCount = toFlatAromaIds(observations).size

    if (showUnsavedChangesDialog) {
        UnsavedAromaChangesDialog(
            onSave = {
                showUnsavedChangesDialog = false
                onConfirm(activePhase, observations.toList())
            },
            onDiscard = {
                showUnsavedChangesDialog = false
                onDismiss()
            },
            onStay = { showUnsavedChangesDialog = false }
        )
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = ::requestDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Aroma wheel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (readOnly) "View only" else "$distinctCount/$MAX_SELECTED_DESCRIPTORS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showListMode = !showListMode },
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (showListMode) "Switch to wheel view" else "Switch to list view"
                        }
                    ) {
                        Icon(
                            if (showListMode) Icons.Default.DonutLarge else Icons.AutoMirrored.Filled.List,
                            contentDescription = null
                        )
                    }
                    if (!readOnly) {
                        TextButton(onClick = { onConfirm(activePhase, observations.toList()) }) {
                            Text("Done")
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TastingPhase.values().forEach { phase ->
                        FilterChip(
                            selected = activePhase == phase || hoveredPhase == phase,
                            onClick = { activePhase = phase },
                            label = { Text(phase.label) },
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                val origin = coordinates.positionInRoot()
                                phaseChipBounds[phase] = Rect(
                                    origin.x, origin.y,
                                    origin.x + coordinates.size.width,
                                    origin.y + coordinates.size.height
                                )
                            }
                        )
                    }
                }

                if (notice != null) {
                    Text(
                        notice!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                Crossfade(targetState = showListMode, label = "PhaseAromaWheelMode") { listMode ->
                    if (listMode) {
                        AromaListContent(
                            selectedIds = activeIds,
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            expandedGroups = expandedGroups,
                            onToggleLeaf = { id -> applyToggle(id) }
                        )
                    } else {
                        val selectedSet: Set<String> = activeIds.toSet()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            AromaWheel(
                                selectedIds = selectedSet,
                                onToggle = { id -> applyToggle(id) },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (activeIds.isNotEmpty()) {
                                SelectedAromasOverlay(
                                    selectedIds = activeIds,
                                    onRemove = { id -> removeChipWithUndo(id) },
                                    onClear = { clearActivePhase() },
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    onDropChipAt = if (readOnly) null else { id, pointerRoot ->
                                        moveChipToPhaseAt(id, pointerRoot)
                                    },
                                    onChipDragChange = if (readOnly) null else { id, pointerRoot ->
                                        chipDragId = id
                                        chipDragPointerRoot = pointerRoot
                                    },
                                    locked = readOnly
                                )
                            }
                        }
                    }
                }
                if (removedChipSnapshot != null && removedChipLabel != null) {
                    AromaRemovalUndoBar(
                        removedLabel = removedChipLabel!!,
                        onUndo = {
                            observations.clear()
                            observations.addAll(removedChipSnapshot!!)
                            removedChipSnapshot = null
                            removedChipLabel = null
                        }
                    )
                }
            }
        }
    }
}
