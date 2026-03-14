# WineTracker Architecture & App Map

## 1. Core Philosophy: Separation of Concerns (SoC)
We strictly enforce modularity to keep files small, maintainable,
and token-efficient. **Do not create "God Objects" or monolithic files.**

**Rules for the AI agent:**
- If a Compose file exceeds 200 lines, extract components
  into the `components/` folder.
- New screens go in `ui/screens/`.
- New reusable widgets go in `ui/components/`.
- New database entities go in `data/models/`.
- New DAO methods go in `data/local/WineDaos.kt` (single file).
- All database access from ViewModels must go through
  `data/repository/WineRepository.kt`. Never call DAOs directly
  from a ViewModel or Screen.
- New ViewModels go in `viewmodels/`. Prefer one per screen
  over one monolithic ViewModel.
- CSV-to-entity mapping logic stays in `data/importer/`.
  Do not create separate mapper classes unless there are
  3+ distinct import sources.

## 2. Directory Structure Map

com.n8rtz.winetracker/
│
├── MainActivity.kt                    # ONLY the entry point and theme wrapper
│
├── ui/
│   ├── theme/
│   │   ├── Color.kt                   # Bordeaux Red, Off-White, Slate, Gray
│   │   ├── Theme.kt                   # Light/Dark theme overrides
│   │   └── Type.kt                    # Typography scale
│   │
│   ├── navigation/
│   │   └── WineTrackerApp.kt          # Scaffold, NavHost, BottomNav, Routes
│   │
│   ├── screens/
│   │   ├── CellarScreen.kt            # Active inventory + dashboard header + search
│   │   ├── HistoryScreen.kt           # Consumed wines chronological (compact ListItems)
│   │   ├── TastingNotesScreen.kt      # Tasting note repository + search + filters
│   │   ├── RankingsScreen.kt          # Sorted by 100-point rating
│   │   ├── AddWineScreen.kt           # Manual entry form + cascading dropdowns
│   │   ├── WineDetailScreen.kt        # Full wine detail + "Mark as Consumed"
│   │   ├── NoteEditorScreen.kt        # Distraction-free note writing + score
│   │   └── ProfileScreen.kt           # User settings and profile
│   │
│   └── components/
│       ├── WineCard.kt                # Color-coded card for Cellar and Rankings
│       ├── HistoryListItem.kt         # Compact list item for History screen
│       ├── DashboardHeader.kt         # Analytics: Total Bottles, Value, Unique
│       ├── FilterSheet.kt             # Modal bottom sheet (FlowRow chips, sliders)
│       ├── EmptyState.kt              # "Your Cellar is Empty" placeholder
│       └── ConsumeConfirmDialog.kt    # Date picker for consumption confirmation
│
├── data/
│   ├── local/
│   │   ├── WineDatabase.kt            # Room database definition + migrations
│   │   └── WineDaos.kt                # All DAO interfaces (single file)
│   │
│   ├── models/
│   │   ├── WineDefinition.kt          # Catalog: Maker, Name, Type, Country, Region
│   │   ├── WineBottle.kt              # Inventory: links to Definition, Vintage, Qty, Price
│   │   ├── TastingNote.kt             # Rating, notes text, rebuy boolean
│   │   ├── BottleStatus.kt            # Enum: IN_CELLAR, ARCHIVED
│   │   └── LocationData.kt            # Cascading dropdown data (Country → Region → Appellation)
│   │
│   ├── repository/
│   │   └── WineRepository.kt          # Single source of truth for all data access
│   │
│   └── importer/
│       ├── DataImporter.kt            # Main CSV import + mapping to relational structure
│       ├── CsvExporter.kt             # CSV export logic
│       ├── CsvImportHelper.kt         # Parsing utilities
│       ├── InventoryExporter.kt       # Exporter for cellar inventory
│       └── LegacyDatabaseImporter.kt  # Legacy import support
│
├── scanner/
│   └── ScannerManager.kt              # ML Kit OCR + CameraX label scanning
│
├── network/                           # Shelved: API Integrations
│   ├── OpenFoodFactsApi.kt            
│   └── WineApiService.kt              
│
├── image/                             # Shelved: Automated image processing
│   ├── WineImageBatchProcessor.kt     
│   └── MetadataScorer.kt              
│
└── viewmodels/
├── CellarViewModel.kt             # Inventory state, filters, search, dashboard metrics
├── HistoryViewModel.kt            # Consumed wines, date sorting
├── RankingsViewModel.kt           # Rating-sorted state
├── AddWineViewModel.kt            # Form state, validation, cascading dropdowns
├── DetailViewModel.kt             # Single wine detail, consume action, undo
└── TastingNoteViewModel.kt        # Note editor state, save logic

## 3. Data Flow Rule
Every data operation follows this chain. No shortcuts.

User Action
│
▼
Screen (UI)         ← observes state, sends events
│
▼
ViewModel           ← holds state, runs business logic in viewModelScope
│
▼
WineRepository      ← single source of truth, coordinates operations
│
▼
WineDaos            ← raw database queries
│
▼
WineDatabase (Room) ← SQLite