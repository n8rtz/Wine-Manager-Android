# Things to Check

> **Scope/status:** These are future verification steps, not test results. Android Studio build, unit tests, and device/emulator checks have not yet been run. The implemented major flows covered here are camera OCR on **Add Wine**, the reviewed Open Food Facts image importer, **Manage Offline Images**, and the **Price vs. Rating** Pareto screen. The Aroma Wheel and Wine Resources are planning briefs only; there are no implemented screens to verify yet. Camera OCR is intentionally not part of **Add Note**.

## One-time Android Studio setup and test launch

1. [ ] In Android Studio, choose **File → Open…** and select the repository root (the folder containing `settings.gradle.kts`). Wait for Gradle sync to finish; resolve any SDK/license prompts rather than dismissing them.
2. [ ] Open **File → Settings → Build, Execution, Deployment → Build Tools → Gradle** (on macOS, **Android Studio → Settings…**). Set **Gradle JDK** to Android Studio's bundled JDK 17 or newer; this project uses AGP 9.1.1.
3. [ ] Open **Tools → SDK Manager** and install Android SDK Platform 36 when prompted (the app compiles against API 36). The app's minimum supported device API is 33.
4. [ ] Open **Tools → Device Manager → Create Device**, choose a Pixel profile, download/start an API 33-or-newer system image, and wait for the emulator to finish booting. A physical Android device is preferable for autofocus/camera checks. If using an emulator for OCR, configure its back-camera input (for example, the host webcam).
5. [ ] Select the **app** run configuration and the booted device in the toolbar, then click **Run ▶**. Confirm the app opens on **Cellar**. Use a disposable emulator/install for any step that writes data; do not clear or alter a personal cellar.
6. [ ] For a clean app database on a disposable emulator, use Android **Settings → Apps → Wine Manager → Storage & cache → Clear storage** (wording varies slightly by Android version), then relaunch the app. This removes all app data on that emulator.

### Run the tests from Android Studio

- [ ] Open Android Studio's integrated terminal with **View → Tool Windows → Terminal**. Run the build and JVM test suite:
  - Windows: `.\gradlew.bat :app:assembleDebug` then `.\gradlew.bat :app:testDebugUnitTest`
  - macOS/Linux: `./gradlew :app:assembleDebug` then `./gradlew :app:testDebugUnitTest`
- [ ] The JVM suite includes the OCR one-shot gate/parser tests and Open Food Facts candidate-mapping/filtering tests. It does **not** include a Pareto-specific test suite at present; use the Pareto manual fixture below.
- [ ] Start the emulator or connect a device, then run the Room/repository instrumentation suite:
  - Windows: `.\gradlew.bat :app:connectedDebugAndroidTest`
  - macOS/Linux: `./gradlew :app:connectedDebugAndroidTest`
- [ ] To launch tests using Android Studio controls instead of the Terminal, switch the Project pane to **Project** view, expand `app/src/test/java` or `app/src/androidTest/java`, and use the green gutter/run icon beside a test class or right-click the class and choose **Run**. For `androidTest`, first select a running API 33+ device in the toolbar. The offline-image suite is `WineImageImportRepositoryTest` under `app/src/androidTest/java/com/n8rtz/winetracker/data/repository/`.
- [ ] The offline-image instrumentation class should pass its path/provenance/history, shared-file-reference, stored-image-list, storage-total, repeated-removal, and stale-request checks. If a test fails, keep the failing method name and Logcat/Gradle output with the device/API details.

### Optional GUI data-integrity tools

- [ ] With the debug app running, open **View → Tool Windows → App Inspection → Database Inspector**, select the `com.n8rtz.winetracker` process and `wine-database`, then inspect `wine_definitions`, `wine_bottles`, `tasting_notes`, `consumption_events`, and `wine_image_assets`. Compare relevant rows before and after analytics/removal actions.
- [ ] To inspect or pull a local image, open **View → Tool Windows → Device Explorer** and browse to the app's `files/wine-images/` directory (typically `/data/data/com.n8rtz.winetracker/files/wine-images/` on the emulator).

