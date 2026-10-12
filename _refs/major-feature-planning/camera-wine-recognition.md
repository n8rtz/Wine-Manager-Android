# Feature Plan: Camera Wine Recognition

## Goal

Let a user scan a wine bottle label from the Add to Cellar or Add Tasting Note flow, extract likely wine details, and use those details to prefill the active form for review.

**Related backlog:** `_refs/wishes-v2.md`, item 36.

## Architecture-Level Work Breakdown Structure — 4.0

### 4.1 Product, privacy, and permission contract

#### 4.1.1 Define scanning requirements
- **4.1.1.1** Confirm entry points and supported fields for Add to Cellar and Add Tasting Note, including different save semantics for each flow.
- **4.1.1.2** Define acceptable confidence states, no-match behavior, manual correction, retake/crop, and when partial recognition is useful.
- **4.1.1.3** Keep scanning opt-in; request camera permission only when the user starts the scan and preserve manual entry after denial.

#### 4.1.2 Approve privacy and processing mode
- **4.1.2.1** Compare on-device OCR/catalog matching with any proposed remote recognition provider for accuracy, supported devices, latency, cost, and terms.
- **4.1.2.2** Before any upload, specify disclosure, consent, retention/deletion, transport, and handling when the service is unavailable.
- **4.1.2.3** Reuse the CameraX 1.4.1, ML Kit Latin text-recognition 19.0.1, Accompanist permission, and CAMERA permission already present in the working tree unless device/build validation shows a reason to change them.

### 4.2 Capture and image-processing architecture

#### 4.2.1 Build the camera lifecycle boundary
- **4.2.1.1** Isolate camera permission, preview, capture, rotation, lifecycle teardown, and temporary-file cleanup from form persistence.
- **4.2.1.2** Support retake and safe cancellation without clearing the originating form draft.
- **4.2.1.3** Define supported image dimensions and bounded preprocessing for glare, rotation, and low-light cases; move bitmap conversion/rotation off the UI thread and release frame/image resources on every path.

#### 4.2.2 Build recognition adapters
- **4.2.2.1** Define a recognition interface returning raw text blocks and/or structured candidates with source and confidence metadata.
- **4.2.2.2** Implement the approved OCR/recognition adapter off the main thread with cancellation and timeouts.
- **4.2.2.3** Keep raw OCR, field extraction, and catalog matching as separate stages so each can be tested and replaced.

### 4.3 Identity resolution and draft data

#### 4.3.1 Extract candidate fields
- **4.3.1.1** Parse maker, wine name, vintage, country, region, grape, and type only where supported by the label evidence.
- **4.3.1.2** Normalize candidate text for lookup without changing the text the user ultimately confirms.
- **4.3.1.3** Never fabricate missing values; keep uncertainty and confidence associated with each suggestion.

#### 4.3.2 Match against catalog data
- **4.3.2.1** Define exact/normalized match rules and ambiguity handling against saved wine definitions and approved reference data.
- **4.3.2.2** Show multiple plausible matches rather than silently selecting one where identity is ambiguous.
- **4.3.2.3** Do not create or update catalog definitions until the user completes the existing form's explicit save flow.

### 4.4 Form integration and persistence boundaries

#### 4.4.1 Prefill Add to Cellar
- **4.4.1.1** Return candidate values to the active Add Wine draft with all fields editable.
- **4.4.1.2** Leave bottle quantity, price, location, and other inventory-only values to the user/form defaults; do not infer them from the label.

#### 4.4.2 Prefill Add Tasting Note
- **4.4.2.1** Return candidate values to the active note draft and maintain the manual-tasting workflow.
- **4.4.2.2** Verify that scanning or saving a manual tasting note never creates cellar bottle/inventory rows.

#### 4.4.3 Define image retention
- **4.4.3.1** Decide whether the captured label image is discarded, retained temporarily, or separately saved only after explicit user action.
- **4.4.3.2** Keep camera capture distinct from the stock bottle-image importer and its source/attribution policy.

### 4.5 Verification and release

#### 4.5.1 Test capture and recognition failure modes
- **4.5.1.1** Cover permission granted/denied/revoked, unavailable camera, rotation, glare, poor focus, no text, ambiguous candidates, cancellation, and process failure.
- **4.5.1.2** Use fake recognition adapters for repeatable parser, confidence, and matching tests.

#### 4.5.2 Test user and data flows
- **4.5.2.1** Verify both forms preserve drafts, permit edits, and save only through their existing explicit actions.
- **4.5.2.2** Test on supported Android versions/devices for latency, memory, privacy disclosures, and lifecycle cleanup.

## Current Working-Tree Baseline

This is a partially built prototype, not a greenfield feature:

