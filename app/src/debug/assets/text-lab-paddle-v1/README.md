# Experimental PP-OCRv5 mobile line recognizer

This directory contains the genuine PaddleOCR character dictionary, upstream Apache-2.0
license, and pinned model provenance. The **actual ONNX model is generated into debug assets
by `:app:prepareTextLabPaddle`**, not manufactured or represented by a stub. Debug builds
fetch the 16,562,373-byte ONNX conversion from GreatV/oar-ocr v0.3.0 and require the exact
published SHA-256. The digest is also recorded in that project's model registry at commit
84817f58d7a0932d5368a9fa18105ba5d842af29. Failure to fetch/verify fails model preparation;
no empty file or fake model is substituted. Generated weights stay out of Git.

Upstream training/model family: PaddlePaddle/PaddleOCR PP-OCRv5_mobile_rec (general multilingual
mobile model, not the English-only variant). ONNX conversion/distribution: GreatV/oar-ocr.
This is not a claim of an independently verified training history or a model trained on these
wine photos. Dictionary bytes were obtained from upstream PaddleOCR at the revision in
provenance.json and match the distribution's dictionary checksum exactly.

Runtime: Microsoft ONNX Runtime Android 1.21.1, CPU, two intra-op threads, one inter-op thread.
It and these assets are **debug-only**. No HTTP client runs on a photo or during recognition.
First use copies and checks assets into private no-backup storage; one app-scoped session is
reused under a mutex and the existing native-engine lease. That lease remains held until the
synchronous ONNX call actually exits, even after cancellation requests native termination.

Input is a manually selected **single word/line**, not a whole label. The original model's
Paddle training/inference contract is BGR, NCHW, height 48, bilinear aspect-preserving resize,
normalization `(pixel/255 - .5)/.5`, and right padding with normalized zeros. Dynamic widths
use at least 320, capped at 1024 for this mobile experiment; overly wide crops are refused.
The upstream training YAML specifies BGR; do not silently switch to RGB based on a different
Android demo's preprocessing. Input/output ranks, types, and dictionary cardinality are checked.
The decoder uses CTC blank index 0, exact dictionary strings, appended space, and consecutive
repeat collapse. Its mean retained CTC posterior is NOT calibrated or comparable to ML Kit's
confidence. No wine dictionary, expected answer or spelling correction is used.

The UI displays both engines' raw outputs separately on the same user-selected crop, with
model-specific preprocessing. PP-OCRv5 supplies no detected word boxes; do not treat its
whole-line reading as a detected word polygon. Only Text Lab calls it; the ordinary scanner,
30-case benchmark and optional Paddle Lite full-label bridge remain unchanged.

## Validation limits

Host checks cover CTC, sizing, channel order, malformed tensors and the cancellation callback.
The Android adapter was compile-checked against genuine ONNX Runtime v1.21.1 Java sources
and an Android SDK jar. This is not an APK build or native inference test.
The sandbox denied GitHub release-asset CDN downloads, so the actual model graph has **not**
been executed here. No accuracy uplift, Merlot result, phone timing, peak native memory, or
debug APK size is claimed. Debug builds need working build-machine access to GitHub release
assets and the usual Maven dependency repositories; installed recognition is offline. Verify
debug APK/native size on the build machine before considering production deployment.

### Windows packaging and failure diagnostics

The upstream `ppocrv5_dict.txt` is packaged as `ppocrv5_dict.utf8` with Git `-text`
attributes. Its bytes and SHA-256 are unchanged. This new filename ensures an existing
Windows checkout receives a fresh byte-exact artifact rather than retaining a previously
CRLF-converted `.txt` copy. Runtime length/hash verification remains strict.

Input metadata accepts symbolic (negative) ONNX dimensions while still requiring any
fixed batch/channel/height dimensions to match 1/3/48. Actual input is constructed at
that shape, and actual output cardinality remains checked after inference. Errors now
identify the processing stage and exception detail, including incompatible tensor shapes;
a failure must not be interpreted as the recognizer seeing no text.
