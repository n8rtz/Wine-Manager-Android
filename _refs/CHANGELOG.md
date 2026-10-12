## [Unreleased]
### Added
- **Event-based consumption history:** Added a `ConsumptionEvent` table and DAO/repository flows to record consumption date, vintage, quantity, price, source bottle, optional linked note, and an import key. New consumption writes the event and decrements inventory in one transaction; it does not create archived bottle rows that would conflict with the unique `(definitionId, vintage, status)` index.
- **Exact consumption undo:** Undo now addresses the specific event ID, restores that event's quantity to its source or matching cellar row, removes the event, and removes only the note linked to that event.
- **Consumption-date selection:** Added a date picker to the consume dialog. History-created notes inherit the event date when one exists; events and legacy rows without dates remain undated rather than receiving inferred historical dates.
- **Tasting-note editor:** Added standalone Add Note and add/edit screens with wine and vintage selection, a tasted-date picker, optional overall rating, written notes, rebuy recommendation, recommended-by and initial-rating metadata, and delete confirmation. The free-text notes field is placed after the structured fields.
- **Event-to-note linking:** History cards can open an existing linked note or create a note linked to that exact event. Saving the note and attaching it to the event is transactional; deleting a note clears its event reference without deleting consumption history.
- **Sensory evaluation:** Added persisted sweetness, aroma, acidity, tannin, body, balance, intensity, complexity, finish, quality, overall-score, and common-fault controls, with descriptor labels and ombre slider tracks.
- **Drinking windows:** Added optional start/end years to wine definitions, four-digit year validation in add/edit forms, display on wine details, cellar year-range and Ready Now / Too Early / Past Peak filters, and soonest/furthest sorting.
- **Legacy CSV import:** Added separate Profile actions for the legacy tasting-notes and consumption-history CSV formats. Imports support quoted fields, use explicit producer/name data or a single exact catalog match, report imported/already-present/skipped counts, and use stable unique import keys for repeat-import idempotency. Undated, incomplete, or ambiguous rows are skipped rather than assigned guessed dates or wine identities.
- **Schema migration 16→17:** Added drinking-window columns, tasting-note sensory/import metadata, and indexed `consumption_events`. Archived legacy bottles become events while retaining nullable consumption dates; a tasting note is linked only when exactly one note references that bottle. A zero legacy remaining-quantity value is represented as at least one consumed item.
- **App information screen:** Added a Settings/Profile destination showing installed app version and version code, the actual SQLite schema version, application ID, and the Git branch captured at build time (with safe fallbacks when branch metadata is unavailable).
- **Coverage and documentation:** Added tests for NV normalization and date-picker round-tripping across a daylight-saving boundary; updated the architecture and this changelog.

### Changed
- **JSON backup/restore:** Exports now include ratings and consumption events, use UTF-8, and read a consistent database snapshot. Restore replaces all app data only after explicit confirmation, validates references and duplicate IDs/keys before deletion, and applies the replacement in one transaction. Wine, bottle, note, and rating IDs are retained; consumption-event IDs are regenerated. Older backups without event/rating collections remain supported, and their archived rows are converted to events.
- **Vintage normalization and lookup:** Standardized blank, `null`, `NV`, and `(NV)` aliases for display, grouping, imports, rankings, and DAO lookups. Price and cellar queries prefer an exact vintage match before falling back to an NV alias.
- **History presentation:** The History screen now renders consumption events (including event quantity and optional rating/note indicator) instead of treating archived inventory rows as the history source. Colors follow the Material theme for dark mode.
- **Cellar filter reset:** Filter-sheet Clear All also clears search. Reselecting the Cellar tab resets filters, search, and expanded wine cards.
- **Date parsing:** Backup `Long` date fields accept epoch values plus strict slash dates with two- or four-digit years (including `1/1/2021`) and supported ISO forms. CSV date parsing requires full input consumption; incomplete or undated legacy rows are skipped rather than assigned guessed dates.

### Fixed
- **Windows KSP/Room build:** Use a newer SQLite JDBC native driver only on the Room processor classpath so query verification can load SQLite on Windows x86_64.
- **Safe import behavior:** Removed partial, row-by-row JSON merge behavior. Invalid backups now fail validation before local data is changed, and a transaction rolls back a failed replacement.
- **Historical-data integrity:** Migration and restore preserve nullable consumption dates; note-to-bottle vintage resolution remains tied to the referenced bottle rather than an unrelated wine/vintage heuristic.
- **Note display and metadata:** Unrated notes display as “Unrated” rather than “0 points”; note edits preserve existing IDs, bottle links, import keys, recommendation fields, and sensory data.
- **Date-picker timezone handling:** Added UTC-midnight/local-date conversion so the selected calendar day round-trips correctly across daylight-saving changes.
- **Permissions:** Declared the INTERNET and CAMERA permissions required by network image lookup and camera scanning.

