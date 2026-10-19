# Three-candidate OCR experiment (debug Text Lab)

## Run and export

Profile → OCR Text Lab → **Choose images**. Use the Android document picker's multiple-selection
controls (often long-press the first image, then select the others). Full images are selected
by default. Use **Previous / Next** to review and optionally draw independent crops; a crop
is never reused for another image. Choosing again replaces the queue. Enable any combination:

1. **PP-OCRv5 Mobile detector + Mobile recognizer** — existing pipeline/models.
2. **PP-OCRv6 Small detector + Medium recognizer**.
3. **PP-OCRv6 Medium detector + Medium recognizer**.

All three are enabled by default. **Run all N images** runs images sequentially, with enabled
candidates sequentially within each image. Candidate toggles, enlargement and line-detection
settings apply across the batch. Each image uses one prepared crop for all its candidates,
never differently recropped/enlarged for different candidates. ML Kit runs once per image as
a shared reference. Unchecked candidates have no result rows. With detection off, crop each
image to one line; candidates 2 and 3 intentionally use the same Medium recognizer.

**Export last batch CSV** exports ONE UTF-8 CSV containing every queued image and selected
candidate, including failed imports, partial results and explicitly unrun candidates/images.
No ZIP or photo upload is needed. Stop preserves completed work and marks remaining images
unrun. Ordinary per-image errors do not prevent later images from running; cancellation or
memory pressure stops the batch. A cleared/invalid crop must be fixed before starting.

Each batch has its own UUID-named CSV in `noBackupFilesDir/text-lab-tests`; export is the last
batch, not a concatenation of history. Previous private CSVs remain, including older single-test
CSVs. The last saved CSV remains exportable after replacing the queue or reopening Text Lab.
Checkpoints rewrite the same batch file at candidate/image boundaries. Process death can
leave an unfinished checkpoint with `batch_status=running` and queued/unrun images; it must
not be interpreted as complete. A final-save failure is reported; an earlier checkpoint may
still be exportable. Original imported files/previews are temporary private files, released
when the queue is replaced or Text Lab closes; only the active image is displayed/processed.

The normal scanner, its eight-second shutter deadline, and automatic wine-field assignment
are unchanged. The older 30-treatment OCR Lab is separate; these toggles/CSV belong to Text Lab.

## Timing, memory and fairness

- Shared preparation has an eight-second budget; the ML Kit reference gets a separate 60
  seconds. Each enabled Paddle candidate then gets its own 60 seconds for initialization,
  detection, rectification and line recognition. Three enabled candidates can therefore take
  roughly four minutes **per image** plus preparation/service startup; synchronous native cleanup can overrun.
- Fixed candidate order 1→2→3 is recorded. Each candidate creates fresh ONNX sessions; **asset
  files and OS caches may be warm**. The first run copies/checks newly bundled weights. Repeat
  the test after that initial copy before comparing steady-file-cache timings.
- Detector sessions are closed before loading the recognizer. Recognizer sessions are reused
  across that candidate's lines, then closed before the next candidate. Medium recognition
  weights are packaged once, shared by candidates 2/3, but their sessions are not reused across
  candidates. Gate/mutex ownership survives native termination and cleanup.
- Detector/CTC/ML Kit scores are distinct uncalibrated measures, not interchangeable accuracy
  or independent votes. No expected word, wine dictionary or field correction is supplied.
- Bitmap ownership stays at base + one resize/rectified line. Detector edge ≤960, recognition
  width ≤1024 at height 48, ≤32 retained lines. Component/contour caps remain bounded and are
  reported. All candidates use the same image-size caps.
- Memory columns are **process-wide Java/native heap samples**, not peak RSS or exclusive model
  memory. They are taken at candidate start, detection/recognition completion, and after cleanup.
  Do not treat the maximum of these samples as a measured native peak.

## CSV layout

Schema 2 adds `batch_id`, `batch_status`, `image_id`, `image_name`, 1-based `image_index`, and
`image_count` to every row. Filenames can repeat; group by batch/image IDs, not filename. Each
image retains its own `test_id` and row sequence. Metadata and coordinates never carry over
from another image. Queued/import-failed images can lack input hashes and geometry.

`test_id`, build/device/API, input/crop SHA-256, original/working/crop dimensions, enlargement,
coordinate transforms, selected IDs, execution order, budgets, session policy and model names,
SHA-256 digests/byte sizes/dictionary cardinalities identify the experiment.

Rows have a `stage` and `status`. Relevant stages:

