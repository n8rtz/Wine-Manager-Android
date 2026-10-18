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

Contracts are in `ocr/Tokens.kt`, `ocr/OcrEngine.kt`, `geom/FrameGeom.kt`, `fill/FillResult.kt`, and `kb/WineKnowledgeBase.kt`. Public geometry methods document their input space. Engines emit one token per **OCR line**; mixing word tokens with a Paddle line token could union several distinct words and lose the wine name.

### Coordinate spaces

1. **Original** means the EXIF-upright capture, not the sensor's unrotated JPEG raster.
2. Decoder records original dimensions and actual sampled working dimensions; working long edge ≤1920.
3. Contour detector operates at ≤640. Its quad is scaled back to **original pixels**. Fallback ML Kit localization operates on the working frame and is explicitly mapped, never treated as an unmapped crop.
4. Quad → upright perspective label: width ≤1280, long edge ≤1920. Crops may be enlarged by at most 2× within those bounds (cubic interpolation for enlargement); dimensions/H remain invertible. No quad → bounded full-frame fallback, with confidence capped YELLOW.
5. `H = H_rectFromWorking × S_workingFromOriginal`. `CoordMapper` maps rectified polygons by `H.inverse()`.
6. Each `TokenRecord` stores four-corner `polyOrig` in original pixels, `bboxOrig` normalized to the original capture, and `bboxRect` normalized to the label. Normalized boxes are clipped to [0,1]. Merger/line geometry uses **rectified**, not full-scene y zones.
7. `ScanReport` keeps the nine homography scalars after native Mats are released. It owns no Bitmap, camera image, Mat or Activity.

A V2 aspect rescue resizes the **whole rectified label** to half-height (rounded, minimum 16px), not an unmapped word crop. The ensemble projects each returned polygon back with `S_baseFromVariant = diag(baseW/inputW, baseH/inputH, 1)` **before** `CoordMapper` inverts the original homography. Actual input/base dimensions are recorded in the pass detail, including odd-height rounding. Text, scores, engine IDs and independent-vote rules are unchanged; the native Mat is resized before allocating the single variant Bitmap. V0/V1 retain their original geometry and the total remains at most three variants.

There is no cylinder unwrap in this implementation. Adding one requires an explicit invertible coordinate map, not a hidden crop or displacement.

### Quality, variants and voting

- Small grayscale Laplacian variance <28 → RED “Hold steady”. Mean <18 → RED “Add light”; extreme exposure otherwise warns YELLOW. These initial thresholds require device tuning, not claims of measured accuracy.
- Detected contour quad <12% of original frame → RED “Move closer”. A text-envelope proposal must itself cover at least 12% after padding before it is considered a label quad; tiny text fragments are declined, not mistaken for proof that the entire label is too small.
- Primary OCR reads the **rectified label**. Only a failed visual quad detector invokes the ≤1500ms ML Kit localization probe. The declared pixel-space policy also limits no-quad/full-scene fallback to ML Kit; optional Paddle never receives an unrectified scene.
- Text-hull localization requires a spatially coherent group with at least one ≥.60 anchor. Positive-score weak boxes can extend that geometry, but their text is NOT promoted into field evidence: the primary recognizer rereads the rectified label and unchanged merger/validator thresholds still apply. Grouping uses box edge gaps/overlap rather than center spacing. The envelope follows the median text-baseline angle (rather than accidentally tilting a tapered group of unequal line widths), and bounded 1.35× padding leaves room for missed glyphs/lines. Separate bottle columns are not joined merely because their y coordinates match.
- V0 raw; V1 highlight compression (above 200, slope .55) + CLAHE (2.0, 8×8) on Lab L. Conditional V2 is a color-preserving unsharp/mild-denoise rescue (or a mapped vertical aspect rescue for weak, unusually tall/condensed glyphs) when meaningful, measured supported text is sparse or plausible identity text remains weak. Repeated variants, bare winery markers, years and packaging do not inflate its usable-line count. It stays within the same absolute deadline, three-variant cap and raw-plus-one-extra Bitmap ownership. Adaptive threshold is explicit last-resort only, never the default color/foil rescue.
- IoU ≥.25 **or** normalized center distance ≤.06; text JW ≥.88 **or** containment with shorter string ≥3 → union-find.
- Per-engine maximum score contributes once: `vote = 1 - product(1 - engineMax)`. Variants are not independent engines. Singleton thresholds: Paddle ≥.75; other/ML Kit ≥.60. Multi-engine clusters with combined vote <.60 are rejected too.
- ML Kit uses conservative element confidences for each line. Missing/unusable confidence gets a marked .65 estimate. GREEN support is recomputed from **measured observations only**, per independent engine: weak real + strong estimated readings still require YELLOW confirmation, while a strong measured specialist is retained. A genuine zero confidence does not count as a second independent supporter. Negative/non-finite/>1 scores are rejected at mapping/merge boundaries, never clipped into a strong probability.
- Display text retains the best read and diacritics. Remaining distinctive wine-name text excludes producer/geography/vintage/ABV/volume/barcode metadata, including when producer and cuvée share one line.