## Camera label OCR — Add Wine

**Purpose:** Verify that one requested CameraX frame is processed on-device and produces editable suggestions only; scanning must not bypass **Save to Cellar**. Use a readable physical label or a suitable emulator camera scene.

### Main scan-and-review path

1. [ ] From **Cellar**, tap the floating **+** button at the bottom right, then tap **Add Wine**. Confirm the title is **Add New Wine** and the camera preview is above the manual form.
2. [ ] On first use, tap **Enable Camera to Scan** and grant camera permission. If permission is denied, confirm the manual form remains available; grant access later from Android app permissions to continue this path.
3. [ ] Aim a clear label in the preview, wait for focus, and tap **Read Label** once. Confirm **Hold the label steady in the frame.**, then **Reading label on this device…** with a spinner. The button should be disabled while that one scan is in progress; OCR must not continuously update from preview frames.
4. [ ] When text is recognized, confirm **Review scanned label** appears with recognized text and any Producer, Wine, and Vintage suggestions. Verify nothing is applied until you choose **Use Suggestions**.
5. [ ] Tap **Cancel**. The dialog should close and any values already typed in the form should remain unchanged.
6. [ ] Scan again and tap **Use Suggestions**. Scroll to the form. Only Producer, Wine Name, and Vintage may be filled from OCR; Type, Country, Region, Appellation, Quantity, Price, and Drinking Window must not be inferred. Edit a suggestion and verify it behaves like ordinary editable text.
7. [ ] Verify scanning, reviewing, and applying suggestions did not save a record. On the disposable emulator only, optionally finish the form and tap **Save to Cellar**; the app should return to **Cellar** and show the wine through the normal save flow.

### Failure, permission, and lifecycle checks

- [ ] **Unreadable/text-free view:** Scan a deliberately obscured or text-free scene. Expect a readable error such as **No readable wine details were found. Try again or enter them manually.**, a **Retry Scan** button, and a usable manual form.
- [ ] **Camera startup/no frame:** If camera startup fails, expect an error without losing manual entry. If no frame arrives, wait for the scan timeout and verify the retry/manual-entry message appears.
- [ ] **Retry:** Tap **Retry Scan** after an OCR error and verify a fresh single scan can be requested.
- [ ] **Permission denied:** Deny/revoke permission in the Android prompt or system app settings, reopen **Cellar → + → Add Wine**, and confirm manual entry still works. Grant permission again before continuing camera checks.
- [ ] **Leave during scan:** Tap **Read Label**, then immediately use system Back. Confirm no crash. Reopen **Cellar → + → Add Wine** and verify there is no stale review dialog/result.
- [ ] **Discard draft:** Apply suggestions, leave without tapping **Save to Cellar**, and reopen Add Wine. Confirm the unsaved scan did not create a cellar entry.

### Regression and automated checks

- [ ] From **Cellar → + → Add Note**, confirm the existing tasting-note editor opens without the label-scanning camera flow. Back out without saving; opening the editor must not add cellar inventory.
- [ ] Confirm the separate Profile action **Review Missing Images (Open Food Facts)** remains available; camera OCR must not start that importer or save an image.
- [ ] Run `OneShotScanGateTest` and `WineLabelParserTest` through `:app:testDebugUnitTest` as described above. These cover the one-shot gate and pure parser, not live camera focus/OCR quality.

## Pareto screen — Price vs. Rating

**Purpose:** Verify that each point represents a rated wine/vintage with a known positive price; charting and filtering are read-only. There is no Pareto-specific automated test suite yet, so use the disposable data below for deterministic visual expectations.

### Create a deterministic test set in the app

