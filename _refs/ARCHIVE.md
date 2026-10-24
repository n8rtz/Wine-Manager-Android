# ARCHIVE.md — Completed Work

Everything here is **finished**. Open work lives in [`BACKLOG.md`](BACKLOG.md); release-level summaries live in [`CHANGELOG.md`](CHANGELOG.md).

This file was created on 2026-10-15 by merging `wishes.md`, `wishes-v2.md`, `bugs.md` and `PLAN.md` (those files were removed; their full text is preserved here or in `BACKLOG.md`, and in git history).

> **Design reference:** the UI/UX specs under Part A (especially W-001, W-002, W-003, W-007, W-008, W-024) remain the project's styling rules even though they are implemented. Where an entry is marked *Superseded*, follow the note, not the original text.

## Contents
- [Part A — Completed wishes](#part-a--completed-wishes)
- [Part B — Resolved bugs](#part-b--resolved-bugs)
- [Part C — Completed plan items](#part-c--completed-plan-items)
- [Part D — Declined ideas](#part-d--declined-ideas)

## ID scheme
- Wishes keep their original numbers as `W-nnn`. The original lists skipped 17, 18 and 31 (never written down). `wishes-v2.md` reused 35–38 for different items than `wishes.md`, so those four were renumbered **W-046 – W-049**; its 32–34 were exact duplicates of `wishes.md` 32–34 and are merged.
- Bugs keep `Bug-nnn`. Five unnumbered 2026-10-15 entries were given **Bug-022 – Bug-026**.
- “Verified” below means checked by reading the source and `CHANGELOG.md`; **no build, test run or device check was performed** unless stated.

---

## Part A — Completed wishes

### W-001 — Global Theme & Color Palette
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

Completely remove the default Material 3 purple/lavender color scheme. Override `Color.kt` and `Theme.kt` with the following premium palette:
* **Primary Accent (The "Wine" Color):** Deep Bordeaux Red (Hex: `#722F37` or `#58181F`). Use for primary buttons, active bottom nav icons, and floating action buttons.
* **Background:** Off-White/Light Gray (Hex: `#F8F9FA`) for Light Mode. Deep Charcoal (Hex: `#121212`) for Dark Mode. This ensures the white cards pop.
* **Surface (Cards/Sheets):** Pure White (`#FFFFFF`) in Light Mode, Dark Gray (`#1E1E1E`) in Dark Mode.
* **Typography Colors:** * Primary Text (Headers/Titles): Dark Slate (`#1C1C1E`).
    * Secondary Text (Subtitles/Metadata): Medium Gray (`#8E8E93`).

</details>

### W-002 — Global Geometry & Typography Specs
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Corner Radius:** Do not use full pill-shapes. Use modern, slight rounding. Cards and Buttons must use `RoundedCornerShape(8.dp)`.
* **Elevations:** Cards must have a very subtle drop shadow (e.g., `elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)`). No harsh black shadows.
* **Padding/Margins:** * Screen edge margins: `16.dp`.
    * Vertical spacing between list items: `8.dp` or `12.dp`.
    * Internal card padding: `16.dp`.

</details>

### W-003 — The "Wine Card" Component (Strict Layout)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

Every single wine card (in Cellar, History, and Rankings) MUST follow this exact UI layout and text formatting. Do not deviate.
* **Layout:** A Compose `Card` containing a `Row`.
* **Left Side (Visual):** A placeholder shape (or future wine label image) with a subtle background (`#E5E5EA`), size `60.dp` by `80.dp`, rounded corners `4.dp`.
* **Right Side (Data Column):** A `Column` taking up the remaining weight, containing:
    * **Line 1 (Title):** `[Producer] - [Wine Name] ([Vintage])`
        * *Font:* `FontWeight.SemiBold`, `16.sp`, Primary Text Color. Max 2 lines, then truncate with ellipsis.
    * **Line 2 (Subtitle):** `[Subregion], [Region]`
        * *Font:* `FontWeight.Normal`, `14.sp`, Secondary Text Color. Max 1 line.
    * **Line 3 (Metadata Row):** A row at the bottom of the data column containing small, subtle text or tags for `Type` (e.g., Red) and `Quantity` (if in Cellar) or `Rating` (if in History/Rankings).
* **Action Button:** The "Drink" button (in the Cellar view) should be a sleek, outlined button or a simple icon button (`Icons.Default.LocalDrink`) aligned to the bottom right of the card, using the Primary Accent color.

</details>

### W-004 — App Architecture: Bottom Navigation
**Status:** Implemented. Marked ✅ in the original `wishes.md`.
**Notes:** Superseded in part: the bottom bar is now Cellar · Notes · History · Rankings · Profile, and Add is a floating action button (see W-010).

<details><summary>Original specification</summary>

The `BottomNavigationBar` must have 4 distinct tabs. Do not combine History and Rankings.
1.  **Cellar:** Active Inventory. (Icon: `WineBar` or `List`)
2.  **History:** Chronological list of consumed wines, sorted by date consumed (Newest first). (Icon: `History` or `Schedule`)
3.  **Rankings:** The Leaderboard. Sorted STRICTLY by 100-point scale rating (Highest to Lowest). (Icon: `Star` or `EmojiEvents`)
4.  **Add:** The camera scanner/manual entry screen. (Icon: `AddCircle` or `CameraAlt`)

</details>

### W-005 — Modern E-Commerce Filtering (The Cellar Screen)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Remove Inline Chips:** Completely delete the horizontal scrolling filter chips from the top of the screen.
* **Filter Trigger:** Add a top app bar (or a row below the search bar) with a "Filter & Sort" button featuring the `Icons.Default.Tune` icon.
* **Modal Bottom Sheet:** Tapping the trigger opens a `ModalBottomSheet`.
* **Sheet UI:** * Header: "Filter Cellar" with an "X" to close.
    * Body: Expandable accordion sections (e.g., "Wine Type", "Country", "Region", "Vintage").
    * Interaction: Inside each section, use multi-select checkboxes (e.g., I can check "Red" and "Sparkling" at the same time).
    * Footer: A sticky row at the bottom with a "Clear All" text button and a solid "Apply Filters" primary button.

</details>

### W-006 — Polish & Edge Cases
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Empty States:** If a list is empty (e.g., the Cellar has no wines, or a filter returns 0 results), display a beautifully formatted Empty State. This should include a large, muted icon (e.g., a wine glass), a title ("Your Cellar is Empty"), and a subtitle ("Scan a label or add a wine manually to get started").

</details>

### W-007 — Filter Sheet Overhaul (Premium UI)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **No Checkboxes:** Completely remove standard checkboxes from the filter sheet.
* **Flow Layout:** Use a `FlowRow` (or similar wrapping layout) so filter options sit side-by-side, utilizing the full width of the screen instead of a single-file vertical list.
* **Toggle Chips:** Filter options must be sleek, pill-shaped toggle buttons (like ChoiceChips). When selected, they should fill with the Primary Accent color (Bordeaux Red) and white text. When unselected, they should be a subtle gray outline.

</details>

### W-008 — Wine Card Redesign (Color Coding & Strict Typography)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Remove Quick Action:** Remove the 'Drink/Trash' icon button from the main card UI. The card should be a clean presentation of data. Tapping the entire card must navigate to a new `WineDetailScreen`.
* **Color-Coded Backgrounds:** The background of the card (or the 60x80 placeholder image box) must be color-coded based on the Wine Type to provide instant visual identification:
    * **Red Wines:** Very subtle pale red/blush tint (e.g., Hex `#FFF5F5`).
    * **White Wines:** Very subtle pale yellow/straw tint (e.g., Hex `#FFFFF0`).
    * **Sparkling/Other:** Clean off-white/gray (e.g., `#F8F9FA`).
* **Typography Refinement:** * Line 1 (Bold): `[Brand/Producer] - [Wine Name] ([Vintage])`
    * Line 2 (Italicized, Secondary Color): `[Appellation/Subregion], [Region], [Country]`

</details>

### W-009 — The "Wine Detail" Screen & Consumption Flow
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Navigation:** Tapping any wine card opens a dedicated Detail Screen showing all data.
* **Consume Action:** Place a prominent, primary-styled "Mark as Consumed" button on this detail screen.
* **Date Entry:** Tapping "Mark as Consumed" must open a dialog or sub-menu allowing the user to confirm the Consumption Date (defaulting to today's date) before finalizing the database move from Cellar to History.

</details>

### W-010 — Strict Bottom Navigation Architecture (Inline Add Button)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.
**Notes:** Superseded: the inline [+] was later replaced by a bottom-end floating action button (`Scaffold.floatingActionButton`, `FabPosition.End`) to make room for the History tab (CHANGELOG [Unreleased] → *History navigation*). The “no FAB” rule is no longer in effect.

<details><summary>Original specification</summary>

* **No Floating FAB:** The `Scaffold` MUST NOT use the `floatingActionButton` property. Do not use `FabPosition`.
* **The Layout:** Inside the `bottomBar` slot of the `Scaffold`, use a standard `NavigationBar` or a custom `Row` that spans the full width.
* **Inline Placement:** The large, primary-colored [+] Add Button must be placed *directly inside* this bottom navigation row, vertically centered, exactly between the 'Tasting Notes' icon and the 'Rankings' icon.
* **Alignment:** The center of the [+] button must share the exact same horizontal axis as the rest of the navigation icons so they form a single, perfectly straight row.

</details>

### W-011 — The "Undo" Consumption Safety Net (Snackbar)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Destructive Action Safety:** Whenever a wine is marked as consumed, the app must immediately display a `Snackbar` at the bottom of the screen.
* **Snackbar UI:** The text should briefly confirm the action (e.g., "Consumed 1 bottle of St. Emilion"). It MUST include a prominent "UNDO" action button using the primary Bordeaux Red color.
* **Undo Logic:** Tapping "UNDO" before the Snackbar disappears must instantly revert the database transaction, restoring the bottle's quantity in the Cellar and removing the accidental History entry.

</details>

### W-012 — Terminology Enforcement
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Strict Naming:** The 100-point sorted screen must be called **Rankings**. Completely remove the word "Leaderboard" from the UI, the codebase, and all comments.

</details>

### W-013 — History Tab Overhaul (Compact Log Book)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.
**Notes:** History now renders `ConsumptionEvent` rows rather than archived bottles (CHANGELOG [Unreleased] → *History presentation*).

<details><summary>Original specification</summary>

* **Purpose:** The History screen is a chronological log of consumed bottles, sorted by Date Consumed (Newest first). It is NOT a flashy showcase.
* **Compact UI:** Do not use the large `WineCard` layout here. Use a sleek, compact `ListItem` layout.
* **Displayed Data:** * Left side: A small, clean date icon or just the formatted Date Consumed (e.g., "Oct 24, 2023").
    * Body: `[Producer] - [Wine Name] ([Vintage])` in a standard font size.
    * Right side: A subtle icon (like a notebook or pen) indicating if a Tasting Note exists.
* **Interaction:** Tapping an item in the History list opens the Tasting Note Detail/Edit screen.

</details>

### W-014 — The "Tasting Notes" Repository Screen
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Purpose:** This screen is a dedicated, fast-access repository for all past tasting notes, acting just like a master spreadsheet tab. It replaces the need to dig through the chronological History log to enter a note.
* **Top Search Bar:** The very top of the screen must feature a prominent, premium Search Bar (e.g., a styled `OutlinedTextField` with a search icon) allowing instant text-search by Producer, Name, or Vintage.
* **Easily Accessible Filters:** Next to or immediately below the search bar, include a "Filter" icon button. Tapping this must open the exact same premium **Modal Bottom Sheet Filter** (with the `FlowRow` toggle chips, no checkboxes) that we designed for the Cellar in Section 7. Include a quick toggle in these filters for "Needs Notes" vs "Completed Notes."
* **Repository List:** Below the search/filter area, display a clean, compact list of all consumed wines.
* **Interaction:** Tapping any wine in this list instantly opens the Note Editor overlay/screen.

</details>

### W-015 — The Note Editor UI
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Clean Interface:** When a wine is selected from the repository, present a distraction-free screen or large bottom sheet.
* **Elements:** * The fixed 2-line Wine Header (`Producer - Name (Vintage)` / `Appellation, Region`).
    * A large, clean text area for the written tasting notes.
    * A sleek numerical input or slider strictly for the 100-point score.
    * A prominent "Save Note" button in the primary Bordeaux Red color.

</details>

### W-016 — The "Add Wine" Form (Cascading Dropdowns)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **No Manual Location Entry:** "Country", "Region", and "Appellation/Subregion" MUST NOT be standard text input fields. They must be modern Compose `ExposedDropdownMenuBox` components.
* **Cascading State Logic:** * The user must select a "Country" first.
    * The "Region" dropdown must only populate with regions belonging to the selected Country.
    * The "Appellation" dropdown must only populate with appellations belonging to the selected Region.
* **Required Fields List:** The form must include: Maker/Winery, Wine Name, Vintage, Type (Red/White/Sparkling/Rose/Dessert), Country, Region, Appellation, Quantity, and Purchase Price.

</details>

### W-019 — The Cellar Dashboard Header (Analytics)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Top-Level Summary:** The top of the Cellar screen (below the app bar, above the list) must feature a premium Dashboard Header.
* **Metrics:** This header should display quick analytics: 'Total Bottles', 'Unique Wines', and 'Estimated Value' (sum of quantity * purchase price). Format this elegantly, perhaps as a horizontal scrolling row of small, premium summary cards or a clean 3-column grid.

</details>

### W-020 — Main Cellar Search
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Search Bar:** Directly below the Analytics header, include an `OutlinedTextField` acting as a Search Bar (Icon: `Search`). This should instantly filter the displayed cellar list by Producer or Wine Name as the user types.

</details>

### W-022 — No Dead Ends (Strict Navigation Architecture)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Universal Top App Bars:** EVERY screen that is not a main bottom-navigation tab (e.g., Detail Screens, Edit Screens, Settings) MUST be wrapped in a `Scaffold` that includes a `TopAppBar`.
* **Universal Back Button:** This `TopAppBar` MUST include a `navigationIcon` (an `Icons.AutoMirrored.Filled.ArrowBack`) that calls `navController.popBackStack()`.
* **Error States:** Even if data fails to load (e.g., "Wine not found"), the screen must still display the TopAppBar so the user can navigate backwards. Never trap the user on a blank screen.

</details>

### W-023 — Save Actions & Back Navigation (Form Handling)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Immediate Back Navigation:** Whenever a user submits a form (e.g., tapping the "Save" checkmark on the Edit Screen, or adding a new wine), the app MUST immediately execute `navController.popBackStack()` upon successful database submission.
* **Coroutine Safety:** Database insertions/updates must be handled in the ViewModel using `viewModelScope.launch`. The UI should observe a success event (like a `SharedFlow` or a boolean state) to trigger the `popBackStack()` navigation safely, ensuring the user is returned to the previous screen rather than getting stuck on a reloading/empty form.

</details>

### W-024 — Bottom Navigation State Colors (Active vs. Inactive)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Visual Hierarchy:** The Bottom Navigation bar must clearly indicate which tab is currently active using strict color coding.
* **Colors:** * **Active State:** Both the Icon and the Label text MUST use the primary Deep Bordeaux Red (`#722F37`).
  * **Inactive State:** Both the Icon and the Label text MUST use the Medium Gray (`#8E8E93`).
* **Clean UI (No Pill):** Override the default Material 3 active indicator. Set the `indicatorColor` to `Color.Transparent` so there is no ugly pill shape behind the active icon. The background of the navigation bar must remain completely solid and clean.

</details>

### W-025 — Advanced E-Commerce Filter & Sort Sheet
**Status:** Implemented. Marked ✅ in the original `wishes.md`.
**Notes:** Core sheet implemented (sticky footer, price/rating sliders with text boxes, keyboard insets, Sort By). **Residual gaps carried to BACKLOG as W-025R** — Cellar sheet has no Vintage range or Maker/Producer control, and there is no Country A–Z sort.

<details><summary>Original specification</summary>

* **Non-Collapsible Layout:** Completely remove the `AccordionSection` logic. All filter categories must be permanently expanded and stacked vertically inside a `verticalScroll` modifier.
* **Sticky Footer:** The "Apply Filters" and "Clear All" buttons MUST remain sticky at the bottom of the sheet, never scrolling out of view.
* **Sort By Section:** At the very top of the sheet, include a "Sort By" section using selectable chips or a dropdown for: `Newest`, `Oldest`, `Price (Low to High)`, `Price (High to Low)`, `Country (A-Z)`, `Country (Z-A)`.
* **Dual-Input Range Sliders (Price, Vintage, Rating):** For numerical filters, the UI must include BOTH a visual `RangeSlider` AND two side-by-side `OutlinedTextField` boxes (Min and Max) below it. Updating the text fields must update the slider, and vice versa.
  * *Price Range:* e.g., $0 to $1000+
  * *Vintage Range:* e.g., 1900 to Current Year
  * *Rating Range:* 0 to 100
* **Maker/Producer Filter:** Include a section for "Maker/Producer". Because the list could be long, use a `FlowRow` of toggle chips for the most common makers, or a nested searchable autocomplete field inside the sheet.
* **Keyboard Inset Safety:** Because we are adding text fields inside a bottom sheet, the sheet MUST handle `WindowInsets.ime` padding so the keyboard doesn't cover the Min/Max inputs when typing.

</details>

### W-026 — Automatic Stock Image Fetching
**Status:** Superseded (never worked as automatic fetching). Marked ✅ in the original `wishes.md`, but that mark was wrong.
**Notes:** The add-time `WineImageFetcher` was a stub with placeholder API keys that always returned nothing, so no image was ever fetched automatically. Coil display with the colour-coded fallback is real and stays. The user-facing image workflow is the reviewed Open Food Facts importer (W-046). The stub, the legacy DuckDuckGo search and the batch image processor were deleted on 2026-10-15.

<details><summary>Original specification</summary>

* **API Integration:** The app should automatically attempt to fetch a stock image of the wine bottle when a new wine is added.
* **Search Query:** The app should generate a search query string combining: `[Producer] + [Wine Name] + [Vintage] + "wine bottle"`.
* **Image Loading:** The app MUST use an asynchronous image loading library (like **Coil**) to load the fetched image URL into the `60.dp x 80.dp` placeholder box on the `WineCard` and Detail screens.
* **Graceful Fallback:** If the API fails, returns no results, or the device is offline, the UI MUST gracefully fall back to the subtle color-coded backgrounds defined in Section 8.
* **Data Layer:** The `WineDefinition` (or `WineBottle`) database entity must be updated to include a `String? imageUrl` field to cache the fetched URL so the app doesn't re-query the API every time the list scrolls.

</details>

### W-027 — Universal Data Portability (Backup & Restore)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.
**Notes:** Implemented as a **JSON** backup/restore with validated, transactional replace (CHANGELOG [Unreleased] → *JSON backup/restore*). **CSV/ZIP export and bundling label images were not implemented and have been declined (Part D, W-027R).**

<details><summary>Original specification</summary>

* **Standardized Exports:** The app must support exporting the entire database (Definitions, Bottles, History, and Tasting Notes) into a standardized **CSV** format for spreadsheet use, or a **ZIP** archive for full backups.
* **Smart Import Logic:** The import pipeline must be "update-aware." It should use `OnConflictStrategy.IGNORE` to avoid duplicates and rely on the `@Update` logic defined in Section 13 to merge records without triggering `CASCADE DELETE`.
* **Media Portability:** If a ZIP export is used, it should include the locally saved label images defined in Section 21.

</details>

### W-028 — Profile & System Settings (The "Utility Hub")
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Location:** Accessible via a "Profile" or "Settings" icon in the Top App Bar of the main screens.
* **Layout:** A clean, vertical list of options using `HorizontalDivider` between items.
* **Data Management Section:** * **Import Data:** A prominent list item that launches the system file picker (`ActivityResultContracts.GetContent`) to select a CSV file.
  * **Export Data:** A list item that generates the standardized CSV/ZIP and launches the system "Share" sheet or "Save to Files" picker.
* **UI Feedback:** Both actions must show a "Processing..." Modal Bottom Sheet (Bordeaux Red accents) with a success/failure Snackbar upon completion.

</details>

### W-029 — Tasting History (Archive)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.
**Notes:** Entry point changed: History is a bottom-nav tab and lists consumption events.

<details><summary>Original specification</summary>

* **Access:** Triggered via the "History" icon in the Top App Bar of the Notes/Cellar screen.
* **Data Source:** Display all `WineBottle` records where `status == BottleStatus.ARCHIVED`.
* **UI Layout:** * A vertical list of cards (`RoundedCornerShape(8.dp)`).
  * **Primary Text:** Maker and Wine Name.
  * **Secondary Text:** Vintage and Date Consumed (format: MMM dd, yyyy).
  * **Exclusion:** Do NOT show tasting notes in this summary list view.
* **Empty State:** If no history exists, show a "No wines tasted yet" message with a Bordeaux Red accent.

</details>

### W-030 — Corrected Import Logic (The Three-Way Split)
**Status:** Implemented. Marked ✅ in the original `wishes.md`.
**Notes:** No in-app importer for `Log (In)` / `Cellar` CSVs remains in the source; master data is loaded through JSON backup/restore (CHANGELOG 0.4.0). Only the legacy Tasting Notes and Log (Out) importers (W-033, W-034) remain.

<details><summary>Original specification</summary>

* **Master Catalog:** Use `Wines - Log (In).csv` to populate the `WineDefinition` table (Maker, Name, Type, Country, Region).
* **Active Cellar:** Use `Wines - Cellar.csv` to create `WineBottle` records with `status = BottleStatus.IN_CELLAR`.
  * *Note:* If a wine is in the Cellar but NOT in Log (In), create a new Definition for it.
* **Tasting History:** Use `Wines - Log (Out).csv` to create `WineBottle` records with `status = BottleStatus.ARCHIVED`.
  * *Data Point:* The "Date" column in Log (Out) should be mapped to the `lastUpdated` or `dateConsumed` field.
* **Conflict Resolution:** If a wine appears in both Log (In) and Log (Out), the app should show one Definition in the catalog, one Archived bottle in History, and (if applicable) an Active bottle in the Cellar.

</details>

### W-032 — Show All Attributes on Cellar Edit Screen
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** `WineDetailScreen` edit mode renders Producer, Name, Vintage, Type, Quantity, Price, Drinking Window, Country, Region and Appellation even when empty.

<details><summary>Original specification</summary>

* **Problem:** Currently, when editing a wine from the Cellar, the UI only renders data entry boxes for fields that already contain data. Missing fields are completely hidden.
* **Goal:** Allow the user to go in at a later date and add missing information to incomplete wine profiles.
* **Implementation:** Modify the Edit Wine UI to display input fields for *all* attributes, exactly mirroring the layout of the "Add Wine" menu. Even if an attribute is currently null/empty, its entry box must be visible and editable.

</details>

### W-033 — Import Legacy Tasting Notes (CSV)
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** Profile → *Import Legacy Tasting Notes (CSV)* (`CsvImportHelper.importTastingNotes`). Rows lacking a date or exact wine match are skipped rather than guessed.

<details><summary>Original specification</summary>

* **Goal:** Import the historical notes from the original spreadsheet.
* **Source File:** Must specifically target the export file named `Wines - Tasting Notes.csv`.
* **UI/Trigger:** Add a dedicated "Import Tasting Notes" button inside the **Profile menu** to initiate this specific dataset import.
* **Implementation:** The importer must read this CSV and map the text to the corresponding `TastingNote` entity, linking it accurately to the existing wines in the Room database.

</details>

### W-034 — Import Legacy Consumption History (CSV)
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** Profile → *Import Legacy History Log (CSV)* (`CsvImportHelper.importConsumptionHistory`). History is a bottom-nav tab (not a Tasting Notes top-right button) and shows consumption events.

<details><summary>Original specification</summary>

* **Goal:** Import the list of consumed bottles so historical drinking data isn't lost.
* **Source File:** Must specifically target the export file named `Wines - Log (out).csv`.
* **UI/Trigger:** Add a dedicated "Import History Log" button inside the **Profile menu** to initiate this process.
* **Navigation/Location:** The imported data belongs on the **History screen**. This screen must be accessed via a button located in the **top right corner of the Tasting Notes screen**.
* **Data Displayed:** The History UI must be formatted to show the **Date Consumed**, the **Wine Info**, and the **Quantity (qty)** based on the imported CSV rows.

</details>

### W-036 — Multi-Wheel Location Filter
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Goal:** Simplify complex location filtering with a intuitive, cascading UI.
* **UI/Trigger:** A "triple scroll wheel" picker (Birthdate style). Left: Country, Middle: Region, Right: Appellation.
* **Logic:** The Region and Appellation wheels must filter dynamically; they should only populate with relevant options once a parent location is selected.
* **Navigation/Location:** Accessed within the **Filter Bottom Sheet** on the Cellar screen.

</details>

### W-037 — Cellar Filter Reset
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Goal:** Allow users to clear all active filters in a single tap.
* **UI/Trigger:** A "Reset" button/icon that clears search text, location wheels, and sorting.
* **Navigation/Location:** Located at the top of the **Filter Bottom Sheet** or next to the search bar on the **Cellar screen**.

</details>

### W-038 — Drinking Window Field
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** Start/end years on `WineDefinition`, validated in add/edit forms and shown on Wine Detail.

<details><summary>Original specification</summary>

* **Goal:** Track when a wine is at its peak quality.
* **UI/Trigger:** An editable date-range field (e.g., 2024–2030).
* **Navigation/Location:** This field should be hidden on the main cellar list but visible and editable on the **Wine Detail screen**.

</details>

### W-039 — Inline Consumption History
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Goal:** View all previous interactions with a specific wine without leaving the detail page.
* **UI/Trigger:** A chronological list showing past dates, scores, and tasting notes.
* **Navigation/Location:** Positioned at the bottom of the **Wine Detail screen**.

</details>

### W-040 — Drinking Window Filter & Sort
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** Ready Now / Too Early / Past Peak filter plus *Drinking Window (Soonest)* / *(Furthest)* sorts in the Cellar sheet.

<details><summary>Original specification</summary>

* **Goal:** Prioritize drinking wines that are nearing the end of their window.
* **UI/Trigger:** Add "Peak Window" as a filter category and "Drinking Window (Oldest/Newest)" as a sort option.
* **Navigation/Location:** Found within the **Filter Bottom Sheet of the Cellar screen**.

</details>

### W-041 — Rating Ranges on Cards
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Goal:** Show the user's personal rating history at a glance.
* **UI/Trigger:** Display the score on the wine card. If multiple ratings exist for that wine, display them as a range (e.g., "88-90").
* **Navigation/Location:** Visible on the **Cellar screen** list items.

</details>

### W-042 — Estimated Drinking Window Integration
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** `drinkingWindowStart` / `drinkingWindowEnd` columns (schema 16→17), *Drinking Window Years* `RangeSlider` with Start/End text boxes, and the two sorts. Values are user-entered; automatic estimation is tracked as W-050.

<details><summary>Original specification</summary>

* **Goal:** Add estimated drinking window to database for each wine. Also add in the cellar screen a sort by drinking window, and a slider filter for drinking window.
* **UI/Trigger:** 
    * Add a `RangeSlider` for Drinking Window (e.g., 2020 - 2050) in the **Filter Bottom Sheet**.
    * Add "Drinking Window (Soonest)" and "Drinking Window (Furthest)" to the sort options.
* **Database:** Add `drinkingWindowStart` and `drinkingWindowEnd` integer fields to the `WineDefinition` entity.

</details>

### W-044 — Expandable FAB for Add Actions
**Status:** Implemented. Marked ✅ in the original `wishes.md`.

<details><summary>Original specification</summary>

* **Goal:** Provide a centralized, fast action menu from the main navigation without cluttering the screen.
* **UI/Trigger:** The `[+]` button in the center of the bottom navigation bar should no longer navigate instantly to `AddWineScreen`.
    * Tapping it should bring up a sleek popup menu (sliding up out of the button, centered on the screen) with two premium options:
        1. **Add New Wine** (for entering catalog/inventory)
        2. **Add Tasting Note** (for evaluating a consumed wine)
* **Interaction:** The background should dim slightly when the menu pops up. Tapping anywhere outside dismisses it.

</details>

### W-045 — Advanced Sensory Evaluation Form (AWS Inspired)
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** Sensory sliders, common faults, free-form notes and the Gson-backed `sensoryData` JSON column are implemented and extended by the aroma wheel (CHANGELOG [Unreleased]).

<details><summary>Original specification</summary>

* **Goal:** Elevate the Tasting Note entry into a comprehensive, professional evaluation framework, drawing inspiration from the American Wine Society (AWS) and similar systematic approaches.
* **UI Structure:** 
    * **Top Section:** A searchable dropdown / auto-complete field to quickly select the specific wine from the cellar/catalog being evaluated.
    * **Visual Presentation:** Instead of basic numerical inputs, use sliding scales with clear semantic text descriptors at tick marks.
    * **Color Palette:** Use aesthetically pleasing ombre colors for the sliders (e.g., transitioning from a light gold/blush to a deep bordeaux, or tailored to the trait like light green to deep purple for intensity).
* **The Sliders & Descriptors:**
    1. **Sweetness:** Bone Dry ➔ Dry ➔ Off-Dry ➔ Medium Sweet ➔ Sweet ➔ Luscious
    2. **Aroma/Bouquet:** Very Subdued ➔ Subdued ➔ Moderate ➔ Expressive ➔ Powerful
    3. **Acidity:** Flabby ➔ Low ➔ Balanced/Medium ➔ Crisp/High ➔ Tart/Searing
    4. **Tannins/Grip:** None/Silky ➔ Low ➔ Medium ➔ High/Grippy ➔ Astringent/Harsh
    5. **Body:** Light/Watery ➔ Medium-Minus ➔ Medium ➔ Medium-Plus ➔ Full/Heavy
    6. **Balance:** Disjointed ➔ Unbalanced ➔ Acceptable ➔ Well-Balanced ➔ Seamless
    7. **Intensity (Flavor):** Weak ➔ Light ➔ Moderate ➔ Pronounced ➔ Overwhelming
    8. **Complexity:** Simple/One-Dimensional ➔ Moderate ➔ Complex ➔ Highly Complex
    9. **Finish (Length):** Short (<3s) ➔ Medium-Minus ➔ Medium (4-7s) ➔ Long (8-10s) ➔ Lingering (>10s)
    10. **Quality Assessment:** Poor ➔ Acceptable ➔ Good ➔ Very Good ➔ Outstanding
    11. **Overall Score:** 50 ➔ 100 (Standard continuous slider for the final grade)
* **Flaws Section:** A group of clearly styled selectable chips or checkboxes for common wine faults: *Cork Taint (TCA), Oxidation, Volatile Acidity (VA), Brettanomyces (Brett), Reduction (Sulfur), Heat Damage*.
* **Open Notes:** A large, multi-line `OutlinedTextField` at the bottom for free-form tasting notes and overall impressions.
* **Implementation Note (Advanced Persistence Strategy):**
    * **The "Approach 3" Model:** To ensure maximum flexibility for future expansion (e.g., adding a 12th or 15th trait), all sensory data will be stored as a single JSON-serialized string in a new `sensoryData` column within the `TastingNote` database entity.
    * **Data Structure:** A Kotlin `data class SensoryData` will hold the trait values (e.g., `sweetness: Int`, `acidity: Int`) and the list of checked flaws.
    * **Room Integration:** A `TypeConverter` using **Gson** will automatically handle the translation between the Kotlin object and the database string.
    * **Extensibility:** This architecture allows us to add new sensory parameters at any time by simply updating the `SensoryData` class with a new field and a default value, avoiding the need for future SQL migrations for every new trait.
* **Implementation Thoughts:** To make this professional yet easy to use, the layout should utilize a `LazyColumn` or vertical scroll since there are many sliders. The ombre effect can be achieved using `Brush.horizontalGradient` applied to the active track color of the Compose `Slider`. A "Snap to grid" (`steps` parameter) is recommended for traits with finite text descriptors to make selection snappy.

</details>

### W-046 — Wine Data Importer — High-Quality Bottle Images *(was wishes-v2 #35)*
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** Implemented as a reviewed Open Food Facts queue (Profile → *Review Missing Images*), local compressed WebP storage with provenance/attribution, a configurable soft storage budget, and *Manage Offline Images* removal. Deep-dive briefs: `major-feature-planning/wine-bottle-label-importer.md`, `manage-offline-wine-images.md`. Build/device verification is still pending — see BACKLOG → Verification.

<details><summary>Original specification</summary>

* **Goal:** Add high-quality stock images of wine bottles or labels for wines already represented in the app.
* **Scope:** Find images for wines that appear in the **Cellar** or in **Tasting Notes**.
* **Implementation:** Provide a wine image importer that locates suitable stock bottle or label images and associates them with the corresponding wine records so they can be shown throughout the app.

</details>

### W-048 — Pareto Plot — Selected-Wine Pop-up Card *(was wishes-v2 #37)*
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** Implemented in `ParetoScreen.kt`: tapping a dot opens an over-chart card, dismissed by an outside tap. Brief: `major-feature-planning/pareto-screen.md`.

<details><summary>Original specification</summary>

* **Interaction:** Tapping a dot on the Pareto chart must display a compact information card over the graph for the selected wine.
* **Dismissal:** Tapping anywhere outside the card dismisses it.
* **Details:** Show the selected wine’s identifying information and its plotted price/rating data.

</details>

### W-049 — Pareto Plot — Best-Value Frontier Dashboard *(was wishes-v2 #38)*
**Status:** Implemented. Not ticked in the original list; verified as implemented on 2026-10-15 (source inspection + `CHANGELOG.md`).
**Notes:** Implemented: Red and White best-value dashboards below the chart rank frontier picks by average score per dollar.

<details><summary>Original specification</summary>

* **Placement:** Add a polished dashboard directly below the Pareto chart.
* **Content:** Highlight the best-value wines leading the Pareto frontier.
* **Categories:** Separate the featured wines into **Red** and **White** categories.

</details>

---

## Part B — Resolved bugs

### Fixed 2026-10-15 (previously unnumbered entries in `bugs.md`)

#### Bug-022: Aroma launcher wrapping and score endpoint alignment
* **Date logged/fixed:** 2026-10-15
* **Fix:** Replaced the launcher’s scrolling chip strip with a wrapping FlowRow, with two-dimensional long-press reordering. Score track, markers, labels, and explicit thumb now share the same inset; font-scaled label space prevents the 100 label from clipping.
* **Verification:** Device check pending: reorder across rows; select 50 and 100; check large-font labels. *(Tracked in BACKLOG → Verification.)*

#### Bug-023: Overall score thumb stuck after endpoint alignment change
* **Date logged/fixed:** 2026-10-15
* **Cause:** The custom slider's empty track slot measured to zero width, collapsing the thumb's travel range.
* **Fix:** Supply a full-width, invisible 8.dp-high Spacer as the track. Keep the existing Canvas gradient and shared thumb/label insets.
* **Verification:** No other empty slider track slots found. Device verification pending: drag from 50 through 75 to 100 and tap both endpoints. *(Tracked in BACKLOG → Verification.)*

#### Bug-024: Duplicate aroma editor below sensory sliders
* **Date logged/fixed:** 2026-10-15
* **Cause:** The original AromaDescriptorSection was still rendered after the Quality slider, duplicating the launcher card and squeezing its heading into a narrow column.
* **Fix:** Removed that section's invocation and extra spacer from SensoryEvaluationFields. The upper aroma wheel launcher and its wrapping, reorderable chips remain unchanged; Common faults now follows the sensory sliders directly.
* **Verification:** Confirmed there are no remaining AromaDescriptorSection call sites. Device verification pending. *(Tracked in BACKLOG → Verification.)*

#### Bug-025 (change): Default wine reviewer list update
* **Date changed:** 2026-10-15
* **Change:** Removed Vinous and Jancis Robinson from the defaults; added Peter Koff, Konstantin Baum, and Costco Wine Blog. Saved custom reviewer lists and existing tasting notes remain unchanged.

#### Bug-026: “Recommended by” label changes position on first interaction
* **Date changed:** 2026-10-15
* **Fix:** Use the state-based outlined field with an attached, always-minimized label, so “Recommended by” starts and stays raised even when empty. The read-only display state follows reviewer selection and clearing without forcing focus or opening the menu.
* **Verification:** Checked label-position and single-line APIs against Material 3 source. Device check pending: initial empty field, open/dismiss, select, and clear. *(Tracked in BACKLOG → Verification.)*

### Resolved, but still listed under “Active Bugs” in `bugs.md`

#### Bug-021: Persistent Sub-route on Tab Switch (Add Wine)
* **Date Logged:** 2026-03-16
* **Component/File:** `MainActivity.kt`
* **Severity:** Minor UI
* **Description:** Navigating to the "Add Wine" screen and then switching to another tab (e.g., Notes) would "save" the Add Wine screen on the Cellar tab stack. Returning to the Cellar tab would then show the Add Wine form instead of the Cellar list.
* **Steps to Reproduce:**
    1. Open FAB menu -> Add New Wine.
    2. Click "Notes" tab.
    3. Click "Cellar" tab.
* **Resolution:** Updated `navigateToTab` to pop `add_wine` and `history` sub-routes when switching *away* from their parent tabs, ensuring a clean state on return. (Already documented as resolved in the original entry; see also `LESSONS_LEARNED.md`.)

#### Bug-020: FAB Submenu Touch Interception (Navigation Lock)
* **Date Logged:** 2026-03-16
* **Component/File:** `MainActivity.kt`
* **Severity:** Minor UI
* **Description:** After opening and closing the FAB submenu, or navigating to "Add Wine", the full-screen container for the menu remained active (though invisible), intercepting touches and preventing navigation back to Cellar or Notes.
* **Steps to Reproduce:**
    1. Tap FAB to open menu.
    2. Tap outside or navigate to "Add Wine".
    3. Try to tap "Cellar" in the bottom navigation bar.
* **Resolution:** Moved the `fillMaxSize()` container inside the `AnimatedVisibility` block so it is entirely removed from the view hierarchy when not visible. (See also `LESSONS_LEARNED.md`.)

### Found already fixed during consolidation (2026-10-15, source inspection only — please confirm on device)

#### Bug-019: Double Vintage Parentheses for NV wines
* **Date Logged:** 2026-03-15
* **Component/File:** `ui/screens/TastingNotesScreen.kt`
* **Severity:** Minor UI
* **Description:** Wines without a vintage (Non-Vintage) should show as "(NV)", but are displaying as "((NV))" in the Tasting Notes screen.
* **Steps to Reproduce:**
    1. Open Tasting Notes screen.
    2. Locate a wine with "NV" as the vintage (e.g., Esperal - Vinho verde).
    3. Observe the vintage display in the card title.
* **Resolution (inferred):** Vintage display now goes through `normalizeVintage()` / `displayVintage()` in `WineDatabase.kt`, which strips surrounding parentheses and maps blank/`null`/`NV` to a plain `NV`; the title builder in `TastingNotesScreen` adds the single pair of parentheses. Unit-tested in `VintageAndDatePickerUtilsTest` (`(NV)` → `NV`). Documented in CHANGELOG [Unreleased] → *Vintage normalization and lookup*.

#### Bug-005: Cellar button fails to reset screen state
* **Date Logged:** 2026-03-14
* **Component/File:** `ui/screens/CellarScreen.kt`
* **Severity:** Minor UI
* **Description:** When on the Cellar screen with a wine card expanded/viewed, pressing the Cellar navigation button does not return the user to the main/reset state of the Cellar screen.
* **Steps to Reproduce:**
    1. Navigate to the Cellar screen.
    2. Expand a wine card to view details.
    3. Tap the "Cellar" button in the bottom navigation bar.
* **Resolution (inferred):** `WineViewModel.cellarResetKey` is bumped on Cellar re-selection and `CellarScreen` resets filters, search and expanded cards in a `LaunchedEffect(cellarResetKey)`. Documented in CHANGELOG [Unreleased] → *Cellar filter reset*.

### Resolved earlier (verbatim from `bugs.md` → “Resolved / Fixed”)

#### **[Bug-018]**: Wine Card Expansion Multi-Press Issue
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 17:56
* **Root Cause:** State synchronization conflict between `WineCard.kt` internal `expanded` state and the external state managed by `TastingNotesScreen.kt`. The card required one press to update the internal state and a second to update the external state (or vice versa), leading to laggy/unreliable expansion.
* **Summary of fix:** Refactored `WineCard` to accept an optional `isExpanded` parameter for external control. When `isExpanded` is provided, the card bypasses its internal state logic. Updated `TastingNotesScreen` to pass its state directly to `WineCard` and provide the details content lambda unconditionally.

#### **[Bug-016]**: Tasting Notes Screen Duplication & Vintage Corruption
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

#### **[Bug-017]**: DataImporter Vintage Fallback Vulnerability
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 17:40
* **Root Cause:** `DataImporter.kt` lines 168-170 contained a fallback that overwrote any note's vintage with an arbitrary bottle's vintage from the same definition. This meant any export/import cycle could silently corrupt vintage assignments, especially for horizontal tastings where multiple vintages share one `definitionId`.
* **Summary of fix:** Removed the `vintageByOldDefId` fallback entirely. Added `bottleIdMapping` so the note's `bottleId` survives import. Vintage is now derived from the actual linked bottle in the backup data — never from a random lookup.

#### **[Bug-015]**: Missing Subregions in Filters & Cards
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 15:30
* **Summary of fix:** Root cause was a corrupted data source (JSON/CSV) where subregions were missing or improperly mapped. Resolved code-side by implementing `normalizeLocation` (Trim/TitleCase) to handle messy data and updating all screen subtitles to explicitly include the `subregion` field.

#### **[Bug-011]**: Persistent Zero Prices in Rankings
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 10:15
* **Summary of fix:** Prioritized non-zero prices in `InventoryDao` queries and added a vintage-agnostic fallback to ensure price data is pulled from the most relevant bottle available.

#### **[Bug-010]**: Duplicate Definitions from Name Variations
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 05:20
* **Summary of fix:** Implemented `MIGRATION_10_11` to consolidate definitions and re-parent all child records (bottles/notes) to a unified ID based on standardized producer names.

#### **[Bug-014]**: Startup Hang (Main Thread Blocked)
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 10:15
* **Summary of fix:** Moved Room database migrations to background thread via `lifecycleScope` and converted heavy ViewModel components (Retrofit, ML Kit OCR) to `by lazy` initialization.
 
#### **[Bug-013]**: Missing "Added on" Dates
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 01:50
* **Summary of fix:** Updated `WineRepository` and `DataImporter` to populate `dateAdded`, and implemented `MIGRATION_11_12` to backfill missing dates for existing cellar entries.

#### **[Bug-012]**: Application Crash (InputDispatcher) on Large Lists
* **Date Logged:** 2026-03-15
* **Date & time Fixed:** 2026-03-15 01:58
* **Summary of fix:** Comprehensive performance overhaul moving all O(N) computations (filtering, sorting, aggregation) into `remember` blocks across Cellar, History, Rankings, and Tasting Notes screens.

#### **[Bug-006]**: Incorrect pluralization for bottle count
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-15 01:46
* **Summary of fix:** Implemented pluralization logic in `CellarScreen.kt` to correctly display "bottle" vs "bottles" based on the count.

#### **[Bug-001]**: Filters broken in Cellar screen
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14
* **Summary of fix:** Implemented `CellarFilterState`, updated `WineViewModel` to manage filter state, and added filtering logic/UI to the now extracted `CellarScreen.kt`.

#### **[Bug-002]**: Rankings screen card expansion needed
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14
* **Summary of fix:** Extracted `WineCard` component with expansion support and implemented expansion details in `RankingsScreen.kt`.

#### **[Bug-003]**: Tasting Notes screen card expansion needed
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14
* **Summary of fix:** Implemented expanded state in `CompactNoteItem` to show full notes, rating, and tasting date.

#### **[Bug-004]**: Incorrect QTY in Consumption History
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14
* **Summary of fix:** Updated `HistoryWineCard` to display a "CONSUMED" badge instead of a raw "QTY: 0" for archived items.
#### **[Bug-007]**: Empty Rankings screen
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14 22:30
* **Summary of fix:** Fixed logic error in `RankingsScreen.kt` where wines with ratings between 1 and 5 were hidden; filter threshold changed from `> 5` to `> 0`.

#### **[Bug-008]**: Inconsistent Tasting Notes styling
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14 22:35
* **Summary of fix:** Refactored `TastingNotesScreen.kt` to use the shared `WineCard` component for 1:1 visual parity with the Cellar screen.

#### **[Bug-009]**: Fragile CSV Import (The "Shifted Columns" Bug)
* **Date Logged:** 2026-03-14
* **Date & time Fixed:** 2026-03-14 22:15
* **Summary of fix:** Migrated the entire backup/restore system to JSON format using Gson to eliminate data misalignment caused by missing or optional columns in legacy CSV backups.


### Original bug-logging rules and template (from `bugs.md`)
These now apply to `BACKLOG.md` (see its *How to use* section). Kept here for reference:

```
### **[Bug-00X]**: Short Title
* **Date Logged:** YYYY-MM-DD
* **Component/File:** `ui/screens/ExampleScreen.kt`
* **Severity:** [Low | Minor UI | Edge Case]
* **Description:** Brief description of what is happening.
* **Steps to Reproduce:**
    1. Step one
    2. Step two
* **Known Workaround / Notes:** Any temporary fix or notes on why it happens.
```

---

## Part C — Completed plan items

From `PLAN.md` (the old “Native Architecture refactor plan”):

### Phase 1: Foundation & Data Migration — completed (v0.1)
Standard import/export with two-pass ID mapping verified and functional.
- [x] `WineDatabase.kt`: `Wine` → `WineDefinition` (catalog); `WineBottle` entity (inventory: definition link, vintage, quantity, price, location, status); `BottleStatus` enum (`IN_CELLAR`, `ARCHIVED`); `TastingNote` with `rebuy` boolean linked to history.
- [x] `WineDaos.kt`: upsert logic (same maker + name + vintage increments quantity); dashboard (active inventory) and rankings queries.

### Phase 2 items completed since the plan was written
- [x] **Dependencies:** CameraX and Google ML Kit text recognition are in `app/build.gradle.kts`.
- [x] **Smart scanning:** `ScannerManager.kt` plus the label-scan pipeline (`scanner/`) pre-fill Add Wine (scanning on Add Tasting Note was declined — see Part D, W-047).
- [x] **ViewModel exposure:** `WineViewModel` exposes `activeInventory` and `tastingHistory` flows (feature ViewModels were also split out — see `LESSONS_LEARNED.md`).
- [x] **Dashboard filters:** Delivered as the Filter & Sort bottom sheet (W-005, W-007, W-025) instead of Material 3 filter chips.

### Phase 3: Archive & Analytics — completed in a different shape
- [x] **Archive / “Leaderboard” view sorted by rating:** Delivered as the Rankings tab (W-004, W-012).
- [x] **Bottom-sheet filter for history searching:** Delivered for Cellar, Rankings and Tasting Notes (W-005, W-007, W-014, W-025).

Items from `PLAN.md` that were *not* completed: drinking-window calculation and Phase 4 cleanup are in `BACKLOG.md`; swipe-to-consume was declined (Part D, W-051).

---

## Part D — Declined ideas

Considered and **rejected by the project owner on 2026-10-15**. They are not on the backlog; don't re-propose or implement them without a new request. The IDs are retired.

| ID | Idea | Origin | What this means in practice |
| --- | --- | --- | --- |
| W-021 | Save the user's own scanned label photo as the wine's image | wishes #21 | Scan photos stay temporary (used for OCR, then deleted). Card/detail image display (Coil, colour-coded fallback) was already built and stays; it is fed by the reviewed Open Food Facts importer (W-046). |
| W-027R | CSV / ZIP export, and bundling images into a backup | wishes #27 (residual) | Backup/restore stays **JSON only**. |
| W-047 | Label scanner on Add Tasting Note | wishes-v2 #36 | Scanning is Add Wine only; Add Tasting Note stays manual (matches the existing `THINGS_TO_CHECK.md` note). |
| W-051 | Swipe-to-consume on cellar cards | AGENTS.md, PLAN.md | Consuming stays on Wine Detail → *Mark as Consumed* (date picker + undo snackbar). |
| W-054 | Per-wine image search / replace on Wine Detail | importer brief | Images stay managed from Profile (reviewed queue + Manage Offline Images). |
| W-055 | Export aromas in CSV | aroma-wheel brief | Moot: no CSV export. Aromas remain in the JSON backup. |

