# Implementation Framework: Structured Tasting Notes (W-061)

> **Status: decisions resolved 2026-11-05; Source implementation written 2026-11-06; Android build/device verification pending.** Every prior open decision is closed below. The implementation adds the Kotlin source and exactly one Room v22 migration, with no new permissions, dependencies, network calls or background work. The agent sandbox has no JDK/Android SDK, so `:app:assembleDebug`, JVM/Compose/device checks have not been run here.

## Goal

Improve the tasting-note function so a note records **what the wine looked like and how its aromas and palate changed over the tasting**, rather than one flat list of sliders and aroma chips. Keep existing notes, stable aroma IDs, inventory and consumption records intact.

## Owner request (2026-11-05)

1. Add a **wine color picker**. The owner suggested a **2D color-picker plot like photo-editing programs** as a possibility, not a settled design.
2. Stop lumping all aroma-wheel descriptors together. Separate them by **where in the tasting they appear**: **Aroma**, **Entry**, **Mid-palate**, **Finish**.
3. Differentiate **stronger / more pronounced aromas** from subtler ones. Two alternatives were offered:
   * **Pinch to resize aroma chips** — bigger means more pronounced, forming a word cloud.
   * **Modulate chip color** — darker means more pronounced, lighter means more subtle.
4. Separate the tasting note into **Visual / aroma**, **Entry**, **Mid-palate**, and **Finish** sections. **This includes the existing sliders.** Add sliders for **Entry (Tight, Open)**.

## Current shape (verified against source, 2026-11-06)

* `ui/screens/TastingNoteEditorScreen.kt` is one vertically scrolling `Column`: wine identity/date fields, `OverallScoreSlider`, `AromaWheelLauncherCard`, `SensoryEvaluationFields`, free-form notes, and score/reviewer controls.
* `ui/components/SensoryEvaluationFields.kt` renders ten flat trait sliders — **Sweetness, Aroma / bouquet, Acidity, Tannins / grip, Body, Balance, Concentration, Complexity, Finish, Quality** — followed by **Common faults**. There are no tasting-phase section headers and no wine-color control.
* `data/local/WineDatabase.kt` defines `SensoryData` with ten `Int` traits, `flaws: List<String>`, `aromas: List<String>`, and `overallScore`. A Gson `TypeConverter` stores it in the opaque `tasting_notes.sensoryData` JSON column; its legacy float-model parser normalizes missing lists. `JsonExporter` serializes whole entities with Gson, so additive `SensoryData` fields can travel in existing JSON backups without a Room schema change.
* `aromawheel/AromaTaxonomy.kt` exposes stable descriptor IDs and `MAX_SELECTED_DESCRIPTORS = 24`; `AromaSelection` enforces the cap. `ui/components/AromaWheelDialog.kt` hosts the wheel/list picker. `ui/components/PhaseAromaCloud.kt` lays out pinch-sized labels in a wrapping cluster and sorts the most intense aromas first.
* `ui/screens/TastingNoteDetailScreen.kt` is the read-only saved-note detail route; the Wine Detail screen has been replaced by an edit-only Wine Edit route. W-053 (filter tasting notes by aroma) is still open in `BACKLOG.md`.
* `WineDatabase.kt` is Room schema **v21** today; `WineDefinition` has no color field. `MainActivity.kt` registers migrations through `MIGRATION_20_21`; `BackupData`/`JsonExporter` serialize whole entities with Gson and `DataImporter` restores them with definition-ID mapping.

## Decision log (closed 2026-11-05)

The owner resolved every design alternative before this framework was written. The W-061 backlog status changes from **Needs decision** to **Open**; no implementation is authorized yet.

