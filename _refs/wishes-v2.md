# WISHES.md: Premium UI/UX & Architecture Handoff

This document defines the strict design system, information architecture, and component layouts for the Wine Cellar app. The AI assistant must override default Material 3 styling and implement these exact specifications to achieve a high-end, professional, e-commerce-grade application.


## 32. Show All Attributes on Cellar Edit Screen
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