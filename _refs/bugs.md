# Known Bugs & Backlog (BUGS.md)

## Rules for the AI Agent
1. **Append Only:** When instructed to log a bug, ADD it to the top of the "Active Bugs" list. Do NOT delete or modify existing bugs unless explicitly told to.
2. **Use the Template:** Always use the exact Markdown format provided in the "Bug Template" section.
3. **Be Concise:** Keep descriptions short and focus on the technical root cause if known.
4. **No Immediate Action:** Files logged here are for future reference. Do NOT attempt to fix them when logging them unless the user specifically asks you to "fix bug #[ID]".

---

## Bug Template
*(AI Agent: Copy this block when logging a new bug)*

### **[Bug-00X]**: Short Title
* **Date Logged:** YYYY-MM-DD
* **Component/File:** `ui/screens/ExampleScreen.kt`
* **Severity:** [Low | Minor UI | Edge Case]
* **Description:** Brief description of what is happening.
* **Steps to Reproduce:**
    1. Step one
    2. Step two
* **Known Workaround / Notes:** Any temporary fix or notes on why it happens.

---

---

## Active Bugs
 
 ### **[Bug-019]**: Double Vintage Parentheses for NV wines
 * **Date Logged:** 2026-03-15
 * **Component/File:** `ui/screens/TastingNotesScreen.kt`
 * **Severity:** Minor UI
 * **Description:** Wines without a vintage (Non-Vintage) should show as "(NV)", but are displaying as "((NV))" in the Tasting Notes screen. 
 * **Steps to Reproduce:**
     1. Open Tasting Notes screen.
     2. Locate a wine with "NV" as the vintage (e.g., Esperal - Vinho verde).
     3. Observe the vintage display in the card title.
 * **Known Workaround / Notes:** Redundant parentheses likely added in `TastingNotesScreen.kt` title construction.
 
 ---
 




### **[Bug-005]**: Cellar button fails to reset screen state
* **Date Logged:** 2026-03-14
* **Component/File:** `ui/screens/CellarScreen.kt`
* **Severity:** Minor UI
* **Description:** When on the Cellar screen with a wine card expanded/viewed, pressing the Cellar navigation button does not return the user to the main/reset state of the Cellar screen.
* **Steps to Reproduce:**
    1. Navigate to the Cellar screen.
    2. Expand a wine card to view details.
    3. Tap the "Cellar" button in the bottom navigation bar.
* **Known Workaround / Notes:** Internal screen state (like `expandedCardId`) is likely not being cleared on navigation re-selection.

---

### **[Bug-021]**: Persistent Sub-route on Tab Switch (Add Wine)
* **Date Logged:** 2026-03-16
* **Component/File:** `MainActivity.kt`
* **Severity:** Minor UI
* **Description:** Navigating to the "Add Wine" screen and then switching to another tab (e.g., Notes) would "save" the Add Wine screen on the Cellar tab stack. Returning to the Cellar tab would then show the Add Wine form instead of the Cellar list.
* **Steps to Reproduce:**
    1. Open FAB menu -> Add New Wine.
    2. Click "Notes" tab.
    3. Click "Cellar" tab.
* **Known Workaround / Notes:** Resolved by updating `navigateToTab` to pop `add_wine` and `history` sub-routes when switching *away* from their parent tabs, ensuring a clean state on return.

---

### **[Bug-020]**: FAB Submenu Touch Interception (Navigation Lock)
* **Date Logged:** 2026-03-16
* **Component/File:** `MainActivity.kt`
* **Severity:** Minor UI
* **Description:** After opening and closing the FAB submenu, or navigating to "Add Wine", the full-screen container for the menu remained active (though invisible), intercepting touches and preventing navigation back to Cellar or Notes.
* **Steps to Reproduce:**
    1. Tap FAB to open menu.
    2. Tap outside or navigate to "Add Wine".
    3. Try to tap "Cellar" in the bottom navigation bar.
* **Known Workaround / Notes:** Resolved by moving the `fillMaxSize()` container inside the `AnimatedVisibility` block so it is entirely removed from the view hierarchy when not visible.

---

## Resolved / Fixed

### **[Bug-018]**: Wine Card Expansion Multi-Press Issue
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 17:56
* **Root Cause:** State synchronization conflict between `WineCard.kt` internal `expanded` state and the external state managed by `TastingNotesScreen.kt`. The card required one press to update the internal state and a second to update the external state (or vice versa), leading to laggy/unreliable expansion.
* **Summary of fix:** Refactored `WineCard` to accept an optional `isExpanded` parameter for external control. When `isExpanded` is provided, the card bypasses its internal state logic. Updated `TastingNotesScreen` to pass its state directly to `WineCard` and provide the details content lambda unconditionally.

### **[Bug-016]**: Tasting Notes Screen Duplication & Vintage Corruption
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 17:40
* **Root Cause:** Two compounding failures:
    1. `TastingNote` had no `bottleId` field — notes couldn't link to specific bottles.
    2. The `DataImporter` had a `vintageByOldDefId` fallback (lines 168-170) that silently overwrote each note's vintage with a random bottle's vintage for that definition. During a horizontal tasting (2022/2023/2024 Malbec consumed simultaneously), all 3 notes got tagged "2022."
