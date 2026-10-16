# Feature Plan: Wine Resources Page

## Goal

Provide a navigable, sourced learning area for wine regions, grape varieties, tasting vocabulary, and related basics. Keep educational content distinct from a user's personal tasting notes.

## Architecture-Level Work Breakdown Structure — 5.0

### 5.1 Product scope and content governance

#### 5.1.1 Define audience, navigation, and launch scope
- **5.1.1.1** Identify the primary learning journeys and initial region/grape coverage; keep later coverage extensible.
- **5.1.1.2** Decide the entry point (Profile/Utility Hub, dedicated destination, or contextual links) without assuming a navigation change.
- **5.1.1.3** Define editorial review, update cadence, correction workflow, and source attribution required for each content item.

#### 5.1.2 Select permitted reference and map sources
- **5.1.2.1** Evaluate content and geographic datasets for accuracy, licensing, attribution, offline use, and redistribution terms.
- **5.1.2.2** Separate regulatory/appellation facts from editorial summaries and record the source and review date for both.
- **5.1.2.3** Define how gaps, conflicting sources, translations, and regional naming variants are represented.

### 5.2 Content and domain architecture

#### 5.2.1 Define stable content models
- **5.2.1.1** Model region, subregion, boundary/map reference, grape variety, profile, source citation, and cross-link with stable IDs.
- **5.2.1.2** Keep claims, attribution, last-reviewed date, and applicable region/variety relationships traceable to their source records.
- **5.2.1.3** Define content schema versioning and localization/alternate-name fields without requiring all languages at launch.

#### 5.2.2 Define shared browse/search contracts
- **5.2.2.1** Specify search and browse behavior across region, grape variety, and topic, including no-result and partial/offline data cases.
- **5.2.2.2** Define links between region pages, grape profiles, glossary terms, and the Aroma Wheel without mutating user notes.

### 5.3 Interactive wine-region map architecture

#### 5.3.1 Select map rendering and geographic data
- **5.3.1.1** Choose a licensed boundary/map source and rendering approach only after attribution, network, storage, and offline constraints are settled.
- **5.3.1.2** Define region/subregion geometry, tap hit-testing, zoom/pan limits, selection state, and graceful behavior when detailed geometry is unavailable.
- **5.3.1.3** Keep map rendering behind a component/data boundary so the source or rendering implementation can change independently.

#### 5.3.2 Build map details and equivalent navigation
- **5.3.2.1** Show a selected region's sourced summary, applicable subregions, linked grapes, and attribution/source date.
- **5.3.2.2** Provide a searchable/list-based region explorer with equivalent access to detail pages and subregions.
- **5.3.2.3** Add accessible selection semantics and non-gesture alternatives for pan/zoom and region selection.

### 5.4 Grape-variety tasting-profile architecture

#### 5.4.1 Define the profile content contract
- **5.4.1.1** Specify typical aroma families/descriptors and balanced body/acidity/tannin/style fields where appropriate to the variety.
- **5.4.1.2** Label profiles as tendencies and capture meaningful variation from region, climate, ripeness, winemaking, age, and blending.
- **5.4.1.3** Attach reviewed citations to claims and distinguish general guidance from legal/regulatory definitions.

#### 5.4.2 Implement variety browse and cross-links
- **5.4.2.1** Support search/browse, profile detail, related varieties, and sourced region links.
- **5.4.2.2** Link vocabulary terms to the Aroma Wheel when a stable mapping exists, without automatically adding descriptors to a user's note.
- **5.4.2.3** Allow learning and comparison without requiring a cellar wine or tasting-note record.

### 5.5 Repository, caching, and offline behavior

#### 5.5.1 Define content delivery
- **5.5.1.1** Choose local bundled data, remote updates, or a hybrid model based on dataset size, update frequency, and licensing.
- **5.5.1.2** Define cache/version invalidation, partial-download handling, stale-content display, and offline fallback.
- **5.5.1.3** Keep map assets bounded and avoid blocking the main thread during search, parsing, or rendering.

### 5.6 Page and navigation implementation

