# WISHES.md: Premium UI/UX & Architecture Handoff

This document defines the strict design system, information architecture, and component layouts for the Wine Cellar app. The AI assistant must override default Material 3 styling and implement these exact specifications to achieve a high-end, professional, e-commerce-grade application.

## ✅ 1. Global Theme & Color Palette
Completely remove the default Material 3 purple/lavender color scheme. Override `Color.kt` and `Theme.kt` with the following premium palette:
* **Primary Accent (The "Wine" Color):** Deep Bordeaux Red (Hex: `#722F37` or `#58181F`). Use for primary buttons, active bottom nav icons, and floating action buttons.
* **Background:** Off-White/Light Gray (Hex: `#F8F9FA`) for Light Mode. Deep Charcoal (Hex: `#121212`) for Dark Mode. This ensures the white cards pop.
* **Surface (Cards/Sheets):** Pure White (`#FFFFFF`) in Light Mode, Dark Gray (`#1E1E1E`) in Dark Mode.
* **Typography Colors:** * Primary Text (Headers/Titles): Dark Slate (`#1C1C1E`).
    * Secondary Text (Subtitles/Metadata): Medium Gray (`#8E8E93`).

## ✅ 2. Global Geometry & Typography Specs
* **Corner Radius:** Do not use full pill-shapes. Use modern, slight rounding. Cards and Buttons must use `RoundedCornerShape(8.dp)`.
* **Elevations:** Cards must have a very subtle drop shadow (e.g., `elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)`). No harsh black shadows.
* **Padding/Margins:** * Screen edge margins: `16.dp`.
    * Vertical spacing between list items: `8.dp` or `12.dp`.
    * Internal card padding: `16.dp`.

## ✅ 3. The "Wine Card" Component (Strict Layout)
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

## ✅ 4. App Architecture: Bottom Navigation
The `BottomNavigationBar` must have 4 distinct tabs. Do not combine History and Rankings.
1.  **Cellar:** Active Inventory. (Icon: `WineBar` or `List`)
2.  **History:** Chronological list of consumed wines, sorted by date consumed (Newest first). (Icon: `History` or `Schedule`)
3.  **Rankings:** The Leaderboard. Sorted STRICTLY by 100-point scale rating (Highest to Lowest). (Icon: `Star` or `EmojiEvents`)
4.  **Add:** The camera scanner/manual entry screen. (Icon: `AddCircle` or `CameraAlt`)

## ✅ 5. Modern E-Commerce Filtering (The Cellar Screen)
* **Remove Inline Chips:** Completely delete the horizontal scrolling filter chips from the top of the screen.
* **Filter Trigger:** Add a top app bar (or a row below the search bar) with a "Filter & Sort" button featuring the `Icons.Default.Tune` icon.
* **Modal Bottom Sheet:** Tapping the trigger opens a `ModalBottomSheet`.
* **Sheet UI:** * Header: "Filter Cellar" with an "X" to close.
    * Body: Expandable accordion sections (e.g., "Wine Type", "Country", "Region", "Vintage").
    * Interaction: Inside each section, use multi-select checkboxes (e.g., I can check "Red" and "Sparkling" at the same time).
    * Footer: A sticky row at the bottom with a "Clear All" text button and a solid "Apply Filters" primary button.

## ✅ 6. Polish & Edge Cases
* **Empty States:** If a list is empty (e.g., the Cellar has no wines, or a filter returns 0 results), display a beautifully formatted Empty State. This should include a large, muted icon (e.g., a wine glass), a title ("Your Cellar is Empty"), and a subtitle ("Scan a label or add a wine manually to get started").

## ✅ 7. Filter Sheet Overhaul (Premium UI)
* **No Checkboxes:** Completely remove standard checkboxes from the filter sheet.
* **Flow Layout:** Use a `FlowRow` (or similar wrapping layout) so filter options sit side-by-side, utilizing the full width of the screen instead of a single-file vertical list.
* **Toggle Chips:** Filter options must be sleek, pill-shaped toggle buttons (like ChoiceChips). When selected, they should fill with the Primary Accent color (Bordeaux Red) and white text. When unselected, they should be a subtle gray outline.

