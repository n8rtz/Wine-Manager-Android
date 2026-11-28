# Cellar and wine-card UI refresh

## Goals

This refresh implements the first visual and organizational improvements requested for the cellar:

- Replace the repeated generic bottle/glass artwork with a cleaner, text-first wine row.
- Use a slim wine-type color rail (red, white, rosé, sparkling, or neutral) instead of a redundant `Red` / `White` label.
- Keep producer, cuvée, vintage, origin, quantity, and rating readable at a glance. Wine photos, when a real user-selected image exists, appear only after expanding a card.
- Put a passive drinking-window reference in the expanded cellar card, without adding an interactive slider or unnecessary controls.
- Add **Bottles** and **Racks** tabs. Racks organize inventory by purpose and are shown as photo-led album tiles when bottle imagery is available.
- Automatically put wines with neither a tasting-note rating nor note text in **New additions** until the wine has useful tasting feedback.

## Interaction and visual rules

### Shared wine cards

`WineCard` is shared by Cellar, Tasting Notes, and Rankings, so its compact redesign is consistent across those lists. The compact state has a type stripe and text metadata only; it does not load or display a generic placeholder image. A real approved image is optional and appears in the expanded state. Ratings remain visible as compact metadata.

### Cellar tabs and racks

- **Bottles** retains the inventory dashboard, search, filtering, sorting, and bottle rows.
- **Racks** opens a set of purpose-based rack tiles: **New additions**, **Long-term aging**, **Daily drinkers**, and **Special occasions**.
- Tapping a tile opens its contents. In an expanded bottle card, a rated/noted wine can be assigned to one of the three user-purpose racks or returned to **No rack**.
- **New additions** is derived from the tasting-note data, not a user assignment: a bottle remains there while its wine/vintage has no note with a positive rating or nonblank note text. Once feedback exists, it leaves that automatic rack unless it has been assigned to a purpose rack.
- Rack assignments are currently device-local preferences keyed by bottle ID. They are not part of JSON backup/restore and do not synchronize across devices; a future schema-backed rack feature should address portability and user-created rack names.
- Album tiles use the first available real bottle photo as a cover. If no photo exists, a restrained color field and initial are used rather than another generic wine illustration.

### Aroma preview and tasting-note viewing

When a wine has tasting-note aroma descriptors, the expanded cellar/rack card previews the most recent note that contains aromas as compact taxonomy-colored chips, with that note's date. When notes exist, a single **Tasting notes** action replaces the inline list of note text. It opens the existing Notes tab focused on that wine/vintage, clearing any active search and filters so the target note group is visible.

When a wine/vintage has multiple tasting notes, expanding its group in the Notes screen presents one tab per tasting date. Selecting a tab shows that note's text, score, aromas, and associated metadata; tapping the note content opens it for editing. A single-note group needs no tabs.

### Drinking-window reference

The expanded cellar card shows a compact, noninteractive three-part color scale with the current year marker and start/end years. A manually entered complete window takes precedence. Otherwise, the existing offline `DrinkingWindowEstimator` supplies an estimate when it can match a vintage and profile. Wines without a complete window show no scale. The scale is an at-a-glance reference, not a promise of readiness and not an editable control.

## Tasting-note editor cleanup

Removed the duplicate **Common faults** quick-chip section from the tasting-note editor; fault descriptors are selected in the Aroma Wheel's **Faults & Other** family. The old `SensoryData.flaws` field remains in the stored model solely for backward-compatible reads of existing notes and backups; it is no longer edited by the form.

## Manual QA checklist

1. Open Cellar, Notes, and Rankings. Confirm compact cards have no generic bottle art and no repeated Red/White type text; the left rail distinguishes wine type.
2. Expand a card with an approved image and a card without one. Confirm the image is only shown when available and the no-image case stays clean.
3. Expand a cellar wine with a complete manual drinking window. Confirm the years and marker fit compactly; verify the current year marker does not extend beyond the ends. Repeat with an estimator-matched vintage and with a non-vintage/unmatched wine.
4. Switch between **Bottles** and **Racks**. Verify bottle filters remain available only on Bottles, racks show bottle counts, and opening/backing out of a rack works.
5. Confirm unrated wines with no tasting text appear in **New additions**. Add a rating or nonblank tasting note and verify it leaves the automatic rack.
6. Assign a reviewed wine to Long-term aging, Daily drinkers, and Special occasions. Confirm it appears only in its selected rack, survives app restart, and can be returned to No rack.
7. In an expanded cellar/rack card, verify the most recent note with aromas is previewed as chips and that one **Tasting notes** button opens Notes focused on the wine/vintage. Confirm active Notes search/filters are cleared so the target group is visible.
8. Expand a Notes group with several entries, select each tasting-date tab, and confirm its text, score, aromas, and edit action match the selected note. Confirm a single-note group has no unnecessary tabs.
9. Verify empty inventory, empty purpose racks, narrow screens, and rows with long producer/cuvée names.
