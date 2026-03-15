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
- **Lazy Initialization:** Always use `by lazy` for heavy components in ViewModels (Retrofit, OCR managers, batch processors) to prevent blocking the main thread during startup.
- **Background Migrations:** Database migrations must be triggered on a background thread during `MainActivity` creation to ensure the UI remains responsive.
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
│    ├── screens/
    │   ├── CellarScreen.kt            # Active inventory + dashboard header + search
    │   ├── CellarFilterState.kt       # Data class for cellar filtering
    │   ├── HistoryScreen.kt           # Consumed wines chronological (compact ListItems)
    │   ├── TastingNotesScreen.kt      # Tasting note repository + search + filters
    │   ├── RankingsScreen.kt          # Sorted by 100-point rating + search + filters
    │   ├── RankingsFilterState.kt     # Data class for rankings filtering
    │   ├── AddWineScreen.kt           # Manual entry form + cascading dropdowns
    │   ├── WineDetailScreen.kt        # Full wine detail + "Mark as Consumed"
       └── components/
        ├── WineCard.kt                # Color-coded card for Cellar and Rankings (Supports Expansion)
        ├── HistoryWineCard.kt         # Compact list item for History screen
        ├── AnalyticsCard.kt           # Dashboard metrics cards (Total Bottles, Value, etc.)
        ├── EmptyState.kt              # "Your Cellar is Empty" placeholder
        ├── WheelPicker.kt             # Birthday-style scroll picker + LocationWheelPicker
        ├── ConsumeConfirmDialog.kt    # Date picker for confirmation
        └── LocationWheelPicker.kt     # Scrollable dial-like selectors for location
│
├── data/
│   ├── local/
│   │   ├── WineDatabase.kt            # Room database definition + migrations
│   │   └── WineDaos.kt                # All DAO interfaces (single file)
│   │
│   ├── models/
│   │   ├── WineDefinition.kt          # Catalog: Maker, Name, Type, Country, Region
│   │   ├── WineBottle.kt              # Inventory: links to Definition, Vintage, Qty, Price
│   │   ├── TastingNote.kt             # Rating, notes text, rebuy boolean, bottleId (links to specific bottle)
│   │   ├── BottleStatus.kt            # Enum: IN_CELLAR, ARCHIVED
│   │   └── LocationData.kt            # Cascading dropdown data
│   │
│   ├── repository/
│   │   └── WineRepository.kt          # Single source of truth for all data access
│   │
│   └── importer/
│       ├── DataImporter.kt            # JSON import logic + relational mapping
│       ├── JsonExporter.kt            # JSON export logic using Gson
│       ├── BackupData.kt              # Backup model for JSON serialization
│       ├── CsvExporter.kt             # (Deprecated) CSV export logic
│       ├── CsvImportHelper.kt         # CSV parsing utilities
│       └── InventoryExporter.kt       # Exporter for cellar inventory
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
    ├── WineViewModel.kt               # Main ViewModel: Cellar, History, Rankings states
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

## 4. Performance Patterns
- **Batch Database Queries**: Always prefer batch queries (e.g., `WHERE id IN (...)`) over single queries in loops.
- **Reactive Filtering/Sorting**: Perform heavy filtering, sorting, and data preparation (like string formatting) in the ViewModel using `combine` and `StateFlow` to keep the UI thread responsive.
- **Logging**: Include execution time logs for heavy data transformations to identify potential bottlenecks.

## 5. Import/Export Integrity Rules
- **ID Mapping is mandatory:** When importing relational data, always maintain mapping dictionaries (`idMapping` for definitions, `bottleIdMapping` for bottles) to translate old auto-generated IDs to new ones. Never assume IDs persist across export/import cycles.
- **Never use fallback vintage lookups:** A note's vintage must come from its directly linked bottle (via `bottleId`), not from a random bottle under the same definition. The `vintageByOldDefId` pattern was banned after [Bug-016] caused silent vintage corruption for horizontal tastings.
- **Bottle is the source of truth for vintage:** `WineBottle.vintage` is authoritative. `TastingNote.vintage` is a copy derived from the linked bottle at creation time or import time.
- **Test with horizontal tastings:** Any changes to the import pipeline must be validated against the scenario of multiple vintages of the same wine consumed on the same day (identical timestamps, same `definitionId`, different vintages).