| ID | Decision | Resolved value |
| --- | --- | --- |
| 6.D1 | Color-picker presentation | **Interactive HSV editor from the swatch**: a hue strip restricted to the wine-tone arc 330°→0°→60° plus saturation/value plane, without numeric H/S/V boxes. Use icon-only X and checkmark actions. |
| 6.D2a | Color format | **HSV components** (`hue`, `saturation`, `value`), not ARGB or a named palette. |
| 6.D2b | Color scope | **WineDefinition default + per-note override**. Effective color is the note override when present, otherwise the definition default. Clearing the override reverts to the definition default. |
| 6.D13 | Where the definition default lives | **Room column + migration v22**: add a nullable `defaultColorHsv` column to `wine_definitions`. No data backfill or inference. |
| 6.D3 | Aroma-phase model | **Same descriptor may appear in several phases**; one cap of **24 distinct descriptor IDs per note**. Duplicate `(descriptorId, phase)` pairs are rejected. The cap counts IDs, not observation rows. |
| 6.D4 | Legacy flat aromas | **Prompt on edit**: before saving changes to a note that still has legacy flat aromas, show a phase-assignment sheet; Save is disabled until every legacy descriptor is assigned to one or more phases or removed. A descriptor may be assigned to multiple phases. No phase is preselected or inferred. Read-only display of untouched legacy notes remains valid. |
| 6.D5 | Intensity interaction | **Pinch-sized aroma cluster**: pinch labels to resize them; show no numeric badges or stepper, and sort the largest labels first. |
| 6.D6 | Intensity storage | **Continuous `0f..1f` per descriptor-phase pair** (overrides the earlier 1–5 answer). Pinch continuously adjusts intensity; the word cloud derives visible order from intensity, with `displayOrder` as a stable tie-breaker. |
| 6.D8 | Entry Tight/Open controls | **One bipolar scale titled `Entry` with `Tight`/`Open` endpoints**, stored as one value; no two-slider or derived-score variant. |
| 6.D7 | Existing slider mapping | **Candidate mapping with Complexity moved to Mid-palate**. See the fixed control map below. |
| 6.D7b | Global controls | **Overall score, Common faults, written notes, reviewer/recommend/value and save/delete stay outside** the four phase sections. |
| 6.D9 | Section layout | **Single vertical scroll with four short headers**. No collapsible sections, tabs or wizard steps. |
| 6.D10 | Data compatibility | **Observations only + derived flat view**: store phase-aware observations; expose the old flat `aromas` list as a read-only compatibility view, never as a second writable source of truth. |
| 6.D11 | W-053 aroma filter | **Search all phases by default**, with an optional phase filter once W-053 ships. |
| 6.D14 | Phase assignment UI | **Active phase selector in the aroma wheel/list picker**; descriptors selected while a phase is active are assigned to that phase. Wheel and list modes share the same contract. |

### UI refinements (owner, 2026-11-06)

* The swatch opens the HSV editor. Its hue track covers the wine-tone arc from 330° through red at 0° to 60°; saturation and value remain adjustable. Clearing a note override returns it to the wine default.
* Aroma intensity is controlled by pinching each label. The cloud is a wrapping cluster with no numeric badges, steppers or drag handles, and the most intense/largest labels sort to the top.

## Fixed product behavior

### Color

* `WineDefinition.defaultColorHsv` is editable from Add Wine and the separate Edit Wine screen using the same compact picker. Short labels only.
* The tasting-note editor shows the resolved color; tapping the swatch opens the HSV editor with the restricted wine-tone hue arc. A clear action removes the nullable note override and returns to the definition color.
* Legacy notes and definitions have no color until the user sets one. No color is inferred from wine type, vintage, image or name.
* The picker uses the selected HSV interaction: its hue track wraps from 330° through red at 0° to 60° (burgundy/ruby through amber/straw). Green, cyan and blue hues are outside the selectable track; saturation and value remain adjustable.

### Aromas and intensity

* Four stable phases: `AROMA_BEFORE_TASTING`, `ENTRY`, `MID_PALATE`, `FINISH`, displayed as **Aroma**, **Entry**, **Mid-palate**, **Finish**.
* The aroma wheel/list picker has an active-phase selector. Selecting a descriptor creates an observation in that phase; the same descriptor may be selected again in another phase.
* `AromaSelection.MAX_SELECTED_DESCRIPTORS = 24` becomes a limit on distinct descriptor IDs per note. Unknown/retired stored IDs are preserved and displayed as raw labels; the wheel never adds unknown IDs.
* Each observation has an independent continuous intensity in `0f..1f`. `null` means unrated and renders at neutral size with no numeric badge. Pinching continuously adjusts the value, starting from neutral intensity when it was unset.
* Aroma labels sit in a wrapping cluster and use font size as the intensity cue. The cloud sorts by intensity descending, with stored `displayOrder` as a tie-breaker; there are no stepper or drag-reorder controls.
* Existing group colors from `AromaWheelTheme` are unchanged; intensity is **not** encoded by darkening chips.
* W-053 filtering matches descriptor IDs across all phases by default. An optional phase predicate narrows results; legacy unphased notes appear only in the all-phase mode.

### Sections and global controls