and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.5] - 2026-03-16
### Added
- **Expandable FAB Menu:** Replaced the static central [+] button in the bottom navigation with a sleek, animated popup menu (Section 44 of WISHES.md).
  - **Dynamic Interactivity:** Tapping the [+] button now toggles a vertical slide-up menu centered over the button.
  - **Dimmed Background:** Implemented a semi-transparent overlay to provide visual depth and focus when the menu is active.
  - **Premium Animations:** Integrated `AnimatedVisibility` with slide and fade transitions for a for a high-end feel.
  - **Action Routing:** Linked the new menu items to "Add New Wine" and "Tasting Notes" for centralized data entry.
- **ViewModel Decomposition:** Refactored the monolithic `WineViewModel` into specialized, single-responsibility ViewModels:
  - `RankingsViewModel`: Handles rankings logic, price lookups, and specialized filtering/sorting.
  - `ScannerViewModel`: Manages CameraX, MLKit OCR, OpenFoodFacts API, and batch image processing jobs.
  - `WineViewModel`: Streamlined to focus exclusively on Cellar management, History flows, and Import/Export operations.
- **Repository Pattern Enforcement:** Enforced a strict data flow where all ViewModels must use `WineRepository`. Removed direct DAO and Database access from all ViewModels (`WineViewModel`, `RankingsViewModel`, `ScannerViewModel`, `TastingNoteViewModel`).
- **Unified Filter Alignment:** Aligned all cellar-style filtering to use `CellarFilterState` for architectural consistency.

### Fixed
- **Navigation Touch Interception [Bug-020]:** Resolved a bug where the FAB menu's full-screen container would stay active and intercept touches after the menu was closed or when navigating to "Add Wine".
- **Tab Backstack Persistence [Bug-021]:** Improved tab navigation to automatically pop transient sub-routes (like Add Wine or History) when switching away, ensuring tabs always reset to their root list on return.
- **Conflicting Imports:** Resolved ambiguous `AndroidViewModel` and `mutableStateOf` imports in `WineViewModel.kt`.
- **Unresolved Database References:** Fixed `db` reference errors in `WineViewModel.kt` by routing all database access through the Repository or using the database instance via the Repository's handle.
- **Repository Pattern Violation:** Corrected `TastingNoteViewModel` to use `repository.getAllPricesForDefinitions` instead of direct DAO access.

### Changed
- **Lean ViewModels:** All ViewModels are now lean wrappers around the Repository, holding only UI-specific state and logic.
- **Robust Tab Policy:** Updated `navigateToTab` to enforce a clean-slate policy for primary navigation tabs.

## [0.5.3] - 2026-03-15
### Added
- **Bottle-Level Note Linking:** `TastingNote` entity now includes `bottleId` for direct linking to the specific `WineBottle` consumed, enabling accurate vintage and price resolution.
- **Note Grouping:** Tasting Notes screen groups notes by (Wine + Vintage), showing one card per vintage with an aggregated rating range badge (e.g., "82-87").
- **Expandable Note History:** Expanding a tasting note card reveals a chronological list of all notes for that vintage, formatted as "M/D/YY - note text".

### Fixed
- **Card Expansion Responsiveness:** Fixed an issue in the Tasting Notes screen where wine cards required multiple presses to expand. Refactored `WineCard` to support external state control to prevent synchronization conflicts with the parent screen.
- **Vintage Corruption [Bug-016]:** Fixed a critical data integrity issue where horizontal tastings (same wine, multiple vintages consumed simultaneously) had all notes silently assigned the wrong vintage during JSON import. Root cause was a `vintageByOldDefId` fallback in `DataImporter.kt` that grabbed an arbitrary bottle's vintage.
- **Import ID Mapping [Bug-017]:** Added `bottleIdMapping` to the JSON importer so that `bottleId` references on tasting notes survive export/import cycles with new auto-generated IDs.
- **Database Migrations 12→16:** Introduced 4 migrations to add the `bottleId` column, attempt retroactive data repair (timestamps, chronological, text-match), and permanently fix existing corrupted vintage assignments.

### Changed
- **DataImporter Hardened:** Removed the dangerous `vintageByOldDefId` vintage fallback. Note vintage is now derived from the linked bottle in the backup data, never from a random lookup. `bottleId` is correctly remapped during import.