- `AddWineScreen` embeds a CameraX `PreviewView`/`ImageCapture`, requests the declared CAMERA permission, converts the captured frame to a bitmap, and sends it to `ScannerViewModel`.
- `ScannerManager` already uses ML Kit Latin text recognition. Its current parser takes the first OCR line as maker, joins later lines as the wine name, and treats a four-digit 1900–2099 year as vintage. Its result contains only maker/name/vintage; processing failures become an empty result.
- `ScannerViewModel` publishes one nullable `ScannedWine` state that `AddWineScreen` consumes by immediately writing non-null values into its draft. There is no explicit processing/result/error review state.
- CameraX 1.4.1, ML Kit text recognition 19.0.1, Accompanist permissions, and the CAMERA manifest permission are already present in the working tree.
- `TastingNoteEditorScreen` and its `MainActivity` routes do not currently receive or invoke the scanner. `ScannerViewModel` also owns the separate stock-image batch-import operation, so those responsibilities should not be expanded together.

## On-Device OCR Pipeline and Performance

### Processing path

`CameraX still capture → orientation-aware InputImage → ML Kit TextRecognizer → OCR blocks/lines → field-candidate parser → conservative local catalog match → user review → form draft`

This is **OCR-assisted form filling**, not guaranteed visual identification of every wine. OCR supplies text; the parser/matcher proposes which text is a producer, wine name, or vintage. A global wine-identification service or image model would be a separate, explicitly approved scope.

### Library calls and threading

The project already has the required libraries: `play-services-mlkit-text-recognition` 19.0.1, CameraX 1.4.1, and `kotlinx-coroutines-play-services`. The current implementation already calls `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` and `recognizer.process(inputImage).await()`.

The core still-image flow is conceptually:

```kotlin
val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
val recognizedText = textRecognizer.process(inputImage).await()
val candidates = wineLabelParser.parse(recognizedText)
```

`await()` suspends the coroutine while ML Kit processes the image; it is not a blocking `Tasks.await()` or `runBlocking` call. Keep the recognizer instance reusable for the scanner's lifetime and close it when that owner is cleared. Convert exceptions/model-unavailable conditions into a typed scan error rather than silently returning an empty wine.

### Image ownership and UI responsiveness

- Prefer one user-triggered still scan; do not run OCR on every preview frame. That avoids unnecessary CPU, heat, battery use, and competing results.
- The current Add Wine callback is scheduled on the main executor and performs `ImageProxy.toBitmap()` plus rotation there. Move that copy/rotation to a background executor, or use a supported `ImageProxy`/`InputImage.fromMediaImage` path and keep the proxy alive until ML Kit finishes. In either path, close image buffers exactly once in a `finally`/completion path.
- The repository already contains a `scanImageProxy()` helper, but the current UI calls the bitmap-based path. Verify the captured `ImageProxy` format before using the media-image path; do not assume every capture format is accepted.
- Start with a full, readable label image, allow only one active scan per form, and release the bitmap after OCR/parsing. If profiling shows large-image cost, benchmark a bounded resize/crop; do not downsample until small label text remains legible.
- Show a nonblocking “Reading label…” state and keep the form responsive. Disable duplicate shutter submissions during an active job; let the user cancel or retake.

### Model delivery and first-run behavior

The current `com.google.android.gms:play-services-mlkit-text-recognition` artifact uses the Play-services-delivered model rather than packaging the model directly in the app. First-use readiness can therefore depend on Google Play services and model availability/download. The scan flow needs a clear preparing/unavailable state and retry path. If offline recognition must work on first launch, compare the bundled ML Kit artifact instead and accept its app-size cost. The current recognizer is Latin-script; support for other scripts requires a separate dependency, testing, and parsing decision.

### Expected performance

A single label OCR pass should be practical on the app's currently supported Android 13+ devices, and should not freeze the UI when image conversion is moved off the main thread. On many current phones, warm OCR for one readable still should be in the hundreds-of-milliseconds to roughly one-second range, but that is an expectation—not a guarantee. Very large images, slow CPUs, poor lighting, and cold model availability can make total scan time longer; the first-use model download is separate from OCR time.

Before setting a release target, measure capture-to-review latency on a representative lower-tier supported device and a mid-range device, using real labels and the model already prepared. Log timings without retaining label photos or raw OCR text. If performance is poor, reduce image work or size based on those measurements rather than adding continuous OCR.

## Recommended Implementation Sequence

### Phase 0 — Confirm the field and privacy contract

1. Confirm which fields a scan may suggest for each form. Start with maker, wine name, and vintage; treat country/region/type as optional only when evidence is strong.
2. Confirm that recognition only prefills a draft. The user reviews and explicitly saves using the existing form action; no catalog, tasting-note, or bottle row is created by scanning alone.
3. Keep recognition on-device with the existing ML Kit OCR as the first implementation. Do not add a remote image-recognition service or upload label photos unless separately approved with disclosure, consent, retention, and provider review.

### Phase 1 — Separate capture from form persistence

1. Extract the inline CameraX preview/capture handling into a reusable scan entry component or coordinator that both forms can invoke.
2. Request permission only after the user taps Scan; preserve manual entry if permission is denied.
3. Bind/unbind use cases with the screen lifecycle, show camera/processing state, support retake/cancel, and release `ImageProxy`/bitmap resources on every success and failure path.
4. Replace the single nullable result signal with a request-scoped state/result (for example, Idle, Capturing, Processing, Review, Error) so stale results cannot populate a different form instance.
5. Separate recognition state from the stock-image batch importer; preserve that existing Profile workflow while assigning each feature a focused owner.