* One `verticalScroll` `Column` with four short headers: **Visual / Aroma**, **Entry**, **Mid-palate**, **Finish**.
* The ten existing `SensoryEvaluationFields` sliders are placed inside those sections as fixed below. One bipolar **Entry** scale with **Tight** and **Open** endpoints is added.
* `OverallScoreSlider`, **Common faults**, **Tasting notes and impressions**, reviewer/recommend/value controls and save/delete remain outside the four sections.
* No explanatory paragraphs, helper text, captions, “how it works” notes or longer reworded labels are added (`.agents/instructions.md` rule 18).

## Data model contract (implemented in source)

These Kotlin shapes are implemented in `TastingNoteModels.kt`, `TastingNoteStructure.kt`, `WineDatabase.kt` and the W-061 UI components; the snippets below remain a compact contract view.

```kotlin
enum class TastingPhase { AROMA_BEFORE_TASTING, ENTRY, MID_PALATE, FINISH }

data class HsvColor(
    val hue: Float,        // 0f..360f
    val saturation: Float, // 0f..1f
    val value: Float       // 0f..1f
)

data class AromaObservation(
    val descriptorId: String,      // stable AromaTaxonomy ID
    val phase: TastingPhase,
    val intensity: Float? = null,  // null = unrated; otherwise 0f..1f
    val displayOrder: Int          // persistence order and stable tie-breaker for cloud sorting
)

data class SensoryData(
    /* existing ten Int traits, flaws, overallScore remain unchanged */,
    val colorHsv: HsvColor? = null,                 // note override; null = inherit
    val entryTightOpen: Float = 0.5f,               // 0f = Tight, 1f = Open
    val aromaObservations: List<AromaObservation> = emptyList(),
    val aromas: List<String> = emptyList()          // derived, read-only compatibility view
)

data class WineDefinition(
    /* existing fields remain unchanged */,
    val defaultColorHsv: HsvColor? = null
)
```

### Normalization and validation rules

1. `HsvColor`: `hue` in `0f..360f`, `saturation` and `value` in `0f..1f`; reject out-of-range values rather than clamping silently.
2. `entryTightOpen`: `0f..1f`; `0f` displays **Tight**, `1f` displays **Open**, `0.5f` is the neutral default.
3. `AromaObservation.intensity`: `null` or `0f..1f`; reject values outside the range.
4. At most **24 distinct `descriptorId` values** per note, regardless of phase count.
5. No duplicate `(descriptorId, phase)` pair. Reordering changes `displayOrder` only.
6. Preserve unknown/retired descriptor IDs on read; never drop them from storage. The wheel may add only active IDs.
7. `aromas` is derived as unique descriptor IDs in first-appearance/display order. It is never written by UI code and is never a second source of truth.
8. Effective color is `sensoryData.colorHsv ?: definition.defaultColorHsv`. No fallback to wine type, image, vintage or name.
9. Legacy JSON with only flat `aromas` parses to `aromaObservations = emptyList()` and `colorHsv = null`. Missing lists become empty lists because Gson bypasses Kotlin defaults.
10. Legacy float-model sensory JSON keeps its existing parser path and gains the same empty defaults. No phase, color or intensity is inferred.

## Persistence and Room migration framework

* `SensoryData` additions stay inside the existing opaque `tasting_notes.sensoryData` JSON column, so they need **no Room migration**. `Converters.toSensoryData` must normalize the new fields and extend the legacy float-JSON parser.
* `WineDefinition.defaultColorHsv` needs **schema v22**:
  1. Re-read `ARCH.md` §5, `LESSONS_LEARNED.md` and rules 14–16 before writing the migration.
  2. Inspect real data rather than assuming values; add `MIGRATION_21_22` with `ALTER TABLE wine_definitions ADD COLUMN defaultColorHsv TEXT` and a `HsvColor` Room `TypeConverter`. Use `NULL` for all existing rows. No backfill.
  3. Bump `@Database(version = 22)` and register `MIGRATION_21_22` in `MainActivity.kt`'s `addMigrations(...)` chain.
  4. Verify `BackupData`/`JsonExporter` include the new entity field through Gson and `DataImporter` restores it with the existing definition-ID mapping; older backups without the field stay `NULL`.
  5. Update `ARCH.md` directory/schema notes when the implementation files are created (rule 12).
* JSON backup → restore must round-trip: definition default, note override, cleared override, four phases, repeated descriptor IDs, continuous intensities, `null` intensity, display order and legacy flat aromas.
* No change to `WineBottle`, `ConsumptionEvent`, `Rating`, `WineImageAsset`, W-057 backup destinations, permissions, network calls or background work.

