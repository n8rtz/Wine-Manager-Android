# Wine Manager

A personal wine cellar app for Android. It tracks what is in the cellar,
what has been consumed, and tasting notes and ratings.

Cellar data is stored locally on the device; there is no account or
application server. Optional external image-import features use the network.

> **Private project, no project licence.**
> Package: `com.n8rtz.winetracker`.
> Bundled third-party software, models and reference datasets retain their
> own licences and attribution requirements.

## What it does

- **Cellar:** wines and bottles with vintage, quantity, price and location.
  Filter, sort and group the list, and mark bottles as consumed.
- **Add Wine:** enter a wine manually or scan a label. The scanner runs
  locally and suggests six reviewable fields; nothing is saved until you save.
- **Tasting notes and history:** notes, scores, sensory sliders, aromas,
  reviewers, value scores and consumption history.
- **Rankings and Price vs. Rating:** compare wines by score and value.
- **Wine images:** optionally import reviewed bottle or label images from
  Open Food Facts and manage their local storage.
- **Backup:** export the cellar to JSON and restore it with validation and
  transactional updates.
- **Profile and Settings:** reviewer management, import/export, image tools
  and App Information.

## Local label scanner

The scanner uses bundled ML Kit Latin OCR and PP-OCRv6 Medium
detection/recognition, perspective rectification and local reference data.
Wine-label recognition does not call a wine-database recognition API.

- **Choose label photo** scans an existing image locally without camera
  permission.
- **Show scan details → Copy diagnostics** provides troubleshooting text.
- Suggestions require review; reference matches are not guarantees.
- The normal Add Wine scanner remains separate from experimental Lab changes.

See:

- [Offline scanner architecture, build and tests](docs/offline-scanner.md)
- [Knowledge-base import formats](docs/offline-scanner-data.md)
- [Production PP-OCRv6 Medium integration](docs/production-v6-medium.md)
- [OCR Text Lab](docs/text-lab-candidates.md)
- [GLiNER lane lab (ML Kit + PP-OCRv6 ➔ GLiNER-Small INT4 ➔ Lab v7 post-processing)](_refs/GLINER_LAB.md)
- [GLiNER lane lab: first host measurement against Lab v7](_refs/GLINER_LAB_HOST_RUN.md)
- [Phone-hosted prebuilt OpenCV](docs/opencv-prebuilt.md)
- [OCR post-processing investigation](docs/ocr-postprocessing-review.md)

## Experimental scanner benchmark

Debug builds provide three independently selectable modes:

- **Production v1.0:** the normal Add Wine scanner.
- **Production v2.0:** the guarded-producer benchmark implementation.
- **Lab:** the latest experimental implementation only.

Lab preserves original OCR observations and uses primary LWIN producer
names and producer-linked wine names as separate matching evidence.
Spatial joins are alternatives, not replacements for the original text.
TTB and winery-map data are supporting evidence, not legal-owner
substitutions.

Lab does not run v2.0 as another live contender. Saved v2 results remain
available for comparison without rerunning its OCR.

These changes are experimental and have not been promoted to Add Wine.
Host and Kotlin logic checks are not substitutes for Android builds or
phone accuracy testing. The supplied f9a8891 phone exports show nine correct
producer fills, but complete reads remain 34/160 because other fields still fail.
The debug benchmark now includes a per-label pipeline bottleneck table, with
separate OCR branches, in the screen and CSVs. Native validation of the latest
changes remains outstanding; no new recognition improvement is claimed.

See:

- [Phone corpus benchmark](docs/phone-corpus-benchmark.md)
- [Primary producer resolver](docs/lab-primary-producer-resolver.md)
- [Producer-linked wine-name resolver](docs/lab-linked-wine-resolver.md)
- [Accuracy recovery plan and audited phone results](docs/scanner-accuracy-plan.md)

## Building

Use the versions pinned in the merged Gradle configuration and version
catalogue. Do not upgrade Gradle, AGP, Kotlin, KSP, SDK or native dependencies
without the owner's approval.

The merged build files are the source of truth for minimum Android version,
compile/target SDK versions and supported ABIs.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

On Windows, use `gradlew.bat`.

For native toolchain setup, see
[the scanner build guide](docs/offline-scanner.md#fix-ndk-is-not-installed).
For phone-hosted builds, consult
[the prebuilt OpenCV instructions](docs/opencv-prebuilt.md).
Code on the Go compatibility depends on its available toolchain and the
project's pinned versions; native source builds on a phone are not assumed
to work.

> **Run instrumented tests only on a disposable emulator/test installation.**
> The instrumented-test workflow can uninstall the app and erase its data.
> Never run it against a phone installation holding the real cellar.

### Which build is installed?

Open **Profile → Settings → App Information** to inspect the installed
build information, including its Git commit and source state.

Pulling source does not update the installed application: rebuild and
install it afterward.

### Test data in debug builds

Debug builds include the test cellar at
`app/src/debug/assets/dev-seed/WineTracker_Backup.json`.

On an empty database, the debug seed mechanism restores this file and
reports that action. It must not overwrite existing cellar data and is not
part of release builds.

> The seed contains real personal data. Do not share debug APKs publicly.

Debug builds also contain scanner benchmark images and experimental
reference assets. Their inclusion does not establish redistribution
rights for every source image. Preserve dataset attribution notices.

## Project layout

```text
app/src/main/java/com/n8rtz/winetracker/   Main application code
app/src/main/java/com/yourapp/wine/       Local scanner components
app/src/debug/                          Debug UI, benchmarks and experiments
app/src/test, app/src/androidTest         JVM and instrumented tests
app/src/debug/assets/dev-seed/            Debug-only test cellar
tools/                                  Host checks and data-build tooling
docs/                                   Scanner and benchmark documentation
_refs/                                  General project documentation
_backup/                                Personal cellar JSON backups
.agents/instructions.md                  Repository agent instructions
```

The general architecture and file map are documented in `_refs/ARCH.md`.

## Project documentation

General project documentation is under `_refs/`:

| File | Purpose |
|---|---|
| `BACKLOG.md` | Priorities, decisions and pending verification |
| `PIPELINE.md` | Development and release process |
| `THINGS_TO_CHECK.md` | Manual test checklists |
| `CHANGELOG.md` | Changes by version |
| `ARCH.md` | Architecture and directory map |
| `LESSONS_LEARNED.md` | Previous issues and lessons |
| `ARCHIVE.md` | Completed work and declined ideas |
| `AGENTS.md` | Agent orientation |
| `major-feature-planning/` | Feature planning briefs |

Consult the changelog for release history and the pending-verification
lists for remaining checks. Historical successful builds do not certify
subsequent changes.