1. [ ] On a clean disposable emulator, open **Profile → Price vs. Rating** before adding anything. Expect **No wines to plot yet** and the guidance to add a tasting note with an overall rating and recorded bottle price. Tap Back and return to **Cellar**.
2. [ ] From **Cellar**, tap **+ → Add Wine** six times. Use the same producer `Arena Pareto Test` and these unique wine names, vintages, types, and prices. Set **Quantity = 1**, leave other optional identity fields at defaults, and tap **Save to Cellar** for each wine:

   | Wine name | Vintage | Type | Price | Tasting-note overall scores |
   | --- | --- | --- | ---: | --- |
   | Red Alpha | 2022 | Red | $20 | 80 and 90 (two separate notes; expected average 85) |
   | Red Bravo | 2021 | Red | $30 | 90 |
   | Red Charlie | 2020 | Red | $40 | 80 |
   | White Delta | 2022 | White | $18 | 82 |
   | White Echo | 2021 | White | $25 | 88 |
   | White No Price | 2020 | White | leave blank | 95 |

3. [ ] For each row, use **Cellar → + → Add Note**. Select **Cellar** mode, choose the matching wine and vintage, and set **Overall score** until the numeric badge shows the value in the table. Confirm the displayed cellar price matches (the unpriced wine should say **Not recorded**) and tap **Save Tasting Note**. Add the two Red Alpha notes separately at the same vintage. Do not add a tasting note for any other wine/vintage.
4. [ ] Before opening the chart, optionally note the row counts in Database Inspector. The seeded data should contain six definitions, six bottles, and seven tasting notes; it should have no consumption events unless you deliberately added one.

### Navigation, chart, details, filters, and dashboard

1. [ ] Tap **Profile** in the bottom navigation, then tap **Price vs. Rating**. Confirm the screen title, explanation, **Pareto frontier**, and **5 rated wines**. The high-rated **White No Price** record must be omitted because it has no positive recorded price.
2. [ ] Confirm the chart labels **Rating** and **Bottle price ($, logarithmic scale)**. Higher ratings should be higher and lower prices farther left. A **Price / rating trend** dashed-line legend should appear because the five plotted points have varied prices.
3. [ ] Check the frontier using the fixture values: the global efficient sequence is White Delta ($18/82), Red Alpha ($20/85), White Echo ($25/88), and Red Bravo ($30/90). Red Charlie ($40/80) is dominated and is not on the global frontier. The dashboard below should show separate Red and White cards, each with **2 frontier** wines: Red Alpha and Red Bravo; White Delta and White Echo. Red Alpha should rank ahead of Red Bravo by the displayed average-score-per-dollar heuristic; White Delta should rank ahead of White Echo. The dashboard's heuristic is not a universal definition of “best value.”
4. [ ] Tap the Red Alpha point. Its card should show `Arena Pareto Test — Red Alpha`, Vintage 2022, Red, **85.0 / 100**, **$20 / bottle**, **Based on 2 tastings**, and **Overall frontier**. Tap Red Charlie; its card should identify **Trade-off**. Tap outside a card to dismiss it; select another point. Change a type filter and verify any selected-point card is cleared. Press system Back with a card open and confirm it dismisses before leaving the screen.
5. [ ] From the default all-types view, tap the **White** chip. Expect **3 rated wines**, only Red points/frontier, and an empty White dashboard message (**No priced, rated wines with the current filters.**). Tap **All** to restore the five points. Then turn off both Red and White chips; expect **No wines match these filters**. Tap **All** again and confirm the full chart returns.
6. [ ] Scroll the page to verify the separate Red/White dashboard remains below the chart and can be reached without clipping. Use the app in light and dark system themes and increase Android's font size one step; verify chips, point cards, legends, and both dashboard cards remain readable and usable.
7. [ ] On the emulator, enable **Settings → Accessibility → TalkBack** and swipe through the Pareto screen. Confirm the chart meaning/frontier explanation is available as text and that plotted wines can be understood without relying on color alone. If individual Canvas points are not reachable or have no useful spoken description, record that as an accessibility failure. Turn TalkBack off and return to Profile with Back.

### Small, tied, missing-price, and data-integrity checks