### Phase 2 — Improve OCR interpretation and local matching

1. Keep OCR text blocks/lines as input to a pure parser; normalize text for matching without discarding the original OCR text needed for review.
2. Produce per-field candidates with evidence and confidence where available. Do not assume the first line is always the producer or the first four-digit year is always the vintage.
3. Treat year matches as candidates only; reject impossible/future or contextually doubtful values rather than filling them silently. Do not infer missing fields.
4. Match candidates conservatively against saved wine definitions and, where relevant, the user's existing cellar choices. Show multiple matches when ambiguous; do not use unapproved external lookup services.
5. Keep raw OCR, field parsing, catalog matching, and form mapping as separate components so each can be tested independently.

### Phase 3 — Add a review-and-apply step

1. Show recognized text and proposed fields in a compact dialog or bottom sheet with Apply, Retake, and Cancel actions.
2. Make every proposed value editable after Apply. If a form field already contains user input, ask before replacing it or default to leaving it unchanged.
3. Display uncertainty and no-match/error states; an empty scan must not look like a successful result.
4. Preserve the original form draft on cancellation, OCR failure, camera denial, or retake.

### Phase 4 — Integrate the two form workflows

1. **Add to Cellar:** route the approved maker/name/vintage suggestions into the existing draft. Do not infer quantity, price, location, drinking window, or other inventory values.
2. **Add Tasting Note:** pass the same scan result contract into the note-editor route. In Manual mode, prefill only the manual note fields. In Cellar mode, offer a confirmed match to an existing cellar wine; do not silently create or select a cellar bottle.
3. Keep edit-note behavior and route-scoped state explicit so a new scan cannot leak into another note or form.
4. Verify that a Manual tasting note remains a note/definition workflow only and never creates cellar inventory.

### Phase 5 — Verify and release

1. Add parser tests using representative OCR text, line ordering, punctuation/diacritics, missing fields, multiple years, and false-positive year strings.
2. Use a fake recognition engine for state, matching, conflict, cancellation, and error tests; UI tests should not require camera hardware.
3. On physical devices, test permission grant/denial/revocation, lifecycle changes, focus/rotation/glare/low light, retake, and camera unavailability.
4. Test both Add to Cellar and Add Tasting Note from Profile/navigation entry points, including Manual vs. Cellar note mode and existing draft values.
5. Confirm image buffers are released, no scan photo is retained or uploaded by default, and no scan path bypasses each form's existing save/validation rules.

## Proposed user flow

1. The user explicitly taps Scan from the Add Wine or Add Tasting Note form.
2. Request camera permission only when needed. If permission is denied, keep manual entry available.
3. Capture or select a label image and extract text/identity candidates. OCR plus catalog matching is a possible first stage; the recognition provider and any visual model remain open decisions.
4. Show the best candidate(s), confidence/uncertainty, and the fields that will be filled. The user can edit every value before continuing.
5. Return to the original form draft. Do not save a wine, note, or bottle until the user uses that form's existing Save action.

## Field candidates

Evaluate producer/maker, wine name, vintage, country, region, and grape/type where available. Treat vintage and region as uncertain when the label is ambiguous; never invent a value to fill a blank field.

## Privacy and technical planning

- Prefer on-device processing for an initial implementation if it meets accuracy and device-support needs. If a service would upload images, decide the provider, disclosure, consent, retention, and failure behavior before implementation.
- Existing `_refs/PLAN.md` mentions CameraX and ML Kit text recognition as a prior proposal. Revalidate versions, capabilities, and privacy implications; this is not a final technology decision.
- Keep capture, recognition, catalog matching, and form-prefill responsibilities separable so the UI can show partial results and support manual correction.
- Handle rotation, low light, glare, multiple labels/text blocks, no match, and network/service failure without losing the user's form draft.

## Data-integrity boundaries

- Recognition provides draft field values only; it must not silently create or edit catalog records.
- Scanning from Add Tasting Note must remain a manual tasting-note workflow and must not create cellar bottles or inventory rows.
- Scanning from Add to Cellar must use that form's normal confirmation and save behavior.
- Camera recognition does not automatically import or persist the scanned photo as a stock bottle image; that is a separate image-import decision.

## Open decisions

- OCR-only with catalog matching, a dedicated wine-recognition provider, or a staged combination?
- Which fields can be matched safely and how should confidence be communicated?
- Should a user be able to retry with another image or manually crop/retake the label?
- Which minimum Android versions and device capabilities are supported?

## Acceptance checks

- Scan actions exist in both requested form flows; manual entry remains available.
- Permission denial and camera/recognition failure do not block either form or discard its draft.
- The user can review and edit all proposed values; nothing is saved without explicit form submission.
- Manual tasting-note scans never create cellar inventory.
- Image privacy, provider behavior, and any network upload are clearly documented before release.
