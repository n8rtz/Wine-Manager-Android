# Label OCR image fixtures

This directory contains 13 original, full-resolution user-supplied JPEGs (about 45 MB total) for reference and regression testing of wine-label recognition. The photos are preserved at their uploaded filenames; the three specifically discussed are:

- `IMG20261013001626.jpg` — Reciente Gran Reserva, Bodegas Olarra, Rioja, 2019.
- `IMG20261013001611.jpg` — Blackstone Winery, Merlot, California; “Merlot” is in a calligraphic font.
- `IMG20261011143217.jpg` — group photo including the Olarra Rioja and Kirkland bottles.

The other ten images are retained under their uploaded date-based or UUID-based filenames. Decode images one at a time in tests to avoid loading the full-resolution set into memory simultaneously.

## Running the tests

- Parser, selector, and other JVM tests: `./gradlew :app:testDebugUnitTest`
- Real ML Kit image pipeline on a connected Android device/emulator: `./gradlew :app:connectedDebugAndroidTest`
- To run only the image regression test, add `-Pandroid.testInstrumentationRunnerArguments.class=com.n8rtz.winetracker.scanner.LabelOcrImageRegressionTest`.

The image regression test scans every JPEG, logs each OCR/parser result under the `LabelOcrImageTest` Logcat tag, and asserts the known Olarra Rioja and Blackstone Merlot results. It requires JDK 17, Android SDK platform 36, and an API 33+ device or emulator. These commands have not yet been run in this workspace because Java/Android tooling is unavailable here.

These fixtures are for evaluation/regression, not model training: the app uses ML Kit’s pre-trained text-recognition model and does not fine-tune it from sample photos. The practical tuning loop is to review the per-image OCR output, add verified label terms/aliases to the wine dictionary, adjust crop/selection logic, then rerun the image test. Actual model fine-tuning would require replacing ML Kit with a trainable OCR model and collecting substantially more labeled text/box data.
