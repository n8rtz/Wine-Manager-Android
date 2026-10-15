# Wine Manager Android

Android wine-cellar manager (`com.n8rtz.winetracker`). The Add Wine camera scanner now uses a local label pipeline: bundled ML Kit Latin OCR, perspective rectification, a small on-device knowledge base, and six independently reviewable fields. Recognition does **not** call a wine-database API.

- Android 8+ (minSdk 26), targetSdk 35; arm64-v8a only.
- Ordinary Android/Google components remain supported. The app's Internet permission is still used by unrelated image-import features.
- No custom OCR/NER weights or wine-world database are fabricated or required.

See [offline scanner architecture, build and tests](docs/offline-scanner.md) and [KB import / optional model formats](docs/offline-scanner-data.md).

**Validation status:** [Genuine Android CI passed](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/38661171436) for source commit `4161b12`: debug app and instrumented test APKs compile, scanner Robolectric tests pass, and the shrunk arm64 release APK measures **24.51 MiB** (packed code + libraries **22.20 MiB**, below 40 MiB). The local harness also passes 28 logic groups and two host SQLite/FTS5 groups. Real-device OCR, physical shutter→review timing, and 20-camera-capture heap/OOM acceptance remain unverified. See the test guide for those remaining checks.