## ✅ 8. Wine Card Redesign (Color Coding & Strict Typography)
* **Remove Quick Action:** Remove the 'Drink/Trash' icon button from the main card UI. The card should be a clean presentation of data. Tapping the entire card must navigate to a new `WineDetailScreen`.
* **Color-Coded Backgrounds:** The background of the card (or the 60x80 placeholder image box) must be color-coded based on the Wine Type to provide instant visual identification:
    * **Red Wines:** Very subtle pale red/blush tint (e.g., Hex `#FFF5F5`).
    * **White Wines:** Very subtle pale yellow/straw tint (e.g., Hex `#FFFFF0`).
    * **Sparkling/Other:** Clean off-white/gray (e.g., `#F8F9FA`).
* **Typography Refinement:** * Line 1 (Bold): `[Brand/Producer] - [Wine Name] ([Vintage])`
    * Line 2 (Italicized, Secondary Color): `[Appellation/Subregion], [Region], [Country]`

## ✅ 9. The "Wine Detail" Screen & Consumption Flow
* **Navigation:** Tapping any wine card opens a dedicated Detail Screen showing all data.
* **Consume Action:** Place a prominent, primary-styled "Mark as Consumed" button on this detail screen.
* **Date Entry:** Tapping "Mark as Consumed" must open a dialog or sub-menu allowing the user to confirm the Consumption Date (defaulting to today's date) before finalizing the database move from Cellar to History.

## ✅ 10. Strict Bottom Navigation Architecture (Inline Add Button)
* **No Floating FAB:** The `Scaffold` MUST NOT use the `floatingActionButton` property. Do not use `FabPosition`.
* **The Layout:** Inside the `bottomBar` slot of the `Scaffold`, use a standard `NavigationBar` or a custom `Row` that spans the full width.
* **Inline Placement:** The large, primary-colored [+] Add Button must be placed *directly inside* this bottom navigation row, vertically centered, exactly between the 'Tasting Notes' icon and the 'Rankings' icon.
* **Alignment:** The center of the [+] button must share the exact same horizontal axis as the rest of the navigation icons so they form a single, perfectly straight row.

## ✅ 11. The "Undo" Consumption Safety Net (Snackbar)
* **Destructive Action Safety:** Whenever a wine is marked as consumed, the app must immediately display a `Snackbar` at the bottom of the screen.
* **Snackbar UI:** The text should briefly confirm the action (e.g., "Consumed 1 bottle of St. Emilion"). It MUST include a prominent "UNDO" action button using the primary Bordeaux Red color.
* **Undo Logic:** Tapping "UNDO" before the Snackbar disappears must instantly revert the database transaction, restoring the bottle's quantity in the Cellar and removing the accidental History entry.

## ✅ 12. Terminology Enforcement
* **Strict Naming:** The 100-point sorted screen must be called **Rankings**. Completely remove the word "Leaderboard" from the UI, the codebase, and all comments.

## ✅ 13. History Tab Overhaul (Compact Log Book)
* **Purpose:** The History screen is a chronological log of consumed bottles, sorted by Date Consumed (Newest first). It is NOT a flashy showcase.
* **Compact UI:** Do not use the large `WineCard` layout here. Use a sleek, compact `ListItem` layout.
* **Displayed Data:** * Left side: A small, clean date icon or just the formatted Date Consumed (e.g., "Oct 24, 2023").
    * Body: `[Producer] - [Wine Name] ([Vintage])` in a standard font size.
    * Right side: A subtle icon (like a notebook or pen) indicating if a Tasting Note exists.
* **Interaction:** Tapping an item in the History list opens the Tasting Note Detail/Edit screen.

## ✅ 14. The "Tasting Notes" Repository Screen
* **Purpose:** This screen is a dedicated, fast-access repository for all past tasting notes, acting just like a master spreadsheet tab. It replaces the need to dig through the chronological History log to enter a note.
* **Top Search Bar:** The very top of the screen must feature a prominent, premium Search Bar (e.g., a styled `OutlinedTextField` with a search icon) allowing instant text-search by Producer, Name, or Vintage.
* **Easily Accessible Filters:** Next to or immediately below the search bar, include a "Filter" icon button. Tapping this must open the exact same premium **Modal Bottom Sheet Filter** (with the `FlowRow` toggle chips, no checkboxes) that we designed for the Cellar in Section 7. Include a quick toggle in these filters for "Needs Notes" vs "Completed Notes."
* **Repository List:** Below the search/filter area, display a clean, compact list of all consumed wines.
* **Interaction:** Tapping any wine in this list instantly opens the Note Editor overlay/screen.

## ✅ 15. The Note Editor UI
* **Clean Interface:** When a wine is selected from the repository, present a distraction-free screen or large bottom sheet.
* **Elements:** * The fixed 2-line Wine Header (`Producer - Name (Vintage)` / `Appellation, Region`).
    * A large, clean text area for the written tasting notes.
    * A sleek numerical input or slider strictly for the 100-point score.
    * A prominent "Save Note" button in the primary Bordeaux Red color.

## ✅ 16. The "Add Wine" Form (Cascading Dropdowns)
* **No Manual Location Entry:** "Country", "Region", and "Appellation/Subregion" MUST NOT be standard text input fields. They must be modern Compose `ExposedDropdownMenuBox` components.
* **Cascading State Logic:** * The user must select a "Country" first.
    * The "Region" dropdown must only populate with regions belonging to the selected Country.
    * The "Appellation" dropdown must only populate with appellations belonging to the selected Region.
* **Required Fields List:** The form must include: Maker/Winery, Wine Name, Vintage, Type (Red/White/Sparkling/Rose/Dessert), Country, Region, Appellation, Quantity, and Purchase Price.

## ✅ 19. The Cellar Dashboard Header (Analytics)
* **Top-Level Summary:** The top of the Cellar screen (below the app bar, above the list) must feature a premium Dashboard Header.
* **Metrics:** This header should display quick analytics: 'Total Bottles', 'Unique Wines', and 'Estimated Value' (sum of quantity * purchase price). Format this elegantly, perhaps as a horizontal scrolling row of small, premium summary cards or a clean 3-column grid.

## ✅ 20. Main Cellar Search
* **Search Bar:** Directly below the Analytics header, include an `OutlinedTextField` acting as a Search Bar (Icon: `Search`). This should instantly filter the displayed cellar list by Producer or Wine Name as the user types.

## 21. Real Label Imagery
* **Image Saving:** When the user uses the Camera to scan a label on the Add Screen, the app must save that cropped image locally to device storage.
* **Card Display:** The `60.dp x 80.dp` placeholder box on the `WineCard` and `WineDetailScreen` must display this saved image using an image loading library (like Coil). If a wine is added manually without a photo, it should gracefully fall back to the color-coded background we established in Section 8.
*
## ✅ 22. No Dead Ends (Strict Navigation Architecture)
* **Universal Top App Bars:** EVERY screen that is not a main bottom-navigation tab (e.g., Detail Screens, Edit Screens, Settings) MUST be wrapped in a `Scaffold` that includes a `TopAppBar`.
* **Universal Back Button:** This `TopAppBar` MUST include a `navigationIcon` (an `Icons.AutoMirrored.Filled.ArrowBack`) that calls `navController.popBackStack()`.
* **Error States:** Even if data fails to load (e.g., "Wine not found"), the screen must still display the TopAppBar so the user can navigate backwards. Never trap the user on a blank screen.

## ✅ 23. Save Actions & Back Navigation (Form Handling)
* **Immediate Back Navigation:** Whenever a user submits a form (e.g., tapping the "Save" checkmark on the Edit Screen, or adding a new wine), the app MUST immediately execute `navController.popBackStack()` upon successful database submission.
* **Coroutine Safety:** Database insertions/updates must be handled in the ViewModel using `viewModelScope.launch`. The UI should observe a success event (like a `SharedFlow` or a boolean state) to trigger the `popBackStack()` navigation safely, ensuring the user is returned to the previous screen rather than getting stuck on a reloading/empty form.

## ✅ 24. Bottom Navigation State Colors (Active vs. Inactive)
* **Visual Hierarchy:** The Bottom Navigation bar must clearly indicate which tab is currently active using strict color coding.
* **Colors:** * **Active State:** Both the Icon and the Label text MUST use the primary Deep Bordeaux Red (`#722F37`).
  * **Inactive State:** Both the Icon and the Label text MUST use the Medium Gray (`#8E8E93`).
* **Clean UI (No Pill):** Override the default Material 3 active indicator. Set the `indicatorColor` to `Color.Transparent` so there is no ugly pill shape behind the active icon. The background of the navigation bar must remain completely solid and clean.

## ✅ 25. Advanced E-Commerce Filter & Sort Sheet
* **Non-Collapsible Layout:** Completely remove the `AccordionSection` logic. All filter categories must be permanently expanded and stacked vertically inside a `verticalScroll` modifier.
* **Sticky Footer:** The "Apply Filters" and "Clear All" buttons MUST remain sticky at the bottom of the sheet, never scrolling out of view.
* **Sort By Section:** At the very top of the sheet, include a "Sort By" section using selectable chips or a dropdown for: `Newest`, `Oldest`, `Price (Low to High)`, `Price (High to Low)`, `Country (A-Z)`, `Country (Z-A)`.
* **Dual-Input Range Sliders (Price, Vintage, Rating):** For numerical filters, the UI must include BOTH a visual `RangeSlider` AND two side-by-side `OutlinedTextField` boxes (Min and Max) below it. Updating the text fields must update the slider, and vice versa.
  * *Price Range:** e.g., $0 to $1000+
  * *Vintage Range:** e.g., 1900 to Current Year
  * *Rating Range:** 0 to 100
* **Maker/Producer Filter:** Include a section for "Maker/Producer". Because the list could be long, use a `FlowRow` of toggle chips for the most common makers, or a nested searchable autocomplete field inside the sheet.
* **Keyboard Inset Safety:** Because we are adding text fields inside a bottom sheet, the sheet MUST handle `WindowInsets.ime` padding so the keyboard doesn't cover the Min/Max inputs when typing.

## ✅ 26. Automatic Stock Image Fetching
* **API Integration:** The app should automatically attempt to fetch a stock image of the wine bottle when a new wine is added.
* **Search Query:** The app should generate a search query string combining: `[Producer] + [Wine Name] + [Vintage] + "wine bottle"`.
* **Image Loading:** The app MUST use an asynchronous image loading library (like **Coil**) to load the fetched image URL into the `60.dp x 80.dp` placeholder box on the `WineCard` and Detail screens.
* **Graceful Fallback:** If the API fails, returns no results, or the device is offline, the UI MUST gracefully fall back to the subtle color-coded backgrounds defined in Section 8.
* **Data Layer:** The `WineDefinition` (or `WineBottle`) database entity must be updated to include a `String? imageUrl` field to cache the fetched URL so the app doesn't re-query the API every time the list scrolls.


## ✅ 27. Universal Data Portability (Backup & Restore)
* **Standardized Exports:** The app must support exporting the entire database (Definitions, Bottles, History, and Tasting Notes) into a standardized **CSV** format for spreadsheet use, or a **ZIP** archive for full backups.
* **Smart Import Logic:** The import pipeline must be "update-aware." It should use `OnConflictStrategy.IGNORE` to avoid duplicates and rely on the `@Update` logic defined in Section 13 to merge records without triggering `CASCADE DELETE`.
* **Media Portability:** If a ZIP export is used, it should include the locally saved label images defined in Section 21.

## ✅ 28. Profile & System Settings (The "Utility Hub")
* **Location:** Accessible via a "Profile" or "Settings" icon in the Top App Bar of the main screens.
* **Layout:** A clean, vertical list of options using `HorizontalDivider` between items.
* **Data Management Section:** * **Import Data:** A prominent list item that launches the system file picker (`ActivityResultContracts.GetContent`) to select a CSV file.
  * **Export Data:** A list item that generates the standardized CSV/ZIP and launches the system "Share" sheet or "Save to Files" picker.
* **UI Feedback:** Both actions must show a "Processing..." Modal Bottom Sheet (Bordeaux Red accents) with a success/failure Snackbar upon completion.

## ✅ 29. Tasting History (Archive)
* **Access:** Triggered via the "History" icon in the Top App Bar of the Notes/Cellar screen.
* **Data Source:** Display all `WineBottle` records where `status == BottleStatus.ARCHIVED`.
* **UI Layout:** * A vertical list of cards (`RoundedCornerShape(8.dp)`).
  * **Primary Text:** Maker and Wine Name.
  * **Secondary Text:** Vintage and Date Consumed (format: MMM dd, yyyy).
  * **Exclusion:** Do NOT show tasting notes in this summary list view.
* **Empty State:** If no history exists, show a "No wines tasted yet" message with a Bordeaux Red accent.
## ✅ 30. Corrected Import Logic (The Three-Way Split)
* **Master Catalog:** Use `Wines - Log (In).csv` to populate the `WineDefinition` table (Maker, Name, Type, Country, Region).
* **Active Cellar:** Use `Wines - Cellar.csv` to create `WineBottle` records with `status = BottleStatus.IN_CELLAR`.
  * *Note:* If a wine is in the Cellar but NOT in Log (In), create a new Definition for it.
* **Tasting History:** Use `Wines - Log (Out).csv` to create `WineBottle` records with `status = BottleStatus.ARCHIVED`.
  * *Data Point:** The "Date" column in Log (Out) should be mapped to the `lastUpdated` or `dateConsumed` field.
* **Conflict Resolution:** If a wine appears in both Log (In) and Log (Out), the app should show one Definition in the catalog, one Archived bottle in History, and (if applicable) an Active bottle in the Cellar.

# 32. Show All Attributes on Cellar Edit Screen
* **Problem:** Currently, when editing a wine from the Cellar, the UI only renders data entry boxes for fields that already contain data. Missing fields are completely hidden.
* **Goal:** Allow the user to go in at a later date and add missing information to incomplete wine profiles.
* **Implementation:** Modify the Edit Wine UI to display input fields for *all* attributes, exactly mirroring the layout of the "Add Wine" menu. Even if an attribute is currently null/empty, its entry box must be visible and editable.

## 33. Import Legacy Tasting Notes (CSV)
* **Goal:** Import the historical notes from the original spreadsheet.
* **Source File:** Must specifically target the export file named `Wines - Tasting Notes.csv`.
* **UI/Trigger:** Add a dedicated "Import Tasting Notes" button inside the **Profile menu** to initiate this specific dataset import.
* **Implementation:** The importer must read this CSV and map the text to the corresponding `TastingNote` entity, linking it accurately to the existing wines in the Room database.

## 34. Import Legacy Consumption History (CSV)
* **Goal:** Import the list of consumed bottles so historical drinking data isn't lost.
* **Source File:** Must specifically target the export file named `Wines - Log (out).csv`.
* **UI/Trigger:** Add a dedicated "Import History Log" button inside the **Profile menu** to initiate this process.
* **Navigation/Location:** The imported data belongs on the **History screen**. This screen must be accessed via a button located in the **top right corner of the Tasting Notes screen**.
* **Data Displayed:** The History UI must be formatted to show the **Date Consumed**, the **Wine Info**, and the **Quantity (qty)** based on the imported CSV rows.

## 35. Country Flag Icons
* **Goal:** Provide a quick visual geographic aid for the wine's origin.
* **UI/Trigger:** Add a small country flag emoji or icon to each wine card.
* **Navigation/Location:** Visible on both the **Cellar screen** and **Rankings screen** list items.

## ✅ 36. Multi-Wheel Location Filter
* **Goal:** Simplify complex location filtering with a intuitive, cascading UI.
* **UI/Trigger:** A "triple scroll wheel" picker (Birthdate style). Left: Country, Middle: Region, Right: Appellation.
* **Logic:** The Region and Appellation wheels must filter dynamically; they should only populate with relevant options once a parent location is selected.
* **Navigation/Location:** Accessed within the **Filter Bottom Sheet** on the Cellar screen.

## ✅ 37. Cellar Filter Reset
* **Goal:** Allow users to clear all active filters in a single tap.
* **UI/Trigger:** A "Reset" button/icon that clears search text, location wheels, and sorting.
* **Navigation/Location:** Located at the top of the **Filter Bottom Sheet** or next to the search bar on the **Cellar screen**.

## 38. Drinking Window Field
* **Goal:** Track when a wine is at its peak quality.
* **UI/Trigger:** An editable date-range field (e.g., 2024–2030).
* **Navigation/Location:** This field should be hidden on the main cellar list but visible and editable on the **Wine Detail screen**.

## ✅ 39. Inline Consumption History
* **Goal:** View all previous interactions with a specific wine without leaving the detail page.
* **UI/Trigger:** A chronological list showing past dates, scores, and tasting notes.
* **Navigation/Location:** Positioned at the bottom of the **Wine Detail screen**.

## 40. Drinking Window Filter & Sort
* **Goal:** Prioritize drinking wines that are nearing the end of their window.
* **UI/Trigger:** Add "Peak Window" as a filter category and "Drinking Window (Oldest/Newest)" as a sort option.
* **Navigation/Location:** Found within the **Filter Bottom Sheet of the Cellar screen**.

## ✅ 41. Rating Ranges on Cards
* **Goal:** Show the user's personal rating history at a glance.
* **UI/Trigger:** Display the score on the wine card. If multiple ratings exist for that wine, display them as a range (e.g., "88-90").
* **Navigation/Location:** Visible on the **Cellar screen** list items.

## 42. Estimated Drinking Window Integration
* **Goal:** Add estimated drinking window to database for each wine. Also add in the cellar screen a sort by drinking window, and a slider filter for drinking window.
* **UI/Trigger:** 
    * Add a `RangeSlider` for Drinking Window (e.g., 2020 - 2050) in the **Filter Bottom Sheet**.
    * Add "Drinking Window (Soonest)" and "Drinking Window (Furthest)" to the sort options.
* **Database:** Add `drinkingWindowStart` and `drinkingWindowEnd` integer fields to the `WineDefinition` entity.
 
 ## 43. Multi-Screen Vintage Control (v0.5.4 Priority)
 * **Goal:** Provide detailed temporal filtering and sorting for serious collectors.
 * **UI/Trigger:** 
     * Add a `RangeSlider` for **Vintage** (e.g., 1900 - 2024+) in the **Filter Bottom Sheet** for Cellar, Tasting Notes, and Rankings.
     * Include dual side-by-side text input boxes for explicit Min/Max vintage entry below the slider.
     * Add "Vintage (Newest)" and "Vintage (Oldest)" to the sort options across all lists.
 * **Note:** Flag user during v0.5.4 development to prioritize this implementation.

## ✅ 44. Expandable FAB for Add Actions
* **Goal:** Provide a centralized, fast action menu from the main navigation without cluttering the screen.
* **UI/Trigger:** The `[+]` button in the center of the bottom navigation bar should no longer navigate instantly to `AddWineScreen`.
    * Tapping it should bring up a sleek popup menu (sliding up out of the button, centered on the screen) with two premium options:
        1. **Add New Wine** (for entering catalog/inventory)
        2. **Add Tasting Note** (for evaluating a consumed wine)
* **Interaction:** The background should dim slightly when the menu pops up. Tapping anywhere outside dismisses it.

## 45. Advanced Sensory Evaluation Form (AWS Inspired)
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

## 46. Tree-Style Tasting Note Selector (Placeholder)
* **Goal:** Implement a basic hierarchical (tree-style) selector for aromas and flavors to serve as a functional placeholder until the high-fidelity interactive Aroma Wheel is fully implemented.
* **UI/Trigger:** A nested list or expandable tree interface where users can drill down from broad categories (e.g., Fruity) to specific descriptors (e.g., Green Apple).
* **Navigation/Location:** Integrated into the **Advanced Sensory Evaluation Form** (Nose/Palate sections).

## 47. Integrated Reference Tools & Resources
* **Goal:** Provide expert-level tools and reference data directly within the app to assist in accurate wine evaluation and collection management.
* **Resources Included:**
    * **Wine Vintage Ratings:** Quick access to vintage quality charts by region/year.
    * **Wine Region Maps:** High-resolution maps of major wine appellations.
    * **Wine Varietal Profiles:** Detailed summaries of grape characteristics and common tasting profiles.
* **Navigation/Location:** Accessible via a new "Resources" or "Reference" section within the **Utility Hub / Settings**.

## 48. Dual-Context Aroma Wheels
* **Goal:** Improve usability and readability of the sensory evaluation by providing tailored aroma selections based on the wine's profile.
* **UI/Trigger:** 
    * Two separate interactive wheel configurations:
        1. **Red Wine Wheel:** Focused on dark fruits, earthy, and spice notes.
        2. **White/Rosé/Sparkling Wheel:** Focused on citrus, floral, and light fruit notes.
* **Logic:** The app should automatically default to the appropriate wheel based on the `WineType` of the selected bottle, while still allowing the user to manually toggle between them if needed.
* **Navigation/Location:** Found within the **Advanced Sensory Evaluation Form** (Nose section).
