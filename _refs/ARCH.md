# WineTracker Architecture & App Map

## 1. Core Philosophy: Separation of Concerns (SoC)
We strictly enforce modularity to keep files small, maintainable,
and token-efficient. **Do not create "God Objects" or monolithic files.**

**Rules for the AI agent:**
- Extract large blocks of code into the `components/` folder if they are reused across multiple places. Otherwise, consider extracting components when a file becomes too complex or unwieldy (typically > 400-500 lines) to maintain readability.
- New screens go in `ui/screens/`.
- New reusable widgets go in `ui/components/`.
- Room entities, converters and migrations currently live together in `data/local/WineDatabase.kt` (there is no `data/models/` package).
- New DAO methods go in `data/local/WineDaos.kt` (single file).
- All database access from ViewModels must go through
  `data/repository/WineRepository.kt`. Never call DAOs directly
  from a ViewModel or Screen.
- **Lazy Initialization:** Always use `by lazy` for heavy components in ViewModels (Retrofit, OCR managers, batch processors) to prevent blocking the main thread during startup.
- **Background Migrations:** Database migrations must be triggered on a background thread during `MainActivity` creation to ensure the UI remains responsive.
- **ViewModel Specialization:** Avoid the "God ViewModel" pattern. Use specialized ViewModels for distinct feature areas (e.g., `ScannerViewModel` for media, `RankingsViewModel` for analytics). This keeps the code modular and prevents token-limit issues during development.
- New ViewModels go in `viewmodels/`. Prefer one per screen or functional group over one monolithic ViewModel.
- CSV-to-entity mapping logic stays in `data/importer/`.
  Do not create separate mapper classes unless there are
  3+ distinct import sources.

## 2. Directory Structure Map
*(Regenerated from the source tree on 2026-10-15. Keep it current whenever a file is added, removed or renamed — agent rule 12.)*

