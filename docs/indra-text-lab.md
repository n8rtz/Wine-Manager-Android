# Indra versus PP-OCRv6 Medium in OCR Text Lab

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
