package com.n8rtz.winetracker.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.data.repository.WineRepository
import com.n8rtz.winetracker.image.resolveWineImageModel
import com.n8rtz.winetracker.ui.components.DefaultColorField
import com.n8rtz.winetracker.ui.components.UnsavedChangesDialog
import com.n8rtz.winetracker.viewmodels.WineViewModel
import java.util.Calendar
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WineEditScreen(
    bottleId: Long,
    viewModel: WineViewModel,
    onBack: () -> Unit
) {
    val inventory by viewModel.activeInventory.collectAsState(initial = emptyList())
    val item = remember(inventory, bottleId) { inventory.find { it.bottle.bottleId == bottleId } }
    
    if (item == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Wine not found")
        }
        return
    }

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val legacyImageUrl = item.definition.imageUrl.takeIf { item.imageAsset == null }
    val imageModel = remember(context, legacyImageUrl, item.imageAsset?.relativePath) {
        resolveWineImageModel(context, item.imageAsset?.relativePath, legacyImageUrl)
    }

    var editedWine by remember(item.definition) { mutableStateOf(item.definition) }
    var editedBottle by remember(item.bottle) { mutableStateOf(item.bottle) }

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
    var defaultColorHsv by remember(editedWine.defaultColorHsv) { mutableStateOf(editedWine.defaultColorHsv) }

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

    var drinkingWindowError by remember { mutableStateOf<String?>(null) }

    // Unsaved-changes tracking: any field differing from the loaded wine
    // prompts before leaving (same behaviour as the tasting-note editor).
    val isDirty = listOf(
        maker, name, vintage, type, quantity, price,
        drinkingWindowStart, drinkingWindowEnd,
        selectedCountry, selectedRegion, selectedAppellation
    ) != listOf(
        item.definition.maker, item.definition.name, item.bottle.vintage,
        item.definition.type, item.bottle.quantity.toString(),
        item.bottle.price?.toString() ?: "",
        item.definition.drinkingWindowStart?.toString() ?: "",
        item.definition.drinkingWindowEnd?.toString() ?: "",
        item.definition.country, item.definition.region ?: "",
        item.definition.subregion ?: ""
    ) || defaultColorHsv != item.definition.defaultColorHsv
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }

    /** Saves the edited wine; returns false when validation blocks it. */
    fun saveWine(): Boolean {
        val startYear = drinkingWindowStart.toIntOrNull()
        val endYear = drinkingWindowEnd.toIntOrNull()
        val yearsAreComplete = (drinkingWindowStart.isBlank() || drinkingWindowStart.length == 4) &&
                (drinkingWindowEnd.isBlank() || drinkingWindowEnd.length == 4)
        if (!yearsAreComplete) {
            drinkingWindowError = "Enter each drinking-window year as four digits."
            return false
        }
        if (startYear != null && endYear != null && startYear > endYear) {
            drinkingWindowError = "Start year must be no later than its end year."
            return false
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
                drinkingWindowEnd = drinkingWindowEnd.toIntOrNull(),
                defaultColorHsv = defaultColorHsv
            ),
            editedBottle.copy(
                vintage = vintage,
                quantity = quantity.toIntOrNull() ?: editedBottle.quantity,
                price = price.toDoubleOrNull()
            )
        )
        return true
    }

    fun requestBack() {
        if (isDirty) showUnsavedChangesDialog = true else onBack()
    }
    BackHandler(onBack = ::requestBack)

    // Keep the title, but let the header scroll away with the content.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Edit Wine", style = MaterialTheme.typography.labelMedium)
                        Text(editedWine.name)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { if (saveWine()) onBack() }) {
                        Icon(Icons.Default.Check, contentDescription = "Save wine")
                    }
                },
                scrollBehavior = scrollBehavior
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
                if (imageModel != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageModel)
                            .crossfade(true)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(if (imageModel is File) CachePolicy.DISABLED else CachePolicy.ENABLED)
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

            item.imageAsset?.let { asset ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = buildString {
                            append("Image source: ${asset.sourceName}")
                            asset.attribution?.takeIf { it.isNotBlank() }?.let { append(" · Contributor: $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Open Food Facts listing: ${asset.productTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    asset.license?.takeIf { it.isNotBlank() }?.let { license ->
                        Text(license, style = MaterialTheme.typography.bodySmall)
                    }
                    asset.sourcePageUrl?.takeIf { it.isNotBlank() }?.let { sourcePage ->
                        Text(
                            text = "View Open Food Facts source",
                            modifier = Modifier.clickable {
                                runCatching { uriHandler.openUri(sourcePage) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DefaultColorField(
                        color = defaultColorHsv,
                        onColorChange = { defaultColorHsv = it }
                    )

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
        }

    if (showUnsavedChangesDialog) {
        UnsavedChangesDialog(
            text = "You have unsaved changes to this wine. Save them before leaving?",
            onSave = {
                showUnsavedChangesDialog = false
                if (saveWine()) onBack()
            },
            onDiscard = {
                showUnsavedChangesDialog = false
                onBack()
            },
            onCancel = { showUnsavedChangesDialog = false }
        )
    }
    }
}
