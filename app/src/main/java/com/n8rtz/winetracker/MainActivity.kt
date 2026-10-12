@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.n8rtz.winetracker

import android.os.Bundle
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.animation.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
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
import com.n8rtz.winetracker.ui.screens.HistoryScreen
import com.n8rtz.winetracker.ui.screens.ProfileScreen
import com.n8rtz.winetracker.ui.screens.TastingNotesScreen
import com.n8rtz.winetracker.ui.screens.TastingNoteEditorScreen
import com.n8rtz.winetracker.ui.screens.WineDetailScreen
import com.n8rtz.winetracker.ui.screens.CellarScreen
import com.n8rtz.winetracker.ui.screens.RankingsScreen
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.viewmodels.RankingsViewModel
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    
    // Consolidate database and repository as single instances for the entire app lifecycle
    private val database by lazy {
        Log.d("WineTracker", "MainActivity: Initializing database builder")
        androidx.room.Room.databaseBuilder(
            applicationContext,
            com.n8rtz.winetracker.data.local.WineDatabase::class.java, 
            "wine-database"
        )
        .addMigrations(
            com.n8rtz.winetracker.data.local.MIGRATION_5_6, 
            com.n8rtz.winetracker.data.local.MIGRATION_6_7,
            com.n8rtz.winetracker.data.local.MIGRATION_7_8,
            com.n8rtz.winetracker.data.local.MIGRATION_8_9,
            com.n8rtz.winetracker.data.local.MIGRATION_9_10,
            com.n8rtz.winetracker.data.local.MIGRATION_10_11,
            com.n8rtz.winetracker.data.local.MIGRATION_11_12,
            com.n8rtz.winetracker.data.local.MIGRATION_12_13,
            com.n8rtz.winetracker.data.local.MIGRATION_13_14,
            com.n8rtz.winetracker.data.local.MIGRATION_14_15,
            com.n8rtz.winetracker.data.local.MIGRATION_15_16,
            com.n8rtz.winetracker.data.local.MIGRATION_16_17
        )
        // Ensure migrations don't block the main thread if possible (though they often do on first access)
        .build()
    }

    private val repository by lazy {
        Log.d("WineTracker", "MainActivity: Initializing repository")
        com.n8rtz.winetracker.data.repository.WineRepository(
            database.wineDao(),
            database.inventoryDao(),
            database.tastingNoteDao(),
            database.ratingDao(),
            database
        )
    }

    private val wineViewModel: WineViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                Log.d("WineTracker", "MainActivity: Creating WineViewModel")
                return WineViewModel(this@MainActivity.application, repository) as T
            }
        }
    }

    private val tastingNoteViewModel: TastingNoteViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                Log.d("WineTracker", "MainActivity: Creating TastingNoteViewModel")
                return TastingNoteViewModel(this@MainActivity.application, repository) as T
            }
        }
    }

    private val rankingsViewModel: RankingsViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                Log.d("WineTracker", "MainActivity: Creating RankingsViewModel")
                return RankingsViewModel(this@MainActivity.application, repository) as T
            }
        }
    }

    private val scannerViewModel: ScannerViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                Log.d("WineTracker", "MainActivity: Creating ScannerViewModel")
                return ScannerViewModel(this@MainActivity.application, repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("WineTracker", "MainActivity: onCreate started")
        enableEdgeToEdge()
        
        // Trigger migrations in background to avoid blocking initial UI render
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Log.d("WineTracker", "MainActivity: Triggering background migration check")
            try {
                database.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT 1"))
                Log.d("WineTracker", "MainActivity: Background migration check complete")
            } catch (e: Exception) {
                Log.e("WineTracker", "MainActivity: Error during background migration", e)
            }
        }

        setContent {
            WineTrackerTheme {
                val navController = rememberNavController()
                MainAppScaffold(navController, wineViewModel, tastingNoteViewModel, rankingsViewModel, scannerViewModel)
            }
        }
    }
}


