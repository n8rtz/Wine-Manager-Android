# Development Plan: Wine Bottle / Label Image Importer

## Goal and visual target

Populate a known wine in the app with a polished, wine-specific image: preferably a front-facing, full bottle on a clean/neutral background with the label unobstructed; use a clear label-only image when a suitable bottle image is unavailable. The image should look at home in a wine catalog, not like a generic bottle placeholder.

The importer finds an image for an existing wine record. It is separate from [Camera wine recognition](camera-wine-recognition.md), which reads a newly scanned label to suggest form values.

**Recommendation in one sentence:** after a user approves an image, download it once, normalize it to a small compressed derivative, and store that file in app-private storage for offline display; keep only the relative file path and source/attribution metadata in Room. Use remote URLs for search previews and optional refreshes—not as the only copy. Never store the original photo as a Room BLOB or download an entire catalog during app startup.

## Recommended first-release decisions

1. **Start with an explicit review queue, not silent auto-import.** Reuse the existing Profile **Find Missing Images** entry point for the first batch experience, but change its outcome from “pick a URL and immediately update Room” to “show candidate → user confirms or skips.” Keep any existing image unless the user explicitly replaces it. Add per-wine search/replace from wine details later if user testing calls for it.
2. **Prioritize exact wine images.** Prefer a matching bottle image with a visible label. Accept a label-only candidate when it clearly matches. Never substitute generic wine stock art and present it as the exact product.
3. **Use Open Food Facts as the candidate source selected by the user.** The user confirms the project has proper permission to use Open Food Facts data and images. Keep source/contributor provenance and required attribution with each selected image. Search results remain unapproved candidates until the user reviews and selects them. Do not scrape or hotlink Vivino, Wine-Searcher, retailer pages, or search-result pages without separate authorization.
4. **Persist one compact local copy of each approved image.** Store the compressed image file in app-private storage so it works offline and is not fetched repeatedly. Room stores a relative path plus provider/source, attribution, image kind, and size metadata—not image bytes. Keep previews and unselected candidates in a small temporary cache; delete them after review.
5. **Keep wine saving independent of image search.** Saving a bottle/wine must not wait on a provider request, image decode, or image download. Image search is a separate cancellable action or a best-effort background job after the normal save completes.
6. **Do not change inventory or note semantics.** An image import may only associate the approved image with the intended wine definition. It must not create/update bottles, quantities, tasting notes, or consumption events.

## Implementation status

The first reviewed-import path is now implemented in the Android source:

- Profile search is sequential and cancellable, uses Open Food Facts only, shows up to five source-linked candidates, and requires the user to choose **Use as bottle** or **Use as label**. Search/preview/skip/cancel alone do not update Room.
- On approval, the selected source is downloaded with HTTPS/source-host, MIME, byte-size, and timeout checks; EXIF orientation is normalized, the image is downsampled/recompressed to WebP on IO, and the derivative is limited to 500 KB. Candidate previews disable Coil disk caching.
- A Room `wine_image_assets` row stores the relative local path and source/contributor metadata. The source URL is retained for provenance and explicit re-import; records with a provenance row do not automatically fetch it when the local file is missing. Cellar, note/ranking, archive, and detail rendering prefer the local file; detail shows source attribution/link.
- The default 256 MiB storage threshold is configurable from Profile (64–1,024 MiB), with batch and exact per-image warnings, actual free-space reserve, and no automatic eviction. JSON backups preserve source metadata but omit image bytes and relative paths; Android cloud/device-transfer rules also exclude `filesDir/wine-images/`. Restore clears stale private files and does not automatically fetch the source URL. Assets with missing local paths are eligible for a fresh reviewed search in Profile.
- Android compilation, tests, migration execution, and device quality remain to be verified in Android Studio. Open Food Facts authorization is assumed confirmed per the user's direction; source/contributor provenance and required attribution remain in the implementation.

## Current code baseline and constraints

