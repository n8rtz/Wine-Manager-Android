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

- Parser, selector, and other JVM tests: `./gradlew :app:testDebugUnitTest`
- Real ML Kit image pipeline on a connected API 33+ device/emulator: single line, e.g.
  `./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.n8rtz.winetracker.scanner.LabelOcrImageRegressionTest"`
  (On Windows with PowerShell, keep the whole command on one line: a trailing `\` is parsed as a
  separate Gradle task and then PowerShell treats the `-P...` argument as its own command. Use the
  PowerShell backtick for continuation instead.)

The image regression test scans every JPEG, logs each result under the `LabelOcrImageTest` Logcat
tag, and asserts the reviewed fields for the single-bottle photos (Olarra, Blackstone, Zinfandel,
Penfolds, Basalu, Juan Gil, Enclave, Kirkland Pinot Noir, Los Haroldos, Los Riscos, Kirkland
Malbec). When an assertion fails, its message carries the raw OCR text, so the difference is either
ML Kit's reading or this app's parsing of it. These instrumented assertions require a device; they
have not been executed in the assistant's sandbox, which has no Android SDK or emulator.
