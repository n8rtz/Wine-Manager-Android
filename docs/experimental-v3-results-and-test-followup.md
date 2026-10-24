# Experimental v3 results and scanner-test follow-up

Actual Android replay: https://github.com/n8rtz/Wine-Manager-Android/actions/runs/39806541535

- 168/168 completed, zero extraction failures, 123.059 seconds instrumentation time.
- Baseline executions: 0; old experimental executions: 0. Saved OCR reused.
- Correct/wrong/abstained: **599/84/211**, versus v2 **566/121/207**.
- Fully correct six-known images: **19/103**, versus v2 **8/103**.
- Producer correct/wrong/abstained: **55/34/76**, versus v2 **24/70/71**.
- Saved lab baseline remains **597/100/197**, with **23/103** fully correct images.

This supports retaining the heading-role change for further experimental work,
not promoting it to production: more total correct fields and fewer wrong fills
than the lab baseline, but fewer completely correct entries and more abstentions.
The reference remains provisional single-agent review, and this is development-set
feedback. v3 also has a larger diagnostic budget than historical v2.

## Separate scanner test failure

Run https://github.com/n8rtz/Wine-Manager-Android/actions/runs/39806541183 failed at
`Scanner Robolectric and deadline tests`. Its check annotation names:

1. `SelectedLabelPhotoImporterTest.oversizedInputsAreRejectedAndOnlyThePrivateCopyIsRemoved`:
   FileProvider could not find a configured root for the current Robolectric test's
   cache file. AndroidX caches path strategies by authority while test sandboxes
   change cache directories. Test setup/teardown now clears only the test app's
   authority in that cache. The manifest roots and importer are unchanged.
2. `PaddleLineWarpTest.perspectiveInverseAndTwoBitmapBudget`: `Singular detected-line warp`
   from Android Matrix.setPolyToPoly. The test now explicitly uses Robolectric
   native graphics (API 35), rather than the legacy graphics shadow, to exercise
   the real perspective transform. All inverse-coordinate and bitmap-budget
   assertions remain intact; production warp code is unchanged.

These are test-environment corrections pending a fresh scanner CI result, not a
claim that the whole branch is green. No test was skipped and no expected output
was loosened. This test-only push does not trigger the saved-evidence workflow.