- `WineDefinition` has a nullable `imageUrl`; `WineBottle` does not hold image bytes. The image is currently associated with the definition, so all vintages of that definition generally share the same image.
- `WineCard` displays images in a small 60×80 dp box using Coil. Other screens also load `imageUrl`. The app already depends on Coil 2.7.0 and enables memory/disk caching in several image requests, but cache budgets and image rendition sizes are not centrally specified.
- The prior Profile action used `WineImageBatchProcessor`, which searched Open Food Facts and DuckDuckGo and wrote a chosen URL directly. The Profile entry point now uses a separate Open Food Facts review queue with explicit bottle/label approval, local compressed storage, and provenance. The legacy importer implementation remains in `ScannerViewModel` for compatibility but is no longer wired to the Profile action; camera OCR remains independent.
- `WineRepository.addWine()` calls `WineImageFetcher.fetchImageUrl()` before inserting a definition. That fetcher currently returns `null` as a placeholder. Do not turn it into a synchronous network call on the Save to Cellar path; decouple image search before enabling a real provider there.
- `WhitespaceAnalyzer` and the batch processor can download/analyze image candidates. A “white background” score may help rank candidates, but it cannot establish wine identity or professional quality.
- Existing bottle/label imports must remain separate from camera OCR and from the stock-image importer used by Profile. Do not route scanned label photos into this catalog-image feature by default.

## Proposed architecture

```text
Explicit user action
  → WineImageImportViewModel / use case
  → approved ImageProvider (network on IO, cancellable, bounded)
  → small candidate previews + identity/source metadata
  → review/preview with target wine and attribution visible
  → user confirms one candidate
  → fetch selected image once; downsample/recompress off-main-thread
  → atomically save one private local derivative + Room path/attribution metadata
  → Coil loads the local file; works offline without another provider request
```

Keep provider access behind an interface so tests can supply a fake provider and production sources can be changed without rewriting UI or persistence. Keep candidate discovery, identity matching, preview rendering, and the final database association as separate operations. Candidate retrieval must not write to Room; only the explicit confirmation action may do that.

## Identity and image contract

### Candidate matching

- Search using the confirmed producer/maker and wine name. Add region/appellation and vintage only when those fields are available and useful to disambiguate the product.
- Show the target wine identity in the candidate-review UI. Surface vintage differences instead of treating every label for the same cuvée as equivalent.
- Candidates should include, where available: provider asset ID, source name, source URL, attribution/license text or link, image kind (`BOTTLE` or `LABEL`), dimensions, product title/identity, and a thumbnail/detail rendition URL.
- Ranking may order candidates by identity evidence, label visibility, portrait/full-bottle framing, resolution, and source quality. Ranking is not approval; no score automatically overwrites the selected image.
- The first release should attach a definition-level image only when it is suitable across that wine definition's vintages. If the chosen label is vintage-specific, either skip it for the definition-level field or introduce an explicit optional vintage key in an image-asset relation. Do not show a known-wrong vintage label on every bottle.

### Persistence

- Use a dedicated `WineImageAsset` relation for the new importer. Keep image bytes outside SQLite so routine wine-list queries and Room flows never materialize photo blobs. Store relative path, definition ID, optional normalized vintage, provider/asset ID, original source URL, attribution/license, image kind, encoded dimensions/size, content hash, and selected/updated time.
- Store the canonical compressed derivative under app-private `filesDir/wine-images/` with a generated stable filename. Store a relative path such as `wine-images/<asset-id>.webp` in Room, not an absolute device path. Resolve it to a `File` when building the Coil request. Keep `WineDefinition.imageUrl` for legacy remote images during migration; new UI should prefer a valid local asset, then fall back to a legacy URL/placeholder.
- Import safely: download to a size-limited temporary file in `cacheDir`, validate MIME/dimensions, normalize orientation, downsample and recompress off the main thread, then atomically rename the completed derivative into `filesDir`. Commit its Room metadata only after the file is ready; on failure, clean up the temporary file and leave the old image untouched. On replacement, install the new file/metadata before deleting the previous local file.
- Add a Room migration and update import/export/backup contracts. Keep the original provider URL and attribution for provenance/re-import. Backups omit compressed images; after restore, detect missing local files and offer a reviewed Profile search/re-import flow that requires explicit approval. Never assume an absolute local path survives restore to another device.
- Preserve legacy `imageUrl` values and user-selected images. A new search alone must never clear or replace an existing selection. Provide a deliberate remove/replace action and clean up the corresponding private file when removed.

