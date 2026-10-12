@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.SensoryData
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.ui.components.OverallScoreSlider
import com.n8rtz.winetracker.ui.components.SensoryEvaluationFields
import com.n8rtz.winetracker.ui.components.datePickerMillisToTimestamp
import com.n8rtz.winetracker.ui.components.timestampToDatePickerMillis
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TastingNoteEditorScreen(
    viewModel: TastingNoteViewModel,
    noteId: Long? = null,
    presetDefinitionId: Long? = null,
    presetVintage: String? = null,
    presetDateTasted: Long? = null,
    linkedEventId: Long? = null,
    onBack: () -> Unit
) {
    val definitions by viewModel.allDefinitions.collectAsState(initial = emptyList())
    val wineVintages by viewModel.wineVintages.collectAsState()
    val reviewerNames by viewModel.reviewerNames.collectAsState()
    var loaded by remember(noteId) { mutableStateOf(noteId == null) }
    var missingNote by remember(noteId) { mutableStateOf(false) }
    var existingNote by remember(noteId) { mutableStateOf<TastingNote?>(null) }

    var selectedDefinitionId by remember(noteId, presetDefinitionId) { mutableStateOf(presetDefinitionId) }
    var wineSearch by remember { mutableStateOf("") }
    var wineMenuExpanded by remember { mutableStateOf(false) }
    var vintage by remember(noteId, presetVintage) { mutableStateOf(presetVintage ?: "NV") }
    var dateTasted by remember(noteId, presetDateTasted) {
        mutableStateOf(presetDateTasted ?: System.currentTimeMillis())
    }
    var rating by remember { mutableStateOf(88f) }
    var ratingSelected by remember(noteId) { mutableStateOf(noteId == null) }
    var notes by remember { mutableStateOf("") }
    var rebuy by remember { mutableStateOf(false) }
    var recommendedBy by remember { mutableStateOf("") }
    var reviewerMenuExpanded by remember { mutableStateOf(false) }
    var initialRating by remember { mutableStateOf("") }
    var reviewerRating by remember { mutableStateOf("") }
    var sensoryData by remember { mutableStateOf(SensoryData()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        if (noteId != null) {
            val note = viewModel.getTastingNoteById(noteId)
            if (note == null) {
                missingNote = true
            } else {
                existingNote = note
                selectedDefinitionId = note.definitionId
                vintage = note.vintage
                dateTasted = note.dateTasted
                rating = note.rating.toFloat().takeIf { it > 0f }?.coerceIn(50f, 100f) ?: 88f
                ratingSelected = note.rating > 0.0
                notes = note.notes.orEmpty()
                rebuy = note.rebuy
                recommendedBy = note.recommendedBy.orEmpty()
                initialRating = note.initialRating?.toString().orEmpty()
                reviewerRating = note.reviewerRating?.toString().orEmpty()
                sensoryData = note.sensoryData ?: SensoryData()
            }
            loaded = true
        }
    }

    val selectedDefinition = definitions.firstOrNull { it.definitionId == selectedDefinitionId }
    val availableWineChoices = remember(definitions, wineVintages) {
        definitions.flatMap { definition ->
            val knownVintages = wineVintages[definition.definitionId].orEmpty().ifEmpty { listOf("NV") }
            knownVintages.map { knownVintage -> WineVintageChoice(definition, knownVintage) }
        }.sortedWith(
            compareBy<WineVintageChoice> { it.definition.maker.lowercase(Locale.ROOT) }
                .thenBy { it.definition.name.lowercase(Locale.ROOT) }
                .thenByDescending { it.vintage.toIntOrNull() ?: Int.MIN_VALUE }
        )
    }
    val matchingWineChoices = remember(availableWineChoices, wineSearch) {
        val query = wineSearch.trim()
        availableWineChoices.filter { choice ->
            val searchText = listOf(
                choice.definition.maker,
                choice.definition.name,
                displayVintage(choice.vintage),
                formatWineChoice(choice.definition, choice.vintage)
            ).joinToString(" ")
            query.isBlank() || searchText.contains(query, ignoreCase = true)
        }.take(30)
    }
    val reviewerOptions = remember(reviewerNames, recommendedBy) {
        (reviewerNames + listOfNotNull(recommendedBy.takeIf { it.isNotBlank() }))
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedBy { it.lowercase(Locale.ROOT) }
    }

    LaunchedEffect(selectedDefinition, vintage) {
        selectedDefinition?.let { definition ->
            wineSearch = formatWineChoice(definition, vintage)
        }
    }

    val formattedDate = remember(dateTasted) {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(dateTasted))
    }
    val reviewerRatingIsValid = reviewerRating.isBlank() ||
        reviewerRating.toIntOrNull()?.let { it in 50..100 } == true
    val canSave = loaded && !missingNote && selectedDefinition != null && vintage.isNotBlank() &&
        ratingSelected && reviewerRatingIsValid

    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    if (missingNote) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tasting note not found")
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == null) "Add Tasting Note" else "Edit Tasting Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (noteId != null) {
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete note")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExposedDropdownMenuBox(
                expanded = wineMenuExpanded,
                onExpandedChange = { wineMenuExpanded = !wineMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = wineSearch,
                    onValueChange = {
                        wineSearch = it
                        selectedDefinitionId = null
                        wineMenuExpanded = true
                    },
                    label = { Text("Wine") },
                    placeholder = { Text("Search producer or wine") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (wineSearch.isNotEmpty()) {
                                IconButton(onClick = {
                                    wineSearch = ""
                                    selectedDefinitionId = null
                                    wineMenuExpanded = true
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear wine search")
                                }
                            }
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = wineMenuExpanded)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().menuAnchor(
                        androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryEditable,
                        true
                    ),
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = wineMenuExpanded,
                    onDismissRequest = { wineMenuExpanded = false }
                ) {
                    if (matchingWineChoices.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No matching wines") },
                            enabled = false,
                            onClick = {}
                        )
                    } else {
                        matchingWineChoices.forEach { choice ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        formatWineChoice(choice.definition, choice.vintage),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                onClick = {
                                    selectedDefinitionId = choice.definition.definitionId
                                    vintage = choice.vintage
                                    wineSearch = formatWineChoice(choice.definition, choice.vintage)
                                    wineMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vintage,
                    onValueChange = { vintage = it },
                    label = { Text("Vintage") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = formattedDate,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date tasted") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "Choose date")
                        }
                    },
                    modifier = Modifier.weight(1.3f)
                )
            }

            if (selectedDefinition != null) {
                Text(
                    text = "${selectedDefinition.maker} - ${selectedDefinition.name} (${displayVintage(vintage)})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = listOfNotNull(selectedDefinition.subregion, selectedDefinition.region, selectedDefinition.country)
                        .filter { it.isNotBlank() }
                        .joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OverallScoreSlider(
                score = rating,
                scoreSelected = ratingSelected,
                onScoreChange = {
                    rating = it
                    ratingSelected = true
                }
            )
            if (!ratingSelected) {
                Text(
                    "Choose an overall score to save this note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            OutlinedTextField(
                value = initialRating,
                onValueChange = { initialRating = it.filter { character -> character.isDigit() }.take(3) },
                label = { Text("Initial rating") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            FilterChip(
                selected = rebuy,
                onClick = { rebuy = !rebuy },
                label = { Text(if (rebuy) "Would buy again" else "Recommend / rebuy") }
            )

            SensoryEvaluationFields(
                data = sensoryData,
                onChange = { sensoryData = it }
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Tasting notes and impressions") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )

            OutlinedTextField(
                value = reviewerRating,
                onValueChange = {
                    reviewerRating = it.filter { character -> character.isDigit() }.take(3)
                },
                label = { Text("Reviewer rating (50–100)") },
                isError = !reviewerRatingIsValid,
                supportingText = {
                    if (reviewerRating.isNotBlank() && !reviewerRatingIsValid) {
                        Text("Enter a score from 50 to 100")
                    } else {
                        Text("Optional")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            ExposedDropdownMenuBox(
                expanded = reviewerMenuExpanded,
                onExpandedChange = { reviewerMenuExpanded = !reviewerMenuExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = recommendedBy,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Recommended by") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (recommendedBy.isNotBlank()) {
                                IconButton(onClick = { recommendedBy = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear reviewer")
                                }
                            }
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = reviewerMenuExpanded)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().menuAnchor(
                        androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                        true
                    ),
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = reviewerMenuExpanded,
                    onDismissRequest = { reviewerMenuExpanded = false }
                ) {
                    if (reviewerOptions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Add reviewers in Settings") },
                            enabled = false,
                            onClick = {}
                        )
                    } else {
                        reviewerOptions.forEach { reviewer ->
                            DropdownMenuItem(
                                text = { Text(reviewer) },
                                onClick = {
                                    recommendedBy = reviewer
                                    reviewerMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val definition = selectedDefinition ?: return@Button
                    val note = TastingNote(
                        noteId = existingNote?.noteId ?: 0L,
                        definitionId = definition.definitionId,
                        vintage = normalizeVintageInput(vintage),
                        dateTasted = dateTasted,
                        rating = rating.toDouble(),
                        notes = notes.takeIf { it.isNotBlank() },
                        rebuy = rebuy,
                        recommendedBy = recommendedBy.takeIf { it.isNotBlank() },
                        reviewerRating = reviewerRating.toIntOrNull()?.takeIf { it in 50..100 },
                        initialRating = initialRating.toIntOrNull(),
                        bottleId = existingNote?.bottleId,
                        sensoryData = sensoryData,
                        importKey = existingNote?.importKey
                    )
                    viewModel.saveTastingNote(note, linkedEventId) { onBack() }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (noteId == null) "Save Tasting Note" else "Save Changes")
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = timestampToDatePickerMillis(dateTasted)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { dateTasted = datePickerMillisToTimestamp(it) }
                    showDatePicker = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showDeleteConfirmation && noteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete tasting note?") },
            text = { Text("This permanently deletes this note. It does not change inventory or consumption history.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTastingNote(noteId, onBack)
                    showDeleteConfirmation = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

private data class WineVintageChoice(
    val definition: WineDefinition,
    val vintage: String
)

private fun formatWineChoice(definition: WineDefinition, vintage: String): String =
    "${definition.maker} - ${definition.name} (${displayVintage(vintage)})"

private fun normalizeVintageInput(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.equals("(NV)", ignoreCase = true) || trimmed.equals("NV", ignoreCase = true)) "NV" else trimmed
}
