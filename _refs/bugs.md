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

## Active Bugs

### **[Bug-004]**: Incorrect QTY in Consumption History
* **Date Logged:** 2026-03-14
* **Component/File:** `ui/screens/HistoryScreen.kt`
* **Severity:** Minor UI
* **Description:** The quantity displayed in the consumption history list is 0 for all bottles, which does not reflect actual consumption or remaining stock correctly.
* **Steps to Reproduce:**
    1. Open Consumption History screen.
    2. Observe the "QTY" badge on any wine card.
* **Known Workaround / Notes:** The `WineBottle` status is set to `ARCHIVED` when quantity reaches 0, but the UI should likely show the quantity consumed or a "Consumed" status instead of raw 0.

### **[Bug-001]**: Filters broken in Cellar screen
* **Date Logged:** 2026-03-14
* **Component/File:** `ui/screens/CellarScreen.kt`
* **Severity:** Minor UI
* **Description:** Filtering functionality in the cellar screen is not working as expected.
* **Steps to Reproduce:**
    1. Open Cellar screen.
    2. Apply any filter.
    3. Observe that the list does not update.
* **Known Workaround / Notes:** N/A

### **[Bug-002]**: Rankings screen card expansion needed
* **Date Logged:** 2026-03-14
* **Component/File:** `ui/screens/RankingsScreen.kt`
* **Severity:** Minor UI
* **Description:** Cards in the rankings screen do not expand to show full details (date of rating, cellar quantity, etc.).
* **Steps to Reproduce:**
    1. Open Rankings screen.
    2. Click on a wine card.
* **Known Workaround / Notes:** N/A

### **[Bug-003]**: Tasting Notes screen card expansion needed
* **Date Logged:** 2026-03-14
* **Component/File:** `ui/screens/TastingNotesScreen.kt`
* **Severity:** Minor UI
* **Description:** Cards in the tasting notes screen do not expand to show full note, score, and date.
* **Steps to Reproduce:**
    1. Open Tasting Notes screen.
    2. Click on a note card.
* **Known Workaround / Notes:** N/A

---

## Resolved / Fixed
*(AI Agent: Move fixed bugs here, add a "Date & time Fixed" line, and brief one sentence summary of fix)*