### Why not store the compressed bytes as a Room BLOB?

Storing a compressed image in SQLite would technically work, but it is not the best default for label-readable photos. SQLite would still grow with every image; loading a BLOB through Room commonly materializes the whole byte array, and repeated image updates enlarge database/WAL/backup work. It also risks pulling image bytes into ordinary wine queries if the entity/DAO boundary is not kept very strict. App-private files are easier to stream, decode, atomically replace, and delete. The app still stores the approved photo locally and can display it offline; Room stores only its small metadata/path row.

If a future use case requires a self-contained database, consider a separate BLOB table only for very small thumbnails, fetched by a dedicated DAO on the image detail path—not joined into the normal cellar list. Keep the label-readable master as a file unless benchmarks prove otherwise.

## Performance and on-device storage plan

### Download once, use offline

- Searching is online, but an approved image should be local thereafter. Download a small preview for candidates; fetch the chosen image only after the user taps **Use as bottle** or **Use as label**. Do not re-request the selected source image when opening cards, reopening the app, or scrolling. If its local file is present, display it offline. Network is needed only for a first search/import or a user-invoked Profile review/import after the local copy is removed or lost.
- Perform provider I/O on `Dispatchers.IO`, and validation, downsampling, orientation correction, and compression on a worker dispatcher. Never do that work in a composable, Room transaction, or the synchronous part of Add Wine/Save to Cellar. Keep requests cancellable, time-bounded, and provider-compliant.
- Use small provider/CDN preview renditions (about 256–384 px on the long edge, targeting 100–250 KB) so browsing candidates does not transfer full originals. Coil's decode-size limit reduces RAM, **but does not reduce network bytes by itself** if the source URL still serves a full-size file.
- On approval, download one selected source image, enforce a byte/dimension limit, correct EXIF orientation, downsample, strip unnecessary EXIF, and encode a local WebP derivative. Validate that the label remains legible on real samples; do not keep the full-resolution source after conversion.

### Proposed local-file size budget

These are starting limits to measure and tune, not assumptions about every provider or photo:

- Whole-bottle derivative: up to about 1,024 px on the long edge. Label-only derivative may go to about 1,200 px when readability needs it.
- WebP lossy quality around 80; target at most 250–300 KB per selected image, with a hard per-image ceiling around 500 KB. If the file exceeds the ceiling, recompress/downsample and recheck legibility; if it still fails, ask the user to choose another candidate rather than retaining an oversized original.
- Keep one durable derivative per selected image initially. Let Coil downsample it for the small 60×80 dp card and render the same local file larger on the detail screen; do not persist duplicate card/detail copies unless profiling proves they are needed.
- At 250 KB per image, 100 definitions use about 25 MB, 500 use about 125 MB, and 1,000 use about 250 MB. At 300 KB, 1,000 use about 300 MB. Show the estimated total in the batch review and storage UI. The current model associates images with `WineDefinition`, so the count is normally distinct wine definitions—not the number of bottles or vintages. Vintage-specific assets would add to that count.

### Storage quota and lifecycle

