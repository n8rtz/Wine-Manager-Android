package com.n8rtz.winetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.data.local.TastingNoteWithDefinition
import com.n8rtz.winetracker.image.resolveWineImageModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(viewModel: WineViewModel) {
    val history by viewModel.tastingHistory.collectAsState(initial = emptyList())
    
    var showFilters by remember { mutableStateOf(false) }
    
    // Applied filter states
    var appliedTypes by remember { mutableStateOf(setOf<String>()) }
    var appliedCountries by remember { mutableStateOf(setOf<String>()) }

    // Strict 100-point scale filter and sorting
    val filteredHistory = history.filter { item ->
        val is100PointScale = item.note.rating > 5.0
        val matchesType = appliedTypes.isEmpty() || item.definition.type in appliedTypes
        val matchesCountry = appliedCountries.isEmpty() || item.definition.country in appliedCountries
        
        is100PointScale && matchesType && matchesCountry
    }.sortedByDescending { it.note.rating }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Wine Leaderboard") },
                actions = {
                    IconButton(onClick = { showFilters = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Filter")
                    }
                }
            )
        }
    ) { padding ->
        if (filteredHistory.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.EmojiEvents, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No Ranked Wines", style = MaterialTheme.typography.titleLarge)
                    Text("Rate a wine to see it here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredHistory) { item ->
                    RankedWineCard(item)
                }
            }
        }

        if (showFilters) {
            ModalBottomSheet(
                onDismissRequest = { showFilters = false },
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            ) {
                // Re-creating the simple filter for Archive since Advanced is Cellar specific
                ArchiveFilterSheetContent(
                    history = history,
                    initialTypes = appliedTypes,
                    initialCountries = appliedCountries,
                    onApply = { types, countries ->
                        appliedTypes = types
                        appliedCountries = countries
                        showFilters = false
                    },
                    onClose = { showFilters = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchiveFilterSheetContent(
    history: List<TastingNoteWithDefinition>,
    initialTypes: Set<String>,
    initialCountries: Set<String>,
    onApply: (Set<String>, Set<String>) -> Unit,
    onClose: () -> Unit
) {
    var selectedTypes by remember { mutableStateOf(initialTypes) }
    var selectedCountries by remember { mutableStateOf(initialCountries) }

    val allTypes = history.map { it.definition.type }.distinct().sorted()
    val allCountries = history.map { it.definition.country }.distinct().sorted()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Filter History", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Close") }
        }
        
        Spacer(Modifier.height(16.dp))

        if (allTypes.isNotEmpty()) {
            Text("Wine Type", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                allTypes.forEach { type ->
                    val isSelected = type in selectedTypes
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedTypes = if (isSelected) selectedTypes - type else selectedTypes + type },
                        label = { Text(type) }
                    )
                }
            }
            HorizontalDivider()
        }

        if (allCountries.isNotEmpty()) {
            Text("Country", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                allCountries.forEach { country ->
                    val isSelected = country in selectedCountries
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCountries = if (isSelected) selectedCountries - country else selectedCountries + country },
                        label = { Text(country) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = {
                    selectedTypes = emptySet()
                    selectedCountries = emptySet()
                    onApply(emptySet(), emptySet())
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear All")
            }
            Button(
                onClick = { onApply(selectedTypes, selectedCountries) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Apply Filters")
            }
        }
    }
}

@Composable
fun RankedWineCard(item: TastingNoteWithDefinition) {
    val wine = item.definition
    val note = item.note
    val context = LocalContext.current
    val legacyImageUrl = wine.imageUrl.takeIf { item.imageAsset == null }
    val imageModel = remember(context, legacyImageUrl, item.imageAsset?.relativePath) {
        resolveWineImageModel(context, item.imageAsset?.relativePath, legacyImageUrl)
    }

    val fallbackColor = when (wine.type.lowercase()) {
        "red"       -> Color(0xFFFFF5F5)   // Subtle blush
        "white"     -> Color(0xFFFFFFF0)   // Subtle straw
        "rosé",
        "rose"      -> Color(0xFFFFF0F5)   // Subtle pink
        "sparkling" -> Color(0xFFF5F5F0)   // Subtle champagne
        else        -> Color(0xFFF8F9FA)   // Neutral off-white
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 80.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(fallbackColor),
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
                        Icons.Default.Star, 
                        null, 
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${wine.maker} - ${wine.name} (${note.vintage})",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = listOfNotNull(wine.region).joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            text = note.rating.toInt().toString(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = wine.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (note.rebuy) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.ThumbUp, 
                            null, 
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF4CAF50)
                        )
                    }
                }
                
                if (!note.notes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = note.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
