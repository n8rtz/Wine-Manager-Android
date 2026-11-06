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
│                                      #   app_info, pareto, backup_restore, add_wine, wine/note editors), bottom bar, Add FAB menu
│
├── ui/
│   ├── theme/
│   │   ├── Color.kt                   # Bordeaux Red, off-white, slate, gray palette
│   │   └── Theme.kt                   # Light/dark theme
│   ├── screens/
│   │   ├── CellarScreen.kt            # Active inventory, stock controls, note/edit shortcuts, search, stats and Filter & Sort
│   │   ├── CellarFilterState.kt       # Cellar filter/sort state
│   │   ├── WineEditScreen.kt          # Edit-only wine metadata and cellar-bottle fields
│   │   ├── AddWineScreen.kt           # Manual entry (cascading dropdowns) + label-photo scan and review dialog
│   │   ├── TastingNotesScreen.kt      # Notes repository: floating search/filter, one-open expandable cards, scrolling title
│   │   ├── TastingNotesFilterState.kt # Notes filter/sort state
│   │   ├── TastingNoteEditorScreen.kt # Add/edit note: Cellar/Manual entry, four sectioned sensory groups, phase wheel
│   │   ├── TastingNoteDetailScreen.kt # Read-only note detail using the entry layout; explicit Edit action
│   │   ├── HistoryScreen.kt           # Consumption-event history, note links, undo-safe list, scrolling title
│   │   ├── RankingsScreen.kt          # Rankings by 100-point score + floating search/filter, scrolling title
│   │   ├── RankingsFilterState.kt     # Rankings filter/sort state
│   │   ├── ParetoScreen.kt            # Price-vs-rating Pareto chart, detail card, Red/White dashboards
│   │   ├── ProfileScreen.kt           # Profile tab (Price vs. Rating, reviewers, Settings link) and the Settings screen (ProfileSection.SETTINGS: backup/restore, import/export, app info)
│   │   ├── AppInfoScreen.kt           # App/DB version, build time, commit and branch
│   │   └── BackupRestoreScreen.kt     # W-057 submenu: destination toggles + setup, priority, schedule, keep-N, restore picker, failure popup
│   └── components/
│       ├── WineCard.kt                # Colour-coded card; body and 48dp caret expand, Coil image
│       ├── FloatingSearchFilterControls.kt # Vertically stacked compact search/filter buttons that appear after the top row scrolls away, plus the pop-up search row
│       ├── HistoryWineCard.kt         # Compact history list item
│       ├── AnalyticsCard.kt           # Dashboard metric cards (bottles, unique wines, value)
│       ├── EmptyState.kt              # Empty-list placeholder
│       ├── WheelPicker.kt             # Birthday-style scroll picker + cascading location wheels
│       ├── DatePickerUtils.kt         # Local-date <-> Material DatePicker UTC-midnight conversion
│       ├── BottomBarLabelSizing.kt    # Uniform bottom-bar label sizing
│       ├── SensoryEvaluationFields.kt # Shared ombre sensory controls, score slider and fault helpers
│       ├── AromaChipDrag.kt           # Long-press drag-reorder logic for aroma chips
│       ├── AromaWheelDialog.kt        # Full-screen aroma wheel: wheel/list mode plus phase-aware W-061 picker
│       ├── LabelScanCameraPreview.kt  # CameraX preview + framing guide for label capture
│       ├── WineColorPicker.kt         # W-061: swatch-anchored menu with a curated wine-color palette
│       ├── PhaseAromaCloud.kt         # W-061: clustered aroma labels resized by pinch and sorted largest-first
│       ├── TastingPhaseSections.kt   # W-061: four sections, bipolar slider, structured summary
│       └── LegacyAromaPhaseSheet.kt  # W-061: explicit legacy aroma phase assignment
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
│   │   ├── WineDatabase.kt            # Room DB (v22): entities WineDefinition, WineImageAsset, WineBottle,
│   │   │                              #   TastingNote, Rating, ConsumptionEvent; BottleStatus; SensoryData;
│   │   │                              #   Converters; vintage helpers; all migrations
│   │   ├── WineDaos.kt                # DAOs: Wine, WineImageAsset, Inventory, TastingNote, Rating, ConsumptionEvent
│   │   ├── TastingNoteModels.kt     # W-061: standalone Room-referenced color/phase/aroma value types
│   │   └── TastingNoteStructure.kt  # W-061: normalization, reducers and validation
│   ├── repository/
│   │   ├── WineRepository.kt          # Single source of truth; inventory and consumption-event transactions
│   ├── importer/
│       ├── BackupData.kt              # JSON backup model (optional reviewer list + image budget since W-057 Phase 2)
│       ├── JsonExporter.kt            # JSON export (Gson) incl. optional preferences
│       ├── DataImporter.kt            # Validated, transactional JSON restore + relational ID mapping (file URI or stream)
│       ├── DevSeedRestorer.kt         # DEBUG ONLY: restores the bundled debug seed backup into an empty database after a fresh install
│       └── BackupPreferences.kt       # Canonical reviewer/budget preference keys + read/write for backup round-trip
│   └── backup/                        # W-057: Backup & Restore (app-open scheduled backups, no background work)
│       ├── BackupSchedule.kt           # Pure Kotlin settings, calendar due rule, priority, naming, retention, warnings
│       ├── BackupConfigRules.kt       # Pure validation of GitHub owner/repo and WebDAV URL setup values
│       ├── BackupDestination.kt       # Upload/verify/list/download/delete contract + snapshot model
│       ├── BackupSettingsStore.kt     # SharedPreferences settings and per-destination confirmed status
│       ├── BackupCredentialStore.kt   # Keystore-wrapped GitHub/DAV secrets + Drive grant (excluded from Auto Backup)
│       ├── BackupHttp.kt              # Shared OkHTTP plumbing and actionable HTTP errors
│       ├── BackupDownloadCache.kt     # Stages a remote snapshot through FileProvider for the validated importer
│       ├── LocalBackupDestination.kt  # Documents/WineManager/ via MediaStore.Files, read-back verification
│       ├── GoogleDriveDestination.kt  # SAF tree folder with persisted read/write grant
│       ├── GitHubDestination.kt       # Private repo + fine-grained PAT, refuses public repos, contents API
│       ├── WebDavDestination.kt       # TrueNAS SCALE WebDAV share / Nextcloud (PUT, PROPFIND, GET, DELETE)
│       ├── BackupCoordinator.kt       # Snapshot -> verify -> prune per destination; app-open due check only
│       └── BackupViewModel.kt         # Backup & Restore state, setup, failure popup, restore picker
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
├── image/                             # Local image file paths, display model and restore cleanup
│   ├── WineImageFileStore.kt          # Resolves local image paths; clears stored images on a JSON restore
│   └── WineImageDisplay.kt            # Prefer approved local file, else legacy URL (for Coil)
│
├── network/
│   ├── OpenFoodFactsApi.kt            # Retrofit interface (barcode product lookup)
│   └── OpenFoodFactsProductResponse.kt # Response models
│
└── viewmodels/
    ├── WineViewModel.kt               # Cellar/History state, filters, consume/undo, import/export
    ├── TastingNoteViewModel.kt        # Note editor state, save/delete, event-note linking
    ├── RankingsViewModel.kt           # Rankings filtering, sorting, price lookups
    └── ScannerViewModel.kt            # Label scan state (capture, OCR, review)

