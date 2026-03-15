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

### **[Bug-011]**: Persistent Zero Prices in Rankings
* **Date Logged:** 2026-03-15
* **Component/File:** `data/local/InventoryDao` / `ui/screens/RankingsScreen.kt`
* **Severity:** Minor UI
* **Description:** Wines show as `$0.00` in rankings even when price data exists in archived bottles or other vintages.
* **Steps to Reproduce:**
    1. Add a bottle with $0 price to cellar.
    2. Mark an older bottle of same wine ($20) as consumed.
    3. View Rankings.
* **Known Workaround / Notes:** Resolved in v0.5.1 by improving query priority and adding vintage fallback.

### **[Bug-010]**: Duplicate Definitions from Name Variations
* **Date Logged:** 2026-03-15
* **Component/File:** `data/local/WineDatabase.kt`
* **Severity:** Minor UI / Data Integrity
* **Description:** Slight variations in producer names (e.g. "Kirkland" vs "Kirkland Signature") create duplicate definitions, breaking the link between inventory prices and tasting notes.
* **Steps to Reproduce:**
    1. Import data with different maker names for the same wine.
    2. Observe two separate entries in the catalog.
* **Known Workaround / Notes:** Resolved via MIGRATION_10_11 consolidation.


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

## Resolved / Fixed

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