### Blackstone device-report regression

The supplied `IMG20261013001611.jpg` matches the existing evaluation JPEG byte-for-byte (SHA-256 `c763e25933d26cd5a50681b300ce2cd9e81202d471116e4742501a994cdb9153`); no duplicate photo or model weights were added. The OnePlus report for `f662329` showed native loading/quality succeeded, no deadline cancellation (856ms), no quad, and only CALIFORNIA surviving confidence filtering. Producer/marker probe scores .511/.453 were below the old localizer's .60 cutoff, so the primary path reused a full-frame probe and never proposed the label crop.

`LocalizationChecks` replays the report's rounded normalized V0 boxes as working AABBs. It checks that weaker boxes can join the strong location anchor without being accepted as field text, the sparse brand-to-footer body stays together, padded bounds include the missing-left-glyph/script region, unrelated columns/invalid boxes are excluded, upscaling stays bounded, and enlarged coordinates round-trip to original pixels. It also verifies scheduling a third rescue from this report instead of incorrectly treating four low-quality raw lines as sufficient. These are geometry/scheduling regressions, **not** a native ML Kit replay or proof that the new phone read recognizes Merlot. No producer spelling or wine identity is hard-coded from this photo. Rescan the same saved photo and inspect the new diagnostic report for `quad_source=text_hull`, `RECTIFIED_LABEL` input, and a V2 pass only if rescue is still needed.

### Kirkland parsing and shared local vocabulary

The supplied `89b560b` Kirkland report already read KIRKLAND, CABERN ET SAUVIGNON, ALEXANDER VALLEY, SONOMA/SONO MA COUNTY and 2022 above the acceptance threshold. Treating the split grape word as a producer was an extraction bug, not a failed native build or a deadline issue. `ReportedWineChecks` replays the rounded actual V0/V1 evidence. It expects a YELLOW **Kirkland Signature** alias and YELLOW **Cabernet Sauvignon** name, the deepest supported **Alexander Valley** subregion, California/United States parents, and vintage 2022. The raw KIRKLAND evidence does **not** contain Signature: the display name comes from the existing authored alias and is explicitly unvalidated for origin.

The old parser's six literal vocabulary tables (red/white/other grapes, styles, producer names/aliases, appellations) are now shared data in `kb/Bundled*.kt`, not duplicated or replaced with an invented catalog. A parity check verifies their original rows. `WineTermSpacing` repairs **exact known letters** across OCR-inserted word breaks, including CABERN ET and SONO MA; it does not turn LACKSTONE into BLACKSTONE or otherwise guess missing/substituted letters. Both measured confidence and raw diagnostic evidence stay intact. Equivalent known spacing is not an OCR contradiction; different letters and explicit country/hierarchy conflicts still require YELLOW confirmation. Curated producer aliases are exact/spacing-only, remain YELLOW, and never imply a country or cuvee. A style/appellation actually printed on a label can supply a YELLOW wine name if no distinctive cuvee was read.

`SupplementedWineKnowledgeBase` presents the existing authored country/region/appellation rows alongside the committed SQLite snapshot. Supplemental IDs are virtual negatives (the import adapter accepts positive IDs), do not enter SQLite, and do not overwrite any imported record or hierarchy. This makes new origins available to already-installed databases without a wipe/schema migration. Explicit real containment links Alexander Valley/Russian River Valley to Sonoma County, and Oakville/Rutherford to Napa Valley. Existing imported parents win; ambiguities/conflicts remain reviewable rather than being silently corrected. This is still a small curated vocabulary, **not** worldwide wine coverage.

Blackstone's later report showed the label crop and all three photometric passes worked, but the condensed producer remained .480–.513 and the script name was not detected. No lexical alias may promote those scores. The new conditional V2 aspect input is an additional real recognizer attempt with an explicit inverse, not a claimed pixel-recognition success. Its selection excludes packaging and bare winery markers and uses weak text geometry rather than full-frame field-position zones. Rescan that same stored photo to measure whether the new input helps the stock recognizer.

### Deadline, ownership and lifecycle

Camera shutter request records `SystemClock.elapsedRealtime()` **before** JPEG capture. One absolute 8000ms budget includes capture/initialization; OCR/geometry reserve 300ms for field extraction. It is not restarted per engine or variant.

