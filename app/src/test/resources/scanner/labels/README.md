# Label OCR image fixtures

This directory contains 13 original, full-resolution user-supplied JPEGs (about 45 MB total) for
reference and regression testing of wine-label recognition. They are evaluation data, not training
data: the app uses ML Kit's pre-trained text recognizer and never fine-tunes it from these photos.
The practical tuning loop is to review the per-image OCR output, add verified label terms/aliases to
the wine dictionary, adjust crop/selection logic, then rerun the image test. Real model tuning would
mean replacing ML Kit with a trainable OCR engine plus a far larger annotated data set.

## What each photo shows (read from the images, not from OCR)

| Fixture | Label |
| --- | --- |
| `IMG20261013001626.jpg` | Reciente Gran Reserva, Bodegas Olarra, Rioja, 2019 (Decanter 95) |
| `IMG20261013001611.jpg` | Blackstone Winery Merlot, California (calligraphic "Merlot") |
| `IMG20261011143217.jpg` | Group shot: Kirkland Côtes du Rhône Villages 2023, Olarra Reciente Gran Reserva 2019, Kirkland Brunello di Montalcino 2021 |
| `0ff8abd6-2410-4787-b8e2-096072e180f0.jpg` | Juan Gil, Jumilla, 2020 (EXIF orientation 6: rotated until EXIF is honored) |
| `1e51ac9e-8c20-418e-9ef7-0dd488a52995.jpg` | La Réserve de l'Enclave, Côtes du Rhône Villages, 2022 |
| `216503e7-7a55-4d88-835c-e2efbd2092c1.jpg` | Kirkland Signature Pinot Noir, Russian River Valley / Sonoma County, 2024 |
| `547348d6-2386-420d-9e01-40083844a424.jpg` | OZV Old Vine Zinfandel, Estate Grown, Lodi, 2022 (calligraphic "Zinfandel") |
| `5e548cb5-4106-4c44-9944-470fcc5d10b8.jpg` | Group shot: Kirkland Prosecco Rosé D.O.C., Kirkland Châteauneuf-du-Pape "Cuvée du Terroir des Papes" 2023, Kirkland Napa Valley Cabernet Sauvignon 2021 |
| `98c91789-1fe5-427c-9daf-8df4eda28eda.jpg` | Penfolds Koonunga Hill Shiraz Cabernet, South Australia, 2019 (EXIF orientation 6) |
| `IMG20250509232919.jpg` | Los Haroldos Reserva Malbec, Valle de Uco, Mendoza, 2020 |
| `IMG20250810211557.jpg` | Los Riscos Chardonnay, Chile |
| `IMG20260309213552.jpg` | Group shot: Kirkland Signature Malbec 2022/2023/2024, Mendoza, Argentina |
| `IMG20260609104335.jpg` | Conde de Basalu Crianza, 12 meses en barrica, Rioja, 2021 |

Decode images one at a time in tests to avoid loading the full-resolution set into memory
simultaneously. The group shots contain several bottles, so their suggestions depend on which label
ML Kit reads first; scan one bottle at a time for a trustworthy result.

## Running the tests

- Extraction/form logic and Compose review tests: `./gradlew :app:testDebugUnitTest --tests 'com.yourapp.wine.*'`
- Real local ML Kit image pipeline on a connected **arm64** device/emulator, single line:
  `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.n8rtz.winetracker.scanner.LabelOcrImageRegressionTest"`
  On Windows PowerShell use `.\gradlew.bat`. Do not paste a Unix trailing `\` into PowerShell; keep the command on one line.

The device test scans every JPEG and logs field states, timings and raw OCR under `LabelOcrImageTest`.
It checks bitmap ownership, coordinate bounds, nonempty coverage, stability of validated fields, and
reviewed GREEN values. If the retained OCR directly reads a reviewed producer/wine name, that value
must not be discarded or misassigned merely because it is YELLOW. Unknown producers are legitimate
OCR-only YELLOW; the small bundled KB is not expected to validate every photographed winery. The
three printed fixtures separately exercise six known GREEN fields and the native memory loop.

These instrumented assertions require a real arm64 Android runtime. They have not been executed in
the assistant's sandbox; host expected-token tests are not evidence of pixel-level OCR accuracy.

## Current scanner flow

`AddWineScreen` uses the offline `OfflineWineScanner` path, not the retired barcode/network/crop
ladder. The original photographed JPEG is EXIF-upright and bounded to a 1920px working edge. A quality
check and geometric label detector precede a cheap ML Kit text-hull localization fallback. The
rectified label is read sequentially as original and enhanced variants under the shutter's shared
8000ms deadline, with at most two working Bitmaps. Evidence is inverse-mapped to original capture
coordinates as well as rectified-label coordinates before merging and local field extraction.

No wine-database API runs during recognition. Old barcode/network helpers and parser tests remain
in the repository for their existing callers, but are not injected into this scanner. Ordinary
Android/Google dependencies and the separate explicit image-import feature remain supported.

Producer-language cues and nearby marker/name lines help separate wineries from large cuvee names
or varietals. A full-text shortlist alone may not replace a readable unknown winery with a familiar
producer. The name, vintage and geographic hierarchy are reviewed independently: GREEN validates,
YELLOW requires one-tap confirmation, RED fills nothing. Retakes preserve manual edits.

After scanning, the camera closes back to the normal Add Wine form. All builds offer **Show scan details → Copy diagnostics** explicitly; the report includes raw/mapped/filtered text, timing, geometry, device ABI and build information, not a photo. Debug builds separately offer **Show OCR boxes**; no large wireframe opens automatically. **Choose label photo** runs the same local scanner on a stored image, without camera permission. Clipboard/picker/import/view-model tests require the full Android Gradle environment. Physical ML Kit accuracy, 8000ms timing and 20 actual camera captures
remain separate device acceptance checks; see `docs/offline-scanner.md`.

### Report-driven Blackstone localization case

`LocalizationChecks` uses the rounded box coordinates/scores from the user's actual OnePlus diagnostic report for this exact Blackstone JPEG. Weak probe boxes are geometry hints around a strong anchor, not accepted producer text. The regression keeps the sparse body together, pads missing-glyph/script margins, bounds upscaling, retains inverse coordinates, and schedules an unsharp rescue from usable confidence rather than raw line count. It does not hard-code Blackstone into the KB, change acceptance thresholds, or claim that host logic tests have rerun native ML Kit. The device must rescan the saved image to verify the new readings.
