# Current experiment: three independently selectable candidates

See `docs/text-lab-candidates.md` for the current UI, combined CSV schema, timing and lifecycle.
Candidate 1 is the v5 pipeline described below; candidate 2 is v6 Small detection + Medium
recognition, and candidate 3 is v6 Medium detection + Medium recognition. `candidate-models.json`
records the additional real pinned v6 assets. All three are enabled by default. The 80 MiB
asset limit is not enforced for this user-requested experiment.

Each candidate now has its own eight-second budget; shared preparation and ML Kit are separate.
Model sessions are released between stages/candidates, not kept resident between tests. Private
CSV checkpoints retain all selected candidates' data, including cancelled or failed attempts.
The original v5 implementation notes below describe its model/preprocessing, not the current
multi-candidate timing or session policy. Native v6 execution remains unverified in this sandbox.

---

# Experimental PP-OCRv5 mobile detection + recognition

**Debug Text Lab only.** The normal scanner, wine field assignment, 30-case benchmark and
optional Paddle Lite bridge are unchanged. Neither model receives expected text or wine
reference answers. ML Kit is a separate comparison, not the source of Paddle's boxes.

## Genuine artifacts and provenance

`provenance.json` records exact URLs, SHA-256 hashes, sizes, and preprocessing sources.
`:app:prepareTextLabPaddle` downloads the real ONNX conversions from GreatV/oar-ocr **v0.3.0**
into generated debug assets. The recognition model is 16,562,373 bytes; detection is 4,826,518
bytes. Both models plus the 74,012-byte dictionary total about **20.47 MiB**. Digests match
published release metadata and the distributor's registry. Build preparation fails if a
fetch/checksum fails; no placeholder model is used and generated weights are not in Git.

Upstream family/configuration/dictionary: PaddlePaddle/PaddleOCR, revision
`dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`, Apache-2.0 (license included). ONNX conversion/
distribution is third-party GreatV/oar-ocr, not an independent training-history verification.

The upstream `ppocrv5_dict.txt` is packaged byte-for-byte as `ppocrv5_dict.utf8` with Git
`-text` attributes. The changed filename replaces previously CRLF-converted Windows copies.
Runtime hash/size checks remain strict. Models/dictionary are copied to private no-backup
storage once; there is no network request during recognition or photo upload.

## Full-label mode (default)

1. The selected crop is the **base coordinate space**. No automatic outer-label crop removes
   content; this is not a claim that the whole label has been perspective-rectified.
2. Independent PP-OCRv5 mobile detection: BGR, NCHW, edge at most 960, dimensions rounded to
   multiples of 32, scale 1/255, means [.485,.456,.406], std [.229,.224,.225]. This follows
   `configs/det/PP-OCRv5/PP-OCRv5_mobile_det.yml`; both axes are mapped back explicitly.
3. Bounded DB-style rectangle decoding: bitmap threshold .3, mean over the entire unexpanded
   rectangle >=.6, minimum side 3 map pixels, rectangular unclip ratio 1.5. Connected-component
   filtering precedes contour extraction. At most 4096 components, the 128 largest eligible
   candidates, 16384 contour vertices per candidate and 32 returned lines. Noisy-map refusal
   and candidate/line truncation are reported. This is an in-house bounded rectangle decoder,
   **not a claim of bit-for-bit equivalence to upstream's general polygon/Clipper code**.
4. Each detected quadrilateral is perspective-warped **directly** from the retained base crop
   to a small 48-pixel-high line bitmap. No third working bitmap or giant rectified line is
   allocated. Tall regions are rotated to their long axis; there is no upside-down classifier.
5. The working recognizer runs sequentially on each line. BGR, height 48, aspect-preserving
   bilinear sampling, `(pixel/255-.5)/.5`, normalized-zero right padding, NCHW. Dynamic tensor
   width is at least 320 and at most 1024; overlong lines fail explicitly rather than stretch.
   CTC uses blank 0, exact dictionary strings, appended space and adjacent-repeat collapse.

Reports contain per-stage timings, raw line text, distinct detector/CTC scores, every detected
region in base and original pixels plus normalized boxes, and forward/inverse coordinate
matrices. These scores are uncalibrated and **not comparable** to ML Kit confidence. No
recognizer-generated word boxes are invented. If detection finds nothing, recognition is not
run on the whole label as a fake single-line fallback. Turn off **Detect text lines first**
for the previous manual single-line experiment.

## Budgets and cancellation

Microsoft ONNX Runtime Android 1.21.1 CPU is debug-only, with two intra-op threads and one
inter-op thread per session. The existing source-built OpenCV 4.12.0 core/imgproc performs
DB geometry. No new runtime dependency is added for the detector.

Both model sessions are app-scoped, individually mutex-protected, and share the normal
scanner's native-engine lease. Native ONNX cancellation requests termination, then retains
RunOptions, tensors and the lease until the synchronous call really returns. OpenCV/native
initialization is cooperative, not a hard real-time guarantee. All temporary Mats and bitmaps
have finally cleanup. The base + detector resize OR base + one 48px line are the only working
bitmaps. Detector probability/label arrays are bounded by 960×960.

The entire ML Kit + Paddle comparison shares eight seconds, including preparation/cold model
loading. All detected boxes are published before line recognition and completed readings are
retained on timeout/failure. No unbounded retries or adaptive image variants are used.

## Validation status

The user confirmed **Merlot** on a tight word crop with build b549663 (45 ms recognizer inference),
while ML Kit returned no text. The previous full-label experiment correctly returned no text
because the entire label was squeezed to 26×48 pixels. Neither result validates the new detector.

Host checks cover detector sizing/BGR normalization, coordinate inverses, box-score arithmetic,
orientation, CTC, malformed inputs and cancellation callbacks. Adapters compile against genuine
ONNX Runtime v1.21.1 Java sources, Android SDK types and vendored OpenCV 4.12.0 Java API sources
(`jenly1314/WeChatQRCode`, commit `cc792b0c51ec35945a84c26abd69c3e399caab77`, compile-only).
Robolectric perspective/two-bitmap tests are authored but not executed here. No Android emulator,
native model inference, full APK build, phone detector timing, peak memory or new APK-size
measurement was performed in this sandbox. Native libraries/release dependencies are unchanged;
this does not assert the experimental debug APK meets the installed-code budget.

Next phone test: select the entire Blackstone label, leave both Paddle/detection checkboxes on,
run, and copy the report. Check whether a detected region covers Merlot and whether that region
reads correctly. Test unrelated labels and blank/decorative regions before production use.
