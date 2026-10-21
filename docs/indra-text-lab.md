# Indra versus PP-OCRv6 Medium in OCR Text Lab

## Enhanced image profile (new option 5)

For the strongest **currently implemented base-recognizer comparison**, select candidate 3
(v6 Medium + Medium) and **5 · Indra enhanced: orientation + adaptive + LM**. Disable option 4
unless you also want the old raw baseline as an ablation. All three can run in the same batch.
The new stable CSV candidate ID is `indra_enhanced`, profile `enhanced-base-2` (decoder/fallback fixes; original enhanced results used version 1).

Added versus the old raw profile:

- Genuine `orient.onnx`, with the upstream 0.95 confidence threshold for quarter turns.
- Projection-profile deskew: coarse ±45° search, then 0.5° and 0.1° refinements. The final
  deskew canvas **expands with white padding** to retain label corners instead of upstream's
  same-size/border-replicated canvas. This intentional difference is not claimed bit-exact.
- Adaptive detection: initial edge 1024; with at least eight regions and median detector-space
  height below 9px, rerun toward 12px median height, up to 2048. Only the final detection set
  is recognized. Both pass timings are recorded; detections are never treated as extra votes.
- Recognizer maximum width restored to 4096 instead of 1024. Cap increased to 256 retained
  regions instead of 32; the component safety cap remains 4096 and truncation is reported.
- Genuine base `lm.npz`, gated exactly as upstream for greedy confidence from 0.5 to below 0.9:
  CTC prefix beam 8, alpha=.5, beta=1.5, candidate floor=.003. No wine knowledge or expected
  answer is supplied. An isolated recognizer stage does not silently fall back if required LM
  artifacts/outputs fail; the candidate/error rows report that failure.
- `raw_text` is the final accepted reading; `indra_greedy_text` preserves the original reading,
  `indra_unfiltered_text` preserves pre-gate output, and `indra_lm_applied` identifies rescoring.
  `ctc_posterior` remains the **uncalibrated GREEDY posterior**, not confidence in the rescored text.
- Quarter turns/deskew are explicitly inverted before returning original/base polygons and
  line transforms. Original prepared-crop metadata remains identical between candidates.

Additional genuine v1.1.0 assets, downloaded only at debug build time:

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| orient.onnx | 371802 | 20eaf670f55a7cf9facfe1bdd6c7b8e40be75bbf4d48a3df43f66aa3bbd874fd |
| lm.npz | 15695957 | 6c0fb618a363a0acdd05d4f5b0edd9e8146d2d990541b87f6130bbfe17cea58d |

Both pins were checked against the official Hugging Face LFS pointers. Model terms are unchanged.
The NPZ reader permits only bounded numeric NPY arrays (no pickle/extraction/executable objects),
with per-entry and total expansion guards and cancellation checks. Loaded tables are per candidate,
not a permanently retained app cache. Setup/decompression/search all count toward the same
60-second candidate budget; no silent extension. The production scanner and Paddle are unchanged.

**Remaining scope:** this is still the base recognizer, not the full multilingual/document stack.
Script routing, extra script recognizers, vertical-script reading, layout networks, line splitting,
and math are not implemented. A confident vertical-script classification is recorded as unsupported,
not falsely routed through the base recognizer. Thus this improves the Latin-script wine-label test,
but must not be described as complete upstream Indra parity or a universal “best possible” result.

**Executed checks for this update:** decoder parity against official Python golden outputs
(16 backoff queries and 40 beam cases), CTC/geometry/31 selection combinations, host suite,
62 Python checks, genuine Android/OpenCV/ORT adapter API compilation and Kotlin syntax checks.
No full Gradle APK build, new graph execution, native orientation/deskew run, LM load on Android,
performance/accuracy comparison, or end-to-end upstream parity test was executed here.

The instructions/configuration below describe **option 4, the retained raw baseline**.

## Run on the phone