## UI component framework

Proposed future files are listed for planning only; **none are created by this task**.

| Path | Role |
| --- | --- |
| `data/local/TastingNoteModels.kt` (new) | Standalone `TastingPhase`, `HsvColor`, and `AromaObservation` value types referenced by Room entities/converters; kept independent of reducers to avoid a source dependency cycle in KSP. |
| `data/local/TastingNoteStructure.kt` (new) | Pure-Kotlin normalization/validation helpers and reducer functions; JVM-testable. |
| `ui/components/WineColorPicker.kt` (new) | HSV editor opened from the color swatch: wine-tone hue arc (330°→0°→60°) and saturation/value plane, no numeric H/S/V boxes, icon-only X/checkmark actions at the lower-right, a compact untitled popup with comfortable padding, and a Bordeaux glass preview with a broad rounded bowl. The floating preview stays clear of the touch point and keeps a fixed Y on the hue strip. Note color can be cleared back to the wine default. |
| `ui/components/PhaseAromaCloud.kt` (new) | Wrapping, intensity-sized label cluster; pinch gesture and largest-first sorting; no numeric badges, stepper or drag handles. |
| `ui/components/TastingPhaseSections.kt` (new) | Four short section headers and section-aware composition of sliders, color picker, aroma cloud and chips. |
| `ui/components/LegacyAromaPhaseSheet.kt` (new) | Blocking phase-assignment sheet shown on first edit of a legacy note; Save disabled until all legacy descriptors are assigned or removed. |
| `WineDatabase.kt` | `SensoryData` additions, `HsvColor` converter, `WineDefinition` field, version 22, `MIGRATION_21_22`, legacy JSON normalization. |
| `MainActivity.kt` | Register the v22 migration. |
| `BackupData.kt`, `JsonExporter.kt`, `DataImporter.kt` | Confirm entity-level Gson serialization, ID mapping and older-backup compatibility. |
| `TastingNoteEditorScreen.kt` | Single-scroll four-section layout; effective color state; legacy prompt; active-phase wheel; save/cancel flow. |
| `SensoryEvaluationFields.kt` | Reuse `SensoryTraitSlider`/`GradientSensorySlider`; expose ten existing traits as section-aware composables and add the Entry scale with Tight/Open endpoints. |
| `AromaWheelDialog.kt`, `AromaWheelLauncherCard` | Live mini-wheel and selected-aroma chip summary, active-phase selector, phase-grouped wheel/list modes, 24-distinct-ID cap and repeated-ID rules. |
| `AromaChipDrag.kt` | Keep pure width-dependent drag maths; test with variable chip widths caused by intensity scaling. |
| `AddWineScreen.kt`, `WineEditScreen.kt`, `TastingNoteDetailScreen.kt` | Compact definition-default color control in wine-definition editing; read-only saved-note detail in its distinct route. |
| `WineRepository.kt`, `TastingNoteViewModel.kt`, `TastingNotesScreen.kt` | Persist/load the new fields, expose effective color and phase-aware observations, and reserve the W-053 all-phase + optional phase filter contract. |

## State and event contracts

Keep these as pure functions so the data rules can be JVM-tested without Compose:

```kotlin
fun normalizeSensoryData(raw: SensoryData?): SensoryData
fun resolveEffectiveColor(noteColor: HsvColor?, definitionColor: HsvColor?): HsvColor?
fun groupByPhase(observations: List<AromaObservation>): Map<TastingPhase, List<AromaObservation>>
fun addAromaObservation(state: SensoryData, descriptorId: String, phase: TastingPhase): Result<SensoryData>
fun setAromaIntensity(state: SensoryData, key: Pair<String, TastingPhase>, intensity: Float?): SensoryData
fun reorderAromaChips(state: SensoryData, phase: TastingPhase, from: Int, to: Int): SensoryData
fun toFlatAromaIds(observations: List<AromaObservation>): List<String>
fun validateForSave(state: SensoryData): List<ValidationError>
```

Editor state sequence:

1. Load note and definition; compute effective color.
2. If `aromaObservations` is empty but legacy `aromas` is not, open the phase-assignment sheet on first edit. Cancel leaves the note unchanged; Save remains disabled until every legacy ID is explicitly assigned to one or more phases or removed.
3. Wheel/list picker starts on the active phase. Adding a descriptor calls `addAromaObservation`; removing deletes that observation.
4. Pinch calls `setAromaIntensity`; the cluster re-sorts by intensity after each change.
5. Save calls `validateForSave`, then persists the existing `TastingNote` entity. No bottle or consumption row is created, moved or deleted.

