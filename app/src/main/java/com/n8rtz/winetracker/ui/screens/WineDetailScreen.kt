package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.n8rtz.winetracker.data.local.WineBottle
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.ui.components.datePickerMillisToTimestamp
import com.n8rtz.winetracker.ui.components.timestampToDatePickerMillis
import com.n8rtz.winetracker.viewmodels.WineViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WineDetailScreen(
    bottleId: Long,
    viewModel: WineViewModel,
    onBack: () -> Unit,
    onConsumed: (String, Long) -> Unit,
    onAddNote: (Long, String) -> Unit,
    onEditNote: (Long) -> Unit
) {
    val inventory by viewModel.activeInventory.collectAsState(initial = emptyList())
    val item = remember(inventory, bottleId) { inventory.find { it.bottle.bottleId == bottleId } }
    
    if (item == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Wine not found")
        }
        return
    }

    val tastingNotes by remember(item.definition.definitionId, item.bottle.vintage) {
        viewModel.tastingNotesForWine(item.definition.definitionId, item.bottle.vintage)
    }.collectAsState(initial = emptyList())

    var editedWine by remember(item.definition) { mutableStateOf(item.definition) }
    var editedBottle by remember(item.bottle) { mutableStateOf(item.bottle) }
    val bottle = item.bottle

    // Form states for editing
    var maker by remember(editedWine.maker) { mutableStateOf(editedWine.maker) }
    var name by remember(editedWine.name) { mutableStateOf(editedWine.name) }
    var vintage by remember(editedBottle.vintage) { mutableStateOf(editedBottle.vintage) }
    var type by remember(editedWine.type) { mutableStateOf(editedWine.type) }
    var quantity by remember(editedBottle.quantity) { mutableStateOf(editedBottle.quantity.toString()) }
    var price by remember(editedBottle.price) { mutableStateOf(editedBottle.price?.toString() ?: "") }
    var drinkingWindowStart by remember(editedWine.drinkingWindowStart) {
        mutableStateOf(editedWine.drinkingWindowStart?.toString() ?: "")
    }
    var drinkingWindowEnd by remember(editedWine.drinkingWindowEnd) {
        mutableStateOf(editedWine.drinkingWindowEnd?.toString() ?: "")
    }

    var selectedCountry by remember(editedWine.country) { mutableStateOf(editedWine.country) }
    var selectedRegion by remember(editedWine.region) { mutableStateOf(editedWine.region ?: "") }
    var selectedAppellation by remember(editedWine.subregion) { mutableStateOf(editedWine.subregion ?: "") }

    val availableCountries = WineRepository.Companion.locationData.keys.toList().sorted()
    val availableRegions = if (selectedCountry.isNotEmpty()) {
        WineRepository.Companion.locationData[selectedCountry]?.keys?.toList()?.sorted() ?: emptyList()
    } else emptyList()
    val availableAppellations = if (selectedCountry.isNotEmpty() && selectedRegion.isNotEmpty()) {
        WineRepository.Companion.locationData[selectedCountry]?.get(selectedRegion)?.sorted() ?: emptyList()
    } else emptyList()

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val vintages = (currentYear downTo 1950).map { it.toString() } + listOf("NV")

    var showConsumeDialog by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var drinkingWindowError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(if (isEditing) "Edit Wine" else editedWine.maker, style = MaterialTheme.typography.labelMedium)
                        Text(editedWine.name)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (isEditing) {
                            val startYear = drinkingWindowStart.toIntOrNull()
                            val endYear = drinkingWindowEnd.toIntOrNull()
                            val yearsAreComplete = (drinkingWindowStart.isBlank() || drinkingWindowStart.length == 4) &&
                                    (drinkingWindowEnd.isBlank() || drinkingWindowEnd.length == 4)
                            if (!yearsAreComplete) {
                                drinkingWindowError = "Enter each drinking-window year as four digits."
                                return@IconButton
                            }
                            if (startYear != null && endYear != null && startYear > endYear) {
                                drinkingWindowError = "Start year must be no later than the end year."
                                return@IconButton
                            }
                            drinkingWindowError = null
                            viewModel.updateWine(
                                editedWine.copy(
                                    maker = maker,
                                    name = name,
                                    type = type,
                                    country = selectedCountry,
                                    region = selectedRegion.takeIf { it.isNotBlank() },
                                    subregion = selectedAppellation.takeIf { it.isNotBlank() },
                                    drinkingWindowStart = drinkingWindowStart.toIntOrNull(),
                                    drinkingWindowEnd = drinkingWindowEnd.toIntOrNull()
                                ),
                                editedBottle.copy(
                                    vintage = vintage,
                                    quantity = quantity.toIntOrNull() ?: editedBottle.quantity,
                                    price = price.toDoubleOrNull()
                                )
                            )
                            isEditing = false
                        } else {
                            isEditing = true
                        }
                    }) {
                        Icon(if (isEditing) Icons.Default.Check else Icons.Default.Edit, contentDescription = "Edit")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (editedWine.imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(editedWine.imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Wine bottle",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        Icons.Default.WineBar, 
                        null, 
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            if (isEditing) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Producer
                    OutlinedTextField(
                        value = maker,
                        onValueChange = { maker = it },
                        label = { Text("Producer / Winery") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Wine Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Wine Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Vintage Dropdown
                        var vintageExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = vintageExpanded,
                            onExpandedChange = { vintageExpanded = !vintageExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = vintage,
                                onValueChange = { vintage = it },
                                label = { Text("Vintage") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vintageExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            ExposedDropdownMenu(
                                expanded = vintageExpanded,
                                onDismissRequest = { vintageExpanded = false },
                                modifier = Modifier.heightIn(max = 200.dp)
                            ) {
                                vintages.forEach { year ->
                                    DropdownMenuItem(
                                        text = { Text(year) },
                                        onClick = {
                                            vintage = year
                                            vintageExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Type Dropdown
                        var typeExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = typeExpanded,
                            onExpandedChange = { typeExpanded = !typeExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = type,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Type") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                            )
                            ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                                listOf("Red", "White", "Sparkling", "Rose", "Dessert").forEach { selection ->
                                    DropdownMenuItem(
                                        text = { Text(selection) },
                                        onClick = {
                                            type = selection
                                            typeExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Geography
                    Text("Geography", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

                    // Country Dropdown
                    var countryExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = countryExpanded,
                        onExpandedChange = { countryExpanded = !countryExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedCountry,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Country") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = countryExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                        )
                        ExposedDropdownMenu(
                            expanded = countryExpanded,
                            onDismissRequest = { countryExpanded = false },
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            availableCountries.forEach { country ->
                                DropdownMenuItem(
                                    text = { Text(country) },
                                    onClick = {
                                        if (selectedCountry != country) {
                                            selectedCountry = country
                                            selectedRegion = ""
                                            selectedAppellation = ""
                                        }
                                        countryExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Region Dropdown
                    if (availableRegions.isNotEmpty()) {
                        var regionExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = regionExpanded,
                            onExpandedChange = { regionExpanded = !regionExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedRegion,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Region") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                            )
                            ExposedDropdownMenu(
                                expanded = regionExpanded,
                                onDismissRequest = { regionExpanded = false },
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                availableRegions.forEach { region ->
                                    DropdownMenuItem(
                                        text = { Text(region) },
                                        onClick = {
                                            if (selectedRegion != region) {
                                                selectedRegion = region
                                                selectedAppellation = ""
                                            }
                                            regionExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Appellation Dropdown
                    if (availableAppellations.isNotEmpty()) {
                        var appellationExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = appellationExpanded,
                            onExpandedChange = { appellationExpanded = !appellationExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedAppellation,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Appellation") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = appellationExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                            )
                            ExposedDropdownMenu(
                                expanded = appellationExpanded,
                                onDismissRequest = { appellationExpanded = false },
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) {
                                availableAppellations.forEach { appellation ->
                                    DropdownMenuItem(
                                        text = { Text(appellation) },
                                        onClick = {
                                            selectedAppellation = appellation
                                            appellationExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(
                            value = quantity,
                            onValueChange = { quantity = it },
                            label = { Text("Quantity") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = price,
                            onValueChange = { price = it },
                            label = { Text("Price") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Text("Drinking Window (Optional)", style = MaterialTheme.typography.titleSmall)
                    drinkingWindowError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = drinkingWindowStart,
                            onValueChange = {
                                drinkingWindowStart = it.filter { character -> character.isDigit() }.take(4)
                                drinkingWindowError = null
                            },
                            label = { Text("Start year") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = drinkingWindowEnd,
                            onValueChange = {
                                drinkingWindowEnd = it.filter { character -> character.isDigit() }.take(4)
                                drinkingWindowError = null
                            },
                            label = { Text("End year") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                }
            } else {
                Text(
                    text = "${editedWine.maker} ${editedWine.name}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = com.n8rtz.winetracker.data.local.displayVintage(bottle.vintage),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(16.dp))

                DetailRow(Icons.Default.Public, "Origin", run {
                    val subregion = editedWine.subregion?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                    val region = editedWine.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                    val country = editedWine.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                    listOfNotNull(subregion, region, country).joinToString(", ")
                })
                DetailRow(Icons.Default.Category, "Type", editedWine.type)
                DetailRow(Icons.Default.Inventory, "In Cellar", "${bottle.quantity} ${bottle.qtyType ?: "Bottles"}")
                DetailRow(Icons.Default.AttachMoney, "Purchase Price", "$${"%.2f".format(bottle.price ?: 0.0)}")
                val windowStart = editedWine.drinkingWindowStart
                val windowEnd = editedWine.drinkingWindowEnd
                DetailRow(
                    Icons.Default.Schedule,
                    "Drinking Window",
                    if (windowStart == null && windowEnd == null) "Not set" else "${windowStart ?: "Any time"} – ${windowEnd ?: "No end year"}"
                )
                Spacer(Modifier.height(16.dp))
                Text("Tasting Notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (tastingNotes.isEmpty()) {
                    Text("No notes for this wine yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    tastingNotes.forEach { note ->
                        val noteDate = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date(note.dateTasted))
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onEditNote(note.noteId) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                val score = note.rating.takeIf { it > 0.0 }?.toInt()?.let { "$it points" } ?: "Unrated"
                                Text("$noteDate · $score", fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = note.notes?.takeIf { it.isNotBlank() } ?: "No written notes",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3
                                )
                                if (!note.recommendedBy.isNullOrBlank()) {
                                    Text("Recommended by ${note.recommendedBy}", style = MaterialTheme.typography.bodySmall)
                                }
                                note.reviewerRating?.let { reviewerRating ->
                                    Text("Rating: $reviewerRating", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onAddNote(editedWine.definitionId, bottle.vintage) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.NoteAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Tasting Note")
                }
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(32.dp))

            if (!isEditing) {
                Button(
                    onClick = { showConsumeDialog = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.LocalDrink, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Mark as Consumed", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showConsumeDialog) {
            ConsumeDialog(
                onDismiss = { showConsumeDialog = false },
                onConfirm = { rating, notes, rebuy, consumptionDate ->
                    showConsumeDialog = false
                    viewModel.consumeBottle(
                        bottleId = bottle.bottleId,
                        rating = rating,
                        notes = notes,
                        rebuy = rebuy,
                        consumptionDate = consumptionDate
                    ) { eventId ->
                        onConsumed("Consumed 1 bottle of ${editedWine.name}", eventId)
                        onBack()
                    }
                }
            )
        }
    }
}

@Composable
fun DetailRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumeDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double?, String?, Boolean, Long) -> Unit
) {
    var rating by remember { mutableStateOf(90f) }
    var notes by remember { mutableStateOf("") }
    var rebuy by remember { mutableStateOf(false) }
    var includeTastingNote by remember { mutableStateOf(true) }
    var consumptionDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val formattedDate = remember(consumptionDate) {
        java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(consumptionDate))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Consumption") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Consumption date", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(formattedDate)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeTastingNote, onCheckedChange = { includeTastingNote = it })
                    Text("Add a tasting note")
                }
                if (includeTastingNote) {
                    Text("Rate this bottle (100-point scale): ${rating.toInt()}")
                    Slider(
                        value = rating,
                        onValueChange = { rating = it },
                        valueRange = 50f..100f,
                        steps = 49
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Tasting Notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rebuy, onCheckedChange = { rebuy = it })
                        Text("Would buy again")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    rating.takeIf { includeTastingNote }?.toDouble(),
                    notes.takeIf { includeTastingNote },
                    rebuy && includeTastingNote,
                    consumptionDate
                )
            }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showDatePicker) {
        val datePickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = timestampToDatePickerMillis(consumptionDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { consumptionDate = datePickerMillisToTimestamp(it) }
                    showDatePicker = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
