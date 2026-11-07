package com.n8rtz.winetracker.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.scanner.LabelScanState
import com.n8rtz.winetracker.scanner.AddWineDraft
import com.n8rtz.winetracker.scanner.AddWineDraftStore
import com.n8rtz.winetracker.scanner.isBusy
import com.n8rtz.winetracker.scanner.scanProgressFraction
import com.n8rtz.winetracker.scanner.scanStepLabel
import com.n8rtz.winetracker.scanner.WineLabelFraming
import com.n8rtz.winetracker.ui.components.LabelScanCameraPreview
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import java.util.Calendar
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.ui.ScanFormState
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWineScreen(
    viewModel: WineViewModel,
    scannerViewModel: ScannerViewModel,
    onWineAdded: () -> Unit,
    onClose: () -> Unit
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
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    // Confirm/retake hold: the photo waits here until the user confirms (scan starts) or retakes.
    // Rotation-safe (id + path only); the file is deleted unless the scan consumes it.
    var holdRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var holdPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var holdConsumed by remember { mutableStateOf(false) }
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    var drawerPeekHeight by remember { mutableStateOf(IDLE_PEEK_HEIGHT) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            cameraError = null
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
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) scannerViewModel.cancelPendingLabelCapture()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scannerViewModel.cancelPendingLabelCapture()
        }
    }
    
    val draftStore = remember(context) { AddWineDraftStore(context) }
    val initialDraft = remember { draftStore.load() }
    var keepDraft by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var maker by remember { mutableStateOf(initialDraft.maker) }
    var name by remember { mutableStateOf(initialDraft.name) }
    var vintage by remember { mutableStateOf(initialDraft.vintage) }
    var type by remember { mutableStateOf(initialDraft.type) }
    var quantity by remember { mutableStateOf(initialDraft.quantity) }
    var price by remember { mutableStateOf(initialDraft.price) }
    var drinkingWindowStart by remember { mutableStateOf(initialDraft.windowStart) }
    var drinkingWindowEnd by remember { mutableStateOf(initialDraft.windowEnd) }

    var selectedCountry by remember { mutableStateOf(initialDraft.country) }
    var selectedRegion by remember { mutableStateOf(initialDraft.region) }
    var selectedAppellation by remember { mutableStateOf(initialDraft.subregion) }

    var scanForm by remember { mutableStateOf(initialDraft.formState()) }
    var lastScanReport by remember { mutableStateOf<ScanReport?>(null) }
    var lastScanId by remember { mutableStateOf(0L) }
    // Camera-first screen: ask once ever, so re-entering Add Wine never re-prompts.
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted && !draftStore.wasCameraPrompted()) {
            draftStore.markCameraPrompted()
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    // Leaving Add Wine blanks the form: back, tab switch and Save all dispose this screen, and
    // the states above are fresh on return. Rotation keeps the draft (recreated below); process
    // death keeps it for scan-return recovery; a running scan is never cancelled by leaving.
    DisposableEffect(Unit) {
        onDispose { if (!isChangingConfigurations(context)) draftStore.clear() }
    }
    // An unconfirmed hold file is orphaned by leaving (or by rotation-safe clearing); the scan
    // owns it once confirmed. Deletion is idempotent, so the retake path shares this cleanup.
    val latestHoldConsumed = rememberUpdatedState(holdConsumed)
    DisposableEffect(holdPhotoPath) {
        val heldPath = holdPhotoPath
        onDispose {
            if (!isChangingConfigurations(context) && latestHoldConsumed.value != true) {
                heldPath?.let { runCatching { File(it).delete() } }
            }
        }
    }
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
    val currentDraft = {
        AddWineDraft(maker, name, vintage, type, quantity, price, drinkingWindowStart, drinkingWindowEnd,
            selectedCountry, selectedRegion, selectedAppellation,
            scanForm.fields.filterValues { it.edited }.keys)
    }
    SideEffect { if (keepDraft) draftStore.save(currentDraft()) }
    val clearField: (FieldId) -> Unit = { field ->
        scanForm = scanForm.clearSuggestion(field, lastScanReport?.fill, formValues(), currentYear)
        syncFormToInputs()
    }
    val requestScan: () -> Unit = {
        if (!labelScanState.isBusy) {
            cameraError = null
            if (cameraPermissionGranted) scannerViewModel.requestLabelScan()
            else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val clearIcon: @Composable (FieldId, String) -> Unit = { field, value ->
        if (value.isNotBlank()) IconButton(onClick = { clearField(field) }, enabled = !labelScanState.isBusy) {
            Icon(Icons.Default.Close, contentDescription = "Clear ${field.name.lowercase().replace('_', ' ')}")
        }
    }
    LaunchedEffect(cameraPermissionGranted) {
        if (cameraPermissionGranted) {
            focusManager.clearFocus()
            scannerViewModel.warmLabelScanner()
        }
    }
    LaunchedEffect(lastScanId) {
        if (lastScanId != 0L) formScrollState.scrollTo(0)
    }
    // Drawer detents: skinny handle card at rest, progress peek while busy — never during the
    // confirm hold, when the user is deciding rather than waiting. Only steers a collapsed
    // drawer; an expanded drawer stays where the user put it.
    val showDrawerProgress = labelScanState.isBusy && holdPhotoPath == null
    LaunchedEffect(showDrawerProgress) {
        drawerPeekHeight = if (showDrawerProgress) PROGRESS_PEEK_HEIGHT else IDLE_PEEK_HEIGHT
        withFrameNanos {}; withFrameNanos {}
        val sheet = scaffoldState.bottomSheetState
        if (sheet.currentValue == SheetValue.PartiallyExpanded) sheet.partiallyExpand()
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
        // OCR done: the drawer slides up to the full entry form.
        scaffoldState.bottomSheetState.expand()
        cameraError = if (review.result.fill.fields.values.all { it.state == com.yourapp.wine.fill.UiState.RED })
            review.result.fill.overallHint ?: "Could not read the label — retake or enter details manually" else null
        // The saved draft must exist before acknowledging/deleting the recovered scan result.
        if (draftStore.save(currentDraft(), synchronous = true)) scannerViewModel.dismissLabelScan(review.requestId)
        else cameraError = "Could not save the scan result — try again or enter details manually"
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
    val processingStill = (labelScanState as? LabelScanState.Processing)?.photoPath
    val confirmingPhoto = holdPhotoPath
    val liveCamera = cameraPermissionGranted && confirmingPhoto == null && processingStill == null
    // The live preview unmounts while the captured still is shown, releasing the camera for
    // the (up to 90-second) analysis; readiness is re-reported when the preview remounts.
    LaunchedEffect(processingStill != null) {
        if (processingStill != null) cameraReady = false
    }
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = drawerPeekHeight,
        sheetDragHandle = { DrawerHandleCard() },
        sheetContent = {
            if (showDrawerProgress) DrawerScanProgress(labelScanState)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(formScrollState)
                    .imePadding()
            ) {
            // Form Section
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add New Wine", style = MaterialTheme.typography.titleLarge)
                (cameraError ?: (labelScanState as? LabelScanState.Error)?.message)?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

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


                // Cascading Geography Section
                Text("Geography", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

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


                // Region stays visible and editable even when the fixture gazetteer has no suggestions.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

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

                }

                if(selectedAppellation.isNotBlank() && selectedRegion.isBlank()) {
                    Text("Select or enter a Region before saving a Subregion.",color=MaterialTheme.colorScheme.error)
                }
                // Subregion stays visible and editable for boutique/unknown wines.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

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
                saveError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(
                    enabled = (selectedAppellation.isBlank() || selectedRegion.isNotBlank()) && isWindowValid && maker.isNotBlank() && name.isNotBlank() && !labelScanState.isBusy && !saving,
                    onClick = {
                        saving = true
                        saveError = null
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
                            drinkingWindowEnd = drinkingWindowEnd.toIntOrNull(),
                            onComplete = { success ->
                                saving = false
                                if (success) {
                                    keepDraft = false
                                    draftStore.clear()
                                    onWineAdded()
                                } else saveError = "Could not save the wine. Your draft is still here; please try again."
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save to Cellar")
                }
            }
            }
        }
    ) {
        AddWineCameraLayer(
            liveCamera = liveCamera,
            frozenPath = confirmingPhoto ?: processingStill,
            showGuide = cameraPermissionGranted && confirmingPhoto == null,
            cameraReady = cameraReady,
            cameraError = cameraError,
            scanState = labelScanState,
            isConfirming = confirmingPhoto != null,
            controlsBottomPadding = drawerPeekHeight + 16.dp,
            scannerViewModel = scannerViewModel,
            onCameraError = { cameraError = it },
            onCameraReadyChanged = { ready ->
                cameraReady = ready
                if (ready) cameraError = null
            },
            onPhotoTaken = { requestId, photoFile ->
                holdConsumed = false
                holdRequestId = requestId
                holdPhotoPath = photoFile.absolutePath
            },
            onShutter = requestScan,
            onGallery = {
                cameraError = null
                scannerViewModel.warmLabelScanner()
                runCatching { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .onFailure { cameraError = "Photo picker could not open. You can still use the camera or enter details manually." }
            },
            onConfirmPhoto = {
                val id = holdRequestId
                val path = holdPhotoPath
                if (id != null && path != null) {
                    // A stale confirm (capture watchdog already fired) deletes the file and
                    // no-ops inside captured(); the Review effect has already shown the error.
                    scannerViewModel.onLabelPhotoCaptured(id, File(path))
                    holdConsumed = true
                    holdRequestId = null
                    holdPhotoPath = null
                }
            },
            onRetakePhoto = {
                holdRequestId = null
                holdPhotoPath = null
                scannerViewModel.cancelPendingLabelCapture()
            },
            onAllowCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            onEnterManually = { scope.launch { scaffoldState.bottomSheetState.expand() } },
            onClose = onClose
        )
    }
}

private val DRAWER_HANDLE_HEIGHT = 40.dp
private val DRAWER_PROGRESS_HEIGHT = 76.dp
private val IDLE_PEEK_HEIGHT = DRAWER_HANDLE_HEIGHT
private val PROGRESS_PEEK_HEIGHT = DRAWER_HANDLE_HEIGHT + DRAWER_PROGRESS_HEIGHT

private fun isChangingConfigurations(context: Context): Boolean {
    var current: Context? = context
    while (current is ContextWrapper) current = current.baseContext
    return (current as? Activity)?.isChangingConfigurations == true
}

/** Full-width skinny card with a centered handle: the drawer's collapsed face. */
@Composable
private fun DrawerHandleCard() {
    Box(modifier = Modifier.fillMaxWidth().height(DRAWER_HANDLE_HEIGHT), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.width(48.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
    }
}

/** Loading bar plus one-line status, sized to fit the progress peek exactly. */
@Composable
private fun DrawerScanProgress(scanState: LabelScanState) {
    val message = scanState.scanStepLabel ?: return
    val progress = scanState.scanProgressFraction
    val animated by animateFloatAsState(targetValue = progress ?: 0f, label = "labelScanProgress")
    Column(modifier = Modifier.fillMaxWidth().height(DRAWER_PROGRESS_HEIGHT).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center) {
        if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Full-screen camera behind the drawer: live preview with the portrait label guide, the frozen
 * confirm/processing still, or the permission prompt. Bottom controls sit above the drawer peek.
 */
@Composable
private fun AddWineCameraLayer(
    liveCamera: Boolean,
    frozenPath: String?,
    showGuide: Boolean,
    cameraReady: Boolean,
    cameraError: String?,
    scanState: LabelScanState,
    isConfirming: Boolean,
    controlsBottomPadding: Dp,
    scannerViewModel: ScannerViewModel,
    onCameraError: (String) -> Unit,
    onCameraReadyChanged: (Boolean) -> Unit,
    onPhotoTaken: (Long, File) -> Unit,
    onShutter: () -> Unit,
    onGallery: () -> Unit,
    onConfirmPhoto: () -> Unit,
    onRetakePhoto: () -> Unit,
    onAllowCamera: () -> Unit,
    onEnterManually: () -> Unit,
    onClose: () -> Unit
) {
    val errorMessage = cameraError ?: (scanState as? LabelScanState.Error)?.message
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            frozenPath != null -> AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(frozenPath))
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
                contentDescription = "Label photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            liveCamera -> LabelScanCameraPreview(
                scannerViewModel = scannerViewModel,
                onCameraError = onCameraError,
                onCameraReadyChanged = onCameraReadyChanged,
                modifier = Modifier.fillMaxSize(),
                onPhotoCaptured = onPhotoTaken
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    cameraError ?: "Allow the camera to scan a label.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAllowCamera, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow camera")
                }
                TextButton(onClick = onEnterManually) { Text("Enter manually", color = Color.White) }
            }
        }

        // A bottle label is portrait, so the guide is portrait too. WineLabelFraming holds the
        // shape and the guarantee that whatever the user fits inside it is inside every crop the
        // scanner reads. It stays up over the frozen still while the photo is processed, and hides
        // only while the user inspects the confirm still.
        if (showGuide) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight(WineLabelFraming.GUIDE_HEIGHT_FRACTION)
                    .aspectRatio(WineLabelFraming.ASPECT_RATIO)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Text("Scan wine label", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }

        // While busy the drawer carries the loading bar and status line, and the permission
        // placeholder carries its own buttons, so neither needs controls down here.
        if (isConfirming || (!scanState.isBusy && liveCamera)) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 20.dp)
                    .padding(bottom = controlsBottomPadding, top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isConfirming) {
                    Text("Use this photo?", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        IconButton(onClick = onRetakePhoto,
                            modifier = Modifier.size(64.dp).border(2.dp, Color.White, CircleShape)) {
                            Icon(Icons.Default.Close, contentDescription = "Retake", tint = Color.White,
                                modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = onConfirmPhoto,
                            modifier = Modifier.size(64.dp).border(2.dp, Color.White, CircleShape)) {
                            Icon(Icons.Default.Check, contentDescription = "Use photo", tint = Color.White,
                                modifier = Modifier.size(32.dp))
                        }
                    }
                } else {
                    errorMessage?.let {
                        Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center)
                    }
                    if (scanState is LabelScanState.Error || cameraError != null) {
                        TextButton(onClick = onEnterManually) { Text("Enter manually", color = Color.White) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onGallery,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                                .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp))) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = "Choose label",
                                tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onShutter, enabled = cameraReady && cameraError == null,
                            modifier = Modifier.size(76.dp).border(4.dp, Color.White, CircleShape)) {
                            Icon(Icons.Default.Camera, contentDescription = "Take photo", tint = Color.White,
                                modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.size(56.dp))
                    }
                    Text(
                        if (!cameraReady && cameraError == null) "Starting camera…"
                        else "Fit the label in the frame.",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
