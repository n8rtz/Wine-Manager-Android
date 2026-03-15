# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.1] - 2026-03-14
### Added
- **Bug Tracking:** Logged [Bug-005] regarding Cellar navigation state reset.
- **Bug Tracking:** Logged [Bug-006] regarding incorrect pluralization of bottle counts in the UI.

## [0.4.0] - 2026-03-14
### Added
- **JSON Backup System:** Migrated the backup/restore engine from a fragile multi-section CSV format to a structured JSON format using Gson.
- **Robust Backup Model:** Introduced a `BackupData` data class to centralize relational data (Definitions, Bottles, Notes) for bulletproof restoration.
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
[0.4.1]: https://github.com/yourusername/winetracker/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/yourusername/winetracker/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/yourusername/winetracker/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/yourusername/winetracker/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/yourusername/winetracker/compare/v0.1.0...v0.2.0