- Use app-private `filesDir/wine-images/` for selected offline assets; use a separate temporary `cacheDir` location for previews and in-progress conversions. Temporary files are always deleted after success/failure/cancel.
- Do **not** use 64 MiB as a hard limit for durable selected images; at the proposed file sizes that would hold only roughly 130–270 labels. Start by evaluating a visible, configurable soft budget around 256 MiB (roughly 537 images at the 500 KB ceiling, or about 895–1,073 at the 250–300 KB target). Show current/estimated storage in batch review. Warn and ask before exceeding the budget; check actual free device storage before importing. Never silently evict a photo the user chose for offline use. Let the user cancel, remove selected local copies, or explicitly continue if there is enough free space. Keep the source reference if a local copy is removed, but disclose that the wine will then need an online re-download to display again.
- Store relative paths and file sizes in Room; keep bytes out of Room BLOB columns, JSON, and ordinary wine queries. Write a new file to a temporary name, validate it, atomically rename it, then commit the Room row. On replacement, retain the old file until the new file and metadata are valid.
- The app-private file is the offline source of truth after import. Use Coil for memory decoding/cache, but avoid creating an unbounded second disk copy of every already-local file. Keep candidate-preview/HTTP caches small and evictable; invalidate memory/preview entries when a selected image changes.
- Uninstall/data-clear removes app-private images. Backups omit compressed files but retain provider IDs/URLs. After restore, show the placeholder for a provenance-bearing asset with no local file; the user can run Profile's reviewed Open Food Facts search and must approve a new copy. Do not automatically fetch the source URL or restore a stale absolute path.

### UI and list performance

- `CellarScreen` already uses a `LazyColumn` with stable bottle IDs, and `WineCard` constrains the image to a 60×80 dp box with Coil `AsyncImage`. Preserve that lazy/keyed structure. The number of image files on disk does not mean all of them are composed, read, or decoded during a scroll.
- The asset is associated with a wine definition, so multiple bottle rows for the same wine should point to the same local file/cache key rather than storing or decoding separate copies per bottle.
- Display list images in a stable portrait box with `ContentScale.Fit` for a whole-bottle shot so the cap/base are not cut off. A label-only image can use a separate fit/crop treatment. Reserve layout dimensions before loading to prevent shifts.
- Load the local file first; only fall back to a legacy remote `imageUrl` when no local selected asset exists. Let Coil decode to the measured card size off the main thread, load visible list rows only, cancel obsolete requests as rows leave the viewport, and never prefetch every cellar image on launch.
- Use a stable asset/version cache key, bounded memory caching, and placeholders for no image, import in progress, missing/offline local file, and decode failure. Image failure must not block wine details or inventory.

### Batch behavior

- First validate one-wine search/review before enabling a full batch. For a batch, process only definitions missing an approved image by default; require explicit opt-in for replacement.
- Candidate previews are small and temporary; only user-approved selections become durable local files. Limit concurrency to a small provider-compliant number (start at 1–2 lookups and at most 2 previews/conversions at a time). Use a cancellable queue, visible progress, estimated storage, and imported/skipped/failed summary. Never start requests for the whole cellar at app launch.
- If a batch must continue when the app is backgrounded, evaluate WorkManager with network constraints and unique work. Otherwise keep the first batch screen-scoped and cancellable; do not add background-service complexity until usage requires it.

## Source authorization and provenance

Open Food Facts is the selected candidate provider, and the user confirms proper permission to use its data and images. Preserve source/contributor metadata and required attribution. This authorization does not approve any candidate as a match for a specific wine; continue showing the listing and require explicit user review. Any future provider or third-party source needs separate authorization.

Do not put private API keys in the Android APK. If a future provider requires a secret, use a small server-side proxy with rate limits and only the minimum wine query fields; review privacy and operating cost before adopting it. Keep request timeouts, user-driven query limits, and source attribution.

## User flow (recommended MVP)

1. User opens **Profile → Find Missing Images** (the current entry point) or, in a later iteration, chooses **Find/Replace Image** on a specific wine.
2. Show a cancellable queue of wine definitions missing an approved image. The target maker/name (and relevant vintage) remains visible.
3. Fetch candidate metadata and small previews in the background. Show one target at a time with a bottle/label preview, match evidence, source, and attribution.
4. User taps **Use Image**, **Skip**, **Retry**, or **Cancel**. Existing selected images stay untouched unless the user enters explicit replacement mode and confirms.
5. On **Use Image**, fetch the selected image once, convert it to the size-bounded local derivative, and persist its app-private relative path plus source/attribution metadata. Do not change wine identity, bottle quantity, price, location, notes, or consumption history.
6. Cellar/history/detail screens load the local derivative, including offline. If an approved asset's local file is missing, show the wine-type placeholder and retain its source link; Profile offers a fresh candidate search, and a new durable file requires explicit approval. Legacy records without provenance metadata may continue using their existing remote URL.
7. A batch ends with counts for imported, skipped, failed, and still missing; retrying does not duplicate local files or silently replace images.

