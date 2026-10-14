# Wine Manager Android

Android wine-cellar manager (`com.n8rtz.winetracker`). The Add Wine camera scanner now uses a local label pipeline: bundled ML Kit Latin OCR, perspective rectification, a small on-device knowledge base, and six independently reviewable fields. Recognition does **not** call a wine-database API.

- Android 8+ (minSdk 26), targetSdk 35; arm64-v8a only.
- Ordinary Android/Google components remain supported. The app's Internet permission is still used by unrelated image-import features.
- No custom OCR/NER weights or wine-world database are fabricated or required.

See [offline scanner architecture, build and tests](docs/offline-scanner.md) and [KB import / optional model formats](docs/offline-scanner-data.md).

**Validation status:** host logic checks pass. A complete Android/native build, device OCR, APK-size measurement and 20-capture heap acceptance have not been run in the restricted development sandbox. The Gradle distribution download fails TLS before configuration; see the test guide for reproducible Android checks and CI.
