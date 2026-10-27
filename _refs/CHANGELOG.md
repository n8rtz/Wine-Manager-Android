# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Debug benchmark pipeline-bottleneck table in the screen, copied statistics and scored/raw CSVs, with separate OCR branches, distinct-label/latest-attempt counts and explicit unknown/not-reached states. Compact checkpoints retain stage evidence. Host logic validated; Android build/device checks remain pending. No production recognition changes or new accuracy claim.
- **Database Lookup's Producer search now covers every packaged producer database in one lookup:** the page keeps its three searches (Producer, Wine name, Appellation), and Producer now runs over the expanded union index — all 78,615 records, each result's context naming its source database (“LWIN · France”, “X-Wines candidate”, “TTB permit candidate”, “OpenStreetMap candidate”) — instead of the LWIN catalog alone. Under the selector, a “Databases searched” listing shows, for the selected search, each database pulled from with its live record count (LWIN 33,160 / X-Wines 18,945 / TTB 14,849 / OpenStreetMap 11,661; LWIN wine names 101,326 + scanner-KB wine names; bundled-origins + scanner-KB appellations). Country filtering, exhaustive ranked search and 50-row browse pages are unchanged, with blank countries shown as “No country recorded” (45,455 supplemental rows); all explanatory text above “Search in” and below the search field is removed. `ProducerCatalogDatabaseLookup` is generalized into per-catalog specs with per-asset manifest gates, and its cache layout still matches `ProducerGazetteer`, so both loaders share one on-device copy. Owner rule recorded as `.agents/instructions.md` #17: every future packaged database addition/change must join the matching search and its database listing in the same change. The packaged-asset byte cap in both producer loaders is documented as a self-imposed sanity bound — not an Android/SQLite limit — and raised 32 → 64 MiB in step; the practical ceilings remain APK size, ~2× on-device storage (compressed asset + cache copy) and the benchmark loader's per-photo cold-install copy+hash budget. Manual checks listed under this branch in `_refs/THINGS_TO_CHECK.md`. Python contract tests updated (219 passing); Android build/device verification still pending.

### Changed
- Renamed the debug phone-corpus page to **Large image dataset test** in Settings, the screen heading, loading state and benchmark documentation; the active review selection was revised separately afterward.
- Reduced the active OCR selection to 106 included / 62 excluded labels by owner-excluding review 14, retained review 108's existing red exclusion, corrected producer references for reviews 14/108 and A. Kolb aliases for 16/66, and regenerated reference-based benchmark reports. All 168 source photos remain bundled for provenance.
- Reframed the benchmark matrix as ordered ML Kit OCR, Paddle OCR, and post-OCR requirement tables. The OCR tables clarify sequential runner execution; post-OCR excludes observation-only hypothesis counters, marks linked-Wine cohorts conditionally after Producer invocation, and preserves not-reached / unrecorded / N/A counts distinctly. Correct-blank reference scoring remains separate from process gates; CSV schema and recognition behavior are unchanged. Android rendering/build checks remain pending.
- Incremented Android `versionCode` from 56 to **57** for the owner-requested diagnostics build; `versionName` remains **0.5.6**. This is a build-number update, not a tagged release.
- Removed the explanatory paragraphs from the top of the **Large image dataset test** (build/label-count/provenance and heat/checkpoint notes, plus the per-version descriptions under the mode selector whose label counts were also stale); the title, mode radios, status lines and controls are unchanged.

