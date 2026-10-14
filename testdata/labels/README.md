# Synthetic wine-label crops

`margaux`, `barolo`, and `napa` are **printed, generated fixtures**, not photos or model-training data.
Each PNG has a matching JSON with six expected fields and synthetic scored `TokenRecord` inputs.
The token scores are **not** claimed to be measured ML Kit results. No weights are supplied.

- `FixtureFieldsTest` uses the expected tokens to exercise merger → lines → fixture KB → validator → fill.
- `OfflineScannerDeviceTest` runs the actual bundled ML Kit / OpenCV pipeline on the PNGs.
- The device test also repeats twenty captures and logs managed/native heap and bitmap ownership.
- Reproduce with `pip install pillow` and `python3 tools/generate-label-fixtures.py` (DejaVu Serif font).
- Fixtures are authored for this repository and can be committed. They are not shipped as app assets.

Original and rectified spaces coincide in the declared synthetic token fixtures. Native warp/device
regressions independently check inverse coordinate mapping. Real field photographs already in
`app/src/test/resources/scanner/labels` remain legacy coverage, not proof of complete KB coverage.
