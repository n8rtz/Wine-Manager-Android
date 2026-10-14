# Feature Plan: Wine Aroma Wheel

## Goal

Give tasters a visual way to explore and record aroma descriptors on a tasting note, without replacing the app's written notes or existing sensory controls.

## Status: agreed design (2026-10)

The original brief left the presentation, vocabulary, and display surface open. After review, the following decisions are locked:

| Decision | Choice |
| --- | --- |
| Presentation | **Drill-down radial wheel** — one ring of large labeled sectors at a time (branch → family → descriptor). Not a full concentric multi-ring wheel; those fail on label fit and touch precision at phone sizes. |
| Vocabulary | **Wine Folly's published "Updated Wine Flavor Wheel with 100+ Flavors"** (https://winefolly.com/tips/wine-aroma-wheel-100-flavors/), restructured as 4 branches → 19 families → 112 leaf descriptors. Attribution required (see below). |
| Selection | Multi-select across branches. Selected descriptor **IDs** are held in the editor draft and committed by the existing Save action. |
| Storage | `SensoryData.aromas: List<String>` — stable hierarchical descriptor IDs serialized inside the existing JSON TypeConverter. No Room migration; old JSON parses with an empty list. |
| Display | Aroma chips in the note editor's sensory section, plus read-only chips on the note cards in `WineDetailScreen`. Filtering notes by aroma is explicitly future scope. |
| Accessibility | The radial view is visual/touch; a **list mode** inside the same dialog exposes the identical taxonomy and selection contract for TalkBack, small screens, and large font scales. |

## Architecture work breakdown

### 1. Taxonomy (`data/aroma/AromaTaxonomy.kt`)

- Pure-Kotlin, versioned tree: `AromaNode(id, label, color, children)`; leaves are selectable descriptors.
- Stable hierarchical IDs (`primary/spice/black-pepper`) independent of displayed text; renaming a label must never change an ID.
- Lookup helpers: `find(id)`, `labelFor(id)`, `pathFor(id)` (breadcrumb chain), `leavesUnder(node)`, selected-descendant counting.
- `AromaSelection.toggle/remove` pure helpers shared by wheel, list mode, and chips.
- Colors are plain ARGB longs so the module stays JVM-testable.
- Attribution constant `SOURCE_NOTE` naming Wine Folly as the vocabulary source; shown in the picker footer.

### 2. Persistence

- Add `aromas: List<String> = emptyList()` to `SensoryData` (`WineDatabase.kt`).
- Normalize nullable list fields after Gson parsing (Gson bypasses Kotlin defaults on legacy rows) and parse `aromas` in the legacy float-model fallback parser.
- No schema change: the column stores opaque JSON text; backup/restore (entity-level Gson) carries the field automatically.

### 3. Wheel UI (`ui/components/AromaWheelDialog.kt`)

- Full-screen dialog: header (back/cancel, selected count, list-mode toggle, Done), breadcrumb row, radial picker, selected-chips row, attribution caption.
- Radial picker: one `Canvas` ring of pie segments per level, with a center hub that navigates up one level. Tap behavior: branch → drill, family → drill, leaf → toggle selection. Selected leaves draw a check and border; families containing selected descendants show a marker dot.
- Labels are Compose `Text` nodes rotated onto each bisector (flipped on the lower half for readability), sized by segment count, ellipsized at two lines; the list mode is the fallback for long names.
- Hit-testing and ARGB shading/contrast math live in pure-Kotlin `AromaWheelGeometry` for unit testing.
- List mode: search field + branch → family → checkbox rows; same selection set; families containing saved selections start expanded.

### 4. Editor integration (`ui/components/SensoryEvaluationFields.kt`)

- New "Aroma descriptors" section between the trait sliders and Common faults: header with selected count, Clear, and "Browse wheel" opening the dialog; removable chips for current selections.
- Selections live in `SensoryData` editor state and commit with the existing Save button; Cancel/back discards. Aroma selection must not create or modify bottles.

### 5. Note detail display (`ui/screens/WineDetailScreen.kt`)

- Note cards resolve stored IDs through the taxonomy and render small chips; unknown IDs (future vocabulary changes) render as raw text so nothing disappears.

### 6. Verification

- JVM unit tests: taxonomy integrity (unique IDs, path consistency, family counts, total = 112, colors present, lookups), selection helpers, wheel geometry/angle math, shading contrast helpers, and `SensoryData` JSON round-trip including legacy JSON without `aromas`.
- Manual checks (device/emulator, per repo process): wheel drill/back parity with list mode, TalkBack in list mode, font-scale 1.0–2.0, light/dark themes, create/edit/cancel/delete of notes, backup/restore round-trip.

## Future (explicitly out of scope now)

- Filtering the tasting-notes list by aroma.
- Taxonomy localization/synonyms; vocabulary growth beyond the Wine Folly set.
- Exporting aromas in CSV (JSON backup already includes them).

## Acceptance checks

- Users can navigate branch → family → descriptor and back without losing prior selections.
- Multiple descriptors across branches can be selected, removed chip-by-chip or cleared, and are restored when editing a saved note.
- Wheel and list modes share one selection state and one taxonomy.
- Works offline; no network permission or data use.
- Existing notes, ratings, and inventory are untouched when the wheel is unused; legacy sensory JSON parses unchanged with an empty aroma list.

## Attribution

Descriptor vocabulary adapted from Wine Folly's "Updated Wine Flavor Wheel with 100+ Flavors" page (© Wine Folly, winefolly.com). Aroma terms themselves are common wine-education vocabulary; the app displays the source note inside the picker. If this app is ever distributed publicly, re-confirm Wine Folly's licensing stance for derivative works before shipping.