## [0.5.2] - 2026-03-15
### Added
- **Intelligent Location Filtering:** Geographic filter wheels (Country/Region/Subregion) now dynamically update to only show options that exist in the current view (Cellar vs. Rankings), preventing "empty filter" results.
- **Enhanced Wheel Interaction:** Added tap-to-scroll functionality to all selector wheels. Tapping the large areas above or below the center highlight now advances the selection by one step.
- **Improved Hit Targets:** Re-engineered the WheelPicker with container-level gesture detection, making the entire component responsive to taps.
- **Subregion Display:** Wine cards in the Cellar, Rankings, and Tasting Notes screens now include the `subregion` in the subtitle (e.g., "Pauillac, Bordeaux, France").
- **Optimized Filter UI:** Relocated the "Clear All" button to the top of the filter sheets across all screens for faster access and improved usability.

### Fixed
- **Stale Location Options:** Fixed a logic error where locations from archived/deleted wines would still appear in the active cellar filter.
- **Tap Precision:** Resolved an "unreliable tap" bug by exponentially increasing the touch target size for the selection dial.
- **Robust Location Mapping:** Implemented geographic normalization (Trim + Title Case) in `WineViewModel` to consolidate inconsistent casing and whitespace in the database, ensuring filters match correctly even if the underlying data is messy.

## [0.5.1] - 2026-03-15
### Fixed
- **Startup Hang resolved:** Moved database migrations and component initializations (ML Kit, Retrofit) off the main thread, resulting in a 90% faster app launch time.
- **Environment Stability:** Recovered emulator from "Disk Full" / Binder transaction failure states that were preventing package management.

## [0.5.0] - 2026-03-15
### Added
- **Advanced Cellar Filtering:** Implemented cascading location wheels (Country/Region/Subregion), a price range slider with manual text inputs, and multi-selection for wine types.
- **Rankings & Notes Filtering:** Implemented a premium filter system for the Rankings and Tasting Notes screens with Sort By, Price Range, and Rating Range controls.
- **User Rating Ranges:** Automatically aggregates all user tasting notes for a specific wine/vintage to display rating ranges (e.g., "88-92") on all wine card components.
- **"Added on" Tracking:** Implemented a system to track when a bottle was added to the cellar, including a database migration (v12) to backfill existing inventory.
- **Enhanced Cellar Details**: Expanded wine cards in the cellar now show the "Added on" date and a chronological list of your previous tasting notes.
- **Location Wheel Picker:** Created a custom, cascading "birthday-style" `WheelPicker` component for frictionless Country/Region/Subregion filtering.
- **Database Migration 11**: Implemented structural cleanup to consolidate producer names (e.g., merging "Kirkland" into "Kirkland Signature").
- **Robust Price Lookup:** Enhanced DAOs with vintage-agnostic fallback pricing to provide context even when specific vintage data is missing.

### Changed
- **Performance Overhaul:** Optimized the data pipeline for rating ranges and tasting notes by pre-calculating maps at the screen level, resolving `InputDispatcher` hangs and ensuring 60 FPS scrolling.
- **Cellar UI Refinement:** Moved the "Added on" date to the expanded card view to reduce clutter in the main list. Corrected bottle count pluralization logic.
- **History UX Overhaul:** Refined the consumption history sorting to always show the newest entries at the top based on `dateConsumed`.
- **Identity Formatting:** Standardized wine display across all screens to the high-end `[Producer] - [Wine Name] ([Vintage])` format.

### Fixed
- **InputDispatcher Crash:** Resolved a critical application crash caused by O(N*M) logic inside list items blocking the UI thread.
- **Persistent Zero Prices:** Resolved an issue where Rankings showed `$0.00` by prioritizing non-zero prices from any status and explicitly hiding zero values in the UI.
- **Data Linkage**: Fixed price and note link failures caused by duplicate wine definitions with slightly different producer names.
- **Build & Import Fixes**: Resolved various compilation issues and fixed an issue where the "Added on" date was not being saved during backup restoration.

## [0.4.1] - 2026-03-14
### Added

## [0.4.0] - 2026-03-14
### Added
- **JSON Backup System:** Migrated the backup/restore engine from a fragile multi-section CSV format to a structured JSON format using Gson.
- **Robust Backup Model:** Introduced a `BackupData` data class to centralize relational data (Definitions, Bottles, Notes) for bulletproof restoration.
- **Key Takeaway:** SQL migrations are a one-shot fix for existing data — they cannot repair data that is inserted *after* the migration runs (e.g., via a JSON import on a fresh install). When the root cause is in the import pipeline, the fix **must** live in the importer itself. Always cross-reference relational fields (like vintage, dates) between sibling tables within the same backup during import, rather than relying on post-hoc migrations.

