# WineTracker Architecture & App Map

## 1. Core Philosophy: Separation of Concerns (SoC)
We strictly enforce modularity to keep files small, maintainable,
and token-efficient. **Do not create "God Objects" or monolithic files.**

**Rules for the AI agent:**
- Extract large blocks of code into the `components/` folder if they are reused across multiple places. Otherwise, consider extracting components when a file becomes too complex or unwieldy (typically > 400-500 lines) to maintain readability.
- New screens go in `ui/screens/`.
- New reusable widgets go in `ui/components/`.
- New database entities go in `data/models/`.
- New DAO methods go in `data/local/WineDaos.kt` (single file).
- All database access from ViewModels must go through
  `data/repository/WineRepository.kt`. Never call DAOs directly
  from a ViewModel or Screen.
- **Lazy Initialization:** Always use `by lazy` for heavy components in ViewModels (Retrofit, OCR managers, batch processors) to prevent blocking the main thread during startup.
- **Background Migrations:** Database migrations must be triggered on a background thread during `MainActivity` creation to ensure the UI remains responsive.
- **ViewModel Specialization:** Avoid the "God ViewModel" pattern. Use specialized ViewModels for distinct feature areas (e.g., `ScannerViewModel` for media, `RankingsViewModel` for analytics). This keeps the code modular and prevents token-limit issues during development.
- New ViewModels go in `viewmodels/`. Prefer one per screen or functional group over one monolithic ViewModel.
- CSV-to-entity mapping logic stays in `data/importer/`.
  Do not create separate mapper classes unless there are
  3+ distinct import sources.

## 2. Directory Structure Map

com.n8rtz.winetracker/
│
├── MainActivity.kt                    # Entry point + persistent App Scaffold & Expandable FAB logic
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
    │   └── WineEvaluationScreen.kt    # Advanced AWS evaluation form (Sensory Analysis)
       └── components/
        ├── WineCard.kt                # Color-coded card for Cellar and Rankings (Supports Expansion)
        ├── HistoryWineCard.kt         # Compact list item for History screen
        ├── AnalyticsCard.kt           # Dashboard metrics cards (Total Bottles, Value, etc.)
        ├── EmptyState.kt              # "Your Cellar is Empty" placeholder
        ├── WheelPicker.kt             # Birthday-style scroll picker + LocationWheelPicker
        ├── ConsumeConfirmDialog.kt    # Date picker for confirmation
        ├── LocationWheelPicker.kt     # Scrollable dial-like selectors for location
        ├── DynamicColorSlider.kt      # Ombre gradient slider for wine hue selection
        ├── AromaWheelOverlay.kt       # High-fidelity radial aroma wheel with interactive zoom
        ├── SommelierAromaWheelOverlay.kt # Standalone alternative aroma wheel (counterclockwise layout, drop-in swap)
        ├── AppearanceCard.kt          # Evaluation section: Clarity, Type, Hue
        ├── NoseCard.kt                # Evaluation section: Intensity, Aroma Wheel entry
        ├── PalateCard.kt              # Evaluation section: Structural sliders (Tannin, Acidity, etc)
        ├── FinishCard.kt              # Evaluation section: Length
        ├── FlawsCard.kt               # Evaluation section: Common faults checkboxes
        └── OverallCard.kt             # Evaluation section: 100-point score display & custom notes
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
│   │   ├── SensoryData.kt             # Data class for JSON-serializable sensory analysis
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
    ├── WineViewModel.kt               # Core ViewModel: Cellar, History, Import/Export
    ├── TastingNoteViewModel.kt        # Note editor state, save logic
    ├── RankingsViewModel.kt           # Rankings filtering, sorting, price lookups
    └── ScannerViewModel.kt            # Camera OCR scanning, batch image import via OpenFoodFacts

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