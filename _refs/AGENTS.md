# AI Agent Instructions: Native Wine Cellar Management App

## Role & Project Overview
You are an expert Android development assistant. We are building a modern, native Android application for Wine Tracking and Cellar Management to replace a legacy spreadsheet.

The app must break free from spreadsheet limitations (no cascading dropdowns for consumption) and instead use relational database logic, camera integrations, and intuitive state management.

## Tech Stack Preferences
* **UI:** Jetpack Compose (Material 3 design guidelines).
* **Database:** Room Database (SQLite) with appropriate relational entities.
* **Architecture:** MVVM (Model-View-ViewModel) with Kotlin Coroutines and Flow.
* **Integrations:** CameraX and Google ML Kit (Text Recognition) for label scanning.

## Core App Features & Screens

### 1. "Add to Cellar" (Intake Flow)
* **Smart Scanning:** Integration with the device camera to scan wine labels. Use ML Kit to extract text and pre-fill fields (Maker, Name, Vintage).
* **Manual Entry:** Form containing `Type`, `Maker`, `Name`, `Vintage`, `Country`, `Region`, `Price`, and `Quantity`.
* **Logic:** If the exact wine (Maker + Name + Vintage) already exists in the database, simply increment the `Quantity`.

### 2. The Cellar Dashboard (Active Inventory)
* **Purpose:** The primary view showing currently owned bottles.
* **UI Elements:** * List/Grid of wine cards.
    * Filter chips at the top (Type, Country, Vintage, Price).
    * **Estimated Drinking Window:** A visual indicator on each card (e.g., "Peak: 2025-2028" or "Drink Now") calculated based on vintage and type.
* **Quick Actions:** Swiping a list item or tapping a button on the card should trigger the "Consume" action.

### 3. Consumption & Tasting Notes (The "Drink" Flow)
* **Trigger:** Initiated directly from a bottle in the Cellar view.
* **Action:** Prompts the user to confirm consumption. Decrements the `Quantity` by 1.
* **Tasting Note Prompt:** Upon consumption, present an optional form to log a `Rating` (1-5 stars or 100-pt scale), a `Rebuy` boolean, and text `Notes`.
* **State Change:** If a wine's quantity reaches 0, its state changes from `IN_CELLAR` to `ARCHIVED`.

### 4. Archive & Rankings (History)
* **Purpose:** A historical database of all wines ever tasted/owned.
* **UI Elements:** A comprehensive list view defaulting to sorted by `Rating` (Descending) to act as a leaderboard.
* **Advanced Filtering:** Must include a robust bottom-sheet or drawer filter allowing the user to filter their history by `Type`, `Country`, `Producer`, `Cost`, and `Vintage`.

## Database Design Rules (Room)
* Do not use a flat spreadsheet-style table.
* Use a primary `WineBottle` entity. Distinguish between active cellar inventory and history using a status flag (e.g., `enum class BottleStatus { IN_CELLAR, ARCHIVED }`) or by tracking quantities and consumption dates in a separate relational `TransactionHistory` table.