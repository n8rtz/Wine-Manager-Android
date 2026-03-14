package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.n8rtz.winetracker.viewmodels.WineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WineDetailScreen(
    bottleId: Long,
    viewModel: WineViewModel,
    onBack: () -> Unit,
    onConsumed: (String, Long) -> Unit
) {
    val inventory by viewModel.activeInventory.collectAsState(initial = emptyList())
    val item = remember(inventory, bottleId) { inventory.find { it.bottle.bottleId == bottleId } }
    
    if (item == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Wine not found")
        }
        return
    }

    var editedWine by remember(item.definition) { mutableStateOf(item.definition) }
    val bottle = item.bottle

    var showConsumeDialog by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }

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
                            viewModel.updateWineDefinition(editedWine)
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
                OutlinedTextField(
                    value = editedWine.maker,
                    onValueChange = { editedWine = editedWine.copy(maker = it) },
                    label = { Text("Producer") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = editedWine.name,
                    onValueChange = { editedWine = editedWine.copy(name = it) },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            } else {
                Text(
                    text = "${editedWine.maker} ${editedWine.name}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = bottle.vintage,
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
                onConfirm = { rating, notes, rebuy ->
                    viewModel.consumeBottle(bottle.bottleId, rating, notes, rebuy)
                    onConsumed("Consumed 1 bottle of ${editedWine.name}", bottle.bottleId)
                    showConsumeDialog = false
                    onBack()
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

@Composable
fun ConsumeDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double?, String?, Boolean) -> Unit
) {
    var rating by remember { mutableStateOf(90f) }
    var notes by remember { mutableStateOf("") }
    var rebuy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Consumption") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Rate this bottle (100-pt scale): ${rating.toInt()}")
                Slider(
                    value = rating,
                    onValueChange = { rating = it },
                    valueRange = 70f..100f,
                    steps = 30
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Tasting Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rebuy, onCheckedChange = { rebuy = it })
                    Text("Mark as Rebuy?")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(rating.toDouble(), notes, rebuy) }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
