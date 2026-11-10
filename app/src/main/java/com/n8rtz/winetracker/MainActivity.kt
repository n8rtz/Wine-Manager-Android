@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.n8rtz.winetracker

import android.os.Bundle
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.n8rtz.winetracker.ui.components.BottomBarLabelSizing
import com.n8rtz.winetracker.ui.screens.AddWineScreen
import com.n8rtz.winetracker.ui.screens.AppInfoScreen
import com.n8rtz.winetracker.ui.screens.BackupRestoreScreen
import com.n8rtz.winetracker.ui.screens.DatabaseLookupScreen
import com.n8rtz.winetracker.ui.screens.HistoryScreen
import com.n8rtz.winetracker.data.importer.DevSeedRestorer
import com.n8rtz.winetracker.ui.screens.ProfileScreen
import com.n8rtz.winetracker.ui.screens.ProfileSection
import com.n8rtz.winetracker.ui.screens.ParetoScreen
import com.n8rtz.winetracker.ui.screens.TastingNotesScreen
import com.n8rtz.winetracker.ui.screens.TastingNoteDetailScreen
import com.n8rtz.winetracker.ui.screens.TastingNoteEditorScreen
import com.n8rtz.winetracker.ui.screens.WineEditScreen
import com.n8rtz.winetracker.ui.screens.CellarScreen
import com.n8rtz.winetracker.ui.screens.RankingsScreen
import com.n8rtz.winetracker.ui.theme.WineTrackerTheme
import com.n8rtz.winetracker.viewmodels.TastingNoteViewModel
import com.n8rtz.winetracker.viewmodels.WineViewModel
import com.n8rtz.winetracker.viewmodels.RankingsViewModel
import com.n8rtz.winetracker.viewmodels.ScannerViewModel
import com.n8rtz.winetracker.backup.BackupViewModel
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
            com.n8rtz.winetracker.data.local.MIGRATION_16_17,
            com.n8rtz.winetracker.data.local.MIGRATION_17_18,
            com.n8rtz.winetracker.data.local.MIGRATION_18_19,
            com.n8rtz.winetracker.data.local.MIGRATION_19_20,
            com.n8rtz.winetracker.data.local.MIGRATION_20_21,
            com.n8rtz.winetracker.data.local.MIGRATION_21_22
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

    private val backupViewModel: BackupViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                Log.d("WineTracker", "MainActivity: Creating BackupViewModel")
                return BackupViewModel(this@MainActivity.application, database) as T
            }
        }
    }

    /**
     * Startup gate: the cellar UI (and its ViewModels, which touch the database on creation)
     * only composes after the database has opened cleanly. A failed open used to crash the
     * app silently during composition; it now shows what went wrong instead.
     */
    private val startupDbState = mutableStateOf<StartupDbState>(StartupDbState.Checking)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("WineTracker", "MainActivity: onCreate started")
        enableEdgeToEdge()

        startDatabaseProbe()

        setContent {
            WineTrackerTheme {
                when (val state = startupDbState.value) {
                    StartupDbState.Checking -> StartupCheckingScreen()
                    StartupDbState.Ready -> {
                        val navController = rememberNavController()
                        MainAppScaffold(
                            navController,
                            wineViewModel,
                            tastingNoteViewModel,
                            rankingsViewModel,
                            scannerViewModel,
                            backupViewModel
                        )
                    }
                    is StartupDbState.Failed -> StartupDbErrorScreen(state.detail) {
                        startupDbState.value = StartupDbState.Checking
                        startDatabaseProbe()
                    }
                }
            }
        }
    }

    /** Opens the database (running pending migrations) off the main thread, then gates the UI. */
    private fun startDatabaseProbe() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Log.d("WineTracker", "MainActivity: Triggering background migration check")
            val openError = try {
                database.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT 1")).use { }
                Log.d("WineTracker", "MainActivity: Background migration check complete")
                null
            } catch (e: Exception) {
                Log.e("WineTracker", "MainActivity: Error during background migration", e)
                e
            }
            if (openError != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    startupDbState.value = StartupDbState.Failed(
                        openError.message?.takeIf { it.isNotBlank() }
                            ?: openError.javaClass.simpleName
                    )
                }
                return@launch
            }

            // Debug builds only: restore the developer's bundled backup into an empty database (see DevSeedRestorer).
            val seedMessage = when (val result = DevSeedRestorer(applicationContext, database).restoreIfNeeded()) {
                is DevSeedRestorer.Result.Restored -> "Dev seed restored: ${result.definitions} wines, ${result.notes} notes"
                is DevSeedRestorer.Result.Failed -> "Dev seed restore failed: ${result.message}"
                else -> null
            }
            if (seedMessage != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(applicationContext, seedMessage, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                startupDbState.value = StartupDbState.Ready
            }
        }
    }

    /** Backup due-check runs on app open / return to the foreground only; no background work. */
    override fun onResume() {
        super.onResume()
        Log.d("WineTracker", "MainActivity: onResume — backup due-check")
        backupViewModel.onAppOpened()
    }
}


