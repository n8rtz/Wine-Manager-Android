package com.n8rtz.winetracker.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.scanner.WineLabelFraming
import com.n8rtz.winetracker.ui.components.LabelScanCameraPreview
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWineScreen(
    viewModel: WineViewModel,
    scannerViewModel: ScannerViewModel,
    onWineAdded: () -> Unit
) {
    val context = LocalContext.current
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

    DisposableEffect(scannerViewModel) {
        onDispose { scannerViewModel.cancelActiveLabelScan() }
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

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val vintages = (currentYear downTo 1950).map { it.toString() } + listOf("NV")
    Scaffold(
        topBar = { TopAppBar(title = { Text("Add New Wine") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Form Section
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        cameraError = null
                        if (cameraPermissionGranted) {
                            showLabelCamera = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Camera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan label with camera")
                }
                cameraError?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Autocomplete Producer Search
                var producerSearchExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = producerSearchExpanded,
                    onExpandedChange = { producerSearchExpanded = !producerSearchExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = maker,
                        onValueChange = { 
                            maker = it
                            producerSearchExpanded = true
                        },
                        label = { Text("Producer / Winery") },
                        trailingIcon = { Icon(Icons.Default.Search, null) },
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
                                        // Auto-fill location if known
                                        selectedCountry = def.country
                                        selectedRegion = def.region ?: ""
                                        selectedAppellation = def.subregion ?: ""
                                        type = def.type
                                        producerSearchExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Autocomplete Wine Name Search
                var nameSearchExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = nameSearchExpanded,
                    onExpandedChange = { nameSearchExpanded = !nameSearchExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { 
                            name = it
                            nameSearchExpanded = true
                        },
                        label = { Text("Wine Name") },
                        trailingIcon = { Icon(Icons.Default.Search, null) },
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
                                        // Auto-fill other details if not already set or if selecting this name provides them
                                        if (maker.isBlank()) maker = def.maker
                                        selectedCountry = def.country
                                        selectedRegion = def.region ?: ""
                                        selectedAppellation = def.subregion ?: ""
                                        type = def.type
                                        nameSearchExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                
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
                            modifier = Modifier.heightIn(max = 200.dp) // Scrollable list constraint
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
                    
                    // Simple Type Dropdown
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

                // Cascading Geography Section
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
                                    if (selectedCountry != country.toString()) {
                                        selectedCountry = country.toString()
                                        selectedRegion = "" // Reset children
                                        selectedAppellation = ""
                                    }
                                    countryExpanded = false
                                }
                            )
                        }
                    }
                }

                // Region Dropdown (Only show if Regions exist for selected Country)
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
                                        if (selectedRegion != region.toString()) {
                                            selectedRegion = region.toString()
                                            selectedAppellation = "" // Reset child
                                        }
                                        regionExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Appellation Dropdown (Only show if Appellations exist for selected Region)
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
                    enabled = isWindowValid,
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

    (labelScanState as? LabelScanState.Review)?.let { review ->
        AlertDialog(
            onDismissRequest = { scannerViewModel.dismissLabelScan(review.requestId) },
            title = { Text("Review scanned label") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("These are suggestions. They will not be saved until you save the wine.")
                    review.result.maker?.let { Text("Producer: $it") }
                    review.result.name?.let { Text("Wine: $it") }
                    review.result.vintage?.let { Text("Vintage: $it") }
                    review.result.type?.let { Text("Type: $it") }
                    val origin = listOfNotNull(
                        review.result.country,
                        review.result.region,
                        review.result.subregion
                    ).distinct().joinToString(" · ")
                    if (origin.isNotBlank()) Text("Origin: $origin")
                    review.result.correctedText
                        ?.takeIf { it.isNotBlank() && it != review.result.rawText }
                        ?.let { corrected ->
                            Text("Wine dictionary correction", style = MaterialTheme.typography.labelLarge)
                            Text(
                                corrected,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    review.result.rawText?.let { rawText ->
                        Text("Original OCR text", style = MaterialTheme.typography.labelLarge)
                        Text(
                            rawText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        review.result.maker?.takeIf(String::isNotBlank)?.let { maker = it }
                        review.result.name?.takeIf(String::isNotBlank)?.let { name = it }
                        review.result.vintage?.takeIf(String::isNotBlank)?.let { vintage = it }
                        review.result.type?.takeIf(String::isNotBlank)?.let { type = it }
                        review.result.country?.takeIf(String::isNotBlank)?.let { selectedCountry = it }
                        review.result.region?.takeIf(String::isNotBlank)?.let { selectedRegion = it }
                        review.result.subregion?.takeIf(String::isNotBlank)?.let { selectedAppellation = it }
                        scannerViewModel.dismissLabelScan(review.requestId)
                        showLabelCamera = false
                    }
                ) {
                    Text("Use Suggestions")
                }
            },
            dismissButton = {
                TextButton(onClick = { scannerViewModel.dismissLabelScan(review.requestId) }) {
                    Text("Cancel")
                }
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

                    when (scanState) {
                        LabelScanState.Idle, is LabelScanState.Error -> Button(
                            enabled = cameraReady && cameraError == null,
                            onClick = { scannerViewModel.requestLabelScan() },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Icon(Icons.Default.Camera, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (scanState is LabelScanState.Error) "Try Again" else "Take Label Photo")
                        }
                        is LabelScanState.CapturingPhoto -> LabelScanProgress("Taking high-quality photo…")
                        is LabelScanState.Processing -> LabelScanProgress("Enhancing the label and reading the photo… This may take a few seconds.")
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