## Implementation sequence

### Phase 0 — Source, quality, and storage decisions

1. Use Open Food Facts under the user's confirmed authorization assumption; retain required attribution. For any future provider, perform a separate permission and source-quality review.
2. Build a small representative test set across regions, producers, bottle shapes, vintages, and label-only cases. Manually record identity match, label legibility, image quality, and source/contributor metadata.
3. Decide whether the MVP is definition-level only or needs vintage-specific image assets.
4. Set and record the derivative format/dimensions, per-image ceiling, a configurable soft storage budget (start by evaluating 256 MiB), and a separate bounded preview-cache budget. Confirm whether compressed image files are included in Android backup/export or re-fetched after restore. Profile on representative devices and adjust; do not guess from search-result dimensions alone.

### Phase 1 — Isolate provider and persistence boundaries

1. Define `ImageProvider`, candidate/attribution models, and an importer repository/use case independent of Compose and the provider SDK.
2. Add a fake provider for JVM tests. Candidate retrieval returns data only; the user-confirm action is the only operation allowed to persist an image.
3. Add a file-storage component that streams a selected candidate to a bounded temporary file, transforms it on a worker dispatcher, and atomically stores the derivative. A Room row records the relative path and provenance; database reads never load the bytes.
4. Keep the current Profile importer reachable while refactoring, but replace its silent URL writes with a review queue before enabling the approved production provider.
5. Remove image-network work from the Add Wine save path before enabling real lookup there. Preserve existing `imageUrl` values and stock-image import behavior until the new path has equivalent tests.

### Phase 2 — Candidate review for one wine

1. Implement one-wine candidate search, target identity, source/attribution, loading/empty/error/offline states, preview, skip, cancel, and explicit apply.
2. Add tests proving search alone, cancel, skip, timeout, and provider failure do not change the database.
3. Add tests proving confirmation updates only the target image association and replacement requires explicit confirmation.
4. Verify a vintage-specific candidate cannot silently become the definition image unless the selected data model explicitly supports that vintage.

### Phase 3 — Local-first image rendering and storage

1. Update DAO projections and image-loading code to resolve a valid local asset first, retain a fallback for legacy remote `imageUrl` values, and otherwise show the existing placeholder.
2. Use Coil for asynchronous local-file decoding with stable image box sizes, appropriate `ContentScale`, accessible descriptions, and bounded memory/preview cache behavior.
3. Verify replacement installs the new file before deleting the old one; remove/uninstall/restore paths handle missing files safely and never leave a broken Room reference.
4. Measure import bytes, encoded file size, transformation time, decoded bitmap memory, durable-store usage, preview-cache size, detail latency, and list-scroll jank on representative devices. Reopen the same wine online and offline and confirm the stored asset is not downloaded again.

### Phase 4 — Reviewed batch queue

1. Add opt-in batching from Profile for missing images with bounded concurrency, progress, per-wine review, cancellation, and a final summary.
2. Add WorkManager only if batch continuation across process death/backgrounding is a confirmed requirement.
3. Verify retries are idempotent and neither existing image choices nor unrelated wine/inventory/note rows are overwritten.

### Phase 5 — Release and maintenance

1. Apply the Open Food Facts authorization assumption provided by the user; verify source/contributor attribution is retained. Keep privacy, request limits, and candidate identity review in place.
2. Run unit, repository, UI, Room migration/backup, and device tests below.
3. Monitor import error rate, durable storage/quota use, and image-to-wine mismatch reports without logging personal tasting notes or retaining the high-resolution source images.

## Verification plan

### Automated tests