/**
 * The bottom bar's destinations, in order. The texts are shared with the size measurement below, so
 * what the bar draws and what it measures to fit can never drift apart.
 */
private const val BOTTOM_BAR_LABEL_CELLAR = "Cellar"
private const val BOTTOM_BAR_LABEL_NOTES = "Notes"
private const val BOTTOM_BAR_LABEL_HISTORY = "History"
private const val BOTTOM_BAR_LABEL_RANKINGS = "Rankings"
private const val BOTTOM_BAR_LABEL_PROFILE = "Profile"

private val BOTTOM_BAR_LABELS = listOf(
    BOTTOM_BAR_LABEL_CELLAR,
    BOTTOM_BAR_LABEL_NOTES,
    BOTTOM_BAR_LABEL_HISTORY,
    BOTTOM_BAR_LABEL_RANKINGS,
    BOTTOM_BAR_LABEL_PROFILE
)

/**
 * The one font size all five labels are drawn at, chosen from the item width the bar actually laid out.
 *
 * Each half of this is a fix for a way the row used to go wrong: the widest label is measured rather
 * than guessed at, so the size is right on any device and at any system text size; and the size is
 * taken from that widest label for all five, so the row reads level instead of shrinking only the long
 * word. The measured width is taken when the bar is drawn at Material's own label size, which is 12sp,
 * so on the default text size this returns exactly that and nothing changes.
 */
@Composable
private fun rememberUniformBottomBarLabelSize(itemWidthPx: Int): TextUnit {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = BottomBarLabelSizing.MAX_FONT_SIZE_SP.sp
    )
    val widestLabelWidthPx = remember(measurer, labelStyle) {
        BOTTOM_BAR_LABELS
            .maxOf { label -> measurer.measure(label, labelStyle).size.width }
            .toFloat()
    }
    // A couple of dp of slack: text measurement rounds, and overrunning the item would clip a label.
    val safetyMarginPx = with(LocalDensity.current) { 2.dp.toPx() }
    val availableWidthPx = (itemWidthPx - safetyMarginPx).coerceAtLeast(1f)
    return BottomBarLabelSizing.uniformFontSizeSp(widestLabelWidthPx, availableWidthPx).sp
}

/**
 * A bottom-navigation label, always one line at the size the whole row shares.
 *
 * One line keeps the bar's height fixed, and the shared size keeps the row even; if a label somehow
 * cannot fit even at the floor size it clips with an ellipsis rather than wrapping onto a second row
 * and growing the bar.
 */
