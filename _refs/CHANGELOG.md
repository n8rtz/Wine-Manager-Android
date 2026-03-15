# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
[0.5.0]: https://github.com/yourusername/winetracker/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/yourusername/winetracker/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/yourusername/winetracker/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/yourusername/winetracker/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/yourusername/winetracker/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/yourusername/winetracker/compare/v0.1.0...v0.2.0