Initialization is an app-scoped `Deferred` on `Dispatchers.Default`, cancellably awaited within the remaining budget. A failed initialization can be retried on the next request, with partially constructed unused engines/DB closed; timing out a caller does not cancel a still-running shared initializer. Completed OCR batches are published immediately. Completed mapped localization evidence is retained if later work fails/times out. Partial/late results still run confidence logic and are capped YELLOW; fields without an acceptable candidate remain RED. Postprocessing that crosses the budget cannot silently return GREEN.

`BitmapWorkspace` admits at most two owned working Bitmaps. Scene Bitmap is released immediately after warp, before variant allocation. Mats and Bitmaps are released on allocating/finally paths **before releasing the shared scan permit**, including cancellation, so two callers cannot overlap workspaces. CameraX's file-output API owns/closes its internal camera image; the YUV-copy helper closes its `ImageProxy` in `finally`. No concurrent full-photo Coil decode is kept for review.

ML Kit Task itself is **not cancellable**. Input is a separately owned grayscale NV21 byte buffer, not a borrowed Bitmap. Coroutine deadline stops waiting; task completion releases bytes and the shared `NativeEngineGate(Semaphore(1))`. Do not release that lease early and start overlapping native recognition. Bitmaps can be recycled while a late task retains only bytes. A future Paddle bridge must follow the same ownership/deadline contract.

Closing/leaving the camera or backgrounding its lifecycle cancels the scan. Heavy decode/OpenCV/OCR/KB/extraction work and debug-overlay merging run on Default, not the UI thread. Imports must also be performed on a worker thread and not concurrently with a scan. There is no `largeHeap` request.

**Timing limitation:** coroutine cancellation cannot preempt synchronous Android/OpenCV/native calls or OS scheduling. The absolute budget is implemented and fake sleeping-engine cancellation is tested; a physical ≤8000ms latency guarantee is **not yet measured**. Reserve/threshold/working-size tuning must follow device measurements.

### Repeatable photo scans and copyable diagnostics

In **Add New Wine**, use **Choose label photo** to select an existing label image using Android's system photo picker (with its document-picker fallback on older devices). This is a local scan, not a server upload. It does not require camera permission or broad media/storage permission. A cloud-backed photo provider may download the image you explicitly select; recognition itself never calls a wine API.

The original compressed bytes are streamed into an app-private temporary file, capped at **32 MiB**. The user's original is never changed/deleted. The same `CaptureDecoder`, EXIF orientation, quality/quad/warp, sequential OCR, KB/field validation and form review paths are used for camera and selected photos. The original-image dimensions remain the coordinate reference. There is no extra full-resolution preview Bitmap or JPEG recompression. The existing 100-megapixel input-dimension guard, 1920px working edge, rectification bounds and two-working-Bitmap limit remain in place. The eight-second deadline starts when the picker returns the selected URI and includes copying/initialization; time spent browsing the picker is not charged. Imports use IO workers, a provider cancellation signal, cancellable chunked transfer and explicit stream closure. Cancellation asks the provider to stop; physical timing with uncooperative providers still needs device verification. Private copies are removed on completion/error/cancellation. Cancelling the picker leaves the previous form/result unchanged.

After **any** completed/failed scan, tap **Show scan details → Copy diagnostics** and paste the text into the support conversation. Text diagnostics are opt-in in **debug and release builds**. Wireframes are a separate **Show OCR boxes** action available only in debug builds and never auto-open. The report is generated on Default only when requested, is capped at **32,000 characters**, and includes:

- Input source (camera/photo library/file), byte count/import time, original/working/rectified sizes and label-localization method.
- Build version, Git commit/branch/dirty state, Android/device model/hardware and device versus packaged ABIs. No serial, Android ID, account or installed-app inventory is queried.
- Native readiness/version, quality measurements, stage/pass timings, deadline stage and exception **types/codes** (not exception messages containing private paths).
- Engine availability (including missing optional assets), up to 30 localization/80 primary raw samples, mapping failures, up to 80 mapped readings before confidence filtering, retained clusters and per-field scanner decisions.

The report **does contain label OCR text**: review it before sharing. No photo, content URI, GPS EXIF metadata, account data or current manually edited cellar fields are attached. Nothing is automatically uploaded or written to shared storage. Copying is explicit; Android 13+ clipboard previews are marked sensitive. This supports debugging native/ABI failures and the difference between missing OCR, coordinate rejection and low-confidence filtering without keeping a full photo alive. For emulator testing, first put a label image into the emulated device's storage, then choose it in the app. Native ABI compatibility is still required.

### Six field review states

