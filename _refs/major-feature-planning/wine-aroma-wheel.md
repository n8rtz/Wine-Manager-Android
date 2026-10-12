# Feature Plan: Wine Aroma Wheel

## Goal

Give tasters a visual way to explore and record aroma descriptors without replacing the app's written note or existing sensory controls.

## Architecture-Level Work Breakdown Structure — 2.0

### 2.1 Product vocabulary and interaction contract

#### 2.1.1 Select and govern the aroma vocabulary
- **2.1.1.1** Review candidate wine-aroma vocabularies, scope, source, licensing, and attribution requirements before adopting descriptors.
- **2.1.1.2** Define a versioned hierarchy of families, groups, and leaf descriptors with stable IDs independent of displayed text.
- **2.1.1.3** Define localization, synonyms, and migration behavior for renamed or retired descriptors; do not silently map distinct aromas together.

#### 2.1.2 Specify selection behavior
- **2.1.2.1** Confirm multi-select behavior, maximum/duplicate rules if any, and how a user removes or clears a descriptor.
- **2.1.2.2** Specify family-to-descriptor navigation, breadcrumbs/back, Done/Cancel, and restoration when an existing note is edited.
- **2.1.2.3** Define the non-wheel list/search alternative with the same taxonomy and selection contract.

### 2.2 Domain and persistence architecture

#### 2.2.1 Define the tasting-note data contract
- **2.2.1.1** Inspect the current note entity, repository, backup/restore, import/export, and detail flows before choosing storage.
- **2.2.1.2** Decide between a normalized descriptor relation and another representation based on multi-select needs, queryability, and migration cost.
- **2.2.1.3** Specify schema versioning and a migration that leaves existing notes valid and empty of inferred aroma descriptors.

#### 2.2.2 Implement selection and persistence rules
- **2.2.2.1** Keep selected stable IDs in the editor draft until the existing Save action commits the note.
- **2.2.2.2** Ensure cancel, edit, delete, backup restore, and legacy-note behavior are deterministic.
- **2.2.2.3** Preserve separation from overall rating, value score, written prose, and cellar inventory; aroma selection must not create a bottle.

### 2.3 UI component architecture

#### 2.3.1 Prototype the wheel presentation
- **2.3.1.1** Compare radial Compose drawing/layout approaches and test label fit, touch precision, and recomposition cost before choosing an implementation.
- **2.3.1.2** Make hierarchy and selected state legible without relying on color alone; expose labels and semantic actions to accessibility services.
- **2.3.1.3** Decide when to switch to drill-down or list mode for narrow screens and larger font scales.

#### 2.3.2 Build the list/search alternative
- **2.3.2.1** Reuse the exact taxonomy and selection state used by the radial view.
- **2.3.2.2** Support keyboard/search navigation and clear selection feedback without network access.

#### 2.3.3 Integrate with the tasting-note editor
- **2.3.3.1** Add the entry point to the aroma/sensory section and return selected descriptors as editor state.
- **2.3.3.2** Show selected descriptors as removable chips and restore them when editing a saved note.
- **2.3.3.3** Keep the existing written-notes and sensory controls available and unaffected when the wheel is unused.

### 2.4 Verification and release

#### 2.4.1 Verify taxonomy and persistence
- **2.4.1.1** Add unit tests for hierarchy lookup, stable IDs, selection toggling, duplicate handling, and taxonomy-version compatibility.
- **2.4.1.2** Test create/edit/cancel/delete and backup/restore without changing unrelated note or inventory data.

#### 2.4.2 Verify interaction and accessibility
- **2.4.2.1** Test wheel/list parity, Back navigation, TalkBack labels, touch targets, large fonts, themes, and offline use.
- **2.4.2.2** Review the final vocabulary and attribution with an appropriate wine-content reviewer before release.

## Proposed user flow

1. Open the wheel from the tasting-note editor's aroma/sensory section.
2. Browse broad aroma families, then drill into narrower groups and descriptors. Use a breadcrumb/back action so the user always knows the current level.
3. Select one or more descriptors; show them as removable chips in the editor and provide Clear/Done actions.
4. On reopening an edited note, restore its saved selections. The wheel remains optional.

## Information architecture

- Use a data-driven, versioned taxonomy rather than hard-coded labels in drawing code.
- Start with broad families such as fruit, floral, herbal/vegetal, spice, earthy/mineral, and oak/other; confirm the final categories and descriptor vocabulary before implementation.
- Support a list/search alternative to the radial visualization. The list must expose the same hierarchy and selection behavior for accessibility and small screens.
- Use visual color as a cue only; every segment must also have a readable text label and accessible semantics.

## Data and integration plan

- Decide how selected descriptors are stored on a tasting note before changing the database. Prefer stable descriptor IDs and a versioned taxonomy so wording can evolve without losing old notes.
- Keep aroma selection separate from overall rating, value score, written notes, and cellar inventory. Adding or editing aroma descriptors must not create a bottle.
- Include selected descriptors in backup/restore and note detail display if they become persisted fields.
- Avoid requiring an internet connection for the core wheel; taxonomy updates can be considered separately.

## Open decisions

- Which aroma vocabulary and source are appropriate, and what attribution is required?
- Should selection be single-level or multi-select across branches? The initial proposal is multi-select.
- Should the wheel be a true concentric graphic on all screen sizes, or switch to a drill-down/list layout on narrow screens?
- Where should selected aromas appear in note detail and export formats?

## Acceptance checks

- Users can navigate from family to descriptor and back without losing prior selections.
- Multiple descriptors can be selected, removed, and restored when editing a note.
- Wheel and list alternatives have equivalent behavior and TalkBack labels.
- The control works at common font scales, in light/dark themes, and without network access.
- Existing notes, ratings, and inventory remain unchanged when the wheel is unused.
