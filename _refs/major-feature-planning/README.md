# Major Feature Planning

Planning briefs for larger Wine Manager features. These documents describe proposed scope and decisions to make; they are not implementation commitments or a substitute for validating data sources, provider terms, or the current app architecture.

## WBS numbering convention

Each feature brief has its own numbered root: **1.0** Pareto screen, **2.0** aroma wheel, **3.0** bottle/label image importer, **4.0** camera recognition, and **5.0** resources page. Within a brief, `N.x` identifies an architecture workstream/component, `N.x.x` a task, and `N.x.x.x` a subtask. Here, `N` is that feature's root number. IDs organize scope; they do not imply priority, sequencing, ownership, or estimates.

## Feature briefs

- [Pareto screen](pareto-screen.md) — price/rating analytics and frontier picks.
- [Wine aroma wheel](wine-aroma-wheel.md) — interactive aroma exploration tied to tasting notes.
- [Wine bottle / label image importer](wine-bottle-label-importer.md) — find, review, and associate bottle or label images.
- [Camera wine recognition](camera-wine-recognition.md) — scan a label and prefill a wine form for user review.
- [Wine resources page](wine-resources-page.md) — educational material, interactive region maps, and grape-variety tasting profiles.

## Cross-feature boundaries

- **Image importer vs. camera recognition:** The importer finds or brings in an image for a known wine record. The camera flow identifies information from a newly scanned label and proposes form values. They may share catalog matching or image storage later, but should remain separate user workflows.
- **Aroma wheel vs. resources:** The wheel is an interactive note-taking control. The resources page explains aromas, grapes, regions, and styles; it should not silently change a tasting note.
- **Analytics vs. records:** Pareto calculations are derived from existing notes and price data. A visualization must not create or repair cellar, tasting, or consumption records.

## Shared planning guardrails

1. Confirm the destination, data contract, and ownership of any persistent data before changing the Room schema.
2. Keep uncertain matches reviewable; never silently overwrite user-entered wine identity or image choices.
3. Treat external data and image sources as unapproved until licensing, attribution, reliability, and terms of use are checked.
4. Provide loading, empty, error, permission-denied, and accessibility states as applicable.
5. Make network use explicit and cancellable; keep personal cellar and note data local unless a separately approved feature requires otherwise.
6. Validate behavior in Android Studio on a device or emulator before considering a feature complete.