A completed scan closes the camera and returns to the **same Add Wine form**, scrolled to its review summary. It does not navigate to a separate result screen. The keyboard loses focus when the camera opens, and stale autocomplete menus close when a new scan is applied. The summary shows how many fields were read, pending confirmations, and a retake action. Text diagnostics are hidden by default, opened with **Show scan details**, and reset on each capture request (even an identical retake). They are copyable in all builds; wireframes require a separate debug-only **Show OCR boxes** action. Per-field explanations are limited to two visible lines so they cannot push the form far down the screen. A location/year-only reading explicitly says the producer and wine name were not read; geographic metadata is not presented as successful wine identification. Status text and confirm/retake controls use light/dark semantic foregrounds instead of dark red on a dark background. Opt-in diagnostics distinguish raw, coordinate-rejected, mapped and retained readings, so a single retained overlay box cannot be confused with the complete OCR output.

- **GREEN**: locally validated exact identity/hierarchy or unambiguous valid vintage; auto-fill.
- **YELLOW**: unknown boutique, fuzzy/OCR-only match, conflicting reads/hierarchy, weak score/exposure, no quad, or partial/deadline; fill editable text and require a one-tap confirmation per field before Save.
- **RED**: no acceptable value or rejected capture; no incoming fill, retake/manual entry.

Alcohol/volume/barcodes cannot become producer/name. A contradictory country is retained for review, not silently replaced by an invented “correct” country. Country/region/subregion follow KB parent IDs. The deepest explicit origin wins (Oakville within Napa Valley), and ancestor/descendant claims are compatible; sibling/different branches, ambiguous same-name places and wrong geographic levels cannot be GREEN. Extractor/validator share cached ID/name hierarchy indexes rather than rebuilding a KB-sized map for every claim. A local-index failure retains weak readings using the explicitly small real fixture, never invents world coverage.

Producer selection uses literal label-language cues, not the largest printed word alone. Nearby aligned marker/name lines (for example `Blackstone` + `WINERY`) can be joined with both sources preserved; a distant marker cannot borrow unrelated text. Varietals, generic reserve/style text and bare winery markers cannot become unknown producers. Repeated brand text can disambiguate a production/origin footer from the prominent brand. FTS supplies a shortlist: it may not rename an unknown winery merely because one word overlaps a known producer. A bounded name match or an exact whole-label producer phrase is required; unsupported identities remain OCR-only YELLOW. These are extraction heuristics, not new OCR weights or claims of worldwide KB coverage.

User-edited values survive retakes (including equivalent edits of GREEN values). A capture failure/timeout also enters an explicit six-field RED review. A RED field on a new capture clears its previous **unedited automatic** suggestion, rather than leaving stale text savable as a new reading; manually edited values remain protected. Editing/keeping a country, region or producer that differs from a new scan downgrades related GREEN name/origin fields for confirmation rather than combining an old country with a falsely GREEN new region. Unknown producers remain savable after confirmation/manual entry. Wine type remains a manual form field, outside this six-field scanner.

## Build on a desktop Android development machine

Requirements: full JDK 17 or 21 (not a runtime-only wheel), Python ≥3.9, Android SDK with Command-line Tools (latest), SDK platform/build-tools 36, and internet for normal build dependencies. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or `sdk.dir` in `local.properties` normally.

The custom OpenCV build uses pinned **NDK 28.2.13676358** and SDK **CMake 3.22.1** (including Ninja). `:opencv-minimal:prepareOpenCvToolchain` runs before `buildOpenCv`, verifies the installed package versions/files, and prefers the current Android CLI (falling back to the legacy Java SDK manager) to install only missing packages into the selected SDK. It does not silently accept licenses. Android Studio's selected Gradle JDK is passed to the child tools; Windows defaults to `python`, other hosts to `python3`.

### Fix “NDK is not installed”

These instructions are for desktop hosts (Windows/macOS/Linux). Native source builds on an Android phone have not been validated; the setup helper will not download incompatible desktop SDK executables onto a phone.

In **Android Studio → Tools → SDK Manager → SDK Tools**, enable **Show Package Details**, then install:

- **NDK (Side by side) → 28.2.13676358**
- **CMake → 3.22.1**
- **Android SDK Command-line Tools (latest)** (for automatic missing-package setup)

Accept the SDK licenses when prompted, then rebuild. Having a different NDK version installed does not satisfy this pin. The setup error prints the exact SDK root it checked, which must match Android Studio / `local.properties`.

