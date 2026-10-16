# Wine Manager

A personal wine cellar app for Android. It tracks what is in the cellar, what has been drunk, and what you thought of it. All data stays on the device in a local database; there is no account and no server.

> **Private project, no licence.** Package `com.n8rtz.winetracker`, current version 0.5.5.

## What it does

- **Cellar:** every wine and bottle with vintage, quantity, price and location. Filter, sort and group the list, and mark bottles as consumed.
- **Add Wine:** enter a wine by hand, or photograph the label. The label is read on the phone (text recognition plus an Open Food Facts barcode lookup) and only *suggests* values for you to review; nothing is saved until you save.
- **Tasting notes and history:** notes with a 100-point score, sensory sliders, an aroma wheel, reviewers and value score; a history of everything consumed.
- **Rankings and Price vs. Rating:** rank wines by score and see which give the best rating for the price.
- **Offline wine images:** optionally import reviewed bottle or label images from Open Food Facts and manage the storage they use.
- **Backup:** export the whole cellar to a JSON file and restore it later (validated and transactional). Automatic off-device backup is planned (see `BACKLOG.md`, W-057).
- **Profile:** Price vs. Rating, the reviewer list, and a Settings submenu (import and export, image tools, App Information).

## Building

Requirements:

- Android Studio Quail 2 (2026.1.2) or newer, or Code on the Go on the phone.
- JDK 17 or newer for Gradle, and the Android SDK Platform 36.
- Gradle 9.6.1 (the wrapper downloads it), AGP 9.3.1, Kotlin 2.3.21. The app runs on Android 13 (API 33) and newer.

```
./gradlew :app:assembleDebug          # build the debug app
./gradlew :app:testDebugUnitTest      # JVM unit tests
```

On Windows use `gradlew.bat`.

> **Instrumented tests (`:app:connectedDebugAndroidTest`) run on an emulator only.** Gradle uninstalls the app when the run finishes, which erases that device's data. Never run them on a phone that holds your real cellar.

### Which build is installed?

Open **Profile → Settings → App Information**. It shows the build time, Git commit (and whether the working tree had uncommitted changes), branch, when the app was first installed and last updated, and a short signing-key fingerprint.

### Test data in debug builds

Debug builds bundle a test cellar from `app/src/debug/assets/dev-seed/WineTracker_Backup.json`. If the app starts with a completely empty database (after a fresh install or Clear storage), it restores that file automatically and says so in a toast. It never overwrites existing data and is not part of release builds. The file contains real personal data, so do not share debug APKs.

## Project layout

```
app/src/main/java/com/n8rtz/winetracker/   Kotlin source (Jetpack Compose UI, Room database, ViewModels)
app/src/test, app/src/androidTest          JVM and instrumented tests
app/src/debug/assets/dev-seed/             debug-only test cellar
_refs/                                     project documentation (below)
_backup/                                   personal cellar JSON backups
.agents/instructions.md                    rules for AI coding agents working in this repo
```

The full file map is in `_refs/ARCH.md`.

## Documentation

All in `_refs/`:

| File | What it is |
| --- | --- |
| `BACKLOG.md` | Everything still to do, ranked, with decisions and the pending-verification list. Start here. |
| `PIPELINE.md` | How a change goes from idea to release, and the release plan. |
| `THINGS_TO_CHECK.md` | Manual test checklists, grouped by Git branch. |
| `CHANGELOG.md` | What changed in each version. |
| `ARCH.md` | Architecture rules and the directory map. |
| `LESSONS_LEARNED.md` | Past bugs and what they taught. |
| `ARCHIVE.md` | Completed work and declined ideas. |
| `AGENTS.md` | Orientation for AI agents. |
| `major-feature-planning/` | Planning briefs for larger features. |

## Status

The code is feature-complete for 0.5.5 plus unreleased work, but a recent cleanup and the newest features have **not yet been compiled or run on a device**. `THINGS_TO_CHECK.md` is the test plan.