## Fixed section/control map

| Section | Controls |
| --- | --- |
| **Visual / Aroma** | Wine color swatch opening an HSV editor, **Aroma** slider, **Aroma** pinch-sized aroma cloud |
| **Entry** | **Entry** scale (**Tight** to **Open**), **Sweetness** slider, **Entry** aroma cloud and chips |
| **Mid-palate** | **Acidity**, **Tannins / grip**, **Body**, **Balance**, **Concentration**, **Complexity** sliders, **Mid-palate** aroma cloud and chips |
| **Finish** | **Finish**, **Quality** sliders, **Finish** aroma cloud and chips |
| **Outside the four sections** | Overall score, Common faults, Tasting notes and impressions, reviewer/recommend/value, save/delete |

All section headers and control labels stay short. The phase labels are also short; no in-app explanatory prose is added.

## WBS 6.0 — implementation phases

| Phase | Scope | Exit gate |
| --- | --- | --- |
| **6.0 Decisions + framework** | Resolve all owner decisions; expand this brief into the detailed framework; change W-061 to `Open`. | **Complete (framework task, doc-only).** Source implementation is written below. |
| **6.1 Pure data contract** | Add `TastingNoteModels.kt` for Room-referenced `HsvColor`/`AromaObservation`/phase types and `TastingNoteStructure.kt` for reducers, normalization, validation and JVM tests. Keep the model file independent of reducer code to avoid a KSP source cycle. | Legacy JSON, new JSON, cap, duplicate-key, intensity and color validation tests pass. No UI yet. |
| **6.2 Room v22 + backup** | `WineDefinition.defaultColorHsv`, `MIGRATION_21_22`, `MainActivity` registration, Gson converter, backup/restore checks. | Migration adds nullable column without backfill; old and new backups restore; no data loss. |
| **6.3 Color UI** | `WineColorPicker`, definition-default controls in Add Wine and Wine Edit, note color in the editor, accessibility fallback. | Color persists through save/edit/backup; legacy rows stay unset; TalkBack and large-font checks pass. |
| **6.4 Phase-aware aromas + word cloud** | Active-phase wheel/list picker, phase grouping, 24-ID cap, repeated IDs, pinch-sized clustered labels, intensity-descending sort and legacy phase sheet. | Wheel/list parity, phase assignment, intensity save/restore and no silent legacy inference verified. |
| **6.5 Sectioned editor + detail** | Four section headers, fixed slider mapping, Entry scale with Tight/Open endpoints, global controls outside sections, grouped saved-note display. | All ten existing sliders remain editable; Entry scale persists; no unrequested prose; note detail grouped by phase. |
| **6.6 W-053 contract + docs** | All-phase search with optional phase filter, `ARCH.md` map update, `THINGS_TO_CHECK.md` checklist, `CHANGELOG.md` entry. | W-053 integration contract documented; docs and host checks pass. |
| **6.7 Android verification** | `:app:assembleDebug`, `:app:testDebugUnitTest`, Compose checks, device/emulator checklist. | Real Android build and device run pass per `PIPELINE.md`; only then can W-061 move to `ARCHIVE.md`. |

## Test framework

JVM `TastingNoteStructureTest` and host `test_w061_structured_notes.py` are written; Android/Compose/device checks remain pending.

| Layer | Tests |
| --- | --- |
| JVM pure logic | `HsvColor` ranges; `entryTightOpen` range; `AromaObservation` normalization; 24-distinct-ID cap; duplicate `(id, phase)` rejection; unknown-ID preservation; `toFlatAromaIds`; pinch intensity math; largest-first sort with stable ties; pure reducer transitions. |
| Gson/Room | `SensoryData` JSON round trip; legacy float-JSON parser; missing/null fields; `HsvColor` TypeConverter; `MIGRATION_21_22` column with `NULL` old rows; backup → restore on a clean install. |
| Compose / UI | HSV color editor (wine-tone hue arc 330°→0°→60°, saturation/value plane, no numeric boxes, icon-only X/checkmark actions) and inheritance choice; active-phase wheel/list parity; pinch-only word-cloud sizing and largest-first sorting; large font/dark theme/narrow screen; legacy phase sheet Save-disabled state; four section headers and global controls placement. |
| Regression | Existing overall score, faults, written notes, wine identity, inventory and consumption history unchanged; W-053 all-phase filter; no new prose; no new permission/dependency/network/background work. |
| Device / emulator | `THINGS_TO_CHECK.md` checklist, TalkBack, font scale 1.0–2.0, rotation/process recreation, backup → restore, real Android build. Not run for this documentation-only task. |