- [ ] The seeded **White No Price** note is the missing-price case: it must not increase the displayed point count. A fresh database with rated notes but no positive price should show **No wines to plot yet**. With one priced/rated wine, expect one dot and no trend-line legend.
- [ ] On a separate disposable database, create three rated Red wine/vintages at the same $20 price with scores 80, 90, and 90. The 80 point is dominated; both equal highest-score points are on the frontier. The equal high points may visually overlap, so also confirm the Red dashboard's frontier count is 2.
- [ ] Before and after viewing/filtering Pareto, compare `wine_bottles`, `tasting_notes`, and `consumption_events` in Database Inspector (or their visible counts/details in the app). The chart must not create, edit, delete, or repair definitions, bottles, notes, scores, or consumption history. Filters and selected points are transient screen state.

## Open Food Facts reviewed image import

**Use a disposable app database.** This action makes live Open Food Facts queries. Candidate previews are temporary; only explicit approval downloads and saves a compressed offline copy. Use a real test wine likely to have an Open Food Facts product listing; if the provider has no match, record that as a no-result/provider check rather than approving a different wine.

### Search, review, skip/cancel, and approve

1. [ ] Create or restore a test wine definition with no image. From the bottom navigation, open **Profile** and note the target producer/name.
2. [ ] Tap **Review Missing Images (Open Food Facts)**. While it searches, verify the progress dialog shows the target and offers **Cancel batch**. Search itself must not create image metadata or change the wine.
3. [ ] In **Review image candidates**, verify the displayed **Target** is the intended producer/name (and location when present). For each candidate, inspect its preview, product title, Open Food Facts source, contributor (or **not listed**), dimensions when available, and **View source listing** link. Decide whether it is the exact wine before choosing an image kind.
4. [ ] Exercise the non-approval path first. Tap **Skip this wine** and confirm no local file or associated candidate was saved. Run the search again and tap **Cancel batch** during search/review; confirm **Image review canceled** and verify no unapproved candidate remains.
5. [ ] Run the search again for a verified matching candidate. Tap **Use as bottle** or **Use as label** yourself. Confirm **Preparing approved image** and **Saving offline image** appear, then the batch completes with the appropriate imported/skipped/failed counts. No camera OCR action or Add Wine save action should have approved this image.
6. [ ] Open the wine in Cellar/detail and confirm the chosen image appears. Confirm detail still exposes the Open Food Facts source/contributor and source-listing link. Reopen Cellar, Notes/Rankings, and Wine Detail with airplane mode enabled; the selected local image should still render without a network download.

### Storage, attribution, backup, and failure checks

- [ ] In Device Explorer, inspect `files/wine-images/`. The approved copy should be a compressed `.webp` derivative, no more than 500 KB; the original downloaded image must not be retained there. In Database Inspector, confirm its `wine_image_assets` row has a relative path, source/provenance, kind, dimensions, byte size, hash, and import time.
- [ ] Open **Profile → Offline wine image storage**. Confirm the default soft budget is 256 MiB and **Change** permits 64–1,024 MiB. Set it to 64 MiB for a disposable budget test. When a sufficiently large batch estimate or prepared image exceeds the budget, expect a warning and explicit **Continue/Keep this image** or **Cancel/Skip** choices; no existing image should be silently evicted. Restore the usual budget afterward. Do not expect one small image to cross the 64 MiB threshold.
- [ ] If feasible, disconnect the emulator before starting a search. Expect **Could not search this wine** with **Retry**, **Skip wine**, and **Cancel** choices; no image should be saved. Reconnect before the approval/offline-display test.
- [ ] From **Profile → Export Data**, save a JSON backup. Pull/open it for inspection and confirm source/provenance is retained but image bytes and app-private absolute paths are absent; exported `imageAssets` should have `relativePath: null`. For restore, import that JSON into a second disposable install with **Profile → Import Data (JSON)** and confirm **Replace and restore**. The restored source metadata should remain, no stale local path should be used, and no image should be fetched or re-saved without a fresh reviewed approval. The source link should remain available and **Review Missing Images** should offer a new candidate search.
- [ ] In Android Studio, open `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`; confirm `files/wine-images/` is excluded from cloud backup and device transfer.
- [ ] Verify that skip, batch cancel, provider failure, download/conversion failure, or low device space leaves the wine definition, bottles, tasting notes, ratings, and consumption events unchanged. For a batch/image budget warning, verify declining does not delete any existing image.
- [ ] Run `OpenFoodFactsWineImageProviderTest` with `:app:testDebugUnitTest`. This checks candidate mapping/filtering; the live search, reviewed approval, compressed file, and offline rendering require the manual device steps above.