Current command-line tools may redirect `sdkmanager` to the new Android CLI. Use its `--sdk` option and **slash-separated package paths** (`ndk/28.2.13676358`, not separate `ndk` and version arguments). Do not pass `ndk@28.2.13676358`: the side-by-side NDK version is part of the package path. See the official [install](https://developer.android.com/tools/agents/android-cli/commands/sdk_install) and [package path](https://developer.android.com/tools/agents/android-cli/commands/sdk_list) references. Do not use `--force` to replace an unrelated NDK30 installation.

Accept any required licenses in SDK Manager, then use the setup task or install the exact package paths directly. On Linux/macOS with `ANDROID_HOME` set:

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/android" --sdk="$ANDROID_HOME" --no-metrics sdk install \
  'platforms/android-36' 'build-tools/36.0.0' 'ndk/28.2.13676358' 'cmake/3.22.1'
./gradlew :opencv-minimal:prepareOpenCvToolchain
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest --tests 'com.yourapp.wine.*'
./gradlew --no-daemon :app:checkArm64ApkBudget
```

On Windows PowerShell, from the project directory (change `$sdk` if `local.properties` selects another SDK):

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$android = Get-ChildItem "$sdk\cmdline-tools" -Filter 'android*' -Recurse -File |
    Where-Object { $_.Name -in @('android.exe', 'android.bat', 'android.cmd', 'android') } |
    Sort-Object @{Expression = { $_.Extension -ne '.exe' }} |
    Select-Object -First 1 -ExpandProperty FullName
if (!$android) { throw 'Android CLI launcher not found; use Android Studio SDK Manager.' }
& $android "--sdk=$sdk" --no-metrics sdk install 'ndk/28.2.13676358' 'cmake/3.22.1'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed; do not rebuild yet.' }
if (!(Test-Path "$sdk\ndk\28.2.13676358\build\cmake\android.toolchain.cmake")) {
    throw 'Required NDK is still missing; do not rebuild yet.'
}
if (!(Test-Path "$sdk\cmake\3.22.1\bin\cmake.exe")) {
    throw 'Required SDK CMake is still missing; do not rebuild yet.'
}
.\gradlew.bat :app:assembleDebug -PpythonExecutable=python
```

For older Java-only SDK tools, legacy `sdkmanager --sdk_root=<SDK> --licenses` and semicolon package IDs remain supported by the helper. The current Android CLI is invoked directly, not through the deprecated redirect, and metrics are disabled for that build-time invocation. No `android init` or agent skill installation is needed to compile this app.

On Windows use `gradlew.bat`; choose a different Python executable with `-PpythonExecutable=python` (or a full executable path). For an already provisioned/offline SDK, `-PopencvInstallMissingSdk=false` verifies the pins without attempting any SDK installation. The setup-only task never downloads OpenCV source or compiles it. CMake/NDK provisioning is a build-time operation, not an app runtime download or wine lookup.

The old custom Exec task queried `sdkComponents.ndkDirectory.get()` before preparing its prerequisites. Unlike AGP's own native-build tasks, this could throw **“NDK is not installed”** without bootstrapping anything. It now resolves the SDK first, prepares the real pinned packages, and passes the explicit side-by-side NDK path to the builder. Missing command-line tools, unaccepted licenses, failed downloads and incomplete installs produce SDK Manager / `sdkmanager` instructions instead of that provider stack trace.

### Library source-set class cast during sync

An exception such as `DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet` indicates a library source-set API mismatch; the IDE's generic cache-corruption banner is not proof of a corrupt download. The OpenCV module avoids that registration path: `androidComponents.onVariants` registers its generated Java, JNI libraries and Java resources with `addGeneratedSourceDirectory`, using three declared `DirectoryProperty` outputs on `BuildOpenCvTask`. This preserves the existing output paths, packages the upstream license, and wires each consumer to the generator. Plugin versions and DSL-mode flags are unchanged. Sync again with the updated `opencv-minimal/build.gradle.kts`; if the exception persists, the full `--stacktrace` is needed to identify another plugin or source-set access.

The app is minSdk26 / targetSdk35 / compileSdk36, arm64-v8a only. Compile SDK is not target SDK. Keep real CameraX, bundled ML Kit, coroutines, Room (existing inventory), Exif and bundled SQLite dependencies.

`opencv-minimal` builds genuine pinned OpenCV **4.12.0** from the official source archive. SHA-256: `44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d`. `tools/build-opencv-minimal.py` enables only `core,imgproc,java` with static selected modules, and packages generated core/imgproc Java + the upstream bitmap Utils JNI subset. No monolithic opencv-world/AAR, imgcodecs/DNN, fake JNI or generated model weights. The first native build downloads source from GitHub and may take several minutes; generated/downloaded files stay under ignored build directories. Upstream license is packaged with generated Java resources.

Release shrinking is enabled, with JNI/Gson compatibility rules. `checkArm64ApkBudget` builds the actual release APK, checks only arm64 native libraries, reports packed/installed code+libs and total APK size, and fails ≥40MiB. **Genuine CI measured 24.51 MiB total, 22.20 MiB packed code+libs and 24.66 MiB installed code+libs**, with only arm64 native libraries. Optional real OCR/NER assets + SQLite logical pages must remain strictly below 80MiB; no downloader is supplied.

CI `.github/workflows/offline-scanner.yml` installs the real SDK/NDK/CMake, compiles, runs scanner Robolectric tests and checks the measured release APK. It completed successfully on a real hosted Android toolchain in [run 38661171436](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/38661171436) for source commit `4161b128ae58792dc891d15c3f75aa9a87ebe651` (23m44s). Debug and instrumented test APKs, shrunk unsigned release APK and test reports are available in the `scanner-check-results` artifact; no generated binaries are committed. Instrumentation needs an ARM64 device/system image; do not install this arm64-only APK on an x86-only emulator.

## Tests and validation ledger

### Executed on the genuine hosted Android toolchain

[Successful CI run 38661171436](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/38661171436) validates source commit `4161b12` on Ubuntu24.04, Java21, SDK36, NDK28.2.13676358 and CMake3.22.1, using the real declared dependencies and source-built OpenCV4.12 core/imgproc/java:

```sh
./gradlew --no-daemon --console=plain :app:assembleDebug
./gradlew --no-daemon --console=plain :app:assembleDebugAndroidTest
./gradlew --no-daemon --console=plain :app:testDebugUnitTest --tests 'com.yourapp.wine.*'
./gradlew --no-daemon --console=plain :app:checkArm64ApkBudget
```

All four commands passed. This includes full Compose/CameraX/ViewModel integration compilation, genuine SDK dependency signatures, arm64 JNI build/link/packaging, release shrinking and size checks. Scanner Robolectric tests passed: `ScannerLogicTest`, `GeometryTest`, `FixtureFieldsTest`, `DeadlineTest`, `BitmapWorkspaceTest`, `OptionalEngineTest`, `OpenWinesAdapterTest` (including real Android JSON parsing). The boundary guard and production SQL/host FTS5 checks passed in CI too.

Measured release APK: **24.51 MiB total**, **22.20 MiB packed code+libs**, **24.66 MiB installed code+libs**, **arm64-v8a only**. The inventory/network feature unit sources also compile, but the CI unit-test filter executes the scanner suite, not every existing app test.

Compilation/packaging is not native execution on Android. CI does **not** prove ML Kit image accuracy, device FTS5 integration, perspective-warp/native memory behavior, camera/lifecycle correctness on OEM devices or the physical 8000ms bound. Those require the device commands and checklist below.

### Executed in the restricted sandbox

The build-setup suite has **30 Python tests**: 28 native-toolchain policy tests and two static Gradle registration guards. The native tests cover installed/missing/version-mismatched packages, exact SDK-root/package selection, licensing/nonzero/false-success handling, Windows Java/native/batch argv handling, modern Android CLI slash paths, deprecated-wrapper avoidance, the setup-only CLI, and Android-host detection/refusal to download desktop packages onto a phone. The registration guards check the generated-source variant API, all three declared output paths, and their producer-task wiring. They first failed against the old library source-set registration and pass with the new variant registration.

These tests mock installation/executable checks and inspect the Gradle script: **they do not compile Kotlin DSL, execute Gradle, or download an NDK/compiler/SDK**. The revised setup and source registration still need genuine Gradle/SDK integration verification; the CI result above predates these fixes. This sandbox has no installed JDK or Android SDK.

```sh
python3 -m unittest discover -s tools/tests -p 'test_*.py'
```

```sh
# Use an installed Kotlin distribution (including its coroutines jar) and real Java runtime.
JAVA_HOME=/path/to/jdk KOTLIN_HOME=/path/to/kotlinc \
  ANDROID_JAR=/path/to/sdk/platforms/android-36/android.jar tools/run-logic-checks.sh
python3 tools/check-kb-import-sql.py
python3 tools/check-offline-recognition-boundary.py
```

The Kotlin harness compiles **production logic/CSV/Reader guards** with real Kotlin 2.2.10/coroutines and tiny, clearly marked host-only PointF/RectF/Bitmap/clock/log shims. A genuine SDK `android.jar` supplies API types, **not executable Android JSON parsing**. It reads all three expected-token fixtures. It does **not** OCR images, execute Android `JsonReader`, run Android/native SQLite, measure native memory or substitute for Gradle. `ANDROID_HOME`/`ANDROID_SDK_ROOT` with platform36 can supply the jar automatically.

**57 Kotlin host groups passed:** inverse mapping; NFKD; merger/votes; rectified lines; vintage; hierarchy conflicts and nested specificity; fill/form states, capture rejection/shutter timing and clearing of stale automatic suggestions; extraction edges; producer/cuvee/varietal, wrapped-marker, footer and FTS-shortlist regressions; location-only readings keep producer/name RED and blank; five diagnostics/provenance/stream-transfer regression groups; eight logged-probe localization, crop-size/inverse and rescue-scheduling groups; nine logged Kirkland/parser, immutable-geography, known-spacing, no-guessed-Blackstone and aspect-inverse/callback groups; failed-index partial preservation; measured-vs-estimated confidence and zero/invalid-score guards; three six-GREEN expected-token fixtures; optional-engine rectified-only policy; deadline/partial/cleanup; expired deadline; native lease serialization; two-bitmap ownership accounting; streaming CSV/quoting/alias order/caller ownership; malformed records; byte/row/UTF-8 guards; JSON lexical guards (not Android parser execution); 25,000 lazily generated rows delivered incrementally.

The photo/diagnostics source `f662329` compiled the Android app and instrumented APK, but its [CI run 39091704136](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/39091704136) failed at Robolectric tests; it is not a full validation pass. No native Android emulator checks are run or awaited locally for this localization update. The host geometry/scheduling suite is not a pixel-recognition measurement.

The review/extraction/native-setup baseline subsequently passed full Android CI in [run 38960911663](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/38960911663) for `557c373`, including the eight original Compose/Robolectric review tests. The current photo/clipboard feature adds coverage for actual clipboard text/sensitive flags, picker-action isolation, busy controls, content-URI copying, source preservation, bounded decoding, oversize/error cleanup, and ViewModel import error/cancel transitions. `ScanDiagnosticsTest` also exercises the shared five host contract groups with real Android geometry. These newer Android tests require the full Gradle/Robolectric environment; the limited host harness does not render Compose, execute ContentResolver/BitmapFactory, or run ML Kit. Test-only Compose dependencies do not enter the release app.

**2 host SQLite groups passed:** `check-kb-import-sql.py` executes the production schema/staging/transfer/FTS SQL with Python's real SQLite/FTS5, tests arbitrary parent/alias ordering, and verifies atomic data+FTS rollback on invalid foreign keys. This validates SQL semantics on **host SQLite**, not the bundled Android driver/JNI. It needs Python with SQLite/FTS5 and leaves no database artifact.

An earlier limited Kotlin backend type-check succeeded with a real Android35 API jar and genuine generated OpenCV Java APIs, but **ML Kit/Tasks/SQLite/Exif were validation-only stand-ins**. It excluded Compose/camera/VM integration and is **not an Android build or dependency-signature verification**. These disposable harness inputs are ignored, not packaged or committed. The genuine Gradle CI above now supersedes this limited check for compile/dependency evidence.

`check-offline-recognition-boundary.py` guards project-owned recognition modules/facade and seven ViewModel scan entry points against HTTP/wine-network dependencies. Normal Android/Google APIs and the explicit image-import feature remain allowed. This is a source regression guard, not instrumentation or a claim that SDKs never perform their own ordinary network activity.

`git diff --check` also passes.

### Instrumentation compiled, device execution pending

- `OfflineScannerDeviceTest`: real ML Kit/warp on three printed labels, inverse/bbox/variant/budget checks, and a warmed 20-scan Java/native heap-growth/ownership check.
- `SqliteKnowledgeBaseDeviceTest`: genuine bundled FTS5, streamed CSV/JSON import, late-schema/duplicate/FK/hierarchy rollback, caller stream ownership and temporary-file cleanup.
- Existing 13-photo `LabelOcrImageRegressionTest`: GREEN values must agree with reviewed truth; directly readable producer/wine-name evidence must not be misassigned even when YELLOW; unknown YELLOW and rejected RED are explicit, rather than requiring the tiny fixture KB to know all brands. Strict synthetic/device tests separately guard against an all-RED no-op pipeline.

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

| Phase | Source / logic validation | Android checkpoint |
| --- | --- | --- |
| 0 contracts/fixture/logic | implemented; host and genuine Robolectric assertions pass | genuine Android compilation passed |
| 1 camera/ML Kit/review | implemented; six-field/rejection/confirmation policy tests pass | app + instrumented test APKs compile; device capture/OCR pending |
| 2 quad/warp/variants/overlay | implemented; inverse algebra passes | genuine arm64 OpenCV JNI builds/packages; runtime warp/20 captures pending |
| 3 merger/extractor/FTS/hierarchy | implemented; genuine Robolectric fixtures/Android JSON and host FTS5 pass | genuine bundled SQLite/device image OCR pending |
| 4 optional Paddle | honest presence/bridge interface; missing-assets/rectified-only policy tests pass | ML Kit-only default; native Paddle intentionally unavailable |

Earlier **local** Gradle9.3.1 wrapper attempts failed with TLS EOF before configuration. Hosted CI resolved the real dependencies and now passes debug, instrumented-test compilation, scanner Robolectric and release budget checks. There is still no SDK/NDK/emulator/device available locally; do not describe 20-camera-capture/OOM, device OCR or physical latency as accepted until the device commands above succeed.

### Image-treatment diagnostics

Each attempted pass now carries its actual preprocessing description: raw base/probe,
Lab-luminance highlight compression plus CLAHE, or the selected unsharp/aspect rescue.
The optional Gaussian adaptive threshold mode is described only when selected; it is
not enabled by the normal scanner. A reused probe is explicitly marked as reuse, not a
new recognition attempt. V2 is conditional; an absent timing is not evidence it ran.
Pass timing measures the recognizer call including adapter conversion/wait, not variant
preparation. ML Kit receives grayscale NV21 after preprocessing on every pass; the
color-preserving filters describe intermediate processing, not color input to ML Kit.

This reporting change adds no new image manipulation. Sauvola/Wolf binarization,
stroke dilation, slant correction, skeletonization and ligature cutting are not
implemented in the normal scanner. They need controlled original-versus-treated image/OCR comparisons;
adding destructive filters without those measurements is not an accuracy guarantee.

### Debug OCR Lab

Debug builds now include **Add New Wine → OCR Lab — compare 30 treatments**.
This opt-in benchmark is separate from normal scanning. See [ocr-lab.md](ocr-lab.md)
for recipes, scoring limitations, budgets and explicit image/diagnostics export.
Sauvola, stroke thickening, fixed shear and Zhang–Suen thinning are implemented
only in this debug source set; the production pipeline remains unchanged.

### Rules learned from the first multi-label CSV

The 90 completed cases (plus one setup failure) do not justify enabling every
experimental filter in production. The combined pipeline remains unchanged.
Extraction now distinguishes standalone Côtes du Rhône (+ a bounded printed
Villages fragment) from a maker. It preserves the observed fragment as a YELLOW
wine-name suggestion instead of completing missing letters or inferring a maker.
French founding statements and narrowly matched OCR variants of legal appellation
text are rejected as identities; explicit producer phrases remain eligible.
An unknown prominent heading immediately above a reserve designation is considered
an ambiguous wine-name heading, not automatically the producer. Exact KB/curated
producer names and explicit domaine/winery cues retain priority.

`AdministrativeLabelChecks` uses transcribed CSV text/scores but **synthetic box
arrangements**, because the CSV does not export individual token boxes. These are
extraction regressions, not a pixel replay or proof of phone OCR improvements.
No expected label answer is fed into OCR, no missing producer is supplied, and
acceptance thresholds and the normal three-variant pipeline are unchanged.

### Designations and contextual appellation spelling

Wine-name display now places printed Reserva/Riserva, Gran Reserva, Grand Cru,
Premier Cru/1er Cru and Villages qualifiers after the name. Adjacent aligned label
lines may join in either vertical order; distant lines do not. Duplicate suffixes
are not repeated and conflicting designations require confirmation. A recognized
appellation may itself be an editable wine name, not an unknown producer. Generic
countries/states are not converted into wine identities. Known cuvee lookup happens
before display reordering, preserving the original KB evidence.

Existing curated geography supplies named-appellation ancestry (e.g. Rioja → Spain,
Chablis → Burgundy → France, Barolo → Piedmont → Italy). Grand Cru, Villages,
Reserva etc. alone do **not** determine a country. Explicit contradictions remain
YELLOW rather than silently overriding label text.

A new narrow correction can suggest a unique authored appellation one edit away
from a retained 6–32-character reading **only** when separately read, supported
country/region text agrees with its local hierarchy. Generic designations, importer
addresses, producer names and expected benchmark answers cannot supply this anchor.
Short/incomplete aliases cannot expand a fragment into a long appellation. Guesses
never alter raw OCR/scores and all dependent candidates are capped YELLOW with a
contextual-repair explanation. No acceptance thresholds or image passes changed.

### Large offline relationship catalog

See [`data/wine-knowledge/README.md`](../data/wine-knowledge/README.md) and its main
[`origins.csv`](../data/wine-knowledge/origins.csv): 1,020 named origins across 29 countries,
121 role/classification/style terms, and 42 non-inferential typical-grape associations.
The bundled generator is deterministic and network-free. UC Davis CC0 AVA metadata is pinned;
non-U.S. additions are explicitly project-authored references, not a verified global legal registry.
New reference-derived geography stays YELLOW, contradictions stay visible, multi-state origins
never pick an arbitrary state, and importer text cannot provide origin evidence. Typical grapes
are not used to fill absent fields. Existing imported records, manual edits, scan deadlines,
bitmap limits and OCR Lab truth isolation remain unchanged. Run the catalog `--check`, Python
tests and host logic checks described in the catalog README; these are not APK/emulator checks.