app/src/main/res/xml/                  # backup_rules.xml / data_extraction_rules.xml (W-057 Phase 0: exclude wine-images/ and backup_credentials.xml), file_paths.xml
app/src/test/java/...                  # JVM tests: aromawheel, backup (schedule/naming/retention/config rules), drinkingwindow, scanner (parser, ladder, session, consensus, barcode...),
                                       #   vintage/date utils, sensory JSON, bottom-bar sizing, aroma drag
app/src/androidTest/java/...           # Instrumented: LabelOcrImageRegressionTest, scanner/evidence device tests
app/src/test/resources/scanner/labels/ # Label OCR image fixtures (see its README)
app/src/debug/assets/dev-seed/         # DEBUG ONLY: WineTracker_Backup.json test cellar (192 bottles, 139 wines, 116 notes), not in release builds
app/src/main/assets/producer-gazetteer-v1/ # Verified primary LWIN producer/wine indexes (producers.sqlite 33,160, wines.sqlite) + manifest/NOTICE; primary_verified gate
app/src/main/assets/producer-gazetteer-expanded-v1/ # LAB ONLY expanded producer index (verbatim LWIN subset + source-tagged X-Wines/TTB/OSM, 78,615); opened only by Lab v4 benchmark + Database Lookup
tools/producer-sources/build_expanded.py # Deterministic builder for the Lab expanded producer index from the pinned refresh report
app/src/main/java/com/yourapp/wine/kb/WineIndexDatabaseLookup.kt # Read-only Database Lookup access to the packaged LWIN wine index (wines.sqlite); cache shared with ProducerGazetteer
app/src/debug/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt # Debug-only per-label pipeline gates, definitions and latest-attempt aggregation
app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt # The only benchmark screen: 125-label run, frozen Prod rows, Cached OCR checkbox next to Lab v4, matrix, CSV save
app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt # Process-scoped Lab pass + one-time Prod capture; survives background navigation; exports scored/raw CSV through CsvUploadParts
app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsRunService.kt # Foreground service + partial wake lock keeping an active 125-label benchmark running when navigated away from
app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsBaselines.kt # Frozen Prod v1.0/v2.0/v3.0 outcomes per photo, keyed by corpus signature
app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsBuildLog.kt # Separate scalar benchmark-results-log.csv (date, time, build #, branch, model version, counts, and per-parameter best-build provenance) powering the "Lab v4 Best build" / "Best build" comparison rows
app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsTimers.kt # Pure formatting and monotonic live-tick arithmetic for Run time (mm ss), Avg / image (s), and Est. remaining countdown (mm ss)
app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsOcrCache.kt # Per-photo cache of verified ML Kit + Paddle v6 Medium OCR outputs for Lab v4, keyed by corpus + model signature
app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt # Fresh or opt-in Lab v4 cached OCR per photo; runImageAllModes is the only entry point
app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusScoreboard.kt # Scoring, bottleneck matrix and CSV rows for the 125-label corpus
app/src/main/assets/wine-app-labels-v1/ # Live benchmark assets: 125 photos + inputs/evaluation-set/scoring/manifest
tools/logic-checks/PipelineFunnelChecks.kt # Actual Kotlin telemetry, legacy evidence, checkpoint/scorer/CSV regression checks
tools/logic-checks/PipelineFunnelHostContext.kt # Host asset-reader stand-in, not an Android runtime
tools/logic-checks/PipelineFunnelHostRunner.kt # Host-only mode constants; does not execute OCR
tools/logic-checks/generate-funnel-fixture.py # Validates/reuses saved scored+raw CSVs as host evaluation inputs
tools/run-pipeline-funnel-checks.sh       # Runs actual Kotlin funnel/checkpoint/scorer/CSV checks; optional saved-export analysis
app/src/androidTest/java/com/yourapp/wine/textlab/PipelineFunnelDeviceTest.kt # Disposable-device native SQLite -> preview/checkpoint/scorer/CSV smoke test
tools/tests/test_pipeline_funnel.py      # Host fixture, compact-mobile layout contract, export wiring and build metadata checks
tools/tests/test_wine_app_labels_corpus.py # 125-label asset/route contract + frozen-baseline capture and CSV-save wiring
app/src/main/java/com/yourapp/wine/textlab/TextLabBottleFilter.kt # LAB ONLY: post-OCR spatial bottle-column & medal/critic sticker filter (works with Cached OCR) + conditional padded tiny-footprint zoom-crop
app/src/main/java/com/yourapp/wine/textlab/TextLabState.kt # Default-off Lab engine switches asserted by the instrumented engine tests
app/src/main/java/com/yourapp/wine/textlab/evidence/PlaceRepair.kt # LAB ONLY, off by default: bounded appellation repair (explicit origin anchor, LabelContext country cue, or >=10-letter globally unique unanchored appellation)
app/src/main/java/com/yourapp/wine/textlab/evidence/LabelContext.kt # LAB ONLY: per-label collector of explicit origin anchors, legal classification cues and unambiguous KB producer countries for cross-line disambiguation and PlaceRepair
tools/tests/test_bottle_filter_and_zoom_crop.py # Host wiring & generality checks for Lab v4 bottle-column/sticker filter and conditional zoom-crop
tools/tests/test_label_context_and_accent_scrubbing.py # Host checks for TextNorm.scrub, framed appellations, LabelContext, Lab v4 geography backfill and frozen Prod subset scoping
tools/tests/test_backup_preferences.py  # W-057 host contract: preference key parity, Auto Backup rules, secrets, failure popup wiring
tools/tests/test_consume_quantity.py # Host wiring checks for the multi-bottle Mark as Consumed selector and one-event/quantity-undo integrity
tools/tests/test_settings_cleanup.py # Host contract: the five retired Settings entries and their code/routes are gone, benchmark entry stays
tools/tests/test_tab_top_and_search_labels.py # Host contract: Cellar/Rankings/Notes open at the top on re-entry and search hints are one line
tools/tests/test_drive_folder_change_setup.py # Host contract: cancelling Drive "Change setup" keeps the already-selected folder
tools/tests/test_floating_search_and_card_clicks.py # Host contract: floating search/filter bubbles, carat-only expansion, stats dialog and scrolling headers
tools/evaluation/retired-phone-corpus-v1/ # OFFLINE ONLY inputs of the retired 168-photo corpus (scoring, adjudication, saved v2); never packaged
data/wine-knowledge/                # Authored catalog inputs: editorial-origins.txt, geography/*.txt region/subregion trees, label-terms.csv, typical-grapes.csv, sources/registry.json + pinned CC0 AVA snapshot
tools/generate-wine-knowledge.py    # Deterministic compiler: authored paths -> origins.csv (+ path column) and kb/GeneratedWineKnowledge.kt; --check detects stale outputs
docs/evidence-designation-levels.md # Why the Lab v4 designation/level/fragment rules changed, with the measured 125-label basis and the rejected flatten convention
tools/tests/test_evidence_selection.py # Host checks for the classification-base mirror over the real catalog + wiring of the designation, fragment and level rules
docs/scanner-accuracy-plan.md          # Audited phone evidence, strict goals and proposed staged accuracy plan (not recognition changes)
docs/lab-v4-accuracy-roadmap.md        # Checkable (- [ ]) root-cause roadmap from the 0c88bda41164 125-label run + shipped Phase 0 d=0 fast-paths

_refs/                                 # Project docs: BACKLOG, ARCHIVE, PIPELINE, CHANGELOG, ARCH, AGENTS, THINGS_TO_CHECK, LESSONS_LEARNED
_refs/major-feature-planning/off-device-backup-and-restore.md # W-057 phase decisions: SAF Drive, GitHub, TrueNAS WebDAV, app-open scheduling
_refs/major-feature-planning/structured-tasting-notes.md # W-061 implementation framework: resolved decisions, data contracts, v22 migration, UI/WBS/test plan
_refs/ui/W-060-cellar-view-selector-reference.jpg # Owner-supplied styling reference for W-060's Bottles/Racks cellar selector
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
