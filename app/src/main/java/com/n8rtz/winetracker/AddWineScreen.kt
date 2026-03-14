package com.n8rtz.winetracker

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.Calendar

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddWineScreen(
    viewModel: WineViewModel,
    onWineAdded: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    val scannedResult by viewModel.scannedResult.collectAsState()
    val allDefinitions by viewModel.allDefinitions.collectAsState(initial = emptyList())
    
    var maker by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var vintage by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Red") }
    var quantity by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }

    // Cascading Location State
    var selectedCountry by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf("") }
    var selectedAppellation by remember { mutableStateOf("") }

    val availableCountries = WineRepository.locationData.keys.toList().sorted()
    val availableRegions = if (selectedCountry.isNotEmpty()) {
        WineRepository.locationData[selectedCountry]?.keys?.toList()?.sorted() ?: emptyList()
    } else emptyList()
    val availableAppellations = if (selectedCountry.isNotEmpty() && selectedRegion.isNotEmpty()) {
        WineRepository.locationData[selectedCountry]?.get(selectedRegion)?.sorted() ?: emptyList()
    } else emptyList()

    // Auto-fill form when scanner emits a result
    LaunchedEffect(scannedResult) {
        scannedResult?.let {
            if (it.maker != null) maker = it.maker
            if (it.name != null) name = it.name
            if (it.vintage != null) vintage = it.vintage
            viewModel.clearScannedResult()
        }
    }

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
            // Camera Section
            if (cameraPermissionState.status.isGranted) {
                Box(modifier = Modifier.height(250.dp).fillMaxWidth()) {
                    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
                    
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                imageCapture = ImageCapture.Builder().build()
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture
                                    )
                                } catch (e: Exception) {
                                    Log.e("Camera", "Use case binding failed", e)
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    Button(
                        onClick = {
                            val capture = imageCapture ?: return@Button
                            capture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = image.toBitmap().rotate(image.imageInfo.rotationDegrees.toFloat())
                                        viewModel.onImageCaptured(bitmap)
                                        image.close()
                                    }
                                }
                            )
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    ) {
                        Icon(Icons.Default.Camera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Label")
                    }
                }
            } else {
                Box(
                    modifier = Modifier.height(200.dp).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                        Text("Enable Camera to Scan")
                    }
                }
            }

            // Form Section
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                
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
                                    if (selectedCountry != country) {
                                        selectedCountry = country
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
                                        if (selectedRegion != region) {
                                            selectedRegion = region
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

                Button(
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
                            price = price.toDoubleOrNull() ?: 0.0
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
}

// Helper to rotate bitmap
fun Bitmap.rotate(degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
