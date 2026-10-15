# Offline wine label scanner

## Scope and entry points

`AddWineScreen` → `LabelScanCameraPreview` → `ScannerViewModel` → app-scoped `WineScannerServices` → `OfflineWineScanner`.

All recognition/producer/place inference is local. Normal Android/Google dependencies are intentional; this is **not** a prohibition on Google Play services. There is no per-recognition wine API, barcode-to-wine resolver, Retrofit lookup, model downloader or cloud NER in this path. The bundled Latin ML Kit artifact is used instead of a first-use downloadable text model.

`ScannerManager.scanLabel(File, shutterMs)` exposes the same report to compatibility callers. `scanImage(File)` adapts it to the old editable result, omitting RED values; new UI code must use `ScanReport` to retain six confidence states. The unbounded borrowed `scanImage(Bitmap)` API is deprecated and returns manual-entry guidance, not an unsafe full-resolution/crop/network pipeline. Legacy parser/enhancer classes remain for existing tests but are not wired into recognition.

## Contracts and modules

Implementation packages under `app/src/main/java/com/yourapp/wine/`:

| Package | Responsibility |
| --- | --- |
| `capture` | EXIF decode, bitmap ownership, quality gate, shared orchestration, image-free report |
| `geom` | original/working/rectified geometry, quad detection, perspective warp, inverse mapping |
| `preprocess` | at most three lazy rectified-label variants |
| `ocr` | engine contracts, native semaphore, byte ownership, deadline, singleton ML Kit, optional Paddle hook |
| `merge` | NFKD text normalization, bounded edit distance/Jaro–Winkler, union-find and rectified lines |
| `extract` | producer/cuvée/place patterns, vintage context, disabled optional NER interface |
| `kb` | real source fixture, separate bundled SQLite FTS5, import adapter, cached place trie |
| `fill` | hierarchy/vintage/identity validation and GREEN/YELLOW/RED decision |
| `ui` | pure form review policy, one-tap confirmation, original-coordinate debug overlay |

Contracts are in `ocr/Tokens.kt`, `ocr/OcrEngine.kt`, `geom/FrameGeom.kt`, `fill/FillTypes.kt`, and `kb/WineKnowledgeBase.kt`. Public geometry methods document their input space. Engines emit one token per **OCR line**; mixing word tokens with a Paddle line token could union several distinct words and lose the wine name.

### Coordinate spaces

1. **Original** means the EXIF-upright capture, not the sensor's unrotated JPEG raster.
2. Decoder records original dimensions and actual sampled working dimensions; working long edge ≤1920.
3. Contour detector operates at ≤640. Its quad is scaled back to **original pixels**. Fallback ML Kit localization operates on the working frame and is explicitly mapped, never treated as an unmapped crop.
4. Quad → upright perspective label: width ≤1280, long edge ≤1920. No quad → bounded full-frame fallback, with confidence capped YELLOW.
5. `H = H_rectFromWorking × S_workingFromOriginal`. `CoordMapper` maps rectified polygons by `H.inverse()`.
6. Each `TokenRecord` stores four-corner `polyOrig` in original pixels, `bboxOrig` normalized to the original capture, and `bboxRect` normalized to the label. Normalized boxes are clipped to [0,1]. Merger/line geometry uses **rectified**, not full-scene y zones.
7. `ScanReport` keeps the nine homography scalars after native Mats are released. It owns no Bitmap, camera image, Mat or Activity.

There is no cylinder unwrap in this implementation. Adding one requires an explicit invertible coordinate map, not a hidden crop or displacement.

### Quality, variants and voting

