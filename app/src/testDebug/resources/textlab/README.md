# Blank field-table regression

`table-regression.tsv` contains the 11 final ML Kit recognition strings and normalized
base boxes from image 1 of user-supplied batch
`202f4888-0043-49ca-9972-05d82336411e`. No photo or full device telemetry is included.
Spain and 2023 are explicit OCR readings, not corrections injected into the app.
The test reproduces the old 0.5 singleton-filter bug and verifies lab-only extraction
retains those suggestions without changing production thresholds or raising scores.