> Open Food Facts use is treated as authorized per the user's direction. Keep source/contributor metadata visible for attribution.

## Manage Offline Images

**Precondition:** On a disposable emulator, have at least two approved offline images for different wines (use the importer checks above), including one wine with a source URL/provenance and existing bottle, tasting-note, and consumption-history data. To create a consumption event for the test wine, add it with Quantity 2, open its Cellar detail, tap **Mark as Consumed**, uncheck **Add a tasting note**, and tap **Confirm**; add a separate tasting note with **Add Tasting Note**. This leaves one bottle in inventory while giving Database Inspector a consumption row to compare.

### List, cancel, and confirm one removal

1. [ ] Open **Profile → Offline wine image storage → Manage**. Wait for loading to finish. Confirm the dialog says **Manage offline images**, gives a local-copy count and MiB usage, and offers **Review missing images** and **Done**.
2. [ ] For each row, inspect the local preview, producer/wine name, image kind, compressed size, import date, source/contributor, and **View source listing** when present. Note the total usage and the selected row's relative path/file size in Database Inspector/Device Explorer.
3. [ ] Tap that row's **Remove offline copy**. In **Remove offline copy?**, verify the text identifies that wine and says its wine/source/history stay. Tap **Cancel**. Reopen/inspect the manager and verify the file, relative path, provenance, copy count, and usage are unchanged.
4. [ ] Tap **Remove offline copy** for the same row again, then confirm the dialog with **Remove offline copy**. Wait for the operation to finish. Expect **Offline copy removed. Wine and source details were kept.**; the selected row disappears and the MiB usage refreshes (rounded to one decimal), with the exact `sizeBytes` total decreasing by that file's size in Database Inspector. The other image remains in the list and on disk.
5. [ ] In Database Inspector, confirm only the selected `wine_image_assets.relativePath` changed to null. Its asset/provenance row and other fields (`sourceName`, source URLs, attribution, kind, size/hash/import time) remain; its `wine_definitions.imageUrl` remains. Confirm `wine_bottles`, `tasting_notes`, ratings, and `consumption_events` are unchanged. In Device Explorer, confirm only the selected local file is gone.
6. [ ] Close/reopen the app and manager. The removed copy must stay absent; the other image must still load. Tap **Review missing images** and verify a new local copy would require fresh candidate review/explicit approval. Do not approve again unless the test explicitly requires it.
7. [ ] Turn on airplane mode. Removal must still work for another selected copy, and any remaining offline image must continue to display. The removed image's source/provenance should still be available in wine details even though its local file is gone.

### Error recovery and repository instrumentation

- [ ] If a deletion failure can be induced safely on the disposable emulator, expect **The offline copy was unlinked, but storage cleanup will retry deleting its file.**; the Room path must remain cleared, the file must not be re-associated, and reopening the manager/app must recover without a crash. If the operation reports an error, confirm the stale path is still not restored. Do not induce filesystem faults on a personal device.
- [ ] Run the complete `WineImageImportRepositoryTest` connected instrumentation class. It checks: path-only clearing and preservation of source/provenance plus inventory, tasting-note, and consumption history; protection of a file still referenced by another asset; the management-list projection; refreshed stored-byte totals; repeated/idempotent removal; and a stale removal request that must not clear/delete a newer image. It also checks a removed path remains eligible for a fresh reviewed candidate search.
- [ ] Keep the Open Food Facts source/contributor metadata visible for attribution. The offline manager removes only a selected confirmed local copy; it is not a bulk delete, source-history delete, or automatic storage eviction.