- Small grayscale Laplacian variance <28 → RED “Hold steady”. Mean <18 → RED “Add light”; extreme exposure otherwise warns YELLOW. These initial thresholds require device tuning, not claims of measured accuracy.
- Detected quad <12% of original frame → RED “Move closer”.
- Primary OCR reads the **rectified label**. Only a failed visual quad detector invokes the ≤1500ms ML Kit localization probe. The declared pixel-space policy also limits no-quad/full-scene fallback to ML Kit; optional Paddle never receives an unrectified scene.
- V0 raw; V1 highlight compression (above 200, slope .55) + CLAHE (2.0, 8×8) on Lab L. Optional V2 only when V0/V1 have fewer than three distinct tokens. Unsharp/mild denoise is the default optional rescue; adaptive threshold is explicit last-resort only.
- IoU ≥.25 **or** normalized center distance ≤.06; text JW ≥.88 **or** containment with shorter string ≥3 → union-find.
- Per-engine maximum score contributes once: `vote = 1 - product(1 - engineMax)`. Variants are not independent engines. Singleton thresholds: Paddle ≥.75; other/ML Kit ≥.60. Multi-engine clusters with combined vote <.60 are rejected too.
- ML Kit uses conservative element confidences for each line. Missing/unusable confidence gets a marked .65 estimate. GREEN support is recomputed from **measured observations only**, per independent engine: weak real + strong estimated readings still require YELLOW confirmation, while a strong measured specialist is retained. A genuine zero confidence does not count as a second independent supporter. Negative/non-finite/>1 scores are rejected at mapping/merge boundaries, never clipped into a strong probability.
- Display text retains the best read and diacritics. Remaining distinctive wine-name text excludes producer/geography/vintage/ABV/volume/barcode metadata, including when producer and cuvée share one line.

### Deadline, ownership and lifecycle

Camera shutter request records `SystemClock.elapsedRealtime()` **before** JPEG capture. One absolute 8000ms budget includes capture/initialization; OCR/geometry reserve 300ms for field extraction. It is not restarted per engine or variant.

Initialization is an app-scoped `Deferred` on `Dispatchers.Default`, cancellably awaited within the remaining budget. A failed initialization can be retried on the next request, with partially constructed unused engines/DB closed; timing out a caller does not cancel a still-running shared initializer. Completed OCR batches are published immediately. Completed mapped localization evidence is retained if later work fails/times out. Partial/late results still run confidence logic and are capped YELLOW; no candidate is RED. Postprocessing that crosses the budget cannot silently return GREEN.

`BitmapWorkspace` admits at most two owned working Bitmaps. Scene Bitmap is released immediately after warp, before variant allocation. Mats and Bitmaps are released on allocating/finally paths **before releasing the shared scan permit**, including cancellation, so two callers cannot overlap workspaces. CameraX's file-output API owns/closes its internal camera image; the YUV-copy helper closes its `ImageProxy` in `finally`. No concurrent full-photo Coil decode is kept for review.

ML Kit Task itself is **not cancellable**. Input is a separately owned grayscale NV21 byte buffer, not a borrowed Bitmap. Coroutine deadline stops waiting; task completion releases bytes and the shared `NativeEngineGate(Semaphore(1))`. Do not release that lease early and start overlapping native recognition. Bitmaps can be recycled while a late task retains only bytes. A future Paddle bridge must follow the same ownership/deadline contract.

Closing/leaving the camera or backgrounding its lifecycle cancels the scan. Heavy decode/OpenCV/OCR/KB/extraction work and debug-overlay merging run on Default, not the UI thread. Imports must also be performed on a worker thread and not concurrently with a scan. There is no `largeHeap` request.

**Timing limitation:** coroutine cancellation cannot preempt synchronous Android/OpenCV/native calls or OS scheduling. The absolute budget is implemented and fake sleeping-engine cancellation is tested; a physical ≤8000ms latency guarantee is **not yet measured**. Reserve/threshold/working-size tuning must follow device measurements.

### Six field review states

- **GREEN**: locally validated exact identity/hierarchy or unambiguous valid vintage; auto-fill.
- **YELLOW**: unknown boutique, fuzzy/FTS-only match, conflicting reads/hierarchy, weak score/exposure, no quad, or partial/deadline; fill editable text and require a one-tap confirmation per field before Save.
- **RED**: no acceptable value or rejected capture; no incoming fill, retake/manual entry.

Alcohol/volume/barcodes cannot become producer/name. A contradictory country is retained for review, not silently replaced by an invented “correct” country. Country/region/subregion follow KB parent IDs. The deepest explicit origin wins (Oakville within Napa Valley), and ancestor/descendant claims are compatible; sibling/different branches, ambiguous same-name places and wrong geographic levels cannot be GREEN. Extractor/validator share cached ID/name hierarchy indexes rather than rebuilding a KB-sized map for every claim. A local-index failure retains weak readings using the explicitly small real fixture, never invents world coverage.

