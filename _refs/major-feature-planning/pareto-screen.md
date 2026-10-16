# Feature Plan: Pareto Screen

## Goal

Help a user compare a wine's recorded average tasting score against its available bottle price, find efficient price/rating trade-offs, and inspect the wine represented by a chart point.

**Related backlog:** `_refs/ARCHIVE.md`, W-048 and W-049 (formerly wishes-v2 items 37–38; completed).

## Architecture-Level Work Breakdown Structure — 1.0

### 1.1 Product and interaction contract

#### 1.1.1 Confirm plotted-point and price semantics
- **1.1.1.1** Specify the wine/vintage identity key and vintage normalization used to group a point.
- **1.1.1.2** Document rating aggregation, valid-rating handling, and whether any display-range clamping is intentional.
- **1.1.1.3** Confirm price-source precedence, positive-price validation, currency assumptions, and the behavior when price is unavailable; never synthesize a price.

#### 1.1.2 Confirm the dashboard's definition of “best value”
- **1.1.2.1** Review the current average-rating-to-price ratio with the product owner and record it as a heuristic or choose a replacement metric.
- **1.1.2.2** Define tie-breaking, number of leaders, and how the selected metric is explained in the UI.
- **1.1.2.3** Preserve separate Red and White candidate sets and frontiers; define behavior when a category has no eligible wines.

#### 1.1.3 Confirm navigation and selection behavior
- **1.1.3.1** Retain the Profile entry point until a separate main-navigation decision is made.
- **1.1.3.2** Specify point-tap hit radius, overlapping-point behavior, outside-tap dismissal, Back dismissal, and filter-change selection reset.
- **1.1.3.3** Define loading, no-data, no-price, and no-filter-match states.

### 1.2 Data and domain layer

#### 1.2.1 Map existing source flows to chart inputs
- **1.2.1.1** Trace tasting-note, consumption-event, linked-bottle, and exact-vintage inventory prices to their existing repository/view-model flows.
- **1.2.1.2** Verify aggregation deduplicates only by the intended wine/vintage identity and does not infer historical dates or repair source records.
- **1.2.1.3** Keep absent/invalid data explicit so the screen can omit or explain it without altering persisted records.

#### 1.2.2 Isolate and verify chart calculations
- **1.2.2.1** Keep point aggregation, log-price scaling, trend fitting, and Pareto dominance calculations in pure/testable functions; extract them from the screen only where that improves maintainability.
- **1.2.2.2** Define deterministic behavior for ties, duplicate coordinates, one-point datasets, and non-finite inputs.
- **1.2.2.3** Calculate the overall frontier and the Red-only and White-only frontiers from the same filtered point set using independently testable results.

#### 1.2.3 Define screen-state ownership
- **1.2.3.1** Keep filter and selected-point state at the screen/view-model layer; avoid persisting transient chart interaction state.
- **1.2.3.2** Ensure updated source flows and filter changes cannot leave a stale point selected.

### 1.3 Presentation architecture

#### 1.3.1 Decompose the screen into cohesive UI sections
- **1.3.1.1** Keep type filters, scatter plot, axis/legend/explanation, selected-point card, and Red/White dashboard separable for previews and tests.
- **1.3.1.2** Keep chart drawing independent from dashboard ranking and detail-card content.

#### 1.3.2 Implement the selected-wine overlay
- **1.3.2.1** Position the compact card relative to the selected point and constrain it to the chart/viewport where possible.
- **1.3.2.2** Show maker, wine, vintage, type, plotted average rating, plotted price, tasting count, and accurately labeled frontier status.
- **1.3.2.3** Route outside-tap and Back dismissal through one selection-clearing action; test interaction when another dot is selected.

#### 1.3.3 Implement the type-specific dashboard
- **1.3.3.1** Present the Red and White frontier summaries as distinct, visually identifiable categories.
- **1.3.3.2** Display each leader's identifying details, rating, price, rank, and remaining-frontier count without implying unrecorded facts.
- **1.3.3.3** Explain the ranking rule and provide an empty state for filtered or absent categories.