@Composable
private fun BottomBarLabel(text: String, fontSize: TextUnit) {
    Text(
        text = text,
        fontSize = fontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun MainAppScaffold(
    navController: androidx.navigation.NavHostController,
    viewModel: WineViewModel,
    tastingNoteViewModel: TastingNoteViewModel,
    rankingsViewModel: RankingsViewModel,
    scannerViewModel: ScannerViewModel,
    backupViewModel: BackupViewModel
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    var isMenuExpanded by remember { mutableStateOf(false) }
    // True while the cellar's bottom wine action bar is showing; the "+"
    // Add button/menu disappears for as long as it is up.
    var wineActionBarVisible by remember { mutableStateOf(false) }
    var menuHistory by remember { mutableStateOf(listOf("cellar")) }
    fun navigateToMenu(route: String) {
        if (menuHistory.lastOrNull() != route) menuHistory = menuHistory + route
        navigateToTab(navController, route)
    }
    val hasPreviousMenu = menuHistory.size > 1
    BackHandler(enabled = isMenuExpanded || currentRoute != null && (currentRoute !in TOP_LEVEL_MENU_ROUTES || hasPreviousMenu)) {
        if (isMenuExpanded) {
            isMenuExpanded = false
        } else if (currentRoute != null && currentRoute in TOP_LEVEL_MENU_ROUTES) {
            if (hasPreviousMenu) {
                val target = menuHistory[menuHistory.lastIndex - 1]
                menuHistory = menuHistory.dropLast(1)
                navigateToTab(navController, target)
            }
        } else if (!navController.popBackStack() && hasPreviousMenu) {
            val target = menuHistory[menuHistory.lastIndex - 1]
            menuHistory = menuHistory.dropLast(1)
            navigateToTab(navController, target)
        }
    }
    // The Add menu is only available from the Cellar and Notes tabs. It stays off every other
    // route, including the wine editor, note editors, Settings and Backup & Restore.
    val showAddMenu = currentRoute == "cellar" || currentRoute == "notes"
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    fun showConsumptionUndo(message: String, eventId: Long) {
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
    val backupState by backupViewModel.state.collectAsState()
    // Close an open Add submenu as soon as the user leaves Cellar or Notes.
    LaunchedEffect(currentRoute) {
        if (!showAddMenu) isMenuExpanded = false
        if (currentRoute != "cellar") wineActionBarVisible = false
    }
    // The cellar wine action bar replaces the "+" menu while it is showing.
    LaunchedEffect(wineActionBarVisible) {
        if (wineActionBarVisible) isMenuExpanded = false
    }

    /** A JSON restore rewrites SharedPreferences; refresh the in-memory ViewModels immediately. */
    fun refreshAfterRestore() {
        tastingNoteViewModel.refreshReviewerNames()
    }
    val fabRotation by animateFloatAsState(targetValue = if (isMenuExpanded) 45f else 0f, label = "fabRotation")

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            floatingActionButton = {
                if (showAddMenu && currentRoute != "add_wine" && !wineActionBarVisible) {
                    Surface(
                        onClick = {
                            if (currentRoute == "notes") {
                                // On Tasting Notes the only sensible add is a note: skip the submenu.
                                Log.d("WineTracker", "FAB: Tasting Notes, opening Add Note directly")
                                isMenuExpanded = false
                                navController.navigate("note_editor/create")
                            } else {
                                isMenuExpanded = !isMenuExpanded
                            }
                        },
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
            },
            floatingActionButtonPosition = FabPosition.End,
            bottomBar = {
            // The camera-first Add Wine screen is full-bleed: no nav bar or FAB over it.
            if (currentRoute != "add_wine") {
            // Every item is given an equal share of the bar, so one item's width is every item's
            // width: measuring the first one is enough to size the whole row's labels.
            var bottomBarItemWidthPx by remember { mutableIntStateOf(0) }
            val bottomBarLabelSize = rememberUniformBottomBarLabelSize(bottomBarItemWidthPx)

            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
                // Deliberately no fixed height. NavigationBar sizes its Surface as 80.dp of icons
                // and labels plus the system navigation-bar inset below them. Forcing a total height
                // of 80.dp made the inset eat into that content, which sliced off the labels — worst
                // on 3-button navigation, where the inset is tallest.
            ) {
                NavigationBarItem(
                    modifier = Modifier.onSizeChanged { bottomBarItemWidthPx = it.width },
                    selected = currentRoute == "cellar" || currentRoute == "add_wine",
                    onClick = {
                        Log.d("WineTracker", "BottomBar: Cellar clicked")
                        isMenuExpanded = false
                        if (currentRoute != "cellar") {
                            // Always land on the Cellar root: this covers the wine editor, an
                            // add/note editor reached from any tab, and every other tab.
                            if (menuHistory.lastOrNull() != "cellar") menuHistory = menuHistory + "cellar"
                            if (!navController.popBackStack("cellar", inclusive = false)) {
                                navigateToMenu("cellar")
                            }
                        }
                        viewModel.resetCellarScreen()
                    },
                    icon = { Icon(Icons.Default.WineBar, "Cellar") },
                    label = { BottomBarLabel(BOTTOM_BAR_LABEL_CELLAR, bottomBarLabelSize) }
                )
                NavigationBarItem(
                    selected = currentRoute == "notes",
                    onClick = {
                        Log.d("WineTracker", "BottomBar: Notes clicked")
                        isMenuExpanded = false
                        navigateToMenu("notes")
                    },
                    icon = { Icon(Icons.Default.Description, "Notes") },
                    label = { BottomBarLabel(BOTTOM_BAR_LABEL_NOTES, bottomBarLabelSize) }
                )
                NavigationBarItem(
                    selected = currentRoute == "history",
                    onClick = {
                        Log.d("WineTracker", "BottomBar: History clicked")
                        isMenuExpanded = false
                        navigateToMenu("history")
                    },
                    icon = { Icon(Icons.Default.History, contentDescription = "Consumption History") },
                    label = { BottomBarLabel(BOTTOM_BAR_LABEL_HISTORY, bottomBarLabelSize) }
                )
                NavigationBarItem(
                    selected = currentRoute == "rankings",
                    onClick = {
                        Log.d("WineTracker", "BottomBar: Rankings clicked")
                        isMenuExpanded = false
                        navigateToMenu("rankings")
                    },
                    icon = { Icon(Icons.Default.Star, "Rankings") },
                    label = { BottomBarLabel(BOTTOM_BAR_LABEL_RANKINGS, bottomBarLabelSize) }
                )
                NavigationBarItem(
                    selected = currentRoute == "profile" || currentRoute == "settings" || currentRoute == "app_info" || currentRoute == "pareto" || currentRoute == "backup_restore",
                    onClick = {
                        Log.d("WineTracker", "BottomBar: Profile clicked")
                        isMenuExpanded = false
                        navigateToMenu("profile")
                    },
                    icon = { Icon(Icons.Default.Person, "Profile") },
                    label = { BottomBarLabel(BOTTOM_BAR_LABEL_PROFILE, bottomBarLabelSize) }
                )
            }
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
                CellarScreen(
                    viewModel = viewModel,
                    onAddNote = { definitionId, vintage ->
                        navController.navigate(
                            "note_editor/create?definitionId=$definitionId&vintage=${Uri.encode(vintage)}"
                        )
                    },
                    onEditWine = { bottleId -> navController.navigate("wine_edit/$bottleId") },
                    onNoteClick = { noteId -> navController.navigate("note_detail/$noteId") },
                    onTastingNotesClick = { definitionId, vintage ->
                        tastingNoteViewModel.openWineTastingNotes(definitionId, vintage)
                        navigateToMenu("notes")
                    },
                    onConsumed = ::showConsumptionUndo,
                    onWineActionBarVisibleChange = { wineActionBarVisible = it }
                )
            }
            composable("notes") {
                TastingNotesScreen(
                    tastingNoteViewModel = tastingNoteViewModel,
                    onNoteClick = { noteId -> navController.navigate("note_detail/$noteId") }
                )
            }
            composable("rankings") {
                RankingsScreen(rankingsViewModel)
            }
            composable("history") {
                HistoryScreen(viewModel) { noteId, eventId, definitionId, vintage, consumedAt ->
                    if (noteId != null) {
                        navController.navigate("note_detail/$noteId")
                    } else {
                        navController.navigate(
                            "note_editor/create?definitionId=$definitionId&vintage=${Uri.encode(vintage)}&eventId=$eventId&dateTasted=${consumedAt ?: -1L}"
                        )
                    }
                }
            }
            composable("profile") {
                ProfileScreen(
                    viewModel = viewModel,
                    tastingNoteViewModel = tastingNoteViewModel,
                    onSettingsClick = { navController.navigate("settings") },
                    onParetoClick = { navController.navigate("pareto") }
                ) {
                    refreshAfterRestore()
                    navigateToMenu("cellar")
                }
            }
            composable("settings") {
                ProfileScreen(
                    viewModel = viewModel,
                    tastingNoteViewModel = tastingNoteViewModel,
                    section = ProfileSection.SETTINGS,
                    onBack = { navController.popBackStack() },
                    onAppInfoClick = { navController.navigate("app_info") },
                    onBackupRestoreClick = { navController.navigate("backup_restore") },
                    onDatabaseLookupClick = { navController.navigate("database_lookup") }
                ) {
                    refreshAfterRestore()
                    navigateToMenu("cellar")
                }
            }
            composable("backup_restore") {
                BackupRestoreScreen(
                    backupViewModel = backupViewModel,
                    wineViewModel = viewModel,
                    onBack = { navController.popBackStack() }
                ) {
                    refreshAfterRestore()
                    navigateToMenu("cellar")
                }
            }
            composable("database_lookup") {
                DatabaseLookupScreen(onBack = { navController.popBackStack() })
            }
            composable("app_info") {
                AppInfoScreen(
                    database = viewModel.repository.database,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("pareto") {
                ParetoScreen(
                    wineViewModel = viewModel,
                    tastingNoteViewModel = tastingNoteViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("add_wine") {
                AddWineScreen(viewModel, scannerViewModel,
                    onWineAdded = { navController.popBackStack("cellar", inclusive = false) },
                    onClose = { navController.popBackStack() })
            }
            composable(
                route = "note_detail/{noteId}",
                arguments = listOf(navArgument("noteId") { type = NavType.LongType })
            ) { backStackEntry ->
                val noteId = backStackEntry.arguments?.getLong("noteId")
                if (noteId != null) {
                    TastingNoteDetailScreen(
                        viewModel = tastingNoteViewModel,
                        noteId = noteId,
                        onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate("note_editor/edit/$noteId") }
                    )
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
                route = "wine_edit/{bottleId}",
                arguments = listOf(navArgument("bottleId") { type = NavType.LongType })
            ) { backStackEntry ->
                val bottleId = backStackEntry.arguments?.getLong("bottleId")
                if (bottleId != null) {
                    WineEditScreen(
                        bottleId = bottleId,
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }

    // W-057: in-app popup on EVERY app open while a backup remains failed. No system
    // notification permission and no background work are involved.
    if (backupState.failureDialog != null) {
        AlertDialog(
            onDismissRequest = { backupViewModel.dismissFailureDialog() },
            title = { Text("Backup failed") },
            text = { Text(backupState.failureDialog!!) },
            confirmButton = {
                TextButton(onClick = { backupViewModel.dismissFailureDialog() }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    backupViewModel.dismissFailureDialog()
                    navController.navigate("backup_restore")
                }) { Text("Open Backup & Restore") }
            }
        )
    }

    // Dimming Overlay
    AnimatedVisibility(
        visible = isMenuExpanded && showAddMenu && !wineActionBarVisible,
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
        visible = isMenuExpanded && showAddMenu && !wineActionBarVisible,
        enter = slideInVertically(initialOffsetY = { 40 }) + fadeIn() + expandVertically(),
        exit = slideOutVertically(targetOffsetY = { 40 }) + fadeOut() + shrinkVertically(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 96.dp, end = 16.dp), // Position above the bottom bar and add button
            contentAlignment = Alignment.BottomEnd
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

/** Result of the startup database probe that gates the cellar UI. */
private sealed interface StartupDbState {
    data object Checking : StartupDbState
    data object Ready : StartupDbState
    data class Failed(val detail: String) : StartupDbState
}

/** Shown while the startup database probe (and any pending migration) runs. */
@Composable
private fun StartupCheckingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text("Opening cellar…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Shown when the database cannot be opened at startup. Only the open failed, so the stored
 * data is untouched; the screen says so, shows the technical detail, and names this build so
 * a build/data mismatch can be matched to the right build.
 */
@Composable
private fun StartupDbErrorScreen(detail: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Couldn't open the cellar",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "The database couldn't be opened, so the cellar is not shown. " +
                    "Your data has not been changed or deleted. This usually means the " +
                    "installed build is older than the data on the device — install the " +
                    "newest build and try again.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                "Build: ${BuildConfig.BUILD_BRANCH} @ ${BuildConfig.BUILD_COMMIT} " +
                    "(${BuildConfig.BUILD_WORKING_TREE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onRetry) {
                Text("Try again")
            }
        }
    }
}

private val TOP_LEVEL_MENU_ROUTES = setOf("cellar", "notes", "history", "rankings", "profile")

private fun navigateToTab(navController: androidx.navigation.NavHostController, route: String) {
    Log.d("WineTracker", "navigateToTab: target=$route")
    val currentRoute = navController.currentBackStackEntry?.destination?.route

    // Selecting the History tab from a note opened there should return to its root screen.
    if (route == "history" && currentRoute != "history" &&
        navController.popBackStack("history", inclusive = false)
    ) {
        return
    }

    // Selecting the Profile tab from Settings, App Information or Price vs. Rating returns to the Profile root.
    if (route == "profile" && currentRoute != "profile" &&
        navController.popBackStack("profile", inclusive = false)
    ) {
        return
    }

    // Do not preserve the transient add-wine form when switching to another tab.
    if (currentRoute == "add_wine" && route != "cellar") {
        navController.popBackStack("cellar", inclusive = false)
    }
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        // The Profile tab always opens its standard root page. Restoring saved state would bring back
        // whichever Settings / App Information / Price vs. Rating screen was open when the user left.
        restoreState = route != "profile"
    }
}