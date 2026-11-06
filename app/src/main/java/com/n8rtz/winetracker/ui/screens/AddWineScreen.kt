package com.n8rtz.winetracker.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.scanner.isBusy
import com.n8rtz.winetracker.scanner.WineLabelFraming
import com.n8rtz.winetracker.ui.components.LabelScanCameraPreview
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import java.util.Calendar
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.ui.ScanFormState
import com.yourapp.wine.ui.ScanFieldStatus
import com.yourapp.wine.ui.ScanFieldChoices
import com.yourapp.wine.ui.ScanReviewHeader
import com.yourapp.wine.ui.LabelScanActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWineScreen(
    viewModel: WineViewModel,
    scannerViewModel: ScannerViewModel,
    onWineAdded: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val formScrollState = rememberScrollState()
    val labelScanState by scannerViewModel.labelScanState.collectAsState()
    val allDefinitions by viewModel.allDefinitions.collectAsState(initial = emptyList())
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var showLabelCamera by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            cameraError = null
            showLabelCamera = true
        } else {
            cameraError = "Camera permission is needed to scan a wine label. You can still enter the wine manually."
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            cameraError = null
            if (!scannerViewModel.onLabelPhotoSelected(uri)) cameraError = "Wait for the previous scan to finish, then choose the photo again."
        }
    }
    DisposableEffect(lifecycleOwner, scannerViewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) scannerViewModel.cancelActiveLabelScan()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scannerViewModel.cancelActiveLabelScan()
        }
    }
    
    var maker by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var vintage by remember { mutableStateOf("NV") }
    var type by remember { mutableStateOf("Red") }
    var quantity by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    var drinkingWindowStart by remember { mutableStateOf("") }
    var drinkingWindowEnd by remember { mutableStateOf("") }

    // Cascading Location State
    var selectedCountry by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf("") }
    var selectedAppellation by remember { mutableStateOf("") }

    var scanForm by remember { mutableStateOf(ScanFormState()) }
    var lastScanReport by remember { mutableStateOf<ScanReport?>(null) }
    var lastScanId by remember { mutableStateOf(0L) }
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val syncFormToInputs = {
        maker = scanForm.fields.getValue(FieldId.PRODUCER).value
        name = scanForm.fields.getValue(FieldId.WINE_NAME).value
        vintage = scanForm.fields.getValue(FieldId.VINTAGE).value
        selectedCountry = scanForm.fields.getValue(FieldId.COUNTRY).value
        selectedRegion = scanForm.fields.getValue(FieldId.REGION).value
        selectedAppellation = scanForm.fields.getValue(FieldId.SUBREGION).value
    }
    val formValues = { mapOf(FieldId.PRODUCER to maker, FieldId.WINE_NAME to name, FieldId.VINTAGE to vintage,
        FieldId.COUNTRY to selectedCountry, FieldId.REGION to selectedRegion, FieldId.SUBREGION to selectedAppellation) }
    val selectChoice: (FieldId, String) -> Unit = { field, value ->
        scanForm = scanForm.selectChoice(field, value)
        syncFormToInputs()
    }
    val dismissChoices: (FieldId) -> Unit = { field -> scanForm = scanForm.dismissChoices(field) }
    val clearField: (FieldId) -> Unit = { field ->
        scanForm = scanForm.clearSuggestion(field, lastScanReport?.fill, formValues(), currentYear)
        syncFormToInputs()
    }
    val fieldChoices: @Composable (FieldId) -> Unit = { field ->
        ScanFieldChoices(scanForm.fields.getValue(field),
            onSelectChoice = { value -> selectChoice(field, value) },
            onDismissChoices = { dismissChoices(field) }, enabled = !labelScanState.isBusy)
    }
    val retake: () -> Unit = {
        if (!labelScanState.isBusy) {
            cameraError = null
            if (cameraPermissionGranted) showLabelCamera = true
            else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val clearIcon: @Composable (FieldId, String) -> Unit = { field, value ->
        if (value.isNotBlank()) IconButton(onClick = { clearField(field) }, enabled = !labelScanState.isBusy) {
            Icon(Icons.Default.Close, contentDescription = "Clear ${field.name.lowercase().replace('_', ' ')}")
        }
    }
    val fieldStatus: @Composable (FieldId) -> Unit = { field ->
        ScanFieldStatus(scanForm.fields.getValue(field),
            onConfirm = { scanForm = scanForm.confirm(field) }, onRetake = retake,
            enabled = !labelScanState.isBusy, showChoices = false)
    }
    LaunchedEffect(showLabelCamera) {
        if (showLabelCamera) {
            focusManager.clearFocus()
            scannerViewModel.warmLabelScanner()
        }
    }
    LaunchedEffect(lastScanId) {
        if (lastScanId != 0L) formScrollState.scrollTo(0)
    }
    LaunchedEffect((labelScanState as? LabelScanState.Review)?.requestId) {
        val review = labelScanState as? LabelScanState.Review ?: return@LaunchedEffect
        val values = mapOf(FieldId.PRODUCER to maker, FieldId.WINE_NAME to name, FieldId.VINTAGE to vintage,
            FieldId.COUNTRY to selectedCountry, FieldId.REGION to selectedRegion, FieldId.SUBREGION to selectedAppellation)
        scanForm = scanForm.apply(review.result.fill, values)
        maker = scanForm.fields.getValue(FieldId.PRODUCER).value
        name = scanForm.fields.getValue(FieldId.WINE_NAME).value
        vintage = scanForm.fields.getValue(FieldId.VINTAGE).value
        selectedCountry = scanForm.fields.getValue(FieldId.COUNTRY).value
        selectedRegion = scanForm.fields.getValue(FieldId.REGION).value
        selectedAppellation = scanForm.fields.getValue(FieldId.SUBREGION).value
        lastScanReport = review.result
        lastScanId = review.requestId
        showLabelCamera = false
        scannerViewModel.dismissLabelScan(review.requestId)
    }

    val availableCountries = (
        WineRepository.Companion.locationData.keys +
            allDefinitions.mapNotNull { it.country.trim().takeIf(String::isNotEmpty) } +
            listOfNotNull(selectedCountry.takeIf(String::isNotEmpty))
        ).distinct().sorted()
    val availableRegions = if (selectedCountry.isNotEmpty()) {
        (
            WineRepository.Companion.locationData[selectedCountry]?.keys.orEmpty() +
                allDefinitions
                    .filter { it.country.trim().equals(selectedCountry, ignoreCase = true) }
                    .mapNotNull { it.region?.trim()?.takeIf(String::isNotEmpty) } +
                listOfNotNull(selectedRegion.takeIf(String::isNotEmpty))
            ).distinct().sorted()
    } else emptyList()
    val availableAppellations = if (selectedCountry.isNotEmpty() && selectedRegion.isNotEmpty()) {
        (
            WineRepository.Companion.locationData[selectedCountry]?.get(selectedRegion).orEmpty() +
                allDefinitions
                    .filter {
                        it.country.trim().equals(selectedCountry, ignoreCase = true) &&
                            it.region?.trim().equals(selectedRegion, ignoreCase = true)
                    }
                    .mapNotNull { it.subregion?.trim()?.takeIf(String::isNotEmpty) } +
                listOfNotNull(selectedAppellation.takeIf(String::isNotEmpty))
            ).distinct().sorted()
    } else emptyList()

    val vintages = (currentYear downTo 1900).map { it.toString() } + listOf("NV")
    Scaffold(
        topBar = { TopAppBar(title = { Text("Add New Wine") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(formScrollState)
        ) {
            // Form Section
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelScanActions(busy = labelScanState.isBusy,
                    progressMessage = if (showLabelCamera) null else when (labelScanState) {
                        is LabelScanState.ImportingPhoto -> "Opening selected photo…"
                        is LabelScanState.Processing -> "Reading selected photo on this device…"
                        is LabelScanState.FinishingCancelledScan -> "Finishing the previous scan…"
                        else -> null
                    }, onCamera = retake, onChoosePhoto = {
                        cameraError = null
                        scannerViewModel.warmLabelScanner()
                        runCatching { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            .onFailure { cameraError = "Photo picker could not open. You can still use the camera or enter details manually." }
                    })
                (cameraError ?: (labelScanState as? LabelScanState.Error)?.message)?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                lastScanReport?.takeUnless { labelScanState.isBusy }?.let { report ->
                    ScanReviewHeader(report = report, form = scanForm, scanId = lastScanId,
                        allowDebugOverlay = BuildConfig.DEBUG, canRetake = !labelScanState.isBusy, onRetake = retake)
                }
                fieldStatus(FieldId.PRODUCER)
                // Autocomplete Producer Search
                var producerSearchExpanded by remember(lastScanId) { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = producerSearchExpanded,
                    onExpandedChange = { producerSearchExpanded = !producerSearchExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = maker,
                        onValueChange = { 
                            maker = it
                            scanForm = scanForm.edit(FieldId.PRODUCER, it)
                            producerSearchExpanded = true
                        },
                        label = { Text("Producer / Winery") },
                        trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                            clearIcon(FieldId.PRODUCER, maker)
                            Icon(Icons.Default.Search, contentDescription = "Search producers")
                        } },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true)
                    )
                    
                    val filteredDefs = allDefinitions.filter { it.maker.contains(maker, ignoreCase = true) }.distinctBy { it.maker }
                    if (maker.isNotEmpty() && filteredDefs.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = producerSearchExpanded, 
                            onDismissRequest = { producerSearchExpanded = false },
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            filteredDefs.take(5).forEach { def ->
                                DropdownMenuItem(
                                    text = { Text(def.maker) },
                                    onClick = {
                                        maker = def.maker
                                        scanForm = scanForm.edit(FieldId.PRODUCER, def.maker)
                                        // Auto-fill location if known
                                        selectedCountry = def.country
                                        selectedRegion = def.region ?: ""
                                        selectedAppellation = def.subregion ?: ""
                                        scanForm = scanForm.edit(FieldId.COUNTRY, selectedCountry)
                                            .edit(FieldId.REGION, selectedRegion).edit(FieldId.SUBREGION, selectedAppellation)
                                        type = def.type
                                        producerSearchExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                fieldChoices(FieldId.PRODUCER)

                fieldStatus(FieldId.WINE_NAME)
                // Autocomplete Wine Name Search
                var nameSearchExpanded by remember(lastScanId) { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = nameSearchExpanded,
                    onExpandedChange = { nameSearchExpanded = !nameSearchExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { 
                            name = it
                            scanForm = scanForm.edit(FieldId.WINE_NAME, it)
                            nameSearchExpanded = true
                        },
                        label = { Text("Wine Name") },
                        trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                            clearIcon(FieldId.WINE_NAME, name)
                            Icon(Icons.Default.Search, contentDescription = "Search wine names")
                        } },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true)
                    )
                    
                    val filteredNames = allDefinitions
                        .filter { 
                            (maker.isBlank() || it.maker.equals(maker, ignoreCase = true)) && 
                            it.name.contains(name, ignoreCase = true) 
                        }
                        .distinctBy { it.name }
                    
                    if (name.isNotEmpty() && filteredNames.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = nameSearchExpanded, 
                            onDismissRequest = { nameSearchExpanded = false },
                            modifier = Modifier.heightIn(max = 200.dp)
                        ) {
                            filteredNames.take(5).forEach { def ->
                                DropdownMenuItem(
                                    text = { Text(def.name) },
                                    onClick = {
                                        name = def.name
                                        scanForm = scanForm.edit(FieldId.WINE_NAME, def.name)
                                        // Auto-fill other details if not already set or if selecting this name provides them
                                        if (maker.isBlank()) {
                                            maker = def.maker
                                            scanForm = scanForm.edit(FieldId.PRODUCER, def.maker)
                                        }
                                        selectedCountry = def.country
                                        selectedRegion = def.region ?: ""
                                        selectedAppellation = def.subregion ?: ""
                                        scanForm = scanForm.edit(FieldId.COUNTRY, selectedCountry)
                                            .edit(FieldId.REGION, selectedRegion).edit(FieldId.SUBREGION, selectedAppellation)
                                        type = def.type
                                        nameSearchExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                fieldChoices(FieldId.WINE_NAME)
                
                fieldStatus(FieldId.VINTAGE)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Vintage Dropdown
                    var vintageExpanded by remember(lastScanId) { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = vintageExpanded,
                        onExpandedChange = { vintageExpanded = !vintageExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = vintage,
                            onValueChange = { vintage = it; scanForm = scanForm.edit(FieldId.VINTAGE, it) },
                            label = { Text("Vintage") },
                            trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                                clearIcon(FieldId.VINTAGE, vintage)
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = vintageExpanded)
                            } },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        ExposedDropdownMenu(
                            expanded = vintageExpanded, 
                            onDismissRequest = { vintageExpanded = false },
                            modifier = Modifier.heightIn(max = 200.dp) // Scrollable list constraint
                        ) {
                            vintages.forEach { year ->
                                DropdownMenuItem(
                                    text = { Text(year) },
                                    onClick = {
                                        vintage = year
                                        scanForm = scanForm.edit(FieldId.VINTAGE, year)
                                        vintageExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    // Simple Type Dropdown
                    var typeExpanded by remember(lastScanId) { mutableStateOf(false) }
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
                fieldChoices(FieldId.VINTAGE)

                // Cascading Geography Section
                Text("Geography", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                
                fieldStatus(FieldId.COUNTRY)
                // Country Dropdown
                var countryExpanded by remember(lastScanId) { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = countryExpanded,
                    onExpandedChange = { countryExpanded = !countryExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedCountry,
                        onValueChange = { selectedCountry = it; scanForm = scanForm.edit(FieldId.COUNTRY, it) },
                        label = { Text("Country") },
                        trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                            clearIcon(FieldId.COUNTRY, selectedCountry)
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = countryExpanded)
                        } },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true)
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
                                    if (selectedCountry != country.toString()) {
                                        selectedCountry = country.toString()
                                        scanForm = scanForm.edit(FieldId.COUNTRY, selectedCountry)
                                        selectedRegion = "" // Reset children
                                        selectedAppellation = ""
                                        scanForm = scanForm.edit(FieldId.REGION, "").edit(FieldId.SUBREGION, "")
                                    }
                                    countryExpanded = false
                                }
                            )
                        }
                    }
                }
                fieldChoices(FieldId.COUNTRY)

                // Region stays visible and editable even when the fixture gazetteer has no suggestions.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    fieldStatus(FieldId.REGION)
                    var regionExpanded by remember(lastScanId) { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = regionExpanded,
                        onExpandedChange = { regionExpanded = !regionExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedRegion,
                            onValueChange = { selectedRegion = it; scanForm = scanForm.edit(FieldId.REGION, it) },
                            label = { Text("Region") },
                            trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                                clearIcon(FieldId.REGION, selectedRegion)
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionExpanded)
                            } },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true)
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
                                        if (selectedRegion != region.toString()) {
                                            selectedRegion = region.toString()
                                            scanForm = scanForm.edit(FieldId.REGION, selectedRegion)
                                            selectedAppellation = "" // Reset child
                                            scanForm = scanForm.edit(FieldId.SUBREGION, "")
                                        }
                                        regionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    fieldChoices(FieldId.REGION)
                }

                if(selectedAppellation.isNotBlank() && selectedRegion.isBlank()) {
                    Text("Select or enter a Region before saving a Subregion.",color=MaterialTheme.colorScheme.error)
                }
                // Subregion stays visible and editable for boutique/unknown wines.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    fieldStatus(FieldId.SUBREGION)
                    var appellationExpanded by remember(lastScanId) { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = appellationExpanded,
                        onExpandedChange = { appellationExpanded = !appellationExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedAppellation,
                            onValueChange = { selectedAppellation = it; scanForm = scanForm.edit(FieldId.SUBREGION, it) },
                            label = { Text("Subregion / Appellation") },
                            trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                                clearIcon(FieldId.SUBREGION, selectedAppellation)
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = appellationExpanded)
                            } },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true)
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
                                        scanForm = scanForm.edit(FieldId.SUBREGION, selectedAppellation)
                                        appellationExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    fieldChoices(FieldId.SUBREGION)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(value = quantity, onValueChange = { quantity = it }, label = { Text("Quantity") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Price") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }

                Text("Drinking Window (Optional)", style = MaterialTheme.typography.titleSmall)
                val startYear = drinkingWindowStart.toIntOrNull()
                val endYear = drinkingWindowEnd.toIntOrNull()
                val yearsAreComplete = (drinkingWindowStart.isBlank() || drinkingWindowStart.length == 4) &&
                        (drinkingWindowEnd.isBlank() || drinkingWindowEnd.length == 4)
                val isWindowValid = yearsAreComplete && (startYear == null || endYear == null || startYear <= endYear)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = drinkingWindowStart,
                        onValueChange = { drinkingWindowStart = it.filter { character -> character.isDigit() }.take(4) },
                        label = { Text("Start year") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = drinkingWindowEnd,
                        onValueChange = { drinkingWindowEnd = it.filter { character -> character.isDigit() }.take(4) },
                        label = { Text("End year") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }

                if (!isWindowValid) {
                    Text(
                        if (!yearsAreComplete) "Enter each drinking-window year as four digits."
                        else "Start year must be no later than the end year.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    enabled = (selectedAppellation.isBlank() || selectedRegion.isNotBlank()) && isWindowValid && maker.isNotBlank() && name.isNotBlank() && !scanForm.hasPendingConfirmation && !labelScanState.isBusy,
                    onClick = {
                        viewModel.addWine(
                            maker = maker,
                            name = name,
                            vintage = vintage,
                            type = type,
                            country = selectedCountry,
                            region = selectedRegion.takeIf { it.isNotEmpty() },
                            subregion = selectedAppellation.takeIf { it.isNotEmpty() },
                            quantity = quantity.toIntOrNull() ?: 1,
                            price = price.toDoubleOrNull(),
                            drinkingWindowStart = drinkingWindowStart.toIntOrNull(),
                            drinkingWindowEnd = drinkingWindowEnd.toIntOrNull()
                        )
                        onWineAdded()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save to Cellar")
                }
            }
        }
    }

    if (showLabelCamera) {
        LabelScanCameraDialog(
            scannerViewModel = scannerViewModel,
            cameraReady = cameraReady,
            cameraError = cameraError,
            onCameraError = { cameraError = it },
            onCameraReadyChanged = { ready ->
                cameraReady = ready
                if (ready) cameraError = null
            },
            onDismiss = {
                scannerViewModel.cancelActiveLabelScan()
                showLabelCamera = false
                cameraReady = false
            }
        )
    }


}

@Composable
private fun LabelScanCameraDialog(
    scannerViewModel: ScannerViewModel,
    cameraReady: Boolean,
    cameraError: String?,
    onCameraError: (String) -> Unit,
    onCameraReadyChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val scanState by scannerViewModel.labelScanState.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                LabelScanCameraPreview(
                    scannerViewModel = scannerViewModel,
                    onCameraError = onCameraError,
                    onCameraReadyChanged = onCameraReadyChanged,
                    modifier = Modifier.fillMaxSize()
                )

                // A bottle label is portrait, so the guide is portrait too: the old full-width box
                // was wider than any label and told the user to frame something no bottle matches.
                // WineLabelFraming holds the shape and the guarantee that whatever the user fits
                // inside it is inside every crop the scanner reads.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight(WineLabelFraming.GUIDE_HEIGHT_FRACTION)
                        .aspectRatio(WineLabelFraming.ASPECT_RATIO)
                        .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close scanner", tint = Color.White)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Scan wine label", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Text(
                                "Fit the label in the frame, then take a photo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        cameraError != null -> Text(
                            cameraError,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        scanState is LabelScanState.Error -> Text(
                            (scanState as LabelScanState.Error).message,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        scanState is LabelScanState.FinishingCancelledScan -> Text(
                            "Finishing the previous scan…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        !cameraReady -> Text(
                            "Starting camera…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (scanState is LabelScanState.Error || cameraError != null) {
                        Text("RED · No fields filled", color = Color(0xffffb4ab), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = onDismiss) { Text("Enter manually", color = Color.White) }
                    }
                    when (scanState) {
                        LabelScanState.Idle, is LabelScanState.Error -> Button(
                            enabled = cameraReady && cameraError == null,
                            onClick = { scannerViewModel.requestLabelScan() },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Icon(Icons.Default.Camera, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (scanState is LabelScanState.Error) "Retake Label" else "Take Label Photo")
                        }
                        is LabelScanState.ImportingPhoto -> LabelScanProgress("Opening selected photo…")
                        is LabelScanState.CapturingPhoto -> LabelScanProgress("Taking high-quality photo…")
                        is LabelScanState.Processing -> LabelScanProgress("Reading the label offline… This can take a little while; review the suggestions when finished.")
                        is LabelScanState.FinishingCancelledScan -> LabelScanProgress("Finishing the previous scan…")
                        is LabelScanState.Review -> Text(
                            "Scan complete. Review the suggestions.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Text(
                        "Hold the phone steady and make sure the label is in focus.",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun LabelScanProgress(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = Color.White
        )
        Spacer(Modifier.width(10.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}
