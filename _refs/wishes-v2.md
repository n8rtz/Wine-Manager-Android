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

## 35. Wine Data Importer — High-Quality Bottle Images
* **Goal:** Add high-quality stock images of wine bottles or labels for wines already represented in the app.
* **Scope:** Find images for wines that appear in the **Cellar** or in **Tasting Notes**.
* **Implementation:** Provide a wine image importer that locates suitable stock bottle or label images and associates them with the corresponding wine records so they can be shown throughout the app.

## 36. Wine Scanner — Camera Label Recognition
* **Goal:** Let users capture a wine label with their phone camera and use image recognition to identify the wine.
* **UI/Trigger:** Make scanning available from both the **Add to Cellar** and **Add Tasting Note** forms.
* **Implementation:** Use recognized label information to automatically fill the corresponding form fields, reducing manual entry.

## 37. Pareto Plot — Selected-Wine Pop-up Card
* **Interaction:** Tapping a dot on the Pareto chart must display a compact information card over the graph for the selected wine.
* **Dismissal:** Tapping anywhere outside the card dismisses it.
* **Details:** Show the selected wine’s identifying information and its plotted price/rating data.

## 38. Pareto Plot — Best-Value Frontier Dashboard
* **Placement:** Add a polished dashboard directly below the Pareto chart.
* **Content:** Highlight the best-value wines leading the Pareto frontier.
* **Categories:** Separate the featured wines into **Red** and **White** categories.
