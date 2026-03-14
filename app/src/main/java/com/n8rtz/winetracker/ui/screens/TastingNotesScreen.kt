package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.viewmodels.WineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TastingNotesScreen(
    viewModel: WineViewModel,
    onNoteClick: (Long) -> Unit, // Navigates to a specific note editor
    onNavigateToHistory: () -> Unit // Navigates to History Screen
) {
    val history by viewModel.tastingHistory.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var showFilterSheet by remember { mutableStateOf(false) }

    // Filters matching the Cellar pattern, plus a completion toggle
    var appliedTypes by remember { mutableStateOf(setOf<String>()) }
    var noteStatusFilter by remember { mutableStateOf("All") } // "All", "Needs Notes", "Completed"

    val filteredHistory = history.filter { item ->
        val matchesSearch = searchQuery.isBlank() ||
                item.definition.maker.contains(searchQuery, ignoreCase = true) ||
                item.definition.name.contains(searchQuery, ignoreCase = true) ||
                item.note.vintage.contains(searchQuery)

        val matchesType = appliedTypes.isEmpty() || item.definition.type in appliedTypes
        
        val matchesStatus = when (noteStatusFilter) {
            "Needs Notes" -> item.note.notes.isNullOrBlank()
            "Completed" -> !item.note.notes.isNullOrBlank()
            else -> true
        }

        matchesSearch && matchesType && matchesStatus
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Tasting Notes") },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "View History")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            
            // Top Search Bar & Filter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search Producer, Name, or Vintage") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { showFilterSheet = true }) {
                    Icon(Icons.Default.Tune, contentDescription = "Filter Notes")
                }
            }

            // Repository List
            if (filteredHistory.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Description, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("No notes found", style = MaterialTheme.typography.titleLarge)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredHistory) { item ->
                        CompactNoteItem(item) {
                            onNoteClick(item.note.noteId)
                        }
                    }
                }
            }
        }

        if (showFilterSheet) {
            ModalBottomSheet(
                onDismissRequest = { showFilterSheet = false },
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                NotesFilterSheet(
                    history = history,
                    initialTypes = appliedTypes,
                    initialStatus = noteStatusFilter,
                    onApply = { types, status ->
                        appliedTypes = types
                        noteStatusFilter = status
                        showFilterSheet = false
                    },
                    onClose = { showFilterSheet = false }
                )
            }
        }
    }
}

@Composable
fun CompactNoteItem(item: TastingNoteWithDefinition, onClick: () -> Unit) {
    val wine = item.definition
    val note = item.note

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${wine.maker} - ${wine.name} (${note.vintage})",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (note.notes.isNullOrBlank()) {
                    Text(
                        text = "No notes written...",
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = note.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(Modifier.width(12.dp))
            
            // Small badge for rating or an edit icon if it needs notes
            if (note.rating > 0) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = note.rating.toInt().toString(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Icon(Icons.Default.Edit, contentDescription = "Needs Review", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NotesFilterSheet(
    history: List<TastingNoteWithDefinition>,
    initialTypes: Set<String>,
    initialStatus: String,
    onApply: (Set<String>, String) -> Unit,
    onClose: () -> Unit
) {
    var selectedTypes by remember { mutableStateOf(initialTypes) }
    var statusFilter by remember { mutableStateOf(initialStatus) }

    val allTypes = history.map { it.definition.type }.distinct().sorted()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Filter Notes", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onClose) { Icon(Icons.Default.Clear, "Close") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status Filter (Needs Notes vs Completed)
        Text("Completion Status", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Needs Notes", "Completed").forEach { status ->
                FilterChip(
                    selected = statusFilter == status,
                    onClick = { statusFilter = status },
                    label = { Text(status) },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // Type Filter
        Text("Wine Type", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            allTypes.forEach { type ->
                val isSelected = type in selectedTypes
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedTypes = if (isSelected) selectedTypes - type else selectedTypes + type },
                    label = { Text(type) },
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = {
                    selectedTypes = emptySet()
                    statusFilter = "All"
                    onApply(emptySet(), "All")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear All")
            }
            Button(
                onClick = { onApply(selectedTypes, statusFilter) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Apply Filters")
            }
        }
    }
}