#### 1.3.4 Meet responsive and accessibility needs
- **1.3.4.1** Verify small-screen sizing, large font scales, theme contrast, screen-reader descriptions, and usable touch targets.
- **1.3.4.2** Ensure chart meaning is available through text and not conveyed by color alone.

### 1.4 App integration

#### 1.4.1 Integrate with the existing Profile route
- **1.4.1.1** Confirm route arguments, back navigation, and state cleanup when entering or leaving the screen.
- **1.4.1.2** Do not move the feature to primary navigation without a separate decision.

### 1.5 Verification and release

#### 1.5.1 Verify domain calculations
- **1.5.1.1** Add unit tests for normalization, price precedence/median, rating aggregation, log-scale boundaries, trend edge cases, frontier dominance/ties, and score-per-price ranking.

#### 1.5.2 Verify Compose interactions and layout
- **1.5.2.1** Test dot selection, popup dismissal, filter changes, empty states, and Red/White category independence.
- **1.5.2.2** Run device/emulator checks for light/dark themes, larger fonts, and representative small and large datasets.

#### 1.5.3 Confirm data integrity
- **1.5.3.1** Verify chart viewing and filtering never write or delete tasting notes, consumption events, or cellar bottles.

## Current working-tree baseline

The screen is reachable from the Profile area for now; its eventual main-navigation location is undecided. The current implementation includes:

- A point per rated wine/vintage with a known positive price; the plotted rating is the average of its tasting-note scores and the plotted price is the median available price from the supported note, consumption-event, linked-bottle, or exact-vintage inventory data.
- Wine-type filters, a logarithmic price axis, the overall Pareto frontier, and a price/rating trend line when enough points exist.
- A compact selected-wine card over the graph, dismissed by tapping outside it.
- A Red and White dashboard below the chart, each based on that type's own frontier. The displayed leaders are currently ranked by average rating divided by price; treat that ranking as a transparent heuristic, not an objectively universal definition of value.

## Screen structure

1. **Context and filters:** Explain that higher ratings and lower prices indicate stronger value. Keep type filters easy to reach and make clearing/restoring filters predictable.
2. **Chart:** Keep axes, logarithmic-price labeling, wine-type legend, frontier, and optional trend line legible on phone-sized screens.
3. **Point details:** Show producer/maker, wine, vintage, type, plotted average rating, plotted price, and tasting count. Keep the card inside the chart bounds where possible; outside taps and Back should dismiss it.
4. **Red/White dashboard:** Show a small number of frontier leaders per type with score and price, plus the frontier count and an explicit empty state when a type has no eligible wines.

## State and data responsibilities

- Keep chart selection and type filters as screen state; recompute derived point/frontier data from source flows.
- Keep the all-type frontier separate from the Red-only and White-only frontiers. Label a point's overall-frontier status accurately.
- Keep price provenance and rating aggregation visible in explanatory copy. Missing prices remain omitted rather than guessed.
- Do not create, archive, or modify cellar bottles, tasting notes, or consumption events from this analytics screen.

## Decisions to confirm

- Is average-rating-per-price the desired dashboard ranking, or should the dashboard show the complete frontier without collapsing it to a ranked list?
- Should dashboard leader counts or price-source explanations be expandable, especially when several records contribute to a median?
- Keep the Profile entry until a separate navigation decision is made; do not relocate it as part of this feature plan.

**Resolved 2026-10-16 (owner):** keep the average-score-per-dollar ranking and the Profile entry exactly as built; revisit only after the device check. The expandable-detail question is left open.

## Acceptance checks

- A tap on a point opens the matching wine/vintage details; a tap outside or Back dismisses the card.
- Filters update the chart and type-specific dashboards consistently, and clearing filters does not leave a stale selection.
- The two category dashboards use independent Red and White frontiers and communicate their ranking rule.
- Empty, one-point, tied-point, and missing-price cases render without misleading values or crashes.
- On-device layout remains readable in light/dark themes and at larger font scales.