1. Build debug on a supported desktop/CI host, install, and open Profile → OCR Text Lab.
2. Choose one or multiple images and review independent crops with Previous / Next.
3. Leave **3 · v6 Medium detector + recognizer** enabled. Enable
   **4 · Indra v1.1 Latin detector + recognizer (raw)** and accept the displayed model terms.
4. Keep **Detect text lines first** enabled for full-label crops. Use upright, Latin-script labels.
5. Run all images, then **Export last batch CSV**. ML Kit remains a separate shared reference.

Both candidates receive the same prepared crop and enlargement setting, but use independent
text detectors. Runs are sequential, in listed order. Each candidate/reference gets 60 seconds
per image (not the production scanner's 15-second shared deadline). Stop retains completed
rows and explicitly marks unrun selected candidates. Filenames may repeat; group by
`batch_id` / `image_id` / `candidate`, not filename. No expected wine text is provided.

Candidate 3 is selected by default. Indra is opt-in after its terms/scope notice. The previous
v5 and v6 Small candidates remain available for regression testing. Indra-only selection is
also supported. With detection disabled, each image must be cropped to one line; that mode
does not compare detectors.

## Precisely what is being compared

This is a **bounded Kotlin port of Indra's base-script raw detector/recognizer path**, not the
complete upstream Indra document pipeline. It uses the real, unmodified v1.1.0 FP32 models:

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| det.onnx | 389948 | f2bd4cba23e9c328cf7ec824634e423e191a2c6aacbbf35c62bd79dd70f62f59 |
| rec.onnx | 5042161 | 475972a2710be4a3cbfeb2bc7f3624ffd563de07de42476fc7a290d4e1ece484 |
| meta.json | 211869 | 4ce4c94b46f5d644033c02e718322014ff4081270f385038c571912b56696339 |

- Detector: grayscale ink map, long side 1024, white padding to multiples of 32, four-connected
  core components, threshold 0.5, stride-two top/bottom distance decoding and inverse scale.
- Recognition: grayscale line crops, 0.3-height horizontal padding, dark-line polarity detection,
  base height 32 and **three-band context window (96 input rows)**, width padding to eight,
  original unpadded width tensor, greedy CTC blank/repeat collapse with the pinned 1,697-token
  vocabulary. Mean retained posterior below upstream's 0.5 gate gives `no_text`;
  `indra_unfiltered_text` preserves the pre-gate reading for diagnosis.
- Phone bounds: at most 4,096 detector components, 32 retained regions, line width 1,024.
  Upstream permits wider lines and has adaptive detector enlargement; this profile does not.
- **Disabled:** language-model rescoring, script routing/extra script recognizers, automatic
  orientation, deskew, adaptive detection, layout/reading-order networks, line-splitting and math.
  Rows explicitly record this configuration. Do not generalize results to all Indra languages
  or compare these numbers directly with upstream document benchmark headlines.
- ONNX Runtime Android 1.21.1 CPU, intra-op=2/inter-op=1. Indra disables the CPU arena and memory
  pattern optimization. Fresh sessions per candidate, detector released before recognizer;
  one line at a time. Private asset files and OS caches may be warm.

CSV output includes model hashes/source/license, original and base normalized boxes, padded
line↔base transforms, setup/preprocessing/inference timings, status, raw text, posterior,
polarity and process-wide heap samples. Heap samples are not peak or model-exclusive memory.
Scores are not calibrated across engines. Errors/unsupported model contracts are recorded,
never replaced with Paddle or fabricated results. Native cancellation retains tensors, options,
sessions and the app gate until synchronous inference returns.

## Provenance and terms

Models: https://huggingface.co/datalab-to/indra-ocr/tree/v1.1.0

Upstream Python code: https://github.com/datalab-to/indra/tree/0e96b93c6810f17cf8352acbb4487cc9e45b66d5
(`det_post.py`, `rec_preproc.py`, `vocab.py`, `pipeline.py`). The Kotlin port is modified as
specified above. The Apache-2.0 code license is bundled as `indra-v1/LICENSE-code.txt`.

Metadata and model-license bytes were obtained from the unmodified mirror at
https://github.com/PT-Perkasa-Pilar-Utama/ppu-indra-ocr/tree/a1f9fa7fe2b271b237b775f911903db3f591a8f8/models/v1.1 .
Both model SHA/size pins were independently checked against the official Hugging Face v1.1.0
LFS pointers. Build-time model downloads use the official repository and verify SHA/size.

**Weights are not Apache-2.0.** They use Datalab's modified OpenRAIL-M terms, including use
restrictions, attribution, redistribution and output share-alike provisions. Read the complete
bundled `indra-v1/LICENSE` or https://huggingface.co/datalab-to/indra-ocr/blob/v1.1.0/LICENSE .
These terms also matter when sharing the exported Indra results. User authorization to use
these weights for this benchmark was obtained before integration.

Indra is debug-only; no Indra model or inference code is added to production. Debug builds
acquire approximately 5.43 MB of Indra ONNX files, requiring build-machine access to Hugging
Face and its asset hosts. Models stay out of Git. Recognition itself never downloads/uploads.

## Validation and outstanding limits

Executed: pure Kotlin CTC, component geometry, polarity and all 15 candidate-selection
combinations; complete existing host logic suite including streaming partial-result checks;
59 Python configuration/provenance checks. Indra/Paddle adapters compile against genuine
Android 36, OpenCV 4.12 and ONNX Runtime 1.21.1 API sources. No mocked native API was used to
claim adapter compilation.

Not executed: a full Gradle APK build, Android UI/native inference, real Indra graph execution,
accuracy/timing comparison or APK-size measurement. This environment cannot retrieve the model
binaries from their permitted public distribution hosts. The phone CSV is needed to validate
actual graph loading, output contracts, preprocessing fidelity, performance and quality.

The existing Code on the Go failure at `:opencv-minimal:prepareOpenCvToolchain` is **not fixed**
by this change. Source-built OpenCV still requires a supported host with the pinned NDK/CMake;
this is not a claim that the new branch builds inside the Android-hosted IDE.

## Option 6: twelve-pass experiment and diagnostic fixes

Select **3 (Paddle Medium)** and **6 (Indra 12-pass experiment)** for the new comparison.
Enable Detect text lines first. Options 4 and 5 remain optional single-pass baselines.
No production engine/field-assignment change is made by this experiment.

The fixed plan is **three detector edges (1024, 1536, 2048) × orientation/deskew off/on ×
contrast off/CLAHE (clip 2, grid 8×8)**. Unlike option 5, higher resolutions are forced, not
conditional on the adaptive-height trigger that did not fire in the prior phone sample.
Contrast is applied to a private grayscale Mat, never to the shared Bitmap. All transforms
still invert back through the prepared crop to the original image. Each pass starts fresh
native sessions and reloads the LM; these costs are included, not hidden or extrapolated.

**One absolute 60-second budget covers all 12 passes for one image**, including setup and
checkpoints, not 60 seconds per pass. A slow device/image may execute fewer than 12. Stop,
timeout and errors preserve existing rows and explicit unrun-pass summaries. Native cleanup
can overrun. The next image still gets its own budget. Independent options 4/5 each still have
their own existing 60-second budget if also enabled.

CSV candidate ID: `indra_multipass`. Every pass row has `pass_id`, `pass_index`, detector edge,
orientation/deskew and contrast settings. Region IDs are local to each pass; compare using
`batch_id/image_id/candidate/pass_id/region_id` and mapped coordinates. `pass_summary` records
status, timings and counts; `candidate_summary` reports aggregate elapsed time and pass counts.
No automatic “best reading” is selected, and repeated reads are **not independent votes**.
These are ablations to establish whether more computation helps, not an assertion of accuracy.

Fixes prompted by the four `Failed requirement.` lines:

- LM `vocab_size` is a backoff normalization count; the decoder now validates token IDs against
  the **recognizer vocabulary** passed explicitly, not that LM count. Unseen but valid tokens
  use the reference backoff behavior. Truly out-of-range recognizer IDs are still rejected.
- Blank log-probability `-Infinity` is legitimate log(0), not malformed output. NaN, positive
  infinity and invalid positive log-probabilities remain rejected. There is no broad clamping.
- Greedy output is journaled before optional LM decoding. LM setup/output/decoding failures
  retain greedy text with explicit `lm_fallback` / `greedy_fallback` records and a degraded
  candidate status instead of silently losing a valid OCR line. Cancellation and memory
  pressure are not swallowed as recoverable LM errors.
- Shape, vocabulary and probability assertions now name their stage and actual values.
  The previous CSV did not contain those values, so these fixes **do not establish which
  assertion caused each of those four specific failures**. The next phone run will verify it.
- `greedy_recognition` is diagnostic backup evidence, **not another OCR vote**. Use final
  `recognition` rows for finished-line comparisons; inspect greedy backups if a pass was cut
  short. Greedy and LM-rescored text remain separate columns.

Validation for this update: executed host tests including all 63 candidate toggle combinations,
12 unique pass recipes, idempotent unrun summaries, pass-context isolation, LM vocabulary-count
mismatch and zero-probability cases, plus retained upstream golden decoder tests; 64 Python
checks; adapter compilation against real Android/OpenCV/ONNX APIs. No native Android execution,
new accuracy benchmark, full APK build or measured 12-pass completion time is claimed.
The existing Code on the Go OpenCV-toolchain limitation is unchanged.

## Human-readable head-to-head fields

After a batch finishes (including Stop/partial runs), Text Lab shows a horizontally
scrollable table: image, candidate/pass, run status, producer, wine name, country,
region, subregion and vintage. Copy OCR result includes a text version. The existing
batch CSV now includes `field_summary` rows with those six named columns, their
states/reasons, input line count, extractor identity and year. Raw OCR and diagnostic
rows remain unchanged. Blank CSV values / em dashes in the table mean unknown.

This is **OCR plus the same local field-extraction/validation/KB rules**, not raw transcription,
ground truth or an accuracy score. Geography may be inferred through the local
hierarchy. Each candidate and each of the 12 passes is extracted separately; there
is no best-pass selection, cross-engine supplementation, or repeated-vote inflation.
Only final successful recognition (including explicit greedy fallback) is input;
greedy diagnostic rows are not extra readings. All lab evidence is treated as
estimated confidence, so suggestions require confirmation. Unrun passes have blank
fields and explicit status; extraction failures do not discard OCR diagnostics.
Extraction runs on a worker after OCR, outside the recorded inference timings.
Older saved CSVs are not retroactively reprocessed; run a new batch for the table.
The table also applies the existing cellar display convention: a known U.S. state
is displayed in Country, and a generic trailing “Winery” is omitted from producer.

### Blank-table regression fix (lab extractor v2)

The original table adapter gave accepted lines an estimated 0.5 score and sent them
through production FillEngine. Its TokenMerger correctly rejects singleton scores
below 0.6 (0.75 for paddle-prefixed engines), so every line disappeared. The lab now
passes each accepted line directly to the shared field rules and validator, without
that production confidence merge gate. Scores are not boosted; every nonblank field
is forced to YELLOW. Production FillEngine and TokenMerger are unchanged. Mixed
engine/pass inputs are rejected, and no line is counted as independent corroboration.

CSV `field_summary.status` is now `suggestions`, `no_readings`, `no_fields`, or `failed`.
`field_values_found` gives the nonblank count, while `ocr_status` remains the separate
inference outcome. The visible table distinguishes both statuses. The regression
fixture contains only 11 OCR strings/normalized boxes from the reported batch, no
photo or inferred ground truth; it checks Spain/2023, empty input, score preservation,
and rejection of mixed passes. Host checks passed; Android execution is separate.