- `detection`: model setup, preprocessing, inference and postprocessing milliseconds, tensor
  shapes, candidate/region counts, truncation and forward/inverse map transforms.
- `region`: every retained detected polygon and normalized box in base/original spaces, with
  detector score and candidate-local `region_id`; published before recognition starts.
- `recognition`: raw text, mean retained CTC posterior (or separately named ML Kit confidence),
  timing, tensor dimensions, rectification transforms, region ID/coordinates where applicable.
- `line_report` / `detection_report`: full readable diagnostics; **not additional OCR votes**.
- `candidate_summary`: one terminal row per enabled candidate, even when stopped or unrun.
  Status distinguishes complete, no_text, no_regions, partial_failed, truncated, timed_out,
  failed, unavailable, memory_pressure, cancelled, cleanup_failed and not_run_*.
- `image_queued` / `image_summary`: per-image execution status (including import failures and unrun images).
- `error`, `checkpoint`, `test_start`/`test_end`, and memory samples preserve operational context.

Use `recognition` rows for text comparisons and `candidate_summary` for completion filtering.
Missing timing/count cells mean unavailable/not completed, not zero. A `test_end` of complete
means that image's candidate loop ended; `image_summary=complete` and `batch_status=complete`
also mean execution finished, not OCR correctness or success of every candidate. Inspect each
candidate's summary for success/failure. Region IDs are local to each image and candidate: candidate 2 region 3 need not be candidate 1 region 3; match by
original coordinates instead. CSV quotes commas/newlines/quotes. Leading spreadsheet formula
characters are escaped with an apostrophe; this does not change inference text internally.

## Real artifacts and model contracts

The v5 pins remain unchanged. New ONNX weights are checksum/size-pinned to GreatV/oar-ocr
**v0.7.0**, recorded in `app/src/debug/assets/text-lab-paddle-v1/candidate-models.json`:

- v6 Small detector: 9,880,512 bytes.
- v6 Medium detector: 62,032,837 bytes.
- v6 Medium recognizer: 76,554,979 bytes.

All five unique ONNX models occupy 169,857,219 bytes before packaging compression. The user
explicitly relaxed the previous 80 MiB asset limit: it is **not enforced** for this experiment.
All are prepared at debug BUILD time regardless of UI selection, stay out of Git, and are
local/offline at recognition time. Future cleanup can remove losing models. Release builds
include neither these debug assets nor ONNX Runtime.

The genuine v6 dictionary is 74,947 bytes, 18,708 entries (CTC expects 18,710 classes including
blank and space), fetched from PaddleOCR at `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf` and matching
the distributor's checksum. Git `-text` preserves both dictionaries byte-for-byte on Windows.
The included upstream Apache-2.0 license applies; no trained weights or dictionary are fabricated.

Upstream YAML contracts were checked for both v6 detectors and Medium recognition: BGR input,
ImageNet detector normalization, 48px-height [-1,1] recognition normalization, CTC plus appended
space. v5 keeps DB .3/.6/unclip 1.5; v6 uses its published .2/.45/unclip 1.4. These values are
exported. The bounded in-house rectangular DB decoder is not claimed to be bit-identical to
upstream's general Clipper implementation. Actual tensor ranks/types/classes are validated;
a mismatch is an explicit failure, not a fake reading.

## Validation limits

Host tests cover all seven nonempty toggle combinations, shared recognizer identity, CSV
quoting/Unicode/formula safety, cancelled/unrun summaries, repeated saves and preservation of
partial observations, plus the existing geometry/CTC/boundary checks. Native adapters compile
against genuine ONNX Runtime 1.21.1/OpenCV 4.12 Java APIs and Android SDK types. This is NOT a
full Gradle APK build or native model execution. V6 ONNX execution, speed, accuracy, OS memory
pressure behavior and the export picker still require phone validation; no emulator/CI wait
was performed here. An unsupported graph/runtime combination will appear as a failed candidate
in the same CSV while preserving other candidates' data.


### Detector probability boundary diagnostics

`detector_output` rows are emitted before probability validation or DB decoding, with model
output name/type/shape, completed setup/preprocessing/inference timings, raw finite min/max,
non-finite count, below-zero/above-one counts and the first invalid value/index. Failed DB
validation therefore no longer hides the raw output range. A symmetric float32 tolerance of
1e-5 is accepted at the 0/1 boundaries; only these tiny excursions are clamped. NaN, infinity,
and values beyond that tolerance still fail explicitly. No automatic sigmoid/logits conversion
or broad clamping is performed. Emulator reports previously lacked the values necessary to
confirm whether boundary roundoff was the cause; rerun on-device to establish that.
