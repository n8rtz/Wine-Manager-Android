# Implementation Plan: Structured Tasting Notes (W-061)

> **Status: planning only (logged 2026-11-05).** No app code, Room migration, dependency, permission, network use, background work or UI change is authorized by this request. The owner asked to record the design alternatives as open decisions, not to choose or implement them.

## Goal

Improve the tasting-note function so a note records **what the wine looked like and how its aromas and palate changed over the tasting**, rather than one flat list of sliders and aroma chips. Keep existing notes, stable aroma IDs, inventory and consumption records intact.

## Owner request (2026-11-05)

1. Add a **wine color picker**. The owner suggested a **2D color-picker plot like photo-editing programs** as a possibility, not a settled design.
2. Stop lumping all aroma-wheel descriptors together. Separate them by **where in the tasting they appear**: **Aroma (before tasting)**, **Entry**, **Mid-palate**, **Finish**.
3. Differentiate **stronger / more pronounced aromas** from subtler ones. Two alternatives were offered:
   * **Pinch to resize aroma chips** — bigger means more pronounced, forming a word cloud.
   * **Modulate chip color** — darker means more pronounced, lighter means more subtle.
4. Separate the tasting note into **Visual / aroma**, **Entry**, **Mid-palate**, and **Finish** sections. **This includes the existing sliders.** Add sliders for **Entry (Tight, Open)**.

## Current shape (verified against source, 2026-11-05)

* `ui/screens/TastingNoteEditorScreen.kt` is one vertically scrolling `Column`: wine identity/date fields, `OverallScoreSlider`, `AromaWheelLauncherCard`, `SensoryEvaluationFields`, free-form notes, and score/reviewer controls.
* `ui/components/SensoryEvaluationFields.kt` renders ten flat trait sliders — **Sweetness, Aroma / bouquet, Acidity, Tannins / grip, Body, Balance, Concentration, Complexity, Finish, Quality** — followed by **Common faults**. There are no tasting-phase section headers and no wine-color control.
* `data/local/WineDatabase.kt` defines `SensoryData` with ten `Int` traits, `flaws: List<String>`, `aromas: List<String>`, and `overallScore`. A Gson `TypeConverter` stores it in the opaque `tasting_notes.sensoryData` JSON column; its legacy float-model parser normalizes missing lists. `JsonExporter` serializes whole entities with Gson, so additive `SensoryData` fields can travel in existing JSON backups without a Room schema change.
* `aromawheel/AromaTaxonomy.kt` exposes stable descriptor IDs and `MAX_SELECTED_DESCRIPTORS = 24`; `AromaSelection` enforces the cap. `ui/components/AromaWheelDialog.kt` hosts the wheel/list picker and removable chips. `ui/components/AromaChipDrag.kt` contains pure drag-reorder maths for chips of uneven widths, and current chip order is described as most-to-least prevalent.
* `ui/screens/WineDetailScreen.kt` renders saved aroma IDs as one flat `FlowRow` of chips. W-053 (filter tasting notes by aroma) is still open in `BACKLOG.md`.

## Decisions to close before implementation

The owner explicitly left the design alternatives open. Each item below needs an owner decision; the “recommended default” is a planning suggestion only.