User-edited values survive retakes (including equivalent edits of GREEN values). A capture failure/timeout also enters an explicit six-field RED review. A RED field on a new capture clears its previous **unedited automatic** suggestion, rather than leaving stale text savable as a new reading; manually edited values remain protected. Editing/keeping a country, region or producer that differs from a new scan downgrades related GREEN name/origin fields for confirmation rather than combining an old country with a falsely GREEN new region. Unknown producers remain savable after confirmation/manual entry. Wine type remains a manual form field, outside this six-field scanner.

## Build on an Android development machine

Requirements: full JDK 17 or 21 (not a runtime-only wheel), Python ≥3.9, Android SDK/command-line tools, SDK platform/build-tools 36, NDK **28.2.13676358**, CMake **3.22.1**, and internet for normal build dependencies. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `local.properties` normally.

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.yourapp.wine.*'
./gradlew --no-daemon :app:checkArm64ApkBudget
```

On Windows use `gradlew.bat` and, if necessary, `-PpythonExecutable=python` for the Python tooling.

The app is minSdk26 / targetSdk35 / compileSdk36, arm64-v8a only. Compile SDK is not target SDK. Keep real CameraX, bundled ML Kit, coroutines, Room (existing inventory), Exif and bundled SQLite dependencies.

`opencv-minimal` builds genuine pinned OpenCV **4.12.0** from the official source archive. SHA-256: `44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d`. `tools/build-opencv-minimal.py` enables only `core,imgproc,java` with static selected modules, and packages generated core/imgproc Java + the upstream bitmap Utils JNI subset. No monolithic opencv-world/AAR, imgcodecs/DNN, fake JNI or generated model weights. The first native build downloads source from GitHub and may take several minutes; generated/downloaded files stay under ignored build directories. Upstream license is packaged with generated Java resources.

Release shrinking is enabled, with JNI/Gson compatibility rules. `checkArm64ApkBudget` builds the actual release APK, checks only arm64 native libraries, reports packed/installed code+libs and total APK size, and fails ≥40MiB. **No APK has been built/measured here**. Optional real OCR/NER assets + SQLite logical pages must remain strictly below 80MiB; no downloader is supplied.

CI `.github/workflows/offline-scanner.yml` installs the real SDK/NDK/CMake, compiles, runs scanner Robolectric tests and checks the measured release APK. CI has been authored, not executed in this workspace. Instrumentation needs an ARM64 device/system image; do not install this arm64-only APK on an x86-only emulator.

## Tests and validation ledger

### Executed in the restricted sandbox

```sh
# Use an installed Kotlin distribution (including its coroutines jar) and real Java runtime.
JAVA_HOME=/path/to/jdk KOTLIN_HOME=/path/to/kotlinc \
  ANDROID_JAR=/path/to/sdk/platforms/android-36/android.jar tools/run-logic-checks.sh