@Composable
fun MainAppScaffold(
    navController: androidx.navigation.NavHostController, 
    viewModel: WineViewModel,
    tastingNoteViewModel: TastingNoteViewModel,
    rankingsViewModel: RankingsViewModel,
    scannerViewModel: ScannerViewModel
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var isMenuExpanded by remember { mutableStateOf(false) }
    val fabRotation by animateFloatAsState(targetValue = if (isMenuExpanded) 45f else 0f, label = "fabRotation")

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                modifier = Modifier.height(80.dp)
            ) {
                NavigationBarItem(
                    selected = currentRoute == "cellar" || currentRoute == "add_wine",
                    onClick = { 
                        Log.d("WineTracker", "BottomBar: Cellar clicked")
                        isMenuExpanded = false
                        if (currentRoute == "cellar") {
                            viewModel.resetCellarScreen()
                        } else {
                            if (currentRoute == "add_wine") {
                                navController.popBackStack("cellar", inclusive = false)
                            } else {
                                navigateToTab(navController, "cellar")
                            }
                            viewModel.resetCellarScreen()
                        }
                    },
                    icon = { Icon(Icons.Default.WineBar, "Cellar") },
                    label = { Text("Cellar") }
                )
                NavigationBarItem(
                    selected = currentRoute == "notes" || currentRoute == "history",
                    onClick = {
                        Log.d("WineTracker", "BottomBar: Notes clicked")
                        isMenuExpanded = false
                        if (currentRoute == "history") {
                            navController.popBackStack("notes", inclusive = false)
                        } else {
                            navigateToTab(navController, "notes")
                        }
                    },
                    icon = { Icon(Icons.Default.Description, "Notes") },
                    label = { Text("Notes") }
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        onClick = { isMenuExpanded = !isMenuExpanded },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        modifier = Modifier.size(56.dp),
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = if (isMenuExpanded) "Close Menu" else "Add",
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer { rotationZ = fabRotation }
                            )
                        }
                    }
                }
                NavigationBarItem(
                    selected = currentRoute == "rankings",
                    onClick = { 
                        Log.d("WineTracker", "BottomBar: Rankings clicked")
                        isMenuExpanded = false
                        navigateToTab(navController, "rankings") 
                    },
                    icon = { Icon(Icons.Default.Star, "Rankings") },
                    label = { Text("Rankings") }
                )
                NavigationBarItem(
                    selected = currentRoute == "profile",
                    onClick = { 
                        Log.d("WineTracker", "BottomBar: Profile clicked")
                        isMenuExpanded = false
                        navigateToTab(navController, "profile") 
                    },
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
                    tastingNoteViewModel = tastingNoteViewModel,
                    onNoteClick = { noteId -> navController.navigate("note_editor/edit/$noteId") },
                    onNavigateToHistory = {
                        Log.d("WineTracker", "MainActivity: Navigating to History")
                        navController.navigate("history")
                    }
                )
            }
            composable("rankings") {
                RankingsScreen(rankingsViewModel)
            }
            composable("history") {
                HistoryScreen(viewModel) { noteId, eventId, definitionId, vintage, consumedAt ->
                    if (noteId != null) {
                        navController.navigate("note_editor/edit/$noteId")
                    } else {
                        navController.navigate(
                            "note_editor/create?definitionId=$definitionId&vintage=${Uri.encode(vintage)}&eventId=$eventId&dateTasted=${consumedAt ?: -1L}"
                        )
                    }
                }
            }
            composable("profile") {
                ProfileScreen(viewModel = viewModel, scannerViewModel = scannerViewModel) {
                    navigateToTab(navController, "cellar")
                }
            }
            composable("add_wine") {
                AddWineScreen(viewModel, scannerViewModel) {
                    navController.popBackStack("cellar", inclusive = false)
                }
            }
            composable(
                route = "note_editor/create?definitionId={definitionId}&vintage={vintage}&eventId={eventId}&dateTasted={dateTasted}",
                arguments = listOf(
                    navArgument("definitionId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("vintage") { type = NavType.StringType; defaultValue = "" },
                    navArgument("eventId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("dateTasted") { type = NavType.LongType; defaultValue = -1L }
                )
            ) { backStackEntry ->
                val definitionId = backStackEntry.arguments?.getLong("definitionId")?.takeIf { it > 0L }
                val vintage = backStackEntry.arguments?.getString("vintage")?.takeIf { it.isNotBlank() }
                val linkedEventId = backStackEntry.arguments?.getLong("eventId")?.takeIf { it > 0L }
                val dateTasted = backStackEntry.arguments?.getLong("dateTasted")?.takeIf { it > 0L }
                TastingNoteEditorScreen(
                    viewModel = tastingNoteViewModel,
                    presetDefinitionId = definitionId,
                    presetVintage = vintage,
                    presetDateTasted = dateTasted,
                    linkedEventId = linkedEventId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "note_editor/edit/{noteId}",
                arguments = listOf(navArgument("noteId") { type = NavType.LongType })
            ) { backStackEntry ->
                val noteId = backStackEntry.arguments?.getLong("noteId")
                if (noteId != null) {
                    TastingNoteEditorScreen(
                        viewModel = tastingNoteViewModel,
                        noteId = noteId,
                        onBack = { navController.popBackStack() }
                    )
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
                        onAddNote = { definitionId, vintage ->
                            navController.navigate(
                                "note_editor/create?definitionId=$definitionId&vintage=${Uri.encode(vintage)}"
                            )
                        },
                        onEditNote = { noteId -> navController.navigate("note_editor/edit/$noteId") },
                        onConsumed = { message, eventId ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = message,
                                    actionLabel = "UNDO",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoConsumeBottle(eventId)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Dimming Overlay
    AnimatedVisibility(
        visible = isMenuExpanded,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.05f),
                            Color.Black.copy(alpha = 0.2f)
                        )
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { isMenuExpanded = false }
        )
    }

    // Popup Menu
    AnimatedVisibility(
        visible = isMenuExpanded,
        enter = slideInVertically(initialOffsetY = { 40 }) + fadeIn() + expandVertically(),
        exit = slideOutVertically(targetOffsetY = { 40 }) + fadeOut() + shrinkVertically(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 96.dp), // Position above BottomBar
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .width(110.dp)
                    .shadow(16.dp, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PopupMenuItem(
                        text = "Add Wine",
                        icon = Icons.Default.WineBar,
                        onClick = {
                            Log.d("WineTracker", "Popup: Add Wine clicked")
                            isMenuExpanded = false
                            navController.navigate("add_wine")
                        }
                    )
                    PopupMenuItem(
                        text = "Add Note",
                        icon = Icons.Default.HistoryEdu,
                        onClick = {
                            Log.d("WineTracker", "Popup: Add Note clicked")
                            isMenuExpanded = false
                            navController.navigate("note_editor/create")
                        }
                    )
                }
            }
        }
    }
}

}


@Composable
fun PopupMenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier.width(90.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF722F37).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color(0xFF722F37),
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun navigateToTab(navController: androidx.navigation.NavHostController, route: String) {
    Log.d("WineTracker", "navigateToTab: target=$route")
    val currentRoute = navController.currentBackStackEntry?.destination?.route

    // Ensure we don't save transient "add_wine" or "history" sub-screens on the tab backstack 
    // when switching to a different tab.
    if (currentRoute == "history" && route != "notes") {
        navController.popBackStack("notes", inclusive = false)
    }
    if (currentRoute == "add_wine" && route != "cellar") {
        navController.popBackStack("cellar", inclusive = false)
    }

    // If we're on history and navigating to another tab, remove history from the back stack
    // so that when returning to the "notes" tab, it defaults to the main notes screen.
    if (navController.currentBackStackEntry?.destination?.route == "history" && route != "notes") {
        navController.popBackStack("notes", inclusive = false)
    }
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}