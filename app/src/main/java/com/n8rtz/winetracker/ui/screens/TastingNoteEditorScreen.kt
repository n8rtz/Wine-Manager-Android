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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.SensoryData
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.WineDefinition
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.ui.components.LocationWheelPicker
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
    var loaded by remember(noteId) { mutableStateOf(noteId == null) }
    var missingNote by remember(noteId) { mutableStateOf(false) }
    var existingNote by remember(noteId) { mutableStateOf<TastingNote?>(null) }

    var selectedDefinitionId by remember(noteId, presetDefinitionId) { mutableStateOf(presetDefinitionId) }
    var selectedCountry by remember(noteId, presetDefinitionId) { mutableStateOf<String?>(null) }
    var selectedRegion by remember(noteId, presetDefinitionId) { mutableStateOf<String?>(null) }
    var selectedSubregion by remember(noteId, presetDefinitionId) { mutableStateOf<String?>(null) }
    var wineSearch by remember { mutableStateOf("") }
    var wineMenuExpanded by remember { mutableStateOf(false) }
    var vintage by remember(noteId, presetVintage) { mutableStateOf(presetVintage ?: "NV") }
    var dateTasted by remember(noteId, presetDateTasted) {
        mutableStateOf(presetDateTasted ?: System.currentTimeMillis())
    }
    var rating by remember { mutableStateOf(88f) }
    var includeRating by remember(noteId) { mutableStateOf(true) }
    var notes by remember { mutableStateOf("") }
    var rebuy by remember { mutableStateOf(false) }
    var recommendedBy by remember { mutableStateOf("") }
    var initialRating by remember { mutableStateOf("") }
    var hasSensoryEvaluation by remember { mutableStateOf(false) }
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
                includeRating = note.rating > 0.0
                rating = note.rating.toFloat().takeIf { it > 0f }?.coerceIn(50f, 100f) ?: 88f
                notes = note.notes.orEmpty()
                rebuy = note.rebuy
                recommendedBy = note.recommendedBy.orEmpty()
                initialRating = note.initialRating?.toString().orEmpty()
                sensoryData = note.sensoryData ?: SensoryData()
                hasSensoryEvaluation = note.sensoryData != null
            }
            loaded = true
        }
    }

    val locationData = remember(definitions) { buildWineLocationData(definitions) }
    val selectedDefinition = definitions.firstOrNull { it.definitionId == selectedDefinitionId }
    LaunchedEffect(selectedDefinition) {
        selectedDefinition?.let { definition ->
            if (wineSearch.isBlank() || selectedDefinitionId != presetDefinitionId) {
                wineSearch = "${definition.maker} - ${definition.name}"
            }
        }
    }

    val formattedDate = remember(dateTasted) {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(dateTasted))
    }
    val canSave = loaded && !missingNote && selectedDefinition != null && vintage.isNotBlank()

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
            Text("Browse wines by location", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LocationWheelPicker(
                locationData = locationData,
                selectedCountry = selectedCountry,
                selectedRegion = selectedRegion,
                selectedSubregion = selectedSubregion,
                onLocationChange = { country, region, subregion ->
                    selectedCountry = country
                    selectedRegion = region
                    selectedSubregion = subregion
                    val currentSelection = definitions.firstOrNull { it.definitionId == selectedDefinitionId }
                    if (currentSelection != null && !matchesWineLocation(
                            currentSelection, country, region, subregion
                        )
                    ) {
                        selectedDefinitionId = null
                        wineSearch = ""
                        wineMenuExpanded = false
                    }
                }
            )
            Text(
                "Narrow the catalog with the wheels, then select a wine below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wineMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(
                        androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryEditable,
                        true
                    ),
                    singleLine = true
                )
                val matchingDefinitions = definitions.filter { definition ->
                    val query = wineSearch.trim()
                    val matchesQuery = query.isBlank() ||
                        "${definition.maker} ${definition.name}".contains(query, ignoreCase = true)
                    matchesQuery && matchesWineLocation(
                        definition, selectedCountry, selectedRegion, selectedSubregion
                    )
                }.take(30)
                ExposedDropdownMenu(
                    expanded = wineMenuExpanded && matchingDefinitions.isNotEmpty(),
                    onDismissRequest = { wineMenuExpanded = false }
                ) {
                    matchingDefinitions.forEach { definition ->
                        DropdownMenuItem(
                            text = { Text("${definition.maker} - ${definition.name}") },
                            onClick = {
                                selectedDefinitionId = definition.definitionId
                                wineSearch = "${definition.maker} - ${definition.name}"
                                wineMenuExpanded = false
                            }
                        )
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = includeRating,
                    onCheckedChange = { includeRating = it }
                )
                Text("Record an overall rating")
            }
            if (includeRating) {
                OverallScoreSlider(
                    score = rating,
                    onScoreChange = { rating = it }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = recommendedBy,
                    onValueChange = { recommendedBy = it },
                    label = { Text("Recommended by") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = initialRating,
                    onValueChange = { initialRating = it.filter { character -> character.isDigit() }.take(3) },
                    label = { Text("Initial rating") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
            FilterChip(
                selected = rebuy,
                onClick = { rebuy = !rebuy },
                label = { Text(if (rebuy) "Would buy again" else "Recommend / rebuy") }
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Professional sensory evaluation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { hasSensoryEvaluation = !hasSensoryEvaluation }) {
                    Text(if (hasSensoryEvaluation) "Hide" else "Add")
                }
            }
            if (hasSensoryEvaluation) {
                SensoryEvaluationFields(
                    data = sensoryData,
                    onChange = { sensoryData = it }
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Tasting notes and impressions") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val definition = selectedDefinition ?: return@Button
                    val note = TastingNote(
                        noteId = existingNote?.noteId ?: 0L,
                        definitionId = definition.definitionId,
                        vintage = normalizeVintageInput(vintage),
                        dateTasted = dateTasted,
                        rating = if (includeRating) rating.toDouble() else 0.0,
                        notes = notes.takeIf { it.isNotBlank() },
                        rebuy = rebuy,
                        recommendedBy = recommendedBy.takeIf { it.isNotBlank() },
                        initialRating = initialRating.toIntOrNull(),
                        bottleId = existingNote?.bottleId,
                        sensoryData = sensoryData.takeIf { hasSensoryEvaluation },
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

private const val UNSPECIFIED_WINE_LOCATION = "Unspecified"

private fun optionalWineLocationLabel(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return null
    return trimmed.lowercase(Locale.getDefault())
        .split(" ")
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}

private fun wineLocationLabel(value: String?): String =
    optionalWineLocationLabel(value) ?: UNSPECIFIED_WINE_LOCATION

private fun buildWineLocationData(
    definitions: List<WineDefinition>
): Map<String, Map<String, List<String>>> = definitions
    .groupBy { wineLocationLabel(it.country) }
    .mapValues { (_, countryDefinitions) ->
        countryDefinitions
            .groupBy { wineLocationLabel(it.region) }
            .mapValues { (_, regionDefinitions) ->
                regionDefinitions.mapNotNull { optionalWineLocationLabel(it.subregion) }.distinct().sorted()
            }
    }

private fun matchesWineLocation(
    definition: WineDefinition,
    country: String?,
    region: String?,
    subregion: String?
): Boolean = (country == null || wineLocationLabel(definition.country) == country) &&
    (region == null || wineLocationLabel(definition.region) == region) &&
    (subregion == null || optionalWineLocationLabel(definition.subregion) == subregion)

private fun normalizeVintageInput(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.equals("(NV)", ignoreCase = true) || trimmed.equals("NV", ignoreCase = true)) "NV" else trimmed
}