```
app/src/main/java/com/n8rtz/winetracker/
│
├── MainActivity.kt                    # Entry point, nav host (routes: cellar, notes, rankings, history, profile, settings,
│                                      #   app_info, pareto, add_wine, wine/note editors), bottom bar, Add FAB menu
│
├── ui/
│   ├── theme/
│   │   ├── Color.kt                   # Bordeaux Red, off-white, slate, gray palette
│   │   └── Theme.kt                   # Light/dark theme
│   ├── screens/
│   │   ├── CellarScreen.kt            # Active inventory, analytics header, search, Filter & Sort sheet
│   │   ├── CellarFilterState.kt       # Cellar filter/sort state
│   │   ├── WineDetailScreen.kt        # Wine detail, edit mode, Mark as Consumed, per-wine note history
│   │   ├── AddWineScreen.kt           # Manual entry (cascading dropdowns) + label-photo scan and review dialog
│   │   ├── TastingNotesScreen.kt      # Notes repository: search, filters, expandable cards
│   │   ├── TastingNotesFilterState.kt # Notes filter/sort state
│   │   ├── TastingNoteEditorScreen.kt # Add/edit note: Cellar/Manual entry, sensory form, aroma wheel launcher
│   │   ├── HistoryScreen.kt           # Consumption-event history, note links, undo-safe list
│   │   ├── RankingsScreen.kt          # Rankings by 100-point score + search + filters
│   │   ├── RankingsFilterState.kt     # Rankings filter/sort state
│   │   ├── ParetoScreen.kt            # Price-vs-rating Pareto chart, detail card, Red/White dashboards
│   │   ├── ProfileScreen.kt           # Profile tab (Price vs. Rating, reviewers, Settings link) and the Settings screen (ProfileSection.SETTINGS: backup/restore, legacy CSV import, image review/storage, app info)
│   │   └── AppInfoScreen.kt           # App/DB version, build time, commit and branch
│   └── components/
│       ├── WineCard.kt                # Colour-coded expandable card (Cellar, Rankings, Notes); Coil image
│       ├── HistoryWineCard.kt         # Compact history list item
│       ├── AnalyticsCard.kt           # Dashboard metric cards (bottles, unique wines, value)
│       ├── EmptyState.kt              # Empty-list placeholder
│       ├── WheelPicker.kt             # Birthday-style scroll picker + cascading location wheels
│       ├── DatePickerUtils.kt         # Local-date <-> Material DatePicker UTC-midnight conversion
│       ├── BottomBarLabelSizing.kt    # Uniform bottom-bar label sizing
│       ├── SensoryEvaluationFields.kt # Ombre sensory sliders, score slider, aroma launcher card, faults
│       ├── AromaChipDrag.kt           # Long-press drag-reorder logic for aroma chips
│       ├── AromaWheelDialog.kt        # Full-screen aroma wheel: wheel host, list+search mode, chips
│       └── LabelScanCameraPreview.kt  # CameraX preview + framing guide for label capture
│
├── drinkingwindow/                    # W-050 Phase 1: offline drinking-window estimate (pure Kotlin)
│   ├── DrinkingWindowEstimator.kt     # Text normaliser, profile matcher, EstimatedWindow (display-only)
│   └── AgeingProfiles.kt              # Draft ageing table: 61 rules, source note + reviewed flag each
│
├── aromawheel/                        # Self-contained zoomable aroma wheel
│   ├── AromaTaxonomy.kt               # Versioned 116-descriptor vocabulary, search, selection rules
│   ├── AromaWheelTheme.kt             # Per-group colours + ring/band labels
│   ├── AromaWheelLayout.kt            # Ring radii, sector spans, hit-testing, label planning (pure Kotlin)
│   ├── AromaWheelGraphics.kt          # Canvas paths + curved-text baselines
│   └── AromaWheel.kt                  # Composable: pinch/pan, sector zoom, tap-to-select, accessibility
│
├── data/
│   ├── local/
│   │   ├── WineDatabase.kt            # Room DB (v21): entities WineDefinition, WineImageAsset, WineBottle,
│   │   │                              #   TastingNote, Rating, ConsumptionEvent; BottleStatus; SensoryData;
│   │   │                              #   Converters; vintage helpers; all migrations
│   │   └── WineDaos.kt                # DAOs: Wine, WineImageAsset, Inventory, TastingNote, Rating, ConsumptionEvent
│   ├── repository/
│   │   ├── WineRepository.kt          # Single source of truth; inventory and consumption-event transactions
│   │   └── WineImageImportRepository.kt # Reviewed image import, provenance, offline-copy removal
│   └── importer/
│       ├── BackupData.kt              # JSON backup model
│       ├── JsonExporter.kt            # JSON export (Gson)
│       ├── DataImporter.kt            # Validated, transactional JSON restore + relational ID mapping (file URI or stream)
│       ├── DevSeedRestorer.kt         # DEBUG ONLY: restores the bundled debug seed backup into an empty database after a fresh install
│       └── CsvImportHelper.kt         # Legacy tasting-note and consumption-history CSV importers
│
├── scanner/                           # On-device label scanning (CameraX still photo -> ML Kit OCR)
│   ├── ScannerManager.kt              # Runs the OCR passes; owns the ML Kit recognizer
│   ├── LabelScanSession.kt            # Per-scan decision state (escalation, merging, trust) — pure Kotlin
│   ├── LabelScanLadder.kt             # Crop/enhancement recipes + when another pass is worth it
│   ├── LabelPassPlan.kt               # What one escalation step reads (full view vs. field zoom)
│   ├── LabelImageEnhancer.kt          # Builds the bitmap one pass reads + coordinate mapping
│   ├── LabelCropMapping.kt            # Crop/zoom coordinate arithmetic
│   ├── LabelFieldGeometry.kt          # Locates the line a field came from for targeted re-reads
│   ├── LabelOcrLine.kt                # Plain OCR line box (decoupled from ML Kit)
│   ├── LabelOcrConsensus.kt           # Line-by-line fusion of OCR passes
│   ├── LabelOcrHypothesisSelector.kt  # Picks the most legible, wine-relevant OCR hypothesis
│   ├── WineLabelParser.kt             # OCR text -> editable Producer/Name/Vintage suggestions
│   ├── WineReferenceDictionary.kt     # Local vocabulary + saved definitions to repair OCR errors
│   ├── WineLabelFraming.kt            # Portrait label-guide geometry
│   ├── ImageQualityAnalyzer.kt        # Up-front blur/darkness measurement
│   ├── CaptureQuality.kt              # Capture-quality result and retake rule
│   ├── OneShotScanGate.kt             # One OCR job per user request
│   ├── LabelScanState.kt              # Scan UI state machine
│   ├── LabelBarcodeReader.kt          # EAN/UPC barcode read from the same still
│   ├── Gtin.kt                        # GS1 check-digit validation
│   ├── LabelIdentityResolver.kt       # Barcode -> wine identity (Open Food Facts) with cache
│   ├── BarcodeIdentityCache.kt        # On-disk barcode lookup cache (works offline)
│   └── WineBarcodeIdentity.kt         # Identity model + Open Food Facts product mapper
│
├── image/                             # Reviewed stock-image import (Open Food Facts) and offline storage
│   ├── OpenFoodFactsWineImageProvider.kt # Candidate search/mapping/filtering; WineImageKind
│   ├── WineImageFileStore.kt          # Downloads, downsamples, stores local WebP copies (<= 500 KB each)
│   └── WineImageDisplay.kt            # Prefer approved local file, else legacy URL (for Coil)
│
├── network/
│   ├── OpenFoodFactsApi.kt            # Retrofit interface (image search + barcode product lookup)
│   └── OpenFoodFactsProductResponse.kt # Response models
│
└── viewmodels/
    ├── WineViewModel.kt               # Cellar/History state, filters, consume/undo, import/export
    ├── TastingNoteViewModel.kt        # Note editor state, save/delete, event-note linking
    ├── RankingsViewModel.kt           # Rankings filtering, sorting, price lookups
    ├── ScannerViewModel.kt            # Label scan state (capture, OCR, review)
    └── WineImageImportViewModel.kt    # Reviewed image-import queue, storage budget, offline-copy management

app/src/main/res/xml/                  # backup_rules.xml, data_extraction_rules.xml (exclude files/wine-images/), file_paths.xml
app/src/test/java/...                  # JVM tests: aromawheel, drinkingwindow, scanner (parser, ladder, session, consensus, barcode...),
                                       #   image provider, vintage/date utils, sensory JSON, bottom-bar sizing, aroma drag
app/src/androidTest/java/...           # Instrumented: WineImageImportRepositoryTest, LabelOcrImageRegressionTest
app/src/test/resources/scanner/labels/ # Label OCR image fixtures (see its README)
app/src/debug/assets/dev-seed/         # DEBUG ONLY: WineTracker_Backup.json test cellar (192 bottles, 139 wines, 116 notes), not in release builds
app/src/debug/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt # Debug-only per-label pipeline gates, definitions and latest-attempt aggregation
app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusPipelineDiagnostics.kt # Original traces -> label gates, including separate OCR branches
app/src/debug/java/com/yourapp/wine/phonecorpus/PhoneCorpusFunnelJson.kt # Compact funnel checkpoint/raw-export JSON, with honest legacy unknowns
tools/logic-checks/PipelineFunnelChecks.kt # Actual Kotlin telemetry, legacy evidence, checkpoint/scorer/CSV regression checks
tools/logic-checks/PipelineFunnelHostContext.kt # Host asset-reader stand-in, not an Android runtime
tools/logic-checks/PipelineFunnelHostRunner.kt # Host-only mode constants; does not execute OCR
tools/logic-checks/generate-funnel-fixture.py # Validates/reuses saved scored+raw CSVs as host evaluation inputs
tools/run-pipeline-funnel-checks.sh       # Runs actual Kotlin funnel/checkpoint/scorer/CSV checks; optional saved-export analysis
app/src/androidTest/java/com/yourapp/wine/textlab/PipelineFunnelDeviceTest.kt # Disposable-device native SQLite -> preview/checkpoint/scorer/CSV smoke test
tools/tests/test_pipeline_funnel.py      # Host fixture, compact-mobile layout contract, export wiring and build metadata checks
docs/scanner-accuracy-plan.md          # Audited phone evidence, strict goals and proposed staged accuracy plan (not recognition changes)

_refs/                                 # Project docs: BACKLOG, ARCHIVE, PIPELINE, CHANGELOG, ARCH, AGENTS, THINGS_TO_CHECK, LESSONS_LEARNED
_backup/                               # Personal cellar JSON backups (source of the debug seed), one-off convert_csv.py (its CSV input was removed)
README.md                              # Project overview, build steps, documentation index
.agents/instructions.md                # AI-agent operating rules
```