#### 5.6.1 Compose the resources information architecture
- **5.6.1.1** Build the Explore/search landing page, region map/list, region detail, grape index, and grape detail using reusable components.
- **5.6.1.2** Keep navigation/back behavior predictable when moving between map selection and detail pages.
- **5.6.1.3** Provide loading, empty, offline/error, source-attribution, and large-font states across the page.

### 5.7 Verification and release

#### 5.7.1 Verify content and source traceability
- **5.7.1.1** Review a representative set of region and grape entries against cited sources and licensing requirements before publication.
- **5.7.1.2** Add validation for stable IDs, required citations, update dates, and broken region/grape cross-links.

#### 5.7.2 Verify interactions and accessibility
- **5.7.2.1** Test map taps, zoom/pan, list parity, subregion navigation, search, and grape/region cross-links.
- **5.7.2.2** Test screen readers, keyboard/accessibility focus, small screens, themes, low connectivity, offline mode, and map-data failure.
- **5.7.2.3** Confirm browsing resources never writes to wine definitions, tasting notes, or inventory.

## Proposed page structure

- **Explore:** Search and browse by region, grape variety, or topic.
- **Regions:** An interactive map with a text/list alternative and detail pages.
- **Grape varieties:** Tasting profiles and links between related grapes and regions.
- **Glossary / tasting basics:** Short, sourced explainers that can link to the aroma wheel without selecting note descriptors automatically.

The entry point (for example, Profile/Utility Hub or a dedicated destination) is an open navigation decision.

## Required content notes

### Interactive wine region maps

- Start with a zoomable/pannable overview; tapping a mapped region opens a detail summary and, where the source data supports it, a more detailed subregion view.
- Region details may include names and boundaries, relevant appellations, commonly associated grapes, and broad style/context notes. Keep legal classification facts separate from general editorial descriptions.
- Provide a searchable/list-based alternative so regions remain usable without precise map gestures, on small screens, and with screen readers.
- Identify the geographic dataset and its license before implementation. Show map/data attribution and a source or last-reviewed date with the relevant content.
- Plan for unavailable tiles/data, offline use, and low-bandwidth devices; map browsing should fail gracefully rather than blanking the whole resource page.

### Tasting profiles by grape variety

- Provide a profile per selected grape variety with typical aroma families/descriptors and a balanced overview of body, acidity, tannin where applicable, and common style variations.
- Explain that profiles describe tendencies, not guarantees: climate, site, ripeness, winemaking, age, and blending can change the result.
- Link aroma terms to the Wine Aroma Wheel and link a variety to relevant regions when sourced data supports the relationship.
- Attribute each factual profile to a reviewed source. Keep editorial summaries distinct from regulatory definitions and user-generated notes.
- Allow users to browse/compare varieties without requiring a cellar entry or tasting note.

## Content and data governance

- Select reference sources and permitted map/content datasets before writing the catalogue. Record attribution, licensing, update cadence, and reviewer for each source.
- Store stable IDs and source references for regions and grape varieties; keep localization and alternative names possible without making them required for the first release.
- Avoid unsupported absolutes such as a single “correct” aroma or style for a region/grape. Clearly label uncertainty and regional variation.
- Decide which content must be available offline and what may be fetched or updated online.

## Open decisions

- Initial coverage: which wine regions and grape varieties should be included first?
- Which map and educational-data sources are suitable for the app's distribution and offline needs?
- Should resources launch as a Profile destination, a searchable library, or contextual links from wine/note screens?
- Who reviews and updates content, and how should corrections be handled?

**Resolved 2026-10-16 (owner):** the page launches as a destination on the **Profile** tab. Coverage, data sources and content ownership are still open (see `BACKLOG.md` W-052 Phase 0).

## Acceptance checks

- Users can reach both an interactive map and a non-map region list; selecting a region opens clearly sourced information.
- Users can find grape-variety profiles by search/browse and understand that tasting descriptions are typical, not deterministic.
- Each map and profile displays required attribution/source information and can be traced to its source record.
- The page supports loading, empty, offline/error, small-screen, large-font, and TalkBack use.
- Resources never modify wine records or personal notes unless a user explicitly chooses a separate action.
