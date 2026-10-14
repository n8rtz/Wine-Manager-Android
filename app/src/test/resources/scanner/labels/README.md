# Label OCR image fixtures

This directory contains 13 original, full-resolution user-supplied JPEGs (about 45 MB total) for reference and regression testing of wine-label recognition. The photos are preserved at their uploaded filenames; the three specifically discussed are:

- `IMG20261013001626.jpg` — Reciente Gran Reserva, Bodegas Olarra, Rioja, 2019.
- `IMG20261013001611.jpg` — Blackstone Winery, Merlot, California; “Merlot” is in a calligraphic font.
- `IMG20261011143217.jpg` — group photo including the Olarra Rioja and Kirkland bottles.

The other ten images are retained under their uploaded date-based or UUID-based filenames. Decode images one at a time in tests to avoid loading the full-resolution set into memory simultaneously.

The current parser unit tests exercise manually transcribed OCR text; they do not invoke ML Kit on these image files. These fixtures are for evaluation/regression, not model training: the app uses ML Kit’s pre-trained text-recognition model and does not fine-tune it from sample photos. Image-level OCR and device validation remain to be run in an Android-capable environment.
