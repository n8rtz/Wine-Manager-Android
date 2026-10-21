# Production ML Kit + PP-OCRv6 Medium

The normal local scanner now registers bundled ML Kit and the genuine PP-OCRv6 Medium detector
+ Medium recognizer (Text Lab candidate 3), replacing the unused optional Paddle-Lite bridge
in service initialization. Wine extraction/knowledge/field-validation logic remains in place.

- Both camera and selected-photo scans use a **15,000 ms shared deadline**, including capture /
  import, preprocessing and OCR, rather than separate 15-second budgets for every engine.
  Native termination/cleanup can overrun; cancellation and partial evidence remain supported.
- ML Kit runs first. Paddle runs once on base `V0` **only when a label is localized/rectified**.
  It never receives a localization/full-frame fallback and is not repeated on V1/V2. ML Kit
  remains available when localization fails or Paddle is missing/unavailable/fails.
- Completed Paddle lines stream through the existing coordinate mapper before cancellation.
  Original and rectified coordinates and engine identities are retained; returned results are
  not published a second time. Native sessions close after each invocation; detector closes
  before recognizer. Only base plus one transient bitmap is used during Paddle inference.
- Raw CTC posterior is preserved, but marked uncalibrated/estimated for field validation. A
  near-1 Paddle posterior alone must not establish GREEN; useful new evidence can require
  confirmation. No vintage-specific override or expected-wine input is added.
- Both debug and release bundle the two Medium models, **138,587,816 bytes** before packaging,
  plus the v6 dictionary. Build-time checksum verification and local-only runtime extraction
  are retained. Debug also retains v5/Small models for the lab. The model/KB combined 80 MiB
  restriction is not applied to these explicitly authorized large models; the KB retains its
  own cap. The 40 MiB code/native check remains; total APK size is reported separately because
  it now includes model assets. New release sizes have not been measured.

This integration was interrupted by the request to add Indra and is included with that lab
addition. See [Indra test instructions and validation limits](indra-text-lab.md). Host logic
checks and adapter API compilation passed; this is not a full APK/device validation claim.
The Android-hosted OpenCV toolchain blocker remains separate and unresolved.
