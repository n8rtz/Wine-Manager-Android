# WineTracker — Product & Stack Overview

*Rewritten 2026-10-15 to describe the app as it is now. The original brief (Archive/“Leaderboard” screen, 1–5 stars, swipe-to-consume) is superseded. Operating rules for AI agents live in `.agents/instructions.md`; open work in `BACKLOG.md`.*

## What this is
A native Android app (package `com.n8rtz.winetracker`) for wine tracking and cellar management, replacing a legacy spreadsheet. It uses relational data rather than flat sheet rows, on-device label scanning, and personal tasting notes with a structured sensory evaluation. All personal data stays on the device.

## Tech stack
* **UI:** Jetpack Compose, Material 3 (custom Bordeaux theme — see UI rules in `ARCHIVE.md` W-001–W-003, W-007, W-008, W-024).
* **Database:** Room/SQLite (schema v22) with KSP; manual dependency injection.
* **Architecture:** MVVM with Kotlin coroutines and Flow; one ViewModel per feature area; all data access through `WineRepository` (see `ARCH.md`).
* **Camera/OCR:** CameraX still capture, ML Kit text recognition and barcode scanning (on-device).
* **Network (explicit, user-initiated only):** Retrofit + Open Food Facts for barcode lookup and the reviewed image import; Coil for image display.
* **Platform:** min SDK 33, target/compile SDK 36.

## Screens and features
* **Cellar** (tab) — active inventory as colour-coded cards; analytics header (bottles, unique wines, estimated value); search; Filter & Sort sheet (type, location wheels, price/rating/drinking-window ranges, Ready Now / Too Early / Past Peak, sorts). Reselecting the tab resets filters and expanded cards.
* **Wine Detail** — all attributes (edit mode shows every field, even empty), drinking window, source/provenance of the image, per-wine note history, **Mark as Consumed** with a date picker and an UNDO snackbar.
* **Add Wine** (via the floating Add menu) — manual entry with cascading Country → Region → Appellation dropdowns, optional label-photo scan whose OCR/barcode results are *suggestions only* (Producer, Wine Name, Vintage, Type, Country/Region/Appellation) shown in a review dialog and applied only when the user taps Use Suggestions and later saves. Quantity is incremented if Maker + Name + Vintage already exists.
* **Notes** (tab) — tasting-note repository with search/filters; **Add/Edit Note** from the Cellar or manually (restaurant wines), with an overall 50–100 score, sensory sliders, common faults, an aroma wheel, reviewer/“recommended by”, value score and notes.
* **History** (tab) — consumption events (date, wine, quantity, linked note), undo-safe; creates/open notes linked to the exact event.
* **Rankings** (tab) — wines ordered strictly by 100-point score with search and filters.
* **Profile** (tab) — **Price vs. Rating** Pareto screen, the reviewer list, and a **Settings** submenu that holds the technical items: JSON backup/restore and reset, legacy CSV note/history import, reviewed Open Food Facts image import, offline-image storage budget and removal, and app information.

## Data model rules (Room)
* Not a flat spreadsheet table. Core entities: `WineDefinition` (catalog), `WineBottle` (inventory, `BottleStatus` `IN_CELLAR`/`ARCHIVED`), `TastingNote` (with `bottleId`, `sensoryData` JSON), `Rating`, `ConsumptionEvent` (source of truth for consumption history) and `WineImageAsset` (image provenance/local path).
* Consuming a bottle decrements the inventory row and writes one `ConsumptionEvent` in a single transaction; undo targets that exact event. Do not create per-consumption archived bottle rows.
* Import/export integrity rules are in `ARCH.md` §5–6 and `LESSONS_LEARNED.md`; agent rules 14–16 apply to any database work.
