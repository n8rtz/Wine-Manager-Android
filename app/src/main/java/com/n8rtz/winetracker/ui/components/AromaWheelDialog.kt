@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.n8rtz.winetracker.ui.components

import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List as ListIcon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.n8rtz.winetracker.data.aroma.AromaNode
import com.n8rtz.winetracker.data.aroma.AromaSelection
import com.n8rtz.winetracker.data.aroma.AromaTaxonomy

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
                    val label = AromaTaxonomy.labelFor(id) ?: id
                    FilterChip(
                        selected = true,
                        onClick = { onChange(AromaSelection.remove(selectedIds, id)) },
                        label = { Text(label) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $label",
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
 * Full-screen drill-down aroma wheel. One ring of segments is shown per level
 * (branch -> family -> descriptor); tapping a branch or family drills in, the
 * center hub navigates back up, and tapping a leaf toggles its selection.
 * A list mode exposes the identical taxonomy and selection state for TalkBack,
 * small screens, and large font scales.
 */
@Composable
fun AromaWheelDialog(
    initiallySelectedIds: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember {
        mutableStateListOf<String>().apply { addAll(initiallySelectedIds.distinct()) }
    }
    var navStack by remember { mutableStateOf(listOf<AromaNode>()) }
    var showListMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val expandedFamilies = remember { mutableStateMapOf<String, Boolean>() }

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
                            if (showListMode) Icons.Default.DonutLarge else ListIcon,
                            contentDescription = null
                        )
                    }
                    TextButton(onClick = { onConfirm(selected.toList()) }) {
                        Text("Done")
                    }
                }

                if (!showListMode) {
                    BreadcrumbBar(
                        stack = navStack,
                        onNavigate = { depth ->
                            navStack = if (depth < 0) emptyList() else navStack.take(depth + 1)
                        }
                    )
                }

                Crossfade(targetState = showListMode, label = "AromaWheelMode") { listMode ->
                    if (listMode) {
                        AromaListContent(
                            selectedIds = selected.toList(),
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            expandedFamilies = expandedFamilies,
                            onToggleLeaf = { id ->
                                replaceSelection(selected, AromaSelection.toggle(selected.toList(), id))
                            }
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Crossfade(
                                targetState = navStack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                label = "AromaWheelLevel"
                            ) { stack ->
                                WheelRing(
                                    levelNodes = stack.lastOrNull()?.children
                                        ?: AromaTaxonomy.roots,
                                    familyColorArgb = stack.lastOrNull()?.color,
                                    selectedIds = selected.toList(),
                                    canLevelUp = stack.isNotEmpty(),
                                    onDrillInto = { node ->
                                        Log.d("WineTracker", "Aroma wheel: drill into ${node.id}")
                                        navStack = stack + node
                                    },
                                    onSelectLeaf = { node ->
                                        Log.d("WineTracker", "Aroma wheel: toggle ${node.id}")
                                        replaceSelection(
                                            selected,
                                            AromaSelection.toggle(selected.toList(), node.id)
                                        )
                                    },
                                    onLevelUp = {
                                        navStack = stack.dropLast(1)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            SelectionChipsRow(
                                selectedIds = selected.toList(),
                                onRemove = { id ->
                                    replaceSelection(
                                        selected,
                                        AromaSelection.remove(selected.toList(), id)
                                    )
                                },
                                onClear = { selected.clear() }
                            )
                            Text(
                                AromaTaxonomy.SOURCE_NOTE,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun replaceSelection(target: androidx.compose.runtime.snapshots.SnapshotStateList<String>, replacement: List<String>) {
    target.clear()
    target.addAll(replacement)
}

@Composable
private fun BreadcrumbBar(
    stack: List<AromaNode>,
    onNavigate: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BreadcrumbText(
            text = "All aromas",
            active = stack.isEmpty(),
            onClick = { onNavigate(-1) }
        )
        stack.forEachIndexed { depth, node ->
            Text(
                "›",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BreadcrumbText(
                text = node.label,
                active = depth == stack.lastIndex,
                onClick = { onNavigate(depth) }
            )
        }
    }
}

@Composable
private fun BreadcrumbText(
    text: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    )
}

/** One radial level of the wheel: colored segments for [levelNodes] plus a hub. */
@Composable
private fun WheelRing(
    levelNodes: List<AromaNode>,
    familyColorArgb: Long?,
    selectedIds: List<String>,
    canLevelUp: Boolean,
    onDrillInto: (AromaNode) -> Unit,
    onSelectLeaf: (AromaNode) -> Unit,
    onLevelUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (levelNodes.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No descriptors", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val selectedSet = selectedIds.toSet()
    BoxWithConstraints(modifier) {
        val wheelDiameter = minOf(maxWidth, maxHeight)
        Box(
            modifier = Modifier
                .size(wheelDiameter)
                .align(Alignment.Center)
                .semantics {
                    contentDescription = "Aroma wheel with ${levelNodes.size} segments. " +
                        "${selectedIds.size} aromas selected. " +
                        "Use list view for accessible selection."
                }
                .pointerInput(levelNodes, selectedSet) {
                    detectTapGestures { offset ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val outerRadius = size.minDimension / 2f
                        val hubRadius = outerRadius * HUB_RADIUS_FRACTION
                        val distance = AromaWheelGeometry.radius(
                            offset.x, offset.y, centerX, centerY
                        )
                        when {
                            distance <= hubRadius -> if (canLevelUp) onLevelUp()
                            distance <= outerRadius * 1.06f -> {
                                val angle = AromaWheelGeometry.clockwiseAngleFromTopDeg(
                                    offset.x, offset.y, centerX, centerY
                                )
                                val index = AromaWheelGeometry.segmentIndex(angle, levelNodes.size)
                                if (index in levelNodes.indices) {
                                    val node = levelNodes[index]
                                    if (node.isLeaf) onSelectLeaf(node) else onDrillInto(node)
                                }
                            }
                        }
                    }
                }
        ) {
            val surfaceColor = MaterialTheme.colorScheme.surface
            Canvas(modifier = Modifier.fillMaxSize()) {
                val outerRadius = size.minDimension / 2f
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val hubRadius = outerRadius * HUB_RADIUS_FRACTION
                val count = levelNodes.size
                val sweep = 360f / count
                val gapDegrees = if (count > 1) 1.4f else 0f
                levelNodes.forEachIndexed { index, node ->
                    val baseArgb = baseArgbFor(node, familyColorArgb)
                    val isLeaf = node.isLeaf
                    val isSelected = isLeaf && node.id in selectedSet
                    val fillArgb = when {
                        isSelected -> AromaWheelGeometry.darkenArgb(baseArgb, 0.10f)
                        isLeaf -> AromaWheelGeometry.shadeArgbAt(baseArgb, index)
                        else -> baseArgb
                    }
                    val start = AromaWheelGeometry.canvasStartAngleDeg(index * sweep) +
                        gapDegrees / 2f
                    val sweepAngle = (sweep - gapDegrees).coerceAtLeast(0.5f)
                    drawArc(
                        color = Color(fillArgb),
                        startAngle = start,
                        sweepAngle = sweepAngle,
                        useCenter = true
                    )
                    if (isSelected) {
                        drawArc(
                            color = Color(0xFFFFFFFF),
                            startAngle = start,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                // Punch the center hole for the hub.
                drawCircle(color = surfaceColor, radius = hubRadius)
                // Marker dots on families that contain selected descendants.
                levelNodes.forEachIndexed { index, node ->
                    if (!node.isLeaf &&
                        AromaTaxonomy.selectedLeafCountUnder(node, selectedSet) > 0
                    ) {
                        val mid = AromaWheelGeometry.segmentMidAngleDeg(index, count)
                        val (dx, dy) = AromaWheelGeometry.directionDeg(mid)
                        val markerDistance = outerRadius * MARKER_RADIUS_FRACTION
                        val markerCenter = Offset(
                            centerX + dx * markerDistance,
                            centerY + dy * markerDistance
                        )
                        drawCircle(
                            color = Color(0x33000000),
                            radius = outerRadius * 0.030f,
                            center = markerCenter
                        )
                        drawCircle(
                            color = Color(0xFFFFFFFF),
                            radius = outerRadius * 0.022f,
                            center = markerCenter
                        )
                    }
                }
            }
            WheelLabels(
                levelNodes = levelNodes,
                familyColorArgb = familyColorArgb,
                selectedIds = selectedSet,
                modifier = Modifier.fillMaxSize()
            )
            WheelHub(
                parentNodeId = levelNodes.firstOrNull()?.id?.substringBeforeLast('/').orEmpty(),
                selectedCount = selectedIds.size,
                diameter = wheelDiameter * HUB_RADIUS_FRACTION,
                onLevelUp = onLevelUp,
                enabled = canLevelUp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun WheelHub(
    parentNodeId: String,
    selectedCount: Int,
    diameter: Dp,
    enabled: Boolean,
    onLevelUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val parentLabel = AromaTaxonomy.find(parentNodeId)?.label.orEmpty()
    Column(
        modifier = modifier
            .size(diameter)
            .clickable(enabled = enabled, onClickLabel = "Back to $parentLabel") { onLevelUp() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (enabled) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = parentLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(
                "Aromas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "$selectedCount selected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WheelLabels(
    levelNodes: List<AromaNode>,
    familyColorArgb: Long?,
    selectedIds: Set<String>,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val radius = minOf(maxWidth, maxHeight) / 2f
        val runInner = radius * LABEL_INNER_FRACTION
        val runOuter = radius * LABEL_OUTER_FRACTION
        val runLength = runOuter - runInner
        val runCenterPx = with(density) { ((runInner + runOuter) / 2f).toPx() }
        val count = levelNodes.size
        levelNodes.forEachIndexed { index, node ->
            val midAngle = AromaWheelGeometry.segmentMidAngleDeg(index, count)
            val flipped = midAngle > 90f && midAngle < 270f
            val rotation = if (flipped) midAngle + 90f else midAngle - 90f
            val (dx, dy) = AromaWheelGeometry.directionDeg(midAngle)
            val backgroundArgb = if (node.isLeaf) {
                AromaWheelGeometry.shadeArgbAt(baseArgbFor(node, familyColorArgb), index)
            } else {
                baseArgbFor(node, familyColorArgb)
            }
            val isSelected = node.isLeaf && node.id in selectedIds
            val text = if (isSelected) "✓ ${node.label}" else node.label
            Text(
                text = text,
                color = Color(AromaWheelGeometry.readableTextArgb(backgroundArgb)),
                fontSize = labelFontSizeSp(count).sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = dx * runCenterPx
                        translationY = dy * runCenterPx
                        rotationZ = rotation
                    }
                    .width(runLength)
                    .padding(horizontal = 2.dp)
            )
        }
    }
}

@Composable
private fun SelectionChipsRow(
    selectedIds: List<String>,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    if (selectedIds.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Selected aromas (${selectedIds.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Clear all")
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp)
                .verticalScroll(rememberScrollState())
        ) {
            selectedIds.forEach { id ->
                val label = AromaTaxonomy.labelFor(id) ?: id
                FilterChip(
                    selected = true,
                    onClick = { onRemove(id) },
                    label = { Text(label) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $label",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun AromaListContent(
    selectedIds: List<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    expandedFamilies: MutableMap<String, Boolean>,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            AromaTaxonomy.roots.forEach { branch ->
                val familiesWithLeaves = branch.children.mapNotNull { family ->
                    val leaves = family.children.filter { leaf ->
                        trimmedQuery.isBlank() ||
                            leaf.label.contains(trimmedQuery, ignoreCase = true)
                    }
                    if (leaves.isEmpty()) null else family to leaves
                }
                if (familiesWithLeaves.isEmpty()) return@forEach
                val branchSelected = AromaTaxonomy.selectedLeafCountUnder(branch, selectedSet)
                item(key = "${branch.id}-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            branch.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (branchSelected > 0) {
                            Text(
                                "$branchSelected selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                familiesWithLeaves.forEach { (family, leaves) ->
                    val familySelected =
                        AromaTaxonomy.selectedLeafCountUnder(family, selectedSet)
                    val isOpen = expandedFamilies[family.id]
                        ?: (trimmedQuery.isNotBlank() || familySelected > 0)
                    item(key = "${family.id}-header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable { expandedFamilies[family.id] = !isOpen }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isOpen) {
                                    "Collapse ${family.label}"
                                } else {
                                    "Expand ${family.label}"
                                }
                            )
                            Text(
                                family.label,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp)
                            )
                            if (familySelected > 0) {
                                Text(
                                    "$familySelected",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (isOpen) {
                        items(leaves, key = { it.id }) { leaf ->
                            val checked = leaf.id in selectedSet
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .clickable { onToggleLeaf(leaf.id) }
                                    .padding(start = 32.dp, end = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { onToggleLeaf(leaf.id) }
                                )
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
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun baseArgbFor(node: AromaNode, familyColorArgb: Long?): Long =
    node.color ?: familyColorArgb ?: DEFAULT_WHEEL_COLOR

private fun labelFontSizeSp(segmentCount: Int): Int = when {
    segmentCount <= 5 -> 14
    segmentCount <= 7 -> 13
    segmentCount <= 9 -> 12
    else -> 11
}

private const val HUB_RADIUS_FRACTION = 0.30f
private const val LABEL_INNER_FRACTION = 0.42f
private const val LABEL_OUTER_FRACTION = 0.93f
private const val MARKER_RADIUS_FRACTION = 0.355f
private const val DEFAULT_WHEEL_COLOR = 0xFF6E5A6E