## Traceability matrix

| Owner request | Decisions | Data / UI | Tests |
| --- | --- | --- | --- |
| 1. Color picker | 6.D1, 6.D2a, 6.D2b, 6.D13 | `HsvColor`, `WineDefinition.defaultColorHsv`, `SensoryData.colorHsv`, swatch-anchored `WineColorPicker`, v22 migration | Restricted wine-tone hue arc, HSV controls, default inheritance, migration and backup round trip |
| 2. Aromas by tasting phase | 6.D3, 6.D4, 6.D14 | `TastingPhase`, `AromaObservation`, active-phase wheel, legacy phase sheet | Cap, duplicate key, legacy prompt, wheel/list parity, phase grouping |
| 3. Pronounced vs subtle aromas | 6.D5, 6.D6 | Continuous intensity, pinch-sized cluster, intensity-descending display order | Pinch math, largest-first sorting, no numeric label or stepper, no color-only cue |
| 4. Four sections + existing sliders + Entry Tight/Open | 6.D7, 6.D7b, 6.D8, 6.D9 | `TastingPhaseSections`, fixed control map, `entryTightOpen`, single-scroll layout | Slider persistence, all ten traits editable, no prose, note-detail grouping |

## Risk register

| Risk | Mitigation |
| --- | --- |
| Two writable aroma representations | Observations are the only source; `aromas` is derived read-only. |
| Silent inference for legacy notes | Blocking phase-assignment sheet; no default phase, color or intensity. |
| Gson bypassing Kotlin defaults | Explicit normalization of every nullable/missing list and new field in `Converters`. |
| Room migration data loss | Inspect data first, add nullable column only, no backfill, register migration, backup/restore round trip. |
| Pinch-only intensity excludes users | Provide TalkBack labels and keep each aroma label accessible in the wheel/list picker. |
| Uneven label sizes make the cloud hard to scan | Flow labels across the available width, sort largest-first, and verify small screens/large fonts. |
| Group-color confusion | Keep `AromaWheelTheme` colors unchanged; intensity uses size only. |
| W-053 regression | Search all phases by default; phase filter optional; legacy unphased notes remain visible in all-phase mode. |
| Unrequested in-app prose | Short labels/values only; rule 18 applies to every new composable. |

## Guardrails and non-goals

* No new permissions, dependencies, network calls, notifications or background work.
* No change to inventory, quantities, consumption events, ratings, image assets or W-057 backup destinations.
* No Room schema change beyond the explicitly authorized v22 `defaultColorHsv` column; no data backfill.
* No CSV/ZIP export, bundled image bytes, live sync or OCR/scanner changes.
* No new in-app explanatory prose, helper text, captions or “how it works” notes.
* **Framework task was documentation only; the implementation task wrote Kotlin source and exactly one Room v22 migration.** No Android build or device verification was run in the agent sandbox.

## Definition of Done (future implementation)

* A wine-definition default color and a per-note override can be set, cleared and restored; legacy rows remain unset.
* Aroma descriptors are assigned to the four phases through an active-phase wheel/list picker, with the same stable ID allowed in multiple phases and the 24-distinct-ID cap enforced.
* Legacy flat aromas require explicit phase assignment on edit; no phase is inferred.
* Pronounced vs subtle aromas are represented by pinch-sized labels in a wrapping cluster; no numeric intensity badges or stepper are shown, and the cloud auto-sorts largest-first.
* The editor has one scroll with four short section headers containing all ten existing sensory sliders plus one Entry scale with Tight/Open endpoints; global controls remain outside.
* Saved-note details group aromas, color, intensity and sliders by section; W-053 can search all phases with an optional phase filter.
* JSON backup → restore, Room v22 migration, JVM tests, Compose checks, `ARCH.md`, `CHANGELOG.md`, `THINGS_TO_CHECK.md` and a real Android/device run all pass per `PIPELINE.md`; JVM/host source tests are written, Android/Compose/device verification remains pending in the agent sandbox.
* No unrequested prose, new permissions, new dependencies, network use, background work, or changes to inventory/consumption records.
