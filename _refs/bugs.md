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

### **[Bug-006]**: Incorrect pluralization for bottle count
* **Date Logged:** 2026-03-14
* **Component/File:** `ui/screens/CellarScreen.kt`
* **Severity:** Low
* **Description:** When a wine has exactly 1 bottle in the cellar, the UI displays "Qty: 1 bottles" instead of "Qty: 1 bottle".
* **Steps to Reproduce:**
    1. Navigate to the Cellar screen.
    2. View a wine card that has a quantity of 1.
    3. Observe the metadata text.
* **Known Workaround / Notes:** The `metadata` string in `CellarScreen.kt` is hardcoded as `"Qty: ${item.bottle.quantity} bottles"` (or similar) without pluralization logic.

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