## 3. Data Flow Rule
Every data operation follows this chain. No shortcuts.

User Action
│
▼
Screen (UI)         ← observes state, sends events
│
▼
ViewModel           ← holds state, runs business logic in viewModelScope
│
▼
WineRepository      ← single source of truth, coordinates operations
│
▼
WineDaos            ← raw database queries
│
▼
WineDatabase (Room) ← SQLite

## 4. Performance Patterns
- **Batch Database Queries**: Always prefer batch queries (e.g., `WHERE id IN (...)`) over single queries in loops.
- **Reactive Filtering/Sorting**: Perform heavy filtering, sorting, and data preparation (like string formatting) in the ViewModel using `combine` and `StateFlow` to keep the UI thread responsive.
- **Logging**: Include execution time logs for heavy data transformations to identify potential bottlenecks.

## 5. Import/Export Integrity Rules
- **ID Mapping is mandatory:** When importing relational data, always maintain mapping dictionaries (`idMapping` for definitions, `bottleIdMapping` for bottles) to translate old auto-generated IDs to new ones. Never assume IDs persist across export/import cycles.
- **Never use fallback vintage lookups:** A note's vintage must come from its directly linked bottle (via `bottleId`), not from a random bottle under the same definition. The `vintageByOldDefId` pattern was banned after [Bug-016] caused silent vintage corruption for horizontal tastings.
- **Bottle is the source of truth for vintage:** `WineBottle.vintage` is authoritative. `TastingNote.vintage` is a copy derived from the linked bottle at creation time or import time.
- **Test with horizontal tastings:** Any changes to the import pipeline must be validated against the scenario of multiple vintages of the same wine consumed on the same day (identical timestamps, same `definitionId`, different vintages).

## 6. Consumption & Vintage Integrity
- `ConsumptionEvent` is the source of truth for consumption history. Consuming a bottle decrements the active inventory row and inserts one event in the same transaction; undo targets that exact event ID. Do not create per-consumption archived `WineBottle` rows because the bottle table has a unique `(definitionId, vintage, status)` index.
- Migration 16→17 converts legacy archived bottle rows to events while preserving nullable `dateConsumed` as-is. It does not infer dates from tasting notes or add historical timestamps.
- Add Note is a tasting-note-only operation; it does not decrement cellar inventory. Notes created from an event are linked to that specific event.
- Normalize non-vintage aliases (`NV`, `(NV)`, and blank legacy values) for display, import, grouping, and lookup. Do not infer a missing vintage from an unrelated bottle.