python3 tools/check-kb-import-sql.py
python3 tools/check-offline-recognition-boundary.py
```

The Kotlin harness compiles **production logic/CSV/Reader guards** with real Kotlin 2.2.10/coroutines and tiny, clearly marked host-only PointF/RectF/Bitmap/clock/log shims. A genuine SDK `android.jar` supplies API types, **not executable Android JSON parsing**. It reads all three expected-token fixtures. It does **not** OCR images, execute Android `JsonReader`, run Android/native SQLite, measure native memory or substitute for Gradle. `ANDROID_HOME`/`ANDROID_SDK_ROOT` with platform36 can supply the jar automatically.

**28 Kotlin host groups passed:** inverse mapping; NFKD; merger/votes; rectified lines; vintage; hierarchy conflicts and nested specificity; fill/form states, capture rejection/shutter timing and clearing of stale automatic suggestions; extraction edges; failed-index partial preservation; measured-vs-estimated confidence and zero/invalid-score guards; three six-GREEN expected-token fixtures; optional-engine rectified-only policy; deadline/partial/cleanup; expired deadline; native lease serialization; two-bitmap ownership accounting; streaming CSV/quoting/alias order/caller ownership; malformed records; byte/row/UTF-8 guards; JSON lexical guards (not Android parser execution); 25,000 lazily generated rows delivered incrementally.

**2 host SQLite groups passed:** `check-kb-import-sql.py` executes the production schema/staging/transfer/FTS SQL with Python's real SQLite/FTS5, tests arbitrary parent/alias ordering, and verifies atomic data+FTS rollback on invalid foreign keys. This validates SQL semantics on **host SQLite**, not the bundled Android driver/JNI. It needs Python with SQLite/FTS5 and leaves no database artifact.

A separate limited Kotlin backend type-check succeeded with a real Android35 API jar and genuine generated OpenCV Java APIs, but **ML Kit/Tasks/SQLite/Exif were validation-only stand-ins**. It excluded Compose/camera/VM integration and is **not an Android build or dependency-signature verification**. These disposable harness inputs are ignored, not packaged or committed.

`check-offline-recognition-boundary.py` guards project-owned recognition modules/facade and five ViewModel scan entry points against HTTP/wine-network dependencies. Normal Android/Google APIs and the explicit image-import feature remain allowed. This is a source regression guard, not instrumentation or a claim that SDKs never perform their own ordinary network activity.

`git diff --check` also passes.

### Authored, not executed here

- Robolectric: `ScannerLogicTest`, `GeometryTest`, `FixtureFieldsTest`, `DeadlineTest`, `BitmapWorkspaceTest`, `OptionalEngineTest`, `OpenWinesAdapterTest` (real Android JSON parsing, malformed/oversized input and CSV edge cases).
- `OfflineScannerDeviceTest`: real ML Kit/warp on three printed labels, inverse/bbox/variant/budget checks, and a warmed 20-scan Java/native heap-growth/ownership check.
- `SqliteKnowledgeBaseDeviceTest`: genuine bundled FTS5, streamed CSV/JSON import, late-schema/duplicate/FK/hierarchy rollback, caller stream ownership and temporary-file cleanup.
- Existing 13-photo `LabelOcrImageRegressionTest`: GREEN values must agree with reviewed truth; unknown YELLOW and rejected RED are explicit, rather than requiring the tiny fixture KB to know all brands. Strict synthetic/device tests separately guard against an all-RED no-op pipeline.

On an ARM64 device:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yourapp.wine.OfflineScannerDeviceTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yourapp.wine.SqliteKnowledgeBaseDeviceTest
# Then run the full app test suite and the existing photo regression set.
```

Also perform **20 actual CameraX shutter captures**, not just file scans. Record shutter→review ms, per-engine ms/token counts/deadline cancellations, `peakBitmaps/alive`, Java/native heap after warmup, and final release APK size. Test airplane mode, unknown boutique confirmation/save, wrong-country hierarchy, glare/blur/darkness/tiny label, fast retakes, background/resume, missing optional assets, and failure/manual entry. File-loop tests are not a substitute for camera/lifecycle/OEM measurements.

### Phase checkpoint status

| Phase | Source / limited validation | Android checkpoint |
| --- | --- | --- |
| 0 contracts/fixture/logic | implemented; host logic/fixture assertions pass | blocked before configuration |
| 1 camera/ML Kit/review | implemented | build/device pending |
| 2 quad/warp/variants/overlay | implemented; inverse algebra passes | native/20 captures pending |
| 3 merger/extractor/FTS/hierarchy | implemented; pure logic passes | genuine SQLite/device OCR pending |
| 4 optional Paddle | honest presence/bridge interface; no weights/bridge supplied | ML Kit-only default; native Paddle intentionally unavailable |

Gradle checkpoint attempts fail downloading Gradle9.3.1 (`SSLHandshakeException` / unexpected TLS EOF from services.gradle.org), before project configuration. No SDK/NDK/emulator is available in this sandbox. Do not describe the phases as Android-compiled, Robolectric-run, APK-under-budget, or 20-capture/OOM accepted until the real commands above succeed.
