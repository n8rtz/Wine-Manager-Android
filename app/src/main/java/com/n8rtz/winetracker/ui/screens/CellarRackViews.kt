@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.n8rtz.winetracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.R
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.image.resolveWineImageModel
import com.n8rtz.winetracker.ui.components.WineCard
import com.n8rtz.winetracker.ui.components.AromaChips
import java.io.File

internal val DEFAULT_CELLAR_RACK_NAMES = listOf("Long-term aging", "Daily drinkers", "Special occasions")
private const val NEW_ADDITIONS_RACK = "New additions"

@Composable
internal fun RackAssignmentControl(
    currentRacks: Set<String>,
    automaticNewAddition: Boolean,
    onOpenRackSelection: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Rack",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        AssistChip(
            onClick = onOpenRackSelection,
            label = {
                val assignedLabel = currentRacks.takeIf { it.isNotEmpty() }?.joinToString(", ")
                Text(if (automaticNewAddition) {
                    if (currentRacks.isEmpty()) "New additions · add rack" else "New additions · +${currentRacks.size} racks"
                } else assignedLabel ?: "Add to rack")
            },
            trailingIcon = { RackGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
        )
    }
}

@Composable
internal fun RackQuickAddButton(
    automaticNewAddition: Boolean,
    onOpenRackSelection: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 40.dp)
            .offset(x = 4.dp, y = (-4).dp)
            .semantics {
                contentDescription = if (automaticNewAddition) "Add wine to another rack" else "Choose wine racks"
            }
            .clickable(onClick = onOpenRackSelection),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            RackGlyph(
                modifier = Modifier.size(width = 25.dp, height = 27.dp),
                color = MaterialTheme.colorScheme.primary,
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

@Composable
private fun RackGlyph(
    modifier: Modifier = Modifier,
    color: Color,
    contentScale: ContentScale = ContentScale.Fit
) {
    Image(
        painter = painterResource(R.drawable.wine_rack_lattice),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        colorFilter = ColorFilter.tint(color)
    )
}

@Composable
internal fun CellarRacks(
    inventory: List<WineBottleWithDefinition>,
    ratingsMap: Map<Pair<Long, String>, String>,
    notesMap: Map<Pair<Long, String>, List<TastingNoteWithDefinition>>,
    assignments: Map<Long, Set<String>>,
    userRackNames: List<String>,
    selectedRack: String?,
    onRackSelected: (String?) -> Unit,
    assignmentBottleId: Long?,
    selectedAssignmentRacks: Set<String>,
    onAssignmentRackToggle: (String) -> Unit,
    onCancelAssignment: () -> Unit,
    onConfirmAssignment: () -> Unit,
    onOpenRackSelection: (Long) -> Unit,
    onCreateRack: (String) -> Unit,
    onRemoveFromRack: (Set<Long>, String) -> Unit,
    onWineClick: (Long) -> Unit,
    onTastingNotesClick: (definitionId: Long, vintage: String) -> Unit
) {
    val rackNames = listOf(NEW_ADDITIONS_RACK) + userRackNames
    var showCreateRackDialog by remember { mutableStateOf(false) }
    var newRackName by remember { mutableStateOf("") }
    var selectedForRemoval by remember(selectedRack) { mutableStateOf<Set<Long>>(emptySet()) }
    val canRemoveFromRack = selectedRack != null && selectedRack != NEW_ADDITIONS_RACK
    BackHandler(enabled = canRemoveFromRack && selectedForRemoval.isNotEmpty()) {
        selectedForRemoval = emptySet()
    }
    val newAdditions = remember(inventory, notesMap) {
        inventory.filter { bottle ->
            val vintage = com.n8rtz.winetracker.data.local.displayVintage(bottle.bottle.vintage)
            notesMap[bottle.definition.definitionId to vintage].orEmpty()
                .none { it.note.rating > 0.0 || !it.note.notes.isNullOrBlank() }
        }.map { it.bottle.bottleId }.toSet()
    }
    val displayed = remember(inventory, selectedRack, assignments, newAdditions) {
        when (selectedRack) {
            null -> emptyList()
            NEW_ADDITIONS_RACK -> inventory.filter { it.bottle.bottleId in newAdditions }
            else -> inventory.filter { selectedRack in assignments[it.bottle.bottleId].orEmpty() }
        }
    }
    val context = LocalContext.current

    if (selectedRack == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (assignmentBottleId != null) {
                Text("Choose one or more racks", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Text("Selected wine will be added to each rack you choose.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            LazyVerticalGrid(
                modifier = Modifier.weight(1f),
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(rackNames, key = { it }) { rack ->
                    val rackItems = when (rack) {
                        NEW_ADDITIONS_RACK -> inventory.filter { it.bottle.bottleId in newAdditions }
                        else -> inventory.filter { rack in assignments[it.bottle.bottleId].orEmpty() }
                    }
                    val cover = rackItems.firstOrNull()?.let { wine ->
                        resolveWineImageModel(context, wine.imageAsset?.relativePath,
                            wine.definition.imageUrl.takeIf { wine.imageAsset == null })
                    }
                    val accent = when (rack) {
                        NEW_ADDITIONS_RACK -> Color(0xFF9B7734)
                        "Long-term aging" -> Color(0xFF6F3342)
                        "Daily drinkers" -> Color(0xFF647D5D)
                        else -> Color(0xFF77739A)
                    }
                    val selectable = assignmentBottleId != null && rack != NEW_ADDITIONS_RACK
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (selectable) onAssignmentRackToggle(rack) else if (assignmentBottleId == null) onRackSelected(rack)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column {
                            Box(Modifier.fillMaxWidth().height(116.dp).background(
                                Brush.verticalGradient(listOf(accent.copy(alpha = .78f), accent))
                            )) {
                                if (cover != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context).data(cover).crossfade(true)
                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                            .diskCachePolicy(if (cover is File) CachePolicy.DISABLED else CachePolicy.ENABLED).build(),
                                        contentDescription = "Bottle photo for $rack",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .3f)))
                                }
                                if (selectable) {
                                    Checkbox(
                                        checked = rack in selectedAssignmentRacks,
                                        onCheckedChange = { onAssignmentRackToggle(rack) },
                                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                                    )
                                }
                                Text(
                                    rack.take(1).uppercase(),
                                    style = MaterialTheme.typography.displayMedium,
                                    color = Color.White.copy(alpha = if (cover == null) .9f else .55f),
                                    fontWeight = FontWeight.Light,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                                Surface(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                                    shape = RoundedCornerShape(50), color = Color.White.copy(alpha = .92f)
                                ) {
                                    Text("${rackItems.sumOf { it.bottle.quantity }}", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent)
                                }
                            }
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                                Text(rack, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${rackItems.size} ${if (rackItems.size == 1) "wine" else "wines"}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (assignmentBottleId == null) {
                    item(key = "add_rack_tile") {
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                newRackName = ""
                                showCreateRackDialog = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().height(116.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Create rack", tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(56.dp))
                                }
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                                    Text("Add rack", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text("Create a custom rack", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (assignmentBottleId != null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancelAssignment, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    val canRemoveExisting = assignments[assignmentBottleId].orEmpty().isNotEmpty()
                    Button(onClick = onConfirmAssignment,
                        enabled = selectedAssignmentRacks.isNotEmpty() || canRemoveExisting,
                        modifier = Modifier.weight(1f)) {
                        Text(if (selectedAssignmentRacks.isEmpty() && canRemoveExisting) "Remove from racks"
                            else "Add to ${selectedAssignmentRacks.size} racks")
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onRackSelected(null) }) { Icon(Icons.Default.ArrowBack, "All racks") }
                Column(Modifier.weight(1f)) {
                    Text(selectedRack, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${displayed.sumOf { it.bottle.quantity }} bottles", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (canRemoveFromRack && selectedForRemoval.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selectedForRemoval.size} selected", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                }
            }
            if (displayed.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (selectedRack == NEW_ADDITIONS_RACK) "Everything here has a tasting note or rating." else "No wines in this rack yet.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayed, key = { it.bottle.bottleId }) { item ->
                        val vintage = com.n8rtz.winetracker.data.local.displayVintage(item.bottle.vintage)
                        val key = item.definition.definitionId to vintage
                        val notes = notesMap[key].orEmpty()
                        val automaticNewAddition = notes.none { it.note.rating > 0.0 || !it.note.notes.isNullOrBlank() }
                        val selectionMode = selectedForRemoval.isNotEmpty()
                        val isSelected = item.bottle.bottleId in selectedForRemoval
                        WineCard(
                            title = "${item.definition.maker} - ${item.definition.name} ($vintage)",
                            subtitle = listOfNotNull(item.definition.subregion, item.definition.region, item.definition.country)
                                .filter { it.isNotBlank() && !it.equals("null", true) }.joinToString(", "),
                            type = item.definition.type,
                            metadata = "Qty: ${item.bottle.quantity}",
                            imageUrl = item.definition.imageUrl.takeIf { item.imageAsset == null },
                            imageLocalPath = item.imageAsset?.relativePath,
                            rating = ratingsMap[key],
                            trailingAction = if (selectionMode) {
                                {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = null,
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = MaterialTheme.colorScheme.primary,
                                            disabledCheckedColor = MaterialTheme.colorScheme.primary,
                                            disabledUncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            } else {
                                {
                                    RackQuickAddButton(
                                        automaticNewAddition = automaticNewAddition,
                                        onOpenRackSelection = { onOpenRackSelection(item.bottle.bottleId) }
                                    )
                                }
                            },
                            details = if (selectionMode) null else {
                                {
                                    RackAssignmentControl(
                                        currentRacks = assignments[item.bottle.bottleId].orEmpty(),
                                        automaticNewAddition = automaticNewAddition,
                                        onOpenRackSelection = { onOpenRackSelection(item.bottle.bottleId) }
                                    )
                                    val aromaNote = notes.firstOrNull { it.note.sensoryData?.aromas.orEmpty().isNotEmpty() }
                                    if (aromaNote != null) {
                                        Text("Aromas · ${java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()).format(java.util.Date(aromaNote.note.dateTasted))}",
                                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        AromaChips(aromaNote.note.sensoryData?.aromas.orEmpty(), maxVisible = 7)
                                    }
                                    if (notes.isNotEmpty()) {
                                        OutlinedButton(onClick = { onTastingNotesClick(item.definition.definitionId, item.bottle.vintage) }) {
                                            Icon(Icons.Default.Description, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Tasting notes")
                                        }
                                    }
                                    TextButton(onClick = { onWineClick(item.bottle.bottleId) }, contentPadding = PaddingValues(0.dp)) {
                                        Text("Wine details")
                                    }
                                }
                            },
                            onClick = {
                                if (selectionMode) {
                                    selectedForRemoval = if (isSelected) selectedForRemoval - item.bottle.bottleId
                                    else selectedForRemoval + item.bottle.bottleId
                                }
                            },
                            onLongClick = if (canRemoveFromRack) {
                                { selectedForRemoval = selectedForRemoval + item.bottle.bottleId }
                            } else null
                        )
                    }
                }
            }
            if (canRemoveFromRack && selectedForRemoval.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = { selectedForRemoval = emptySet() }, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            selectedRack?.let { onRemoveFromRack(selectedForRemoval, it) }
                            selectedForRemoval = emptySet()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Remove from rack") }
                }
            }
        }
    }
    if (showCreateRackDialog) {
        val candidate = newRackName.trim()
        val duplicate = rackNames.any { it.equals(candidate, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { showCreateRackDialog = false },
            title = { Text("Create rack") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Give this rack a name.")
                    OutlinedTextField(
                        value = newRackName,
                        onValueChange = { newRackName = it },
                        label = { Text("Rack name") },
                        singleLine = true,
                        isError = candidate.isNotEmpty() && (duplicate || '|' in candidate)
                    )
                    if (candidate.isNotEmpty() && duplicate) {
                        Text("A rack with this name already exists.", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = candidate.isNotEmpty() && !duplicate && '|' !in candidate,
                    onClick = {
                        onCreateRack(candidate)
                        showCreateRackDialog = false
                        newRackName = ""
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateRackDialog = false }) { Text("Cancel") }
            }
        )
    }
}
