package com.n8rtz.winetracker.ui.screens

import androidx.compose.animation.*
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.n8rtz.winetracker.data.models.SensoryData
import com.n8rtz.winetracker.data.local.WineBottleWithDefinition
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.ui.components.*
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WineEvaluationScreen(
    noteId: Long? = null,
    tastingNoteViewModel: TastingNoteViewModel,
    wineViewModel: WineViewModel,
    onBack: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val sensoryData by tastingNoteViewModel.editorSensoryData.collectAsState()
    
    // Selection state
    var selectedBottle by remember { mutableStateOf<WineBottleWithDefinition?>(null) }
    var existingNote by remember { mutableStateOf<TastingNoteWithDefinition?>(null) }
    var showWineSearch by remember { mutableStateOf(false) }
    
    val activeInventory by wineViewModel.activeInventory.collectAsState(initial = emptyList())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    var showAromaWheel by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(noteId == null) }

    LaunchedEffect(noteId) {
        if (noteId != null) {
            tastingNoteViewModel.loadNoteForEditing(noteId) { loadedNote ->
                existingNote = loadedNote
                notes = loadedNote.note.notes ?: ""
            }
        }
    }

    val currentDefinition = selectedBottle?.definition ?: existingNote?.definition
    val currentVintage = selectedBottle?.bottle?.vintage ?: existingNote?.note?.vintage ?: ""
    val hasWineSelected = currentDefinition != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = when {
                            !isEditing && existingNote != null -> "Tasting Evaluation"
                            isEditing && existingNote != null -> "Edit Evaluation"
                            else -> "Evaluate Wine"
                        },
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditing && existingNote != null) {
                            // Cancel editing — revert to view mode and reload saved data
                            tastingNoteViewModel.loadNoteForEditing(existingNote!!.note.noteId) { loadedNote ->
                                existingNote = loadedNote
                                notes = loadedNote.note.notes ?: ""
                            }
                            isEditing = false
                        } else {
                            // Clear editor state when leaving
                            tastingNoteViewModel.updateSensoryData { SensoryData() }
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEditing) {
                        TextButton(
                            onClick = {
                                val currentNote = existingNote
                                val currentBottle = selectedBottle
                                if (currentNote != null) {
                                    tastingNoteViewModel.updateExistingTastingNote(
                                        noteId = currentNote.note.noteId,
                                        definitionId = currentNote.definition.definitionId,
                                        vintage = currentNote.note.vintage,
                                        bottleId = currentNote.note.bottleId,
                                        dateTasted = currentNote.note.dateTasted,
                                        notes = notes.takeIf { it.isNotBlank() },
                                        onSuccess = {
                                            isEditing = false
                                            onSaveSuccess()
                                        }
                                    )
                                } else if (currentBottle != null) {
                                    tastingNoteViewModel.saveTastingNote(
                                        definitionId = currentBottle.definition.definitionId,
                                        vintage = currentBottle.bottle.vintage,
                                        bottleId = currentBottle.bottle.bottleId,
                                        dateTasted = System.currentTimeMillis(),
                                        notes = notes.takeIf { it.isNotBlank() }, 
                                        onSuccess = onSaveSuccess
                                    )
                                }
                            },
                            enabled = hasWineSelected
                        ) {
                            Text(
                                text = "Save",
                                color = if (hasWineSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        // View mode — show edit pencil icon
                        IconButton(onClick = { isEditing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .then(if (showAromaWheel) Modifier.blur(16.dp) else Modifier)
        ) {
            // Wine Selection Header
            if (!hasWineSelected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable { showWineSearch = true },
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Cellar",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Select Wine from Cellar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Thumbnail
                        Box(
                            modifier = Modifier
                                .size(60.dp, 80.dp)
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            if (currentDefinition.imageUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(currentDefinition.imageUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Wine bottle",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    Icons.Default.WineBar, 
                                    contentDescription = null, 
                                    modifier = Modifier.fillMaxSize(),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            val cleanVintage = currentVintage.removeSurrounding("(", ")")
                            Text(
                                text = "${currentDefinition.maker} - ${currentDefinition.name} ($cleanVintage)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val subregion = currentDefinition.subregion.takeIf { !it.isNullOrBlank() }
                            val region = currentDefinition.region.takeIf { !it.isNullOrBlank() }
                            val locationText = listOfNotNull(subregion, region).joinToString(", ")
                            if (locationText.isNotEmpty()) {
                                Text(
                                    text = locationText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            if (existingNote == null && isEditing) {
                                Spacer(modifier = Modifier.height(8.dp))
                                AssistChip(
                                    onClick = { showWineSearch = true },
                                    label = { Text("Change Wine") }
                                )
                            }
                        }
                    }
                }
            }

            if (hasWineSelected) {
                AppearanceCard(sensoryData = sensoryData, onDataChange = { tastingNoteViewModel.updateSensoryData { _ -> it } }, enabled = isEditing)
                NoseCard(sensoryData = sensoryData, onDataChange = { tastingNoteViewModel.updateSensoryData { _ -> it } }, onOpenAromaWheel = { showAromaWheel = true }, enabled = isEditing)
                PalateCard(sensoryData = sensoryData, onDataChange = { tastingNoteViewModel.updateSensoryData { _ -> it } }, enabled = isEditing)
                FinishCard(sensoryData = sensoryData, onDataChange = { tastingNoteViewModel.updateSensoryData { _ -> it } }, enabled = isEditing)
                FlawsCard(sensoryData = sensoryData, onDataChange = { tastingNoteViewModel.updateSensoryData { _ -> it } }, enabled = isEditing)
                OverallCard(sensoryData = sensoryData, onDataChange = { tastingNoteViewModel.updateSensoryData { _ -> it } }, notes = notes, onNotesChange = { notes = it }, enabled = isEditing)
            }
        }
    }

    // Aroma Wheel Dialog Popup
    if (showAromaWheel) {
        SommelierAromaWheelOverlay(
            selectedAromas = sensoryData.aromas,
            onToggleAroma = { aroma ->
                val updatedAromas = sensoryData.aromas.toMutableList()
                if (updatedAromas.contains(aroma)) {
                    updatedAromas.remove(aroma)
                } else {
                    updatedAromas.add(aroma)
                }
                tastingNoteViewModel.updateSensoryData { it.copy(aromas = updatedAromas) }
            },
            onClose = { showAromaWheel = false }
        )
    }

    // Wine Search Bottom Sheet
    if (showWineSearch) {
        ModalBottomSheet(
            onDismissRequest = { showWineSearch = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = "Select from Cellar",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by producer or name...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                val filteredList = activeInventory.filter {
                    val query = searchQuery.lowercase()
                    it.definition.maker.lowercase().contains(query) || 
                    it.definition.name.lowercase().contains(query)
                }
                
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredList) { item ->
                        Card(
                            onClick = { 
                                selectedBottle = item
                                scope.launch { sheetState.hide() }.invokeOnCompletion { 
                                    if (!sheetState.isVisible) showWineSearch = false 
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${item.definition.maker} - ${item.definition.name} (${item.bottle.vintage})",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
