@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.TastingNote
import com.n8rtz.winetracker.data.local.displayVintage
import com.n8rtz.winetracker.data.local.normalizeSensoryData
import com.n8rtz.winetracker.data.local.withDerivedAromas
import com.n8rtz.winetracker.ui.components.CommonFaultsFields
import com.n8rtz.winetracker.ui.components.OverallScoreSlider
import com.n8rtz.winetracker.data.local.TastingPhase
import com.n8rtz.winetracker.ui.components.PhaseAromaWheelDialog
import com.n8rtz.winetracker.ui.components.SectionedSensoryFields
import com.n8rtz.winetracker.ui.components.WheelPicker
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TastingNoteDetailScreen(
    viewModel: TastingNoteViewModel,
    noteId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val definitions by viewModel.allDefinitions.collectAsState(initial = emptyList())
    val cellarPrices by viewModel.cellarPrices.collectAsState(initial = emptyMap())
    var note by remember(noteId) { mutableStateOf<TastingNote?>(null) }
    var loaded by remember(noteId) { mutableStateOf(false) }
    var showAromaWheel by remember { mutableStateOf(false) }
    var aromaWheelEditMode by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        note = viewModel.getTastingNoteById(noteId)
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    val currentNote = note
    if (currentNote == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tasting note not found")
                androidx.compose.material3.TextButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }

    val definition = definitions.firstOrNull { it.definitionId == currentNote.definitionId }
    val sensoryData = remember(currentNote.sensoryData) {
        normalizeSensoryData(currentNote.sensoryData)
    }
    val wineTitle = listOfNotNull(
        definition?.maker?.takeIf { it.isNotBlank() },
        definition?.name?.takeIf { it.isNotBlank() }
    ).joinToString(" - ").let { name ->
        if (name.isBlank()) displayVintage(currentNote.vintage)
        else "$name (${displayVintage(currentNote.vintage)})"
    }
    val formattedDate = remember(currentNote.dateTasted) {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(currentNote.dateTasted))
    }
    val recordedPrice = currentNote.price
        ?: cellarPrices[currentNote.definitionId to displayVintage(currentNote.vintage)]
    val reviewerRating = currentNote.reviewerRating?.coerceIn(50, 100) ?: 80
    val valueScore = currentNote.valueScore?.coerceIn(1, 10) ?: 5
    val rating = currentNote.rating.toFloat().takeIf { it > 0f }?.coerceIn(50f, 100f) ?: 88f
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Tasting Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit tasting note")
                    }
                },
                windowInsets = WindowInsets(0.dp),
                scrollBehavior = scrollBehavior
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
            OutlinedTextField(
                value = formattedDate,
                onValueChange = {},
                readOnly = true,
                label = { Text("Date tasted") },
                trailingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = wineTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            OverallScoreSlider(
                score = rating,
                scoreSelected = currentNote.rating > 0.0,
                enabled = false,
                onScoreChange = {}
            )

            SectionedSensoryFields(
                data = sensoryData,
                definitionColor = definition?.defaultColorHsv,
                legacyAromasUnresolved = sensoryData.aromaObservations.isEmpty() && sensoryData.aromas.isNotEmpty(),
                // Long-pressing a chip here calls up aroma edit mode;
                // categorising/deleting chips saves straight to the note.
                onDataChange = { newData ->
                    val updatedNote = currentNote.copy(
                        sensoryData = withDerivedAromas(newData)
                    )
                    viewModel.saveTastingNote(updatedNote) { note = updatedNote }
                },
                onOpenWheel = { aromaWheelEditMode = false; showAromaWheel = true },
                onAssignLegacy = {},
                readOnly = true,
                allowAromaEdit = true
            )

            if (showAromaWheel) {
                // Viewing mode: the wheel opens locked — aromas can be
                // browsed by phase but not changed.
                PhaseAromaWheelDialog(
                    initialObservations = sensoryData.aromaObservations,
                    initialPhase = TastingPhase.AROMA_BEFORE_TASTING,
                    // Confirm only fires after switching to edit mode via
                    // the pencil: persist the new aromas on the note.
                    onConfirm = { _, observations ->
                        val updatedNote = currentNote.copy(
                            sensoryData = withDerivedAromas(
                                sensoryData.copy(aromaObservations = observations)
                            )
                        )
                        viewModel.saveTastingNote(updatedNote) {
                            note = updatedNote
                            showAromaWheel = false
                        }
                    },
                    onDismiss = { showAromaWheel = false },
                    readOnly = !aromaWheelEditMode,
                    onEditRequest = { aromaWheelEditMode = true }
                )
            }
            CommonFaultsFields(data = sensoryData, onChange = {}, readOnly = true)

            OutlinedTextField(
                value = currentNote.notes.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Tasting notes and impressions") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Value", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                currentNote.valueScore?.toString() ?: "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text("/10", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Slider(
                    value = valueScore.toFloat(),
                    onValueChange = {},
                    enabled = false,
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        disabledThumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        disabledActiveTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeTickColor = MaterialTheme.colorScheme.onPrimary,
                        disabledActiveTickColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        disabledInactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Price", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        recordedPrice?.let { "$${"%.2f".format(it)}" } ?: "Not recorded",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            FilterChip(
                selected = currentNote.rebuy,
                onClick = {},
                enabled = false,
                label = { Text(if (currentNote.rebuy) "Would buy again" else "Recommend / rebuy") }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = currentNote.recommendedBy.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Recommended by") },
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
                WheelPicker(
                    items = remember { (50..100).map { it.toString() } },
                    selectedIndex = reviewerRating - 50,
                    onSelectedChange = {},
                    label = "Rating",
                    itemHeight = 32.dp,
                    visibleCount = 3,
                    modifier = Modifier.weight(1f),
                    enabled = false
                )
            }
        }
    }
}
