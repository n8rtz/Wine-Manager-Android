**Accurate as of 3/14/26 11AM**

changing project structure soon after.



com.n8rtz.winetracker
├── assets/                 # (Files: WineTracker_Backup.csv) Raw data assets
├── image/                  # Image processing and optimization
│   ├── WhitespaceAnalyzer.kt
│   └── WineImageBatchProcessor.kt
├── network/                # Network service implementations
│   ├── DuckDuckGoImageSearch.kt
│   └── OpenFoodFactsApi.kt
├── ui/                     # UI theme definitions (Material 3)
│   └── theme/
├── AddWineScreen.kt        # Entry form for new wine entries
├── ArchiveScreen.kt        # View for historical (archived) wines
├── HistoryScreen.kt        # Historical data list/ranking view
├── ProfileScreen.kt        # User/Account-related configuration
├── WineDetailScreen.kt     # Deep-dive view of a single wine bottle
├── TastingNotesScreen.kt   # Form to log ratings and notes after consumption
├── MainActivity.kt         # Entry point and navigation host for the app
├── WineViewModel.kt        # Primary state holder and business logic coordinator
├── WineRepository.kt       # Data abstraction layer (Source of Truth)
├── WineDatabase.kt         # Room database configuration and setup
├── WineDaos.kt             # Data Access Objects for database operations
├── ScannerManager.kt       # Logic for ML Kit label scanning
├── MetadataScorer.kt       # Logic for calculating drinking windows/ratings
├── CsvExporter.kt          # Export logic for inventory
├── DataImporter.kt         # Master importer with Two-Pass ID Mapping logic
├── CsvImportHelper.kt      # Utility for parsing CSV files
├── LegacyDatabaseImporter.kt # Migration helper for old spreadsheet data
└── WineApiService.kt       # API client for external wine data


File Responsibilities
•
MainActivity.kt: Acts as the root ComponentActivity and primary Navigation Host for the application.
•
WineViewModel.kt: Centralizes UI state management and connects the UI components to the repository.
•
WineRepository.kt: Manages the abstraction of data sources (Room DB vs Network APIs), acting as the single source of truth for the app.
•
WineDatabase.kt & WineDaos.kt: Define the Room relational database structure and the queries to interact with it.
•
ScannerManager.kt: Handles camera and ML Kit integration for automated label reading.
•
DataImporter.kt: Orchestrates complex data restoration using a Two-Pass ID Mapping strategy to prevent Foreign Key violations.
•
MetadataScorer.kt: Handles business domain logic for calculating drinking windows and historical performance.
Summary of Concerns
The current architecture exhibits signs of "God Object" tendencies in WineViewModel.kt. Because it acts as the primary coordinator for a wide variety of features—including inventory, consumption tracking, scanning, and history—it is becoming bloated. Moving toward a more modular architecture (e.g., separate ViewModels for InventoryViewModel, ArchiveViewModel, and ScanViewModel) would be a prudent step for the upcoming refactor to improve maintainability and follow cleaner MVVM principles.