- Fake-provider unit tests: exact match, ambiguous candidates, no results, duplicate URLs, wrong vintage, malformed metadata, unsupported MIME, oversized response, timeout, rate limit, offline, and cancellation.
- Domain tests: candidate ranking is deterministic; ranking never writes; confirm targets the correct `definitionId`; skip/cancel/retry leave the chosen image unchanged; a definition-level image is not assigned from an incompatible vintage-specific label.
- Repository/Room/file-store tests: selected relative path and attribution persist; the file exists and decodes; failure/cancellation leaves the old image intact; replace/remove cleans up files; migrations and backup/restore handle missing paths; no image bytes are stored in Room; bottles, notes, and events remain untouched.
- UI tests with a fake provider: loading/empty/error/preview/attribution/apply/skip/cancel states; local-first rendering; manual wine fields and Add Note remain unchanged.
- Image-store tests: dimension/byte ceilings, orientation correction, compression legibility, quota behavior, corrupt/missing local file, remote preview failure, cache invalidation, and no re-download when the selected local file exists. No camera hardware should be required.

### Device/performance checks

- Test on a representative lower-tier supported Android device and a mid-range device, with Wi-Fi and offline cases.
- Verify Add Wine save completes without waiting for image search; an explicit lookup/import is asynchronous and does not freeze interaction.
- After importing an image, reopen its wine several times and confirm no second image download occurs. Turn off networking and confirm the selected local image still renders.
- Scroll a large cellar and confirm local images decode smoothly, image boxes do not reflow, the configured storage budget/low-space warning behaves correctly, and unselected previews are temporary and do not accumulate.
- Record candidate response bytes, selected derivative size, storage used, transformation time, memory/bitmap use, detail latency, and scroll responsiveness. Confirm label text is legible at detail size.

## Acceptance checks

- A confirmed exact candidate displays a clean full bottle with its label visible; a clearly matching label-only image is an acceptable fallback.
- Wrong, vintage-mismatched, generic, low-quality, or unavailable candidates can be skipped and never silently assigned.
- The provider/source and required attribution remain associated with the selected image and visible wherever required.
- Candidate discovery never writes an image. The user explicitly confirms association/replacement.
- Wine identity, inventory quantity, price, location, tasting notes, and consumption records are unchanged by importing an image.
- The selected compressed derivative is stored locally and renders offline without repeating a provider download. The original photo and image bytes are never stored in Room; only a relative path and provenance metadata are.
- Durable image usage and batch size estimates are visible, with a configurable soft budget (initially evaluate 256 MiB), a per-image size ceiling, and a low-device-storage guard. The app never silently deletes a user's selected offline image to make room; preview caches are separate and evictable.
- Wine save remains responsive and works with no network. Network is used only for explicit search/import/refresh, not every view of an already imported image; transformation and decoding stay off the main thread.
- Empty, broken, offline, or rejected images retain a readable placeholder and do not block cellar use.

## Remaining decisions / release gates

- Open Food Facts is the selected source; proper permission is assumed per the user's direction. Preserve source/contributor provenance and required attribution. License metadata remains optional when the provider supplies it.
- Profile now provides the reviewed batch queue. Per-wine detail search/replace remains a possible later iteration.
- The MVP uses one definition-level image, shared across its vintages. A known vintage-specific label should be skipped unless the data model is explicitly expanded.
- The configurable 256 MiB soft budget is implemented with a 64–1,024 MiB user range; validate the default on real devices and tune after measuring.
- JSON backups retain Open Food Facts source/provenance metadata but do not include compressed image bytes or relative local paths. Restore nulls local paths and clears app-private files; provenance-bearing assets do not automatically fetch remote source images. They become eligible for Profile's reviewed missing-image search, where a new offline copy again requires explicit approval.
- Open Food Facts small preview renditions are used when supplied. Confirm representative source quality, preview response sizes, and full-image URL behavior during device testing.
- Profile now includes explicit per-image offline removal while preserving provenance; the design and pending checks are documented in [`manage-offline-wine-images.md`](manage-offline-wine-images.md). Android build, instrumented tests, and device behavior remain unverified.
