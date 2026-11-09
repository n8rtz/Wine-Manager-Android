@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.image.resolveWineImageModel
import com.n8rtz.winetracker.ui.components.WineCard
import com.n8rtz.winetracker.ui.components.AromaChips
import java.io.File

private val cellarRackNames = listOf("Long-term aging", "Daily drinkers", "Special occasions")
private const val NEW_ADDITIONS_RACK = "New additions"

@Composable
internal fun RackAssignmentControl(
    currentRack: String?,
    automaticNewAddition: Boolean,
    onAssign: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Rack",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        if (automaticNewAddition) {
            AssistChip(onClick = {}, label = { Text("New additions · automatic") }, enabled = false)
        } else {
            Box {
                AssistChip(
                    onClick = { expanded = true },
                    label = { Text(currentRack ?: "Add to rack") },
                    trailingIcon = { Icon(Icons.Default.Inventory2, null, Modifier.size(16.dp)) }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("No rack") }, onClick = { onAssign(null); expanded = false })
                    cellarRackNames.forEach { rack ->
                        DropdownMenuItem(text = { Text(rack) }, onClick = { onAssign(rack); expanded = false })
                    }
                }
            }
        }
    }
}

@Composable
internal fun CellarRacks(
    inventory: List<WineBottleWithDefinition>,
    ratingsMap: Map<Pair<Long, String>, String>,
    notesMap: Map<Pair<Long, String>, List<TastingNoteWithDefinition>>,
    assignments: Map<Long, String>,
    selectedRack: String?,
    onRackSelected: (String?) -> Unit,
    onAssign: (Long, String?) -> Unit,
    onWineClick: (Long) -> Unit,
    onTastingNotesClick: (definitionId: Long, vintage: String) -> Unit
) {
    val rackNames = listOf(NEW_ADDITIONS_RACK) + cellarRackNames
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
            else -> inventory.filter { assignments[it.bottle.bottleId] == selectedRack && it.bottle.bottleId !in newAdditions }
        }
    }
    val context = LocalContext.current

    if (selectedRack == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text("Wine racks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Group bottles by how you plan to enjoy or age them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(rackNames, key = { it }) { rack ->
                    val rackItems = when (rack) {
                        NEW_ADDITIONS_RACK -> inventory.filter { it.bottle.bottleId in newAdditions }
                        else -> inventory.filter { assignments[it.bottle.bottleId] == rack && it.bottle.bottleId !in newAdditions }
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
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onRackSelected(rack) },
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
            if (displayed.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (selectedRack == NEW_ADDITIONS_RACK) "Everything here has a tasting note or rating." else "No wines in this rack yet.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayed, key = { it.bottle.bottleId }) { item ->
                        val vintage = com.n8rtz.winetracker.data.local.displayVintage(item.bottle.vintage)
                        val key = item.definition.definitionId to vintage
                        val notes = notesMap[key].orEmpty()
                        WineCard(
                            title = "${item.definition.maker} - ${item.definition.name} ($vintage)",
                            subtitle = listOfNotNull(item.definition.subregion, item.definition.region, item.definition.country)
                                .filter { it.isNotBlank() && !it.equals("null", true) }.joinToString(", "),
                            type = item.definition.type,
                            metadata = "Qty: ${item.bottle.quantity}",
                            imageUrl = item.definition.imageUrl.takeIf { item.imageAsset == null },
                            imageLocalPath = item.imageAsset?.relativePath,
                            rating = ratingsMap[key],
                            details = {
                                RackAssignmentControl(
                                    currentRack = assignments[item.bottle.bottleId],
                                    automaticNewAddition = notes.none { it.note.rating > 0.0 || !it.note.notes.isNullOrBlank() },
                                    onAssign = { rack -> onAssign(item.bottle.bottleId, rack) }
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
                            },
                            onClick = { }
                        )
                    }
                }
            }
        }
    }
}
