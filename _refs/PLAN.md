# Wine Tracker - Refactor Plan (Native Architecture)

This plan outlines the steps to transition from the initial flat database structure to the relational, feature-rich architecture defined in `AGENTS.md`.

## Phase 1: Foundation & Data Migration [COMPLETED v0.1]
*   **Status**: Standard Import/Export with Two-Pass ID Mapping is verified and functional.
*   [x] **Modify `WineDatabase.kt`**:
    *   Rename/Refactor existing `Wine` to `WineDefinition` (The "Catalog" - Maker, Name, Type, Country, Region).
    *   Create `WineBottle` entity (The "Inventory" - Links to `WineDefinition`, contains Vintage, Quantity, Price, Location, Status).
    *   Add `BottleStatus` enum (`IN_CELLAR`, `ARCHIVED`).
    *   Update `TastingNote` to include a `rebuy` boolean and link it to the history.
*   [x] **Update `WineDaos.kt`**:
    *   Implement `Upsert` logic: If (Maker + Name + Vintage) exists, increment Quantity; otherwise, create new.
    *   Add complex queries for the Dashboard (Active Inventory) and Archive (Leaderboard).

## Phase 2: Advanced Cellar Management (ACTIVE)
*   [ ] **Refactor `WineViewModel.kt`**:
    *   Expose separate Flows for `activeInventory` and `tastingHistory`.
    *   Implement "Drinking Window" calculation logic (e.g., `vintage + 5` base offset).
*   [ ] **Dependencies**: Add CameraX and Google ML Kit (Text Recognition) to `build.gradle.kts`.
*   [ ] **Smart Scanning**: Create `ScannerManager.kt` to handle label OCR and pre-fill the "Add" form.
*   [ ] **Dashboard (Active)**:
    *   Implement Material 3 Filter Chips (Type, Country, Vintage).
    *   Add "Swipe-to-Consume" action on wine cards.

## Phase 3: Archive & Analytics
*   [ ] **Archive (History)**:
    *   Create the Leaderboard view sorted by Rating.
    *   Implement the Bottom-Sheet filter for advanced history searching.

## Phase 4: Cleanup
*   [ ] **Cleanup**: Remove unused "flat" entities and old UI code.