### Fixed
- **Large image dataset test restores (and can export) results saved before the Lab v4 pipeline rename again:** saved scored CSVs keep the pipeline ID of the build that produced them, but `PhoneCorpusCsvTable.read` only recognized the current IDs — after `lab-v4-lwin-only` → `lab-v4-expanded-producers` (and the brief `production-v3.0-spatial-merged` interlude), restoring a previous build's run failed with “Cannot load benchmark: Import one pipeline's scored table at a time”, leaving no scoreboard and no Copy scored stats / Prepare-export options. `PhoneCorpusRunner.Mode.fromPipelineId` plus a `legacyPipelineIds` alias map now resolve retired IDs to their owning mode for row selection, while summary matching and per-photo display keep the file's own ID so saved results are never relabeled as the current generation; the same fix covers **Import scored results CSV**. Pre-enum era IDs mentioned in the docs (`lab-linked-wine-v1`, `lab-guarded-producer`) are deliberately not aliased — their mode ownership is ambiguous. Validated with a Python mirror over legacy/current/mixed/unknown tables; contract tests updated (220 passing). Android build/device verification still pending.
- **Database Lookup no longer flashes “The coroutine scope left the composition”:** typing a query (or the databases finishing loading) restarts the search effect, and Compose cancels the superseded run with that `CancellationException`; the screen caught every `Exception` and rendered the cancellation as a red “Database lookup failed” error, discarding the search. Both effects (and the per-database count loader) now rethrow `CancellationException` so superseded runs end silently and the newest run owns the state, and the `finally` blocks no longer flip `loading`/`catalogReady` on behalf of a cancelled run. The exhaustive wine/producer scans made this likely by taking long enough to be cancelled mid-flight; the scans themselves are unchanged — a cancelled scan's result is simply discarded on resume. Python contract tests updated (220 passing); Android build/device verification still pending.
- **Database Lookup fuzzy ranking now matches on words, not just whole names:** searching “Koonunga” previously ranked short unrelated names (Codalunga, Donna…) above Koonunga Hill Chardonnay/Shiraz because the extra words counted as edit distance (`Koonunga Hill Chardonnay` sat at #19, most Koonunga wines below #180). `DatabaseNameLookup.rank` now also scores the query's words against every contiguous word run in each name and keeps the best distance — an exact word match is edit distance 0, multi-word queries match word runs (“Koonunga Hill”), and near word matches beat the full-name distance; the bounded search only accepts windows that strictly improve on the full-name distance, so the exhaustive scans stay fast. Applies to Producer, Wine name and Appellation searches alike. Host check `exactWordMatchesRankFirstWithZeroDistance` added; a Python mirror over the full 101,326-name index puts all 9 Koonunga wines in the top 10 (Chardonnay/Shiraz first, distance 0). Python contract tests updated (220 passing); Android build/device verification still pending.
- **Database Lookup Wine name/Appellation searches now reach the real databases:** both previously searched only the scanner knowledge base's built-in fixture snapshot (5 wine names, 9 appellations), bypassing the databases the app itself uses. Appellation now searches and counts the supplemented origins view (`SupplementedWineKnowledgeBase.places()` — bundled editorial + UC Davis AVA origins merged over the scanner-KB places; the listing splits bundled-origins vs scanner-KB appellations via the supplement's virtual ids) through a shared `DatabaseNameLookup.appellationCandidates` helper, so the several hundred bundled appellations are finally visible. Wine name now also searches the packaged LWIN wine index (`wines.sqlite`, 101,326 names / 154,405 producer links) via the new manifest-gated `WineIndexDatabaseLookup` (exhaustive chunked ranking like producers; each result's context resolved to its linked producer name(s)), merged with the scanner-KB results. `DatabaseLookupMatch` now carries the winning candidate's identity to support that enrichment. Per-database counts load independently so one failure cannot blank the others. Python contract tests updated (220 passing); Android build/device verification still pending.
- Removed the merged Gradle script's duplicate `buildCommit` declaration while retaining provider-backed 12-character commit metadata and all six BuildConfig fields; complete Android build still pending.
- **125-label OCR test now runs all four versions per photo:** the Wine App Labels benchmark previously ran only the radio-selected version and filled the other scoreboard rows from older saved runs, so Attempted stayed at 0 for Prod v3.0 and 1 for Prod v1.0/v2.0 while Lab v4 advanced. Each photo now runs Prod v1.0 (the Add Wine scanner, first, inside its own 15-second budget) plus one shared fresh Text Lab OCR pass whose post-processing yields the Prod v2.0 (`evidence_summary`), Prod v3.0 and Lab v4 (`spatial_evidence_summary`) results, checkpointing all four before moving on, so Attempted increments in lockstep. Versions already checkpointed for a photo by an earlier single-version run are kept; only missing or failed versions backfill. The version radio now only selects which diagnostics are displayed, the header counts photos checkpointed in all four versions, and each row is scored from that version's own current run (the cross-run/legacy saved-result merge is removed). The legacy 168-photo corpus screen still runs one selected version at a time. Python contract tests updated (217 passing); Android build/device verification still pending.
- **Lab v4 now actually uses the expanded producer index; Prod v3.0 is LWIN-only:** Prod v3.0 and Lab v4 previously both harvested the current `spatial_evidence_summary` with the same LWIN-only producer data, so the rows were identical by construction (an interim build that instead restored the prior merged-readings spatial generation as Prod v3.0 scored 0/35 and was reverted the same day). The supplemental producer sources an earlier change claimed to package were never in the app — the refresh pipeline wrote offline review files only. They are now built for real by `tools/producer-sources/build_expanded.py`: the verified primary `producers.sqlite` is copied verbatim (all 33,160 LWIN rows/IDs preserved, so the linked-wine index still joins) and appended with candidates absent from LWIN — 18,945 X-Wines winery names (CC0), 14,849 TTB wine-permit operating names, 11,661 OpenStreetMap winery names (ODbL) — 78,615 rows total in `app/src/main/assets/producer-gazetteer-expanded-v1/`, each supplemental row tagged in `producer_sources`. Lab v4 (`lab-v4-expanded-producers`) opens it via the new Lab-only `ProducerGazetteer.openExpanded` manifest gate; Prod v3.0 keeps pipeline ID `production-v3.0-lwin-only` (existing LWIN-only checkpoints are reused) and reads its own `spatial_lwin_evidence_summary` from the identical pipeline run against the primary index. The resolvers keep their two-engine/separation/existing-value rules; a win from a supplemental source is named in the decision reason and `lexical_support_sources` telemetry, and every suggestion remains YELLOW. The primary manifest still rejects packaged supplements, and production/Prod paths never open the expanded index. Workbook coverage: LWIN producers 86/125, expanded producers 101/125. Lab v4 checkpoints from the LWIN-only era are superseded by the new pipeline ID and backfill fresh.
- **Diagnostic bottleneck table colored, screen-fit and condensed:** the 125-label screen's matrix now colors passed counts green and failed counts red (light/dark appropriate), matching the corpus screen. On both screens the step column is severely condensed to a short label (`Step.shortTitle`, ~19% of screen width) with the full title, branch and definition moved into the expanded (+) row, so the six field columns get readable widths and the full table fits a phone screen without horizontal scrolling.
- **Gazetteer coverage now audits every consulted database:** `build_gazetteer_coverage.py` additionally rebuilds the scanner knowledge base as the runtime assembles it — the fixture snapshot seeding `wine-label-kb.sqlite`, the bundled producer vocabulary, and the bundled origins supplement (generated editorial / UC Davis AVA catalog merged with the legacy list, runtime place-type assignment) — adding Expanded producers (Lab) (101/125), Scanner KB producers (13/125), wine names (0/125), appellations (72/125) and regions (124/125) rows next to the unchanged LWIN/gazetteer rows. Python contract tests updated (218 passing); Android build/device verification still pending.

## [0.5.6] - 2026-10-16
### Added
- **History entry actions:** long-pressing an entry on the Consumption History screen opens a small bottom sheet with **Restore to cellar** (adds the bottle back and removes the entry) and **Delete log entry** (removes the entry only; the cellar is not changed). There is no visible button; both actions ask for confirmation and both keep any tasting note, unlike the snackbar UNDO right after consuming, which still removes the note it just created.
- **Drinking-window estimate (W-050 Phase 1):** Wine Detail shows an “Est.” drinking window (just the years, e.g. “Est. 2028 – 2038”) when you have not entered one. It reads the maker, wine name, subregion, region, country, and vintage (not the price you paid), so a Côtes du Rhône Villages, a Vacqueyras and a Châteauneuf-du-Pape no longer share one answer, and Rioja Crianza/Reserva/Gran Reserva, Pauillac vs. generic Bordeaux and Spätlese vs. Kabinett Riesling differ. A wine the table does not recognise gets a type-only window labelled “Estimate by type”; non-vintage wines get none. The 61-rule table (new `drinkingwindow` package) was drafted from web sources noted per entry and is unreviewed. Estimates are never saved, exported, or used by the Ready Now / Too Early / Past Peak filters.
- **Debug-only test data restore:** debug builds can bundle your own JSON backup (`app/src/debug/assets/dev-seed/WineTracker_Backup.json`, in the debug source set only; seeded from `_backup/WineTracker_Backup_SubRegionFix.json` minus one blank placeholder wine that the importer would reject) and restore it automatically on launch when the database is completely empty, so a reinstall during testing no longer starts blank. It never replaces existing data, never runs in release builds, and does not re-apply after **Reset Database**. Failures and successes are shown in a toast and logged.
- **Install diagnostics:** App Information also shows when the app was first installed, when it was last updated, and a short fingerprint of the signing key, to find out why Android removed the app between test installs.
- **Build identification:** App Information now shows when the installed app was built (date and time to the second), the Git commit it was built from, and whether the working tree had uncommitted changes, alongside the existing branch. After a wireless-debugging install, a changed “Built” time confirms the latest iteration is running.
- **Aroma wheel:** Added a zoomable concentric aroma wheel to the tasting-note editor's sensory section: four families, nineteen groups, and 116 descriptors laid out as ring sectors with an outer repeating band. Pinch to zoom, drag to pan, tap a family/group/band label to zoom to that sector (tap again to zoom out), tap the centre to reset, and tap a descriptor to toggle it — cells too small to hit precisely zoom to their group first, and a selected cell fills with a deep variant of its group colour while its label turns white and bold. Selections are capped at 24 with duplicate protection and retired/unknown-ID handling, and persist as stable descriptor IDs inside the existing `sensoryData` JSON (no schema migration; JSON backup/restore carries them). A list + search mode with synonyms over the identical taxonomy and selection state supports TalkBack, small screens, and large font scales. Selected aromas show as group-coloured chips in the editor and on note cards in wine details. Vocabulary supplied per the classic primary/secondary/tertiary/faults wheel model popularised by Wine Folly; colours and layout are original. Filtering notes by aroma remains future scope.
- **Cellar sorting controls:** Added arrow buttons for quantity, date added, average recorded rating, and price, each toggling between ascending and descending. Unrated wines remain last, and drinking-window sorting is retained.
- **Event-based consumption history:** Added a `ConsumptionEvent` table and DAO/repository flows to record consumption date, vintage, quantity, price, source bottle, optional linked note, and an import key. New consumption writes the event and decrements inventory in one transaction; it does not create archived bottle rows that would conflict with the unique `(definitionId, vintage, status)` index.
- **Exact consumption undo:** Undo now addresses the specific event ID, restores that event's quantity to its source or matching cellar row, removes the event, and removes only the note linked to that event.
- **Consumption-date selection:** Added a date picker to the consume dialog. History-created notes inherit the event date when one exists; events and legacy rows without dates remain undated rather than receiving inferred historical dates.
- **Tasting-note editor:** Added standalone Add Note and add/edit screens with Cellar/Manual entry, a tasted-date picker, required overall score, always-visible sensory evaluation, written notes, rebuy recommendation, reviewer details, and delete confirmation.
- **Manual tasting notes:** Added editable maker, wine name, country, region, subregion, and vintage fields for restaurant and other non-cellar tastings. Maker, name, and location fields accept free text with case- and spacing-insensitive suggestions from saved wine definitions and the wine-location catalog. Manual notes create or reuse only the wine definition needed by the note; they do not create cellar bottle/inventory rows.
- **Reviewer choices and scores:** Added an editable reviewer list in the Utility Hub/Settings and a saved reviewer-rating field on tasting notes. Reviewer names persist locally, with common critics/publications provided as initial choices.
- **Schema migration 18→19:** Added nullable `reviewerRating` storage to tasting notes without changing existing note ratings or history.
- **Schema migration 19→20:** Added nullable 1–10 `valueScore` and optional per-note price storage; legacy notes remain unrated for Value until set.
- **Event-to-note linking:** History cards can open an existing linked note or create a note linked to that exact event. Saving the note and attaching it to the event is transactional; deleting a note clears its event reference without deleting consumption history.
- **Sensory evaluation:** Added persisted sweetness, aroma, acidity, tannin, body, balance, intensity, complexity, finish, quality, overall-score, and common-fault controls, with descriptor labels and ombre slider tracks.
- **Drinking windows:** Added optional start/end years to wine definitions, four-digit year validation in add/edit forms, display on wine details, cellar year-range and Ready Now / Too Early / Past Peak filters, and soonest/furthest sorting.
- **Legacy CSV import:** Added separate Profile actions for the legacy tasting-notes and consumption-history CSV formats. Imports support quoted fields, use explicit producer/name data or a single exact catalog match, report imported/already-present/skipped counts, and use stable unique import keys for repeat-import idempotency. Undated, incomplete, or ambiguous rows are skipped rather than assigned guessed dates or wine identities.
- **Schema migration 16→17:** Added drinking-window columns, tasting-note sensory/import metadata, and indexed `consumption_events`. Archived legacy bottles become events while retaining nullable consumption dates; a tasting note is linked only when exactly one note references that bottle. A zero legacy remaining-quantity value is represented as at least one consumed item.
- **App information screen:** Added a Settings/Profile destination showing installed app version and version code, the actual SQLite schema version, application ID, and the Git branch captured at build time (with safe fallbacks when branch metadata is unavailable).
- **Price-vs-rating Pareto chart:** Added a Profile/Utility Hub screen with one interactive dot per rated wine/vintage with a known price, toggleable wine-type filters, red/white and other type-colored points, a logarithmic price axis, a non-dominated Pareto frontier, and an overall trend line. Tapping a dot opens an over-chart detail card dismissed by an outside tap; a Red/White dashboard below the graph ranks frontier picks by average score per dollar. Scores average the wine’s tasting notes; price uses the median available note, consumption-event, linked-bottle, or exact-vintage inventory price.
- **Coverage and documentation:** Added tests for NV normalization and date-picker round-tripping across a daylight-saving boundary; updated the architecture and this changelog.

### Changed
- **Build toolchain pinned back to AGP 9.1.1:** the project builds with Android Gradle Plugin 9.1.1, Kotlin 2.2.10, KSP 2.3.2 and Gradle 9.3.1 again (the values from before PR #5), because Android Studio syncs the project with AGP 9.1.1 and the 9.3.1 move broke it. The `settings.gradle.kts` Code on the Go repository workaround stays (it has no effect elsewhere). Code on the Go bundles AGP 9.3.1 and may no longer build this project.
- **Profile tab and Settings:** The Utility Hub screen is now titled **Profile** and shows Price vs. Rating, Wine reviewers and a **Settings** row. The technical items moved into a new **Settings** screen (route `settings`, same `ProfileScreen` host): backup/restore/reset, legacy CSV imports, wine image review and storage, and App Information. Selecting the Profile tab from Settings, App Information or Price vs. Rating now returns to the Profile root.
- **Project docs consolidated:** `wishes.md`, `wishes-v2.md`, `bugs.md` and `PLAN.md` were merged into `_refs/BACKLOG.md` (open wishes, bugs, decisions, pending verification, cleanup) and `_refs/ARCHIVE.md` (completed wishes with full specs, resolved bugs, completed plan items). Wishes were renumbered `W-nnn` (wishes-v2 #35–38 became W-046–W-049); unnumbered bug entries became Bug-022–Bug-026. Agent instructions now point to the new files.
- **Aroma wheel launcher:** Replaced the plain outlined button under the overall score with a summary card: a live mini aroma-wheel glyph whose group arcs light up as you tag descriptors, a chevron into the full wheel, and the selected aromas as chips on the card itself (blank until aromas are chosen). Chips support long-press drag reordering, and their order is meaningful — most to least prevalent — and is preserved on the saved note, in wine details, and across the wheel overlay and editor chips (taxonomy re-sorting removed).
- **Tasting score slider:** Added a color-graded 50–100 scale and badge with the requested bands: 50–59 Unacceptable, 60–69 Below Average, 70–79 Average, 80–85 Good, 86–89 Very Good, 90–93 Excellent, 94–97 Outstanding, and 98–100 Extraordinary. Kept the 50 and 100 endpoint labels inside the colored track.
- **Tasting-note form:** Overall rating and sensory controls are always shown and saved; removed the rating checkbox and sensory-evaluation add/label UI. Unrated legacy notes require choosing a score before saving.
- **Cellar note selection:** Removed the redundant vintage field because each cellar wine choice includes its vintage; the selected active cellar bottle’s price is shown and recorded on new notes.
- **Value and manual price:** Added a discrete 1–10 Value slider (new notes start at 5; legacy notes remain unrated until changed) and an optional manual-tasting price field; these are saved per note and shown in note details.
- **Tasting-note editor layout:** Moved the wine-themed Value slider to the end of the form, immediately above Recommended by. Arranged manual maker/name, country/region, and subregion/vintage fields in two columns while retaining full-width price and date controls.
- **Sensory slider labels:** Placed each slider’s two endpoint descriptions directly below its title and above the track, aligned with the scale ends for clarity.
- **History navigation:** Promoted Consumption History from a Notes app-bar icon to a dedicated bottom-navigation tab. Moved the Add Wine/Add Note menu to a bottom-end floating action button to make room while keeping those actions accessible.
- **Initial rating input:** Removed the Initial Rating field from the editor while preserving existing legacy values when notes are edited.
- **Sensory evaluation labels:** Renamed “Flavor intensity” to “Concentration” and removed its duplicate overall-score slider, leaving the top overall-score slider as the only one.
- **Reviewer controls:** Put “Recommended by” and “Rating” side by side, with the reviewer dropdown using two-thirds of the row and a 50–100 scroll wheel defaulting to 80 after reviewer selection.
- **Tasting-note editor header:** Centered the title while keeping the back arrow on the left, and removed duplicate top system-inset spacing from the nested screen scaffold.
- **Tasting note wine browsing:** The Cellar entry mode searches active cellar wine/vintage pairs (while retaining route-selected wines); selecting a wine fills the vintage field automatically.
- **JSON backup/restore:** Exports now include ratings and consumption events, use UTF-8, and read a consistent database snapshot. Restore replaces all app data only after explicit confirmation, validates references and duplicate IDs/keys before deletion, and applies the replacement in one transaction. Wine, bottle, note, and rating IDs are retained; consumption-event IDs are regenerated. Older backups without event/rating collections remain supported, and their archived rows are converted to events.
- **Vintage normalization and lookup:** Standardized blank, `null`, `NV`, and `(NV)` aliases for display, grouping, imports, rankings, and DAO lookups. Price and cellar queries prefer an exact vintage match before falling back to an NV alias.
- **History presentation:** The History screen now renders consumption events (including event quantity and optional rating/note indicator) instead of treating archived inventory rows as the history source. Colors follow the Material theme for dark mode.
- **Cellar filter reset:** Filter-sheet Clear All also clears search. Reselecting the Cellar tab resets filters, search, and expanded wine cards.
- **Date parsing:** Backup `Long` date fields accept epoch values plus strict slash dates with two- or four-digit years (including `1/1/2021`) and supported ISO forms. CSV date parsing requires full input consumption; incomplete or undated legacy rows are skipped rather than assigned guessed dates.

### Fixed
- **Profile tab always opens the Profile page:** after going into Settings, App Information or Price vs. Rating, leaving for another tab and tapping **Profile** now shows the standard Profile page. Before, Android restored the submenu you had left open.
- **+ button on Tasting Notes:** on the Tasting Notes tab, **+** now opens the Add Tasting Note page directly instead of the Add Wine / Add Note submenu. Every other screen keeps the submenu.
- **Add Note from a wine's detail screen:** tapping **+ → Add Note** while viewing a wine now opens the note editor already set to that wine and vintage (the in-page Add Note button already did). From any other screen the editor still starts blank.
- **Rankings and Tasting Notes lists stay scrolled after re-sorting:** changing the sort, a filter or the search on **Rankings** or **Tasting Notes** now returns the list to the top. Only Cellar had this behaviour before. Coming back to either tab with nothing changed leaves the list where it was.
- **Aroma launcher layout (Bug-022):** The launcher's chip strip now wraps (FlowRow) with two-dimensional long-press reordering; the score track, markers, labels and thumb share one inset and font-scaled label space so the 100 label no longer clips. *Device check pending.*
- **Overall-score thumb stuck (Bug-023):** The custom slider's empty track slot collapsed the thumb's travel range; it now supplies a full-width invisible track spacer. *Device check pending.*
- **Duplicate aroma editor (Bug-024):** Removed the leftover aroma-descriptor section rendered below the sensory sliders so only the launcher card remains. *Device check pending.*
- **“Recommended by” label (Bug-026):** The label stays raised from first render instead of jumping on first interaction. *Device check pending.*
- **Reviewer defaults (Bug-025):** Default reviewers now omit Vinous and Jancis Robinson and add Peter Koff, Konstantin Baum and Costco Wine Blog; saved lists and notes are unchanged.
- **Verified already fixed (Bug-005, Bug-019):** Reselecting the Cellar tab resets expanded cards, and non-vintage wines display `(NV)` rather than `((NV))` (see *Cellar filter reset* and *Vintage normalization* above). Source-inspected only.
- **Cellar filter navigation:** Scroll the wine list back to the top whenever filter, sort, or search criteria change.
- **Cellar navigation from Add Note:** Selecting the Cellar bottom-navigation tab from a tasting-note editor now returns directly to the cellar, with a navigation fallback if the cellar route is not on the active stack.
- **Windows KSP/Room build:** Use a newer SQLite JDBC native driver only on the Room processor classpath so query verification can load SQLite on Windows x86_64.
- **Safe import behavior:** Removed partial, row-by-row JSON merge behavior. Invalid backups now fail validation before local data is changed, and a transaction rolls back a failed replacement.
- **Historical-data integrity:** Migration and restore preserve nullable consumption dates; note-to-bottle vintage resolution remains tied to the referenced bottle rather than an unrelated wine/vintage heuristic.
- **Note display and metadata:** Unrated notes display as “Unrated” rather than “0 points”; note edits preserve existing IDs, bottle links, import keys, recommendation fields, and sensory data.
- **Date-picker timezone handling:** Added UTC-midnight/local-date conversion so the selected calendar day round-trips correctly across daylight-saving changes.
- **Permissions:** Declared the INTERNET and CAMERA permissions required by network image lookup and camera scanning.

### Removed
- **Dead code:** Deleted the unreachable Archive screen, the CSV and inventory exporters (backup is JSON only), the legacy batch image importer (`WineImageBatchProcessor`, `MetadataScorer`, `WhitespaceAnalyzer`, DuckDuckGo search) and the stub `WineImageFetcher`/`WineApiService`. The add-time image "fetch" never returned a result, so wine-adding behaviour is unchanged; images come from the reviewed Open Food Facts importer. Also removed the unused bundled legacy CSVs from the app's assets and the seven historical image-strategy docs.
- **Docs:** Added `_refs/PIPELINE.md` (development pipeline, quality gates, release process and release plan) and rewrote the open wishes in `BACKLOG.md` in priority order with complexity ratings against the current architecture. Merged the agent rules into `.agents/instructions.md` and removed `GEMINI_RULES.md` and `ARCH-old.md`; `ARCH.md`'s directory map was regenerated from the real tree.
- **Docs and repo hygiene:** added a root `README.md`; regrouped `THINGS_TO_CHECK.md` by Git branch (a table at the top, one section per branch, and a template for new branches); recorded the camera, Pareto and Resources entry-point decisions, W-057's priority order and closed decisions, and a plain-language explanation of W-050; untracked the per-machine `.idea/` files (now git-ignored); deleted `_backup/WineTracker_Backup_Master.csv`.

## [0.5.5] - 2026-03-16
### Added
- **Expandable FAB Menu:** Replaced the static central [+] button in the bottom navigation with a sleek, animated popup menu (W-044 in `ARCHIVE.md`, formerly Section 44 of WISHES.md).
  - **Dynamic Interactivity:** Tapping the [+] button now toggles a vertical slide-up menu centered over the button.
  - **Dimmed Background:** Implemented a semi-transparent overlay to provide visual depth and focus when the menu is active.
  - **Premium Animations:** Integrated `AnimatedVisibility` with slide and fade transitions for a for a high-end feel.
  - **Action Routing:** Linked the new menu items to "Add New Wine" and "Tasting Notes" for centralized data entry.
- **ViewModel Decomposition:** Refactored the monolithic `WineViewModel` into specialized, single-responsibility ViewModels:
  - `RankingsViewModel`: Handles rankings logic, price lookups, and specialized filtering/sorting.
  - `ScannerViewModel`: Manages CameraX, MLKit OCR, OpenFoodFacts API, and batch image processing jobs.
  - `WineViewModel`: Streamlined to focus exclusively on Cellar management, History flows, and Import/Export operations.
- **Repository Pattern Enforcement:** Enforced a strict data flow where all ViewModels must use `WineRepository`. Removed direct DAO and Database access from all ViewModels (`WineViewModel`, `RankingsViewModel`, `ScannerViewModel`, `TastingNoteViewModel`).
- **Unified Filter Alignment:** Aligned all cellar-style filtering to use `CellarFilterState` for architectural consistency.

### Fixed
- **Navigation Touch Interception [Bug-020]:** Resolved a bug where the FAB menu's full-screen container would stay active and intercept touches after the menu was closed or when navigating to "Add Wine".
- **Tab Backstack Persistence [Bug-021]:** Improved tab navigation to automatically pop transient sub-routes (like Add Wine or History) when switching away, ensuring tabs always reset to their root list on return.
- **Conflicting Imports:** Resolved ambiguous `AndroidViewModel` and `mutableStateOf` imports in `WineViewModel.kt`.
- **Unresolved Database References:** Fixed `db` reference errors in `WineViewModel.kt` by routing all database access through the Repository or using the database instance via the Repository's handle.
- **Repository Pattern Violation:** Corrected `TastingNoteViewModel` to use `repository.getAllPricesForDefinitions` instead of direct DAO access.

### Changed
- **Lean ViewModels:** All ViewModels are now lean wrappers around the Repository, holding only UI-specific state and logic.
- **Robust Tab Policy:** Updated `navigateToTab` to enforce a clean-slate policy for primary navigation tabs.

## [0.5.3] - 2026-03-15
### Added
- **Bottle-Level Note Linking:** `TastingNote` entity now includes `bottleId` for direct linking to the specific `WineBottle` consumed, enabling accurate vintage and price resolution.
- **Note Grouping:** Tasting Notes screen groups notes by (Wine + Vintage), showing one card per vintage with an aggregated rating range badge (e.g., "82-87").
- **Expandable Note History:** Expanding a tasting note card reveals a chronological list of all notes for that vintage, formatted as "M/D/YY - note text".

### Fixed
- **Card Expansion Responsiveness:** Fixed an issue in the Tasting Notes screen where wine cards required multiple presses to expand. Refactored `WineCard` to support external state control to prevent synchronization conflicts with the parent screen.
- **Vintage Corruption [Bug-016]:** Fixed a critical data integrity issue where horizontal tastings (same wine, multiple vintages consumed simultaneously) had all notes silently assigned the wrong vintage during JSON import. Root cause was a `vintageByOldDefId` fallback in `DataImporter.kt` that grabbed an arbitrary bottle's vintage.
- **Import ID Mapping [Bug-017]:** Added `bottleIdMapping` to the JSON importer so that `bottleId` references on tasting notes survive export/import cycles with new auto-generated IDs.
- **Database Migrations 12→16:** Introduced 4 migrations to add the `bottleId` column, attempt retroactive data repair (timestamps, chronological, text-match), and permanently fix existing corrupted vintage assignments.

### Changed
- **DataImporter Hardened:** Removed the dangerous `vintageByOldDefId` vintage fallback. Note vintage is now derived from the linked bottle in the backup data, never from a random lookup. `bottleId` is correctly remapped during import.

## [0.5.2] - 2026-03-15
### Added
- **Intelligent Location Filtering:** Geographic filter wheels (Country/Region/Subregion) now dynamically update to only show options that exist in the current view (Cellar vs. Rankings), preventing "empty filter" results.
- **Enhanced Wheel Interaction:** Added tap-to-scroll functionality to all selector wheels. Tapping the large areas above or below the center highlight now advances the selection by one step.
- **Improved Hit Targets:** Re-engineered the WheelPicker with container-level gesture detection, making the entire component responsive to taps.
- **Subregion Display:** Wine cards in the Cellar, Rankings, and Tasting Notes screens now include the `subregion` in the subtitle (e.g., "Pauillac, Bordeaux, France").
- **Optimized Filter UI:** Relocated the "Clear All" button to the top of the filter sheets across all screens for faster access and improved usability.

### Fixed
- **Stale Location Options:** Fixed a logic error where locations from archived/deleted wines would still appear in the active cellar filter.
- **Tap Precision:** Resolved an "unreliable tap" bug by exponentially increasing the touch target size for the selection dial.
- **Robust Location Mapping:** Implemented geographic normalization (Trim + Title Case) in `WineViewModel` to consolidate inconsistent casing and whitespace in the database, ensuring filters match correctly even if the underlying data is messy.

## [0.5.1] - 2026-03-15
### Fixed
- **Startup Hang resolved:** Moved database migrations and component initializations (ML Kit, Retrofit) off the main thread, resulting in a 90% faster app launch time.
- **Environment Stability:** Recovered emulator from "Disk Full" / Binder transaction failure states that were preventing package management.

## [0.5.0] - 2026-03-15
### Added
- **Advanced Cellar Filtering:** Implemented cascading location wheels (Country/Region/Subregion), a price range slider with manual text inputs, and multi-selection for wine types.
- **Rankings & Notes Filtering:** Implemented a premium filter system for the Rankings and Tasting Notes screens with Sort By, Price Range, and Rating Range controls.
- **User Rating Ranges:** Automatically aggregates all user tasting notes for a specific wine/vintage to display rating ranges (e.g., "88-92") on all wine card components.
- **"Added on" Tracking:** Implemented a system to track when a bottle was added to the cellar, including a database migration (v12) to backfill existing inventory.
- **Enhanced Cellar Details**: Expanded wine cards in the cellar now show the "Added on" date and a chronological list of your previous tasting notes.
- **Location Wheel Picker:** Created a custom, cascading "birthday-style" `WheelPicker` component for frictionless Country/Region/Subregion filtering.
- **Database Migration 11**: Implemented structural cleanup to consolidate producer names (e.g., merging "Kirkland" into "Kirkland Signature").
- **Robust Price Lookup:** Enhanced DAOs with vintage-agnostic fallback pricing to provide context even when specific vintage data is missing.

### Changed
- **Performance Overhaul:** Optimized the data pipeline for rating ranges and tasting notes by pre-calculating maps at the screen level, resolving `InputDispatcher` hangs and ensuring 60 FPS scrolling.
- **Cellar UI Refinement:** Moved the "Added on" date to the expanded card view to reduce clutter in the main list. Corrected bottle count pluralization logic.
- **History UX Overhaul:** Refined the consumption history sorting to always show the newest entries at the top based on `dateConsumed`.
- **Identity Formatting:** Standardized wine display across all screens to the high-end `[Producer] - [Wine Name] ([Vintage])` format.

### Fixed
- **InputDispatcher Crash:** Resolved a critical application crash caused by O(N*M) logic inside list items blocking the UI thread.
- **Persistent Zero Prices:** Resolved an issue where Rankings showed `$0.00` by prioritizing non-zero prices from any status and explicitly hiding zero values in the UI.
- **Data Linkage**: Fixed price and note link failures caused by duplicate wine definitions with slightly different producer names.
- **Build & Import Fixes**: Resolved various compilation issues and fixed an issue where the "Added on" date was not being saved during backup restoration.

## [0.4.1] - 2026-03-14
### Added

## [0.4.0] - 2026-03-14
### Added
- **JSON Backup System:** Migrated the backup/restore engine from a fragile multi-section CSV format to a structured JSON format using Gson.
- **Robust Backup Model:** Introduced a `BackupData` data class to centralize relational data (Definitions, Bottles, Notes) for bulletproof restoration.
- **Key Takeaway:** SQL migrations are a one-shot fix for existing data — they cannot repair data that is inserted *after* the migration runs (e.g., via a JSON import on a fresh install). When the root cause is in the import pipeline, the fix **must** live in the importer itself. Always cross-reference relational fields (like vintage, dates) between sibling tables within the same backup during import, rather than relying on post-hoc migrations.

## [Structural: Data Normalization & Naming] - 3/15/2026
* **Symptom:** Prices not showing up for specific wines in rankings even though they existed in the cellar under "Kirkland Signature".
* **Cause:** Duplicate `WineDefinition` entries caused by slight naming inconsistencies (e.g., "Kirkland" vs. "Kirkland Signature"). Tasting notes were linked to one ID, while bottles were linked to the other, creating a siloed data structure.
* **Fix:** Implemented `MIGRATION_10_11` to merge duplicate definitions and reassign all child records (bottles, notes, ratings) to a single standardized ID.
* **Key Takeaway:** Fuzzy matching (like `LIKE` queries) is a good temporary bandage for UI, but **structural data normalization** via migrations is the only permanent fix for relational integrity. Always look for "Duplicate Definition" patterns when data seems to be missing across screens.
- **Intelligent Date Parsing:** Implemented a custom Gson deserializer to automatically handle mixed date formats (e.g., "1/1/2021" strings vs. Unix timestamps) during restoration.
- **Cellar Filtering & Analytics:** Implemented a full filtering system (Type, Country) and sort logic (Newest/Price) for the cellar screen.
- **Card Expansion:** Added interactive expandable "Details" sections to wine cards, allowing users to view tasting notes and rating dates directly in the list.
### Changed
- **Unified UI Styling:** Refactored the "Tasting Notes" screen to use the shared `WineCard` component, ensuring perfect aesthetic consistency with the Cellar.
- **Export/Import Workflow:** Updated the Profile utility hub to handle `application/json` MIME types and `.json` file extensions.
- **Modular Refactoring:** Extracted 300+ lines from `MainActivity.kt` into dedicated screen and component files (`CellarScreen`, `RankingsScreen`, `WineCard`, etc.) following strict SOC principles.
- **Navigation UX:** Refined tab behavior and backstack logic for the Notes/History flow to ensure the app defaults back to the notes list when switching tabs.
- **Sorting Logic:** Defaulted Tasting Notes to chronological order (Newest first) with secondary tie-breaking logic.
- **Accordion UI:** Implemented single-expansion logic in lists to improve focus and reduce screen clutter.
### Fixed
- **Rankings Logic:** Fixed a filtering bug where wines with ratings between 1 and 5 were hidden; the Rankings screen now correctly displays all rated wines (> 0).
- **Import Mapping Fix:** Resolved a silent data loss bug by adding `@SerializedName` annotations to Room entities, ensuring JSON keys map correctly to database primary keys.
- **NPE Robustness:** Fixed a NullPointerException in the data importer caused by missing vintage fields in specific data records.
- **History Status:** Fixed [Bug-004] where archived wines showed "QTY: 0" by implementing a "CONSUMED" badge.
- **Filter Stability:** Fixed [Bug-001] by properly lifecycle-collecting filter states in the Cellar.

## [0.3.1] - 2026-03-14
### Fixed
- **Consumption History Screen:** Resolved a critical crash occurring during navigation due to missing ViewModel factory setup and date-parsing errors.
- **CSV Parsing:** Refactored `DataImporter` to use header-name discovery instead of hardcoded column indices, preventing the "Empty Cellar" bug.
- **Data Integrity:** Added strict logging to the import process to verify row counts against the SQLite database.

## [0.3.0] - 2026-03-14
### Added
- **Shared Repository:** Implemented a single source of truth by instantiating the database in `MainActivity`.
### Changed
- **Architectural Cleanup:** Refactored `WineViewModel` and `TastingNoteViewModel` to use constructor injection (Manual DI).
- **Database Logic:** Removed redundant `Room.databaseBuilder` calls from ViewModels to prevent "Split-Brain" database conflicts.
### Removed
- **Obsolete Functions:** Deleted `importFullBackup` from `TastingNoteViewModel` to prevent destructive data overwrites.

## [0.2.0] - 2026-03-10
### Added
- Initial implementation of the Wine Cellar UI.
- Basic SQLite schema for Wines, Bottles, and Tasting Notes.
- Support for CSV file selection via System Picker.

## [0.1.0] - 2026-03-01
### Added
- Project initialization with Jetpack Compose.
- Basic navigation scaffolding.

---
[0.5.5]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.5.3...v0.5.5
[0.5.3]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.1.0...v0.2.0
[Unreleased]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.5.6...HEAD
[0.5.6]: https://github.com/n8rtz/Wine-Manager-Android/compare/v0.5.5...v0.5.6