* **Failed Fix Attempts:**
    1. **MIGRATION_13_14** (timestamp matching): Tried to match `dateTasted` to `dateConsumed` within 2000ms. Failed because a previous migration (`MIGRATION_9_10`) had overwritten `dateConsumed` with identical values.
    2. **MIGRATION_14_15** (chronological ranking): Tried pairing bottles/notes by insertion order. Failed because all 3 notes from a horizontal tasting had identical timestamps and creation order didn't map to vintage order.
    3. **MIGRATION_15_16** (text matching): Manually matched exact note text to correct vintages. Worked for this specific case but was not a systemic fix.
* **Correct Fix:**
    1. Added `bottleId` to `TastingNote` entity + `@Relation` in `TastingNoteWithDefinition`.
    2. Updated `consumeBottle` to store `bottleId` and `bottle.vintage` on each note.
    3. Added `bottleIdMapping` to `DataImporter` — old bottle IDs are mapped to new IDs during import.
    4. Removed the dangerous `vintageByOldDefId` fallback — note vintage is now derived from the linked bottle's data in the backup.

### **[Bug-017]**: DataImporter Vintage Fallback Vulnerability
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 17:40
* **Root Cause:** `DataImporter.kt` lines 168-170 contained a fallback that overwrote any note's vintage with an arbitrary bottle's vintage from the same definition. This meant any export/import cycle could silently corrupt vintage assignments, especially for horizontal tastings where multiple vintages share one `definitionId`.
* **Summary of fix:** Removed the `vintageByOldDefId` fallback entirely. Added `bottleIdMapping` so the note's `bottleId` survives import. Vintage is now derived from the actual linked bottle in the backup data — never from a random lookup.

### **[Bug-015]**: Missing Subregions in Filters & Cards
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 15:30
* **Summary of fix:** Root cause was a corrupted data source (JSON/CSV) where subregions were missing or improperly mapped. Resolved code-side by implementing `normalizeLocation` (Trim/TitleCase) to handle messy data and updating all screen subtitles to explicitly include the `subregion` field.

### **[Bug-011]**: Persistent Zero Prices in Rankings
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 10:15
* **Summary of fix:** Prioritized non-zero prices in `InventoryDao` queries and added a vintage-agnostic fallback to ensure price data is pulled from the most relevant bottle available.

### **[Bug-010]**: Duplicate Definitions from Name Variations
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 05:20
* **Summary of fix:** Implemented `MIGRATION_10_11` to consolidate definitions and re-parent all child records (bottles/notes) to a unified ID based on standardized producer names.

### **[Bug-014]**: Startup Hang (Main Thread Blocked)
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 10:15
* **Summary of fix:** Moved Room database migrations to background thread via `lifecycleScope` and converted heavy ViewModel components (Retrofit, ML Kit OCR) to `by lazy` initialization.
 
### **[Bug-013]**: Missing "Added on" Dates
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 01:50
* **Summary of fix:** Updated `WineRepository` and `DataImporter` to populate `dateAdded`, and implemented `MIGRATION_11_12` to backfill missing dates for existing cellar entries.

### **[Bug-012]**: Application Crash (InputDispatcher) on Large Lists
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 01:58
* **Summary of fix:** Comprehensive performance overhaul moving all O(N) computations (filtering, sorting, aggregation) into `remember` blocks across Cellar, History, Rankings, and Tasting Notes screens.

### **[Bug-006]**: Incorrect pluralization for bottle count
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-15 01:46
* **Summary of fix:** Implemented pluralization logic in `CellarScreen.kt` to correctly display "bottle" vs "bottles" based on the count.

### **[Bug-001]**: Filters broken in Cellar screen
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14
* **Summary of fix:** Implemented `CellarFilterState`, updated `WineViewModel` to manage filter state, and added filtering logic/UI to the now extracted `CellarScreen.kt`.

### **[Bug-002]**: Rankings screen card expansion needed
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14
* **Summary of fix:** Extracted `WineCard` component with expansion support and implemented expansion details in `RankingsScreen.kt`.

### **[Bug-003]**: Tasting Notes screen card expansion needed
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14
* **Summary of fix:** Implemented expanded state in `CompactNoteItem` to show full notes, rating, and tasting date.

### **[Bug-004]**: Incorrect QTY in Consumption History
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14
* **Summary of fix:** Updated `HistoryWineCard` to display a "CONSUMED" badge instead of a raw "QTY: 0" for archived items.
### **[Bug-007]**: Empty Rankings screen
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14 22:30
* **Summary of fix:** Fixed logic error in `RankingsScreen.kt` where wines with ratings between 1 and 5 were hidden; filter threshold changed from `> 5` to `> 0`.

### **[Bug-008]**: Inconsistent Tasting Notes styling
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14 22:35
* **Summary of fix:** Refactored `TastingNotesScreen.kt` to use the shared `WineCard` component for 1:1 visual parity with the Cellar screen.

### **[Bug-009]**: Fragile CSV Import (The "Shifted Columns" Bug)
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14 22:15
* **Summary of fix:** Migrated the entire backup/restore system to JSON format using Gson to eliminate data misalignment caused by missing or optional columns in legacy CSV backups.

### Aroma launcher wrapping and score endpoint alignment
* **Date logged/fixed:** 2026-10-15
* **Fix:** Replaced the launcher’s scrolling chip strip with a wrapping FlowRow, with two-dimensional long-press reordering. Score track, markers, labels, and explicit thumb now share the same inset; font-scaled label space prevents the 100 label from clipping.
* **Verification:** Device check pending: reorder across rows; select 50 and 100; check large-font labels.
