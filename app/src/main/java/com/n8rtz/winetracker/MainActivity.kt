@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.n8rtz.winetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.n8rtz.winetracker.ui.screens.AddWineScreen
import com.n8rtz.winetracker.ui.screens.ProfileScreen
import com.n8rtz.winetracker.ui.screens.TastingNotesScreen
import com.n8rtz.winetracker.ui.screens.WineDetailScreen
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.n8rtz.winetracker.viewmodels.WineViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WineTrackerTheme {
                val viewModel: WineViewModel = viewModel()
                val navController = rememberNavController()
                MainAppScaffold(navController, viewModel)
            }
        }
    }
}

data class CellarFilterState(
    val sortOrder: String = "Newest",
    val priceRange: ClosedFloatingPointRange<Float>? = null,
    val vintageRange: ClosedFloatingPointRange<Float>? = null,
    val ratingRange: ClosedFloatingPointRange<Float>? = null,
    val makers: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val countries: Set<String> = emptySet()
)

@Composable
fun MainAppScaffold(navController: androidx.navigation.NavHostController, viewModel: WineViewModel) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                modifier = Modifier.height(80.dp)
            ) {
                NavigationBarItem(
                    selected = currentRoute == "cellar",
                    onClick = { navigateToTab(navController, "cellar") },
                    icon = { Icon(Icons.Default.WineBar, "Cellar") },
                    label = { Text("Cellar") }
                )
                NavigationBarItem(
                    selected = currentRoute == "notes",
                    onClick = { navigateToTab(navController, "notes") },
                    icon = { Icon(Icons.Default.Description, "Notes") },
                    label = { Text("Notes") }
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        onClick = { navController.navigate("add_wine") },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        modifier = Modifier.size(56.dp),
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, "Add", modifier = Modifier.size(32.dp))
                        }
                    }
                }
                NavigationBarItem(
                    selected = currentRoute == "rankings",
                    onClick = { navigateToTab(navController, "rankings") },
                    icon = { Icon(Icons.Default.Star, "Rankings") },
                    label = { Text("Rankings") }
                )
                NavigationBarItem(
                    selected = currentRoute == "profile",
                    onClick = { navigateToTab(navController, "profile") },
                    icon = { Icon(Icons.Default.Person, "Profile") },
                    label = { Text("Profile") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "cellar",
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            composable("cellar") {
                CellarScreen(viewModel) { bottleId ->
                    navController.navigate("detail/$bottleId")
                }
            }
            composable("notes") {
                TastingNotesScreen(
                    viewModel = viewModel,
                    onNoteClick = { /* Handle note click */ },
                    onNavigateToHistory = { /* Handle navigation */ }
                )
            }
            composable("rankings") {
                RankingsScreen(viewModel)
            }
            composable("profile") {
                ProfileScreen(viewModel) {
                    navigateToTab(navController, "cellar")
                }
            }
            composable("add_wine") {
                AddWineScreen(viewModel) {
                    navController.popBackStack("cellar", inclusive = false)
                }
            }
            composable(
                route = "detail/{bottleId}",
                arguments = listOf(navArgument("bottleId") { type = NavType.LongType })
            ) { backStackEntry ->
                val bottleId = backStackEntry.arguments?.getLong("bottleId")
                if (bottleId != null) {
                    WineDetailScreen(
                        bottleId = bottleId,
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onConsumed = { message, bid ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = message,
                                    actionLabel = "UNDO",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoConsumeBottle(bid)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun navigateToTab(navController: androidx.navigation.NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun CellarScreen(viewModel: WineViewModel, onWineClick: (Long) -> Unit) {
    val inventory by viewModel.activeInventory.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var showFilterSheet by remember { mutableStateOf(false) }

    val totalBottles = inventory.sumOf { it.bottle.quantity }
    val uniqueWines = inventory.size
    val estimatedValue = inventory.sumOf { it.bottle.quantity * (it.bottle.price ?: 0.0) }

    val filteredInventory = inventory.filter {
        it.definition.maker.contains(searchQuery, ignoreCase = true) ||
        it.definition.name.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalyticsCard("Total Bottles", totalBottles.toString(), Modifier.weight(1f))
            AnalyticsCard("Unique Wines", uniqueWines.toString(), Modifier.weight(1f))
            AnalyticsCard("Est. Value", "$${"%.0f".format(estimatedValue)}", Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search producer or name...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { showFilterSheet = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.Tune, "Filter & Sort")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredInventory) { item ->
                WineCard(
                    title = run {
                        val vintage = item.bottle.vintage?.takeIf { it.isNotBlank() && it.lowercase() != "null" } ?: "NV"
                        "${item.definition.maker} - ${item.definition.name} ($vintage)"
                    },
                    subtitle = run {
                        val validRegion = item.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        val validCountry = item.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                        listOfNotNull(validRegion, validCountry).joinToString(", ")
                    },
                    type = item.definition.type,
                    metadata = "Qty: ${item.bottle.quantity}",
                    imageUrl = item.definition.imageUrl,
                    onClick = { onWineClick(item.bottle.bottleId) }
                )
            }
            
            if (filteredInventory.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.WineBar,
                        title = if (inventory.isEmpty()) "Your Cellar is Empty" else "No matching wines",
                        subtitle = if (inventory.isEmpty()) "Scan a label or add a wine manually to get started" else "Try adjusting your search or filters"
                    )
                }
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            Box(Modifier.fillMaxWidth().padding(32.dp)) { Text("Filters") }
        }
    }
}

@Composable
fun AnalyticsCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun RankingsScreen(viewModel: WineViewModel) {
    val history by viewModel.tastingHistory.collectAsState(initial = emptyList())
    val rankedWines = history.filter { it.note.rating > 5 }.sortedByDescending { it.note.rating }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Rankings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        
        if (rankedWines.isEmpty()) {
            EmptyState(Icons.Default.EmojiEvents, "No Ranked Wines", "Rate a wine to see it here")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rankedWines) { item ->
                    WineCard(
                        title = run {
                            val vintage = item.note.vintage?.takeIf { it.isNotBlank() && it.lowercase() != "null" } ?: "NV"
                            "${item.definition.maker} - ${item.definition.name} ($vintage)"
                        },
                        subtitle = run {
                            val validRegion = item.definition.region?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            val validCountry = item.definition.country?.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                            listOfNotNull(validRegion, validCountry).joinToString(", ")
                        },
                        type = item.definition.type,
                        metadata = "Rating: ${item.note.rating.toInt()}",
                        imageUrl = item.definition.imageUrl,
                        rating = item.note.rating.toInt(),
                        onClick = {}
                    )
                }
            }
        }
    }
}

@Composable
fun WineCard(
    title: String,
    subtitle: String,
    type: String,
    metadata: String,
    imageUrl: String? = null,
    rating: Int? = null,
    onClick: () -> Unit
) {
    val backgroundColor = when (type.lowercase()) {
        "red" -> Color(0xFFFFF5F5)
        "white" -> Color(0xFFFFFFF0)
        else -> Color(0xFFF8F9FA)
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() }, 
            indication = null,
            onClick = onClick
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(width = 60.dp, height = 80.dp).clip(RoundedCornerShape(4.dp)).background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .crossfade(true)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.WineBar, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rating != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Text(
                                text = rating.toString(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    Text(text = type, style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