| ID | Open decision | Options / recommended default |
| --- | --- | --- |
| 6.D1 | Color-picker presentation | 2D hue/saturation-style plot with a separate lightness/value control (owner's example), curated wine-color swatches, or both. **Recommended default:** prototype the 2D plot plus a tappable swatch/numeric fallback for accessibility; no new color-picker dependency without approval. |
| 6.D2 | Color value and scope | Store ARGB, HSV components, or a named palette ID; record color per tasting note only, or also on `WineDefinition`. **Recommended default:** one nullable color per note, versioned in `SensoryData`; do not infer a color for old notes. |
| 6.D3 | Aroma-phase model | Four phase buckets; whether one descriptor may appear in several phases; whether the 24-descriptor cap is per note or per phase. **Recommended default:** allow the same stable ID in different phases, keep one cap across the note, and never derive phase from taxonomy family. |
| 6.D4 | Legacy aroma mapping | Existing `aromas` have no phase. **Recommended default:** preserve them as explicitly unphased in legacy notes; do not silently assign them to “Aroma (before tasting)” (rules 14–16, `ARCH.md` §5, `LESSONS_LEARNED.md`). Owner must choose the visible treatment or approve an explicit migration. |
| 6.D5 | Intensity representation | Pinch-to-resize word cloud, darker/lighter chip shading, or both. **Recommended default:** choose after a small interactive prototype; if color is used, provide a non-color cue and a non-pinch control for TalkBack, large fonts and small screens. |
| 6.D6 | Intensity data and meaning | Discrete levels vs continuous value; intensity per descriptor, per phase, or both; relation to the existing prevalence chip order. **Recommended default:** one explicit discrete intensity per aroma observation, independent of chip order; keep current drag-to-reorder behavior until the owner decides whether it stays. |
| 6.D7 | Section slider mapping | Which of the ten existing trait sliders belongs in each phase section. **Recommended default:** the candidate mapping below; owner confirms. `OverallScoreSlider`, common faults and written notes stay outside the phase sections unless the owner says otherwise. |
| 6.D8 | “Entry (Tight, Open)” shape | Two independent **Tight** and **Open** sliders, or one bipolar **Tight ↔ Open** slider. **Recommended default:** leave open; both readings of the request must be presented to the owner before coding. |
| 6.D9 | Section layout | One long scroll with four short headers, collapsible sections, or tabs/steps. **Recommended default:** one scroll with four short section labels; use only short labels/values, no explanatory prose (`.agents/instructions.md` rule 18). |

### Candidate slider/section mapping (not approved)

| Section | Existing controls | New controls / aroma bucket |
| --- | --- | --- |
| **Visual / aroma** | Aroma / bouquet; Complexity (owner to confirm whether this means aroma complexity) | Wine color picker; **Aroma (before tasting)** descriptors and intensity |
| **Entry** | Sweetness (candidate; owner to confirm) | **Tight** and **Open** sliders; **Entry** descriptors and intensity |
| **Mid-palate** | Acidity; Tannins / grip; Body; Balance; Concentration | **Mid-palate** descriptors and intensity |
| **Finish** | Finish; Quality (candidate; owner to confirm) | **Finish** descriptors and intensity |

Keep `OverallScoreSlider`, **Common faults**, **Tasting notes and impressions**, reviewer/recommend/value controls, and save/delete behavior outside the four sections unless the owner explicitly includes them.

## Architecture work breakdown (WBS 6.0)

### 6.1 Data contract and compatibility

- **6.1.1** Define a versioned representation for wine color, phase-aware aroma observations (`stable descriptor ID`, `phase`, `intensity`, `order`), and new entry slider values. Keep stable IDs from `AromaTaxonomy`; never derive IDs from labels.
- **6.1.2** Extend `SensoryData` and its Gson converter carefully. Normalize nullable/missing fields after Gson parsing. Preserve unknown/retired IDs rather than dropping them. No silent phase or intensity inference for legacy notes.
- **6.1.3** Verify JSON backup → restore round trips, including notes with no color, no phase, no intensity and the old flat `aromas` list. Confirm no Room migration is required for additive JSON fields; if a schema change is proposed later, follow rules 14–16, `ARCH.md` §5 and `LESSONS_LEARNED.md`.
- **6.1.4** Decide whether the existing ordered `aromas` list remains as a compatibility view, or whether note detail, wheel launcher, chips and W-053 filtering all consume the new observation model. Do not leave two competing sources of truth.

### 6.2 Visual / aroma controls

- **6.2.1** Build the chosen color picker with a readable current-color swatch and an accessibility fallback. Keep the stored color nullable for old notes.
- **6.2.2** Add four phase buckets — **Aroma (before tasting)**, **Entry**, **Mid-palate**, **Finish** — to the aroma-wheel launcher, wheel/list picker and chips without changing the 116-descriptor taxonomy or its group colors.
- **6.2.3** Implement the chosen intensity interaction. If chips are resized, check `aromaChipDragTarget`'s width-dependent maths and wrapping behavior. If chips are darkened, check contrast against existing group colors and provide a non-color cue.
- **6.2.4** Supply a non-gesture intensity control and clear semantics for TalkBack, large font scales and small screens.

### 6.3 Sectioned note editor

- **6.3.1** Group the ten existing `SensoryEvaluationFields` sliders into the four confirmed sections; add the confirmed **Entry (Tight, Open)** slider shape.
- **6.3.2** Place the color picker, phase-specific aroma chips and relevant sliders inside their sections; keep the editor's existing save/cancel state flow.
- **6.3.3** Use short section labels only. Do not add explanatory paragraphs, helper text, captions, “how it works” prose or longer reworded labels (rule 18).

### 6.4 Note display, filtering and boundaries

- **6.4.1** Render saved note details grouped by the four sections, showing color, aromas by phase, intensity, and the mapped sliders. Preserve legacy/unphased notes without inventing values.
- **6.4.2** Define W-053 compatibility: search across all phases by default, with optional phase-specific filtering once W-053 is implemented. Do not silently change the open W-053 scope.
- **6.4.3** No new permissions, dependencies, network calls, background work, notification, or change to inventory/consumption semantics. A tasting-note edit must not create, move or delete bottles or consumption events.
- **6.4.4** Keep the wheel as the descriptor-selection control; this plan owns phase, intensity and section presentation.

### 6.5 Verification (when implementation is authorized)

- **6.5.1** JVM tests: `SensoryData` JSON round trip and legacy float-JSON parser; missing/null color, phase and intensity; stable-ID preservation; phase assignment; intensity normalization; chip-drag maths with variable chip sizes.
- **6.5.2** Editor tests: create/edit/cancel/save; all ten existing sliders remain editable; new Tight/Open controls persist; color and intensity survive rotation/process recreation; no unrequested prose.
- **6.5.3** Accessibility/device checks: pinch alternative, TalkBack labels, large fonts, dark/light theme, narrow screens, 2D color plot hit targets, grouped note detail, and JSON backup → restore on a clean install.
- **6.5.4** Android build, unit tests and device/emulator validation per `PIPELINE.md`, plus a `THINGS_TO_CHECK.md` checklist when the feature is implemented. This plan itself has no build or device verification.

## Acceptance checks (future implementation)

* A selected wine color is saved, displayed and restored; notes without a color remain explicitly unset.
* Aroma descriptors are grouped into the four phases, and existing flat aroma IDs are preserved without invented phases.
* The chosen intensity representation makes pronounced vs subtle aromas distinguishable, is adjustable, survives save/restore, and has an accessible alternative.
* The editor has **Visual / aroma**, **Entry**, **Mid-palate**, **Finish** sections containing all existing sensory sliders, plus the confirmed **Tight / Open** entry controls.
* Overall score, faults, written notes, wine identity, inventory, consumption history and W-057 backup destinations are otherwise unchanged.
* No new in-app explanatory prose, permissions, dependencies, network use or background work appears.