## [Structural: Data Normalization & Naming] - 3/15/2026
* **Symptom:** Prices not showing up for specific wines in rankings even though they existed in the cellar under "Kirkland Signature".
* **Cause:** Duplicate `WineDefinition` entries caused by slight naming inconsistencies (e.g., "Kirkland" vs. "Kirkland Signature"). Tasting notes were linked to one ID, while bottles were linked to the other, creating a siloed data structure.
* **Fix:** Implemented `MIGRATION_10_11` to merge duplicate definitions and reassign all child records (bottles, notes, ratings) to a single standardized ID.
* **Key Takeaway:** Fuzzy matching (like `LIKE` queries) is a good temporary bandage for UI, but **structural data normalization** via migrations is the only permanent fix for relational integrity. Always look for "Duplicate Definition" patterns when data seems to be missing across screens.
- **Intelligent Date Parsing:** Implemented a custom Gson deserializer to automatically handle mixed date formats (e.g., "1/1/2021" strings vs. Unix timestamps) during restoration.
- **Cellar Filtering & Analytics:** Implemented a full filtering system (Type, Country) and sort logic (Newest/Price) for the cellar screen.
- **Card Expansion:** Added interactive expandable "Details" sections to wine cards, allowing users to view tasting notes and rating dates directly in the list.
### Changed
- **Unified UI Styling:** Refactored the "Tasting Notes" screen to use the shared `WineCard` component, ensuring perfect aesthetic consistency with the Cellar.
- **Export/Import Workflow:** Updated the Profile utility hub to handle `application/json` MIME types and `.json` file extensions.
- **Modular Refactoring:** Extracted 300+ lines from `MainActivity.kt` into dedicated screen and component files (`CellarScreen`, `RankingsScreen`, `WineCard`, etc.) following strict SOC principles.
- **Navigation UX:** Refined tab behavior and backstack logic for the Notes/History flow to ensure the app defaults back to the notes list when switching tabs.
- **Sorting Logic:** Defaulted Tasting Notes to chronological order (Newest first) with secondary tie-breaking logic.
- **Accordion UI:** Implemented single-expansion logic in lists to improve focus and reduce screen clutter.
### Fixed
- **Rankings Logic:** Fixed a filtering bug where wines with ratings between 1 and 5 were hidden; the Rankings screen now correctly displays all rated wines (> 0).
- **Import Mapping Fix:** Resolved a silent data loss bug by adding `@SerializedName` annotations to Room entities, ensuring JSON keys map correctly to database primary keys.
- **NPE Robustness:** Fixed a NullPointerException in the data importer caused by missing vintage fields in specific data records.
- **History Status:** Fixed [Bug-004] where archived wines showed "QTY: 0" by implementing a "CONSUMED" badge.
- **Filter Stability:** Fixed [Bug-001] by properly lifecycle-collecting filter states in the Cellar.

## [0.3.1] - 2026-03-14
### Fixed
- **Consumption History Screen:** Resolved a critical crash occurring during navigation due to missing ViewModel factory setup and date-parsing errors.
- **CSV Parsing:** Refactored `DataImporter` to use header-name discovery instead of hardcoded column indices, preventing the "Empty Cellar" bug.
- **Data Integrity:** Added strict logging to the import process to verify row counts against the SQLite database.

## [0.3.0] - 2026-03-14
### Added
- **Shared Repository:** Implemented a single source of truth by instantiating the database in `MainActivity`.
### Changed
- **Architectural Cleanup:** Refactored `WineViewModel` and `TastingNoteViewModel` to use constructor injection (Manual DI).
- **Database Logic:** Removed redundant `Room.databaseBuilder` calls from ViewModels to prevent "Split-Brain" database conflicts.
### Removed
- **Obsolete Functions:** Deleted `importFullBackup` from `TastingNoteViewModel` to prevent destructive data overwrites.

## [0.2.0] - 2026-03-10
### Added
- Initial implementation of the Wine Cellar UI.
- Basic SQLite schema for Wines, Bottles, and Tasting Notes.
- Support for CSV file selection via System Picker.

## [0.1.0] - 2026-03-01
### Added
- Project initialization with Jetpack Compose.
- Basic navigation scaffolding.

---
[0.5.5]: https://github.com/yourusername/winetracker/compare/v0.5.3...v0.5.5
[0.5.3]: https://github.com/yourusername/winetracker/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/yourusername/winetracker/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/yourusername/winetracker/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/yourusername/winetracker/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/yourusername/winetracker/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/yourusername/winetracker/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/yourusername/winetracker/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/yourusername/winetracker/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/yourusername/winetracker/compare/v0.1.0...v0.2.0
