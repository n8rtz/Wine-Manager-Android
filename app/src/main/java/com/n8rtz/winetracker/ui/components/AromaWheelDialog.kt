@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.n8rtz.winetracker.aromawheel.AromaSelection
import com.n8rtz.winetracker.aromawheel.AromaTaxonomy
import com.n8rtz.winetracker.aromawheel.WheelLayout
import com.n8rtz.winetracker.aromawheel.AromaWheel
import com.n8rtz.winetracker.aromawheel.AromaWheelTheme
import com.n8rtz.winetracker.aromawheel.MAX_SELECTED_DESCRIPTORS
import com.n8rtz.winetracker.aromawheel.TaxNode
import kotlinx.coroutines.delay

/**
 * Tasting-note launcher for the aroma wheel: a summary card with a live
 * [AromaWheelGlyph] preview (tagged groups light up in their group colour), the
 * current selection summary, and a chevron. Opens [AromaWheelDialog] via [onClick].
 */
@Composable
fun AromaWheelLauncherCard(
    selectedIds: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orderedLabels = remember(selectedIds) {
        AromaTaxonomy.sortByTaxonomy(selectedIds).map { AromaTaxonomy.describe(it).label }
    }
    val subtitle = when {
        orderedLabels.isEmpty() -> "Tag the aromas you find in the glass"
        orderedLabels.size == 1 -> orderedLabels[0]
        orderedLabels.size == 2 -> orderedLabels.joinToString(", ")
        else -> "${orderedLabels[0]}, ${orderedLabels[1]} +${orderedLabels.size - 2} more"
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClickLabel = "Open the aroma wheel", onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AromaWheelGlyph(
                selectedIds = selectedIds.toSet(),
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "Aroma wheel selection overview" }
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Aroma wheel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    val orderedIds = remember(selectedIds) { AromaTaxonomy.sortByTaxonomy(selectedIds) }
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
        if (orderedIds.isEmpty()) {
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
                orderedIds.forEach { id ->
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

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2600)
            notice = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                    IconButton(onClick = onDismiss) {
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
                                    onRemove = { id -> applyToggle(id) },
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
            }
        }
    }
}

@Composable
private fun SelectedAromasOverlay(
    selectedIds: List<String>,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orderedIds = remember(selectedIds) { AromaTaxonomy.sortByTaxonomy(selectedIds) }
    // Wraps into rows so nothing is pushed off-screen; capped at roughly three rows and
    // scrollable beyond that so a very large selection can never cover the whole wheel.
    FlowRow(
        modifier = modifier
            .heightIn(max = 148.dp)
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        orderedIds.forEach { id ->
            val info = AromaTaxonomy.describe(id)
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(AromaWheelTheme.groupColor(info.groupId)))
                    )
                    Text(
                        info.label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove ${info.label}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .clip(CircleShape)
                            .clickable { onRemove(id) }
                            .padding(3.dp)
                            .size(14.dp)
                    )
                }
            }
        }
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
            label = { Text("Search aromas") },
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
