@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.n8rtz.winetracker

import android.os.Bundle
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
import com.n8rtz.winetracker.ui.screens.HistoryScreen
import com.n8rtz.winetracker.ui.screens.ProfileScreen
import com.n8rtz.winetracker.ui.screens.TastingNotesScreen
import com.n8rtz.winetracker.ui.screens.WineDetailScreen
import com.n8rtz.winetracker.ui.screens.CellarScreen
import com.n8rtz.winetracker.ui.screens.RankingsScreen
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    
    // Consolidate database and repository as single instances for the entire app lifecycle
    private val database by lazy {
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
            com.n8rtz.winetracker.data.local.MIGRATION_9_10
        )
        .build()
    }

    private val repository by lazy {
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
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return WineViewModel(this@MainActivity.application, repository) as T
            }
        }
    }

    private val tastingNoteViewModel: TastingNoteViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return TastingNoteViewModel(this@MainActivity.application, repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WineTrackerTheme {
                val navController = rememberNavController()
                MainAppScaffold(navController, wineViewModel, tastingNoteViewModel)
            }
        }
    }
}


@Composable
fun MainAppScaffold(
    navController: androidx.navigation.NavHostController, 
    viewModel: WineViewModel,
    tastingNoteViewModel: TastingNoteViewModel
) {
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
                    selected = currentRoute == "notes" || currentRoute == "history",
                    onClick = {
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
                    tastingNoteViewModel = tastingNoteViewModel,
                    onNoteClick = { /* Handle note click */ },
                    onNavigateToHistory = {
                        Log.d("WineTracker", "MainActivity: Navigating to History")
                        navController.navigate("history")
                    }
                )
            }
            composable("rankings") {
                RankingsScreen(viewModel)
            }
            composable("history") {
                // Shared WineViewModel passed correctly to HistoryScreen
                HistoryScreen(viewModel)
            }
            composable("profile") {
                ProfileScreen(viewModel = viewModel) {
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