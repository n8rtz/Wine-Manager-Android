# Things to Check

> **Status:** these are verification steps, not results. No Android build, unit test or emulator run has been done for any branch below. Each section carries a **Last run** line to fill in.

## How to use this file

Checks are grouped **by branch**, newest first, so you can load one branch on the emulator and step through everything that branch changed.

1. Find the branch you want in the table below and jump to its section.
2. Check it out and build it (each section has the commands). Use an **emulator or a disposable install** for anything that writes data. Never run instrumented tests (`connectedDebugAndroidTest`) on the phone with your real cellar: Gradle uninstalls the app afterward, which erases its data.
3. Open **Profile → Settings → App Information** and confirm **Built**, **Build commit** and **Build branch** match the branch you meant to test.
4. Work down that branch's section top to bottom and tick the boxes. Write the date, device and result on each **Last run** line. Failures become `Bug-nnn` entries in `BACKLOG.md`.
5. When a branch is merged and fully ticked, leave its section in place with the passing **Last run** dates (the log of what was verified). Unfinished checks from an older branch stay under that branch, so nothing gets lost.

When you start a new branch, copy the **Template** at the bottom, put it directly under the table, and add a row to the table.

| Branch | PR | State | What it changes | Section |
| --- | --- | --- | --- | --- |
| `arena/814c691f-wine-manager-android` | not opened yet | current work | Cleanup of dead code, Profile + Settings, build info in App Information, debug test-data restore, backlog and docs rewrite | [Jump](#branch-arena814c691f-wine-manager-android) |
| `arena/a8add687-wine-manager-android` | #5, merged 2026-10-15 | in `main` | Toolchain matched to Code on the Go (AGP 9.3.1, Kotlin 2.3.21, Gradle 9.6.1); **reverted on this branch to AGP 9.1.1 / Kotlin 2.2.10 / Gradle 9.3.1** | [Jump](#branch-arenaa8add687-wine-manager-android) |
| `arena/77d08ab9-wine-manager-android` | #4, merged 2026-10-15 | in `main` | Aroma wheel and tasting-note editor controls, Bug-022 to Bug-026 | [Jump](#branch-arena77d08ab9-wine-manager-android) |
| `arena/d055fead-wine-manager-android`, `arena/9d4919dd-wine-manager-android` | #2 and #3, merged 2026-10-15 | in `main` | Camera label scan, Open Food Facts image import, Manage Offline Images, Pareto screen | [Jump](#branches-arenad055fead-wine-manager-android-and-arena9d4919dd-wine-manager-android) |

*Merged branches live in `main` now, so “load the branch” for them means building `main` (or any later branch) and running that section. The mapping of the camera, importer and Pareto work to PRs #2 and #3 comes from the PR titles and the changelog; the repository history was squashed, so it could not be confirmed from the commits.*

## Common setup and test launch (every branch)

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] In Android Studio, choose **File → Open…** and select the repository root (the folder containing `settings.gradle.kts`). Wait for Gradle sync to finish; resolve any SDK/license prompts rather than dismissing them.
2. [ ] Open **File → Settings → Build, Execution, Deployment → Build Tools → Gradle** (on macOS, **Android Studio → Settings…**). Set **Gradle JDK** to Android Studio's bundled JDK 17 or newer; this project is pinned to AGP 9.1.1 with Gradle 9.3.1 (the wrapper downloads it), so the Android Studio sync message must not say the project was built with a different AGP.
3. [ ] Open **Tools → SDK Manager** and install Android SDK Platform 36 when prompted (the app compiles against API 36). The app's minimum supported device API is 33.
4. [ ] Open **Tools → Device Manager → Create Device**, choose a Pixel profile, download/start an API 33-or-newer system image, and wait for the emulator to finish booting. A physical Android device is preferable for autofocus/camera checks. If using an emulator for OCR, configure its back-camera input (for example, the host webcam).
5. [ ] Select the **app** run configuration and the booted device in the toolbar, then click **Run ▶**. Confirm the app opens on **Cellar**. Use a disposable emulator/install for any step that writes data; do not clear or alter a personal cellar.
6. [ ] For a clean app database on a disposable emulator, use Android **Settings → Apps → Wine Manager → Storage & cache → Clear storage** (wording varies slightly by Android version), then relaunch the app. This removes all app data on that emulator. A debug build then restores the bundled test cellar on the next launch (see *Keeping your data between test installs*).

### Run the tests from Android Studio

- [ ] Open Android Studio's integrated terminal with **View → Tool Windows → Terminal**. Run the build and JVM test suite:
  - Windows: `.\gradlew.bat :app:assembleDebug` then `.\gradlew.bat :app:testDebugUnitTest`
  - macOS/Linux: `./gradlew :app:assembleDebug` then `./gradlew :app:testDebugUnitTest`
- [ ] The JVM suite includes the OCR one-shot gate/parser tests and Open Food Facts candidate-mapping/filtering tests. It does **not** include a Pareto-specific test suite at present; use the Pareto manual fixture below.
- [ ] Start an **emulator** (not the phone that holds your real cellar: Gradle uninstalls the app when the run ends, which erases its data), then run the Room/repository instrumentation suite:
  - Windows: `.\gradlew.bat :app:connectedDebugAndroidTest`
  - macOS/Linux: `./gradlew :app:connectedDebugAndroidTest`
- [ ] To launch tests using Android Studio controls instead of the Terminal, switch the Project pane to **Project** view, expand `app/src/test/java` or `app/src/androidTest/java`, and use the green gutter/run icon beside a test class or right-click the class and choose **Run**. For `androidTest`, first select a running API 33+ device in the toolbar. The offline-image suite is `WineImageImportRepositoryTest` under `app/src/androidTest/java/com/n8rtz/winetracker/data/repository/`.
- [ ] The offline-image instrumentation class should pass its path/provenance/history, shared-file-reference, stored-image-list, storage-total, repeated-removal, and stale-request checks. If a test fails, keep the failing method name and Logcat/Gradle output with the device/API details.

### Optional GUI data-integrity tools

- [ ] With the debug app running, open **View → Tool Windows → App Inspection → Database Inspector**, select the `com.n8rtz.winetracker` process and `wine-database`, then inspect `wine_definitions`, `wine_bottles`, `tasting_notes`, `consumption_events`, and `wine_image_assets`. Compare relevant rows before and after analytics/removal actions.
- [ ] To inspect or pull a local image, open **View → Tool Windows → Device Explorer** and browse to the app's `files/wine-images/` directory (typically `/data/data/com.n8rtz.winetracker/files/wine-images/` on the emulator).

## Branch `arena/814c691f-wine-manager-android`

**Build it:** `git checkout arena/814c691f-wine-manager-android`, then run the app on an emulator (or the phone, for everything except the instrumented tests).

**What changed:** dead-code cleanup (nothing was compiled, so the first build matters), the Utility Hub became **Profile** with a **Settings** submenu, **App Information** shows build time/commit/install details, debug builds can restore a bundled test cellar, and the backlog/pipeline docs were rewritten.

### Smoke test after the dead-code cleanup

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` both succeed. Any “Unresolved reference” points at something the cleanup removed that was still used; report the file and line.
2. [ ] The app opens on **Cellar** and lists wines. Open **Rankings** and **Tasting Notes**; each lists entries and its filter sheet opens.
3. [ ] **Add Wine** by hand and save; it appears in **Cellar**. **Add Note** and save; it appears in **Tasting Notes**.
4. [ ] On a Wine Detail screen, **Mark as Consumed**; the bottle count drops and the event appears in **History**.
5. [ ] **Settings → Export Data**, then on a disposable install **Import Data (JSON)** the same file; counts match.


### Profile tab and Settings

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] Tap **Profile** in the bottom navigation. Confirm the heading is **Profile** (not "Utility Hub") and it lists only **Price vs. Rating**, **Wine reviewers** and **Settings**.
2. [ ] Tap **Wine reviewers**, edit/add/remove a name, tap **Save**, and confirm the count in the row updates.
3. [ ] Tap **Settings**. Confirm a top bar titled **Settings** with a Back arrow, and three groups: **Backup & data** (Import Data (JSON), both legacy CSV imports, Export Data, Reset Database), **Wine images** (Review Missing Images, Offline wine image storage) and **About** (App Information).
4. [ ] Use the system Back and the top-bar Back arrow: each returns to **Profile**. The Profile tab stays highlighted on Profile, Settings, App Information and Price vs. Rating.
5. [ ] From **Settings → App Information**, then tap the **Profile** bottom-bar tab. Expect the Profile root, not Settings. Repeat from **Price vs. Rating**.
6. [ ] Open **Settings → App Information**. Confirm **Built** shows the build date and time, **Build commit** shows the short commit hash (with “+ uncommitted changes” when you built from a modified working tree) and **Build branch** is correct. Rebuild and reinstall with a trivial change and confirm **Built** moves forward.
7. [ ] On a disposable install, run each Settings action once (export, restore, a legacy CSV import, reset, image review, storage Manage/Change). Each should behave as before the move, and result snackbars/dialogs appear on the Settings screen.

### Drinking-window estimate on Wine Detail (W-050 Phase 1)

*Last run: never (update this line with the date, device/emulator and result after each run).*

**What it is.** When a wine has no drinking window typed in, Wine Detail shows a computed “Est.” line from a draft ageing table (61 rules, each with a source note, all unreviewed). It is display-only: nothing is saved, and Ready Now / Too Early / Past Peak do not use it. The dev seed has no windows, so every in-date wine should show one.

1. [ ] `./gradlew :app:testDebugUnitTest` passes, including `DrinkingWindowEstimatorTest` (the `drinkingwindow` package and this test class were compiled with a standalone Kotlin 2.4.21 compiler and the 15 tests passed with a stand-in runner, 2026-10-16; the Detail screen edit and the Gradle build itself were not run, so report any failure with its name).
2. [ ] Open **Kirkland Signature Cotes Du Rhone Villages 2024**. Drinking Window reads just **Est. 2026 – 2030**, with no wine, grape or appellation name after it.
3. [ ] Open **Kirkland Signature Vacqueyras 2023** (Est. 2026 – 2033) and **Chateauneuf-du-Pape 2023** (Est. 2028 – 2038). Same region field, three different windows.
4. [ ] Open **Kirkland Signature Rioja Reserva** (Est. 2024 – 2032 for the 2020) and **Castillo D Logrono Rioja 2019** (Est. 2020 – 2022): a plain Rioja is shorter than a Reserva.
5. [ ] Open **Kirkland Signature Pauillac Bordeaux 2023** (Est. 2029 – 2038) and a **Bordeaux Superieur 2023** (Est. 2025 – 2030).
6. [ ] Open **Thanisch Riesling Spatlese 2016** (Est. 2021 – 2031): not “past peak” as the old type-only idea would have said.
7. [ ] Open a wine the table does not know (for example **Penfold's Max's Cab 2022**, or **Marani Kindzmarauli 2022**). The line ends **(Estimate by type)**, the only case that carries a note.
8. [ ] Open a **non-vintage** wine: the row says **Not set** (no estimate).
9. [ ] Tap **Edit** on a wine, type a start and an end year and **Save**: the row now shows your years with no “Est.”. Clear both fields and save: the estimate returns.
10. [ ] In **Cellar**, the filters and sorts Ready Now / Too Early / Past Peak still behave as before: a wine with no typed window is not placed in any of them because of its estimate.
11. [ ] Look at the ageing table (`drinkingwindow/AgeingProfiles.kt`) and mark what you disagree with. Each entry's `source` says where it came from; entries marked “assumption” have a start or end year no source gave. Send me the corrections and I will edit the table (and set `reviewed`).

### Rankings and Tasting Notes scroll-to-top

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] Open **Rankings** and scroll down to a wine in the middle of the list (for example Thanisch Riesling).
2. [ ] Open the filter sheet, choose **Rating (Low)** and close the sheet. The list is at the top, showing the lowest-rated wine first.
3. [ ] Repeat with **Rating (High)**, a type or price filter, and by typing in the search box. Each time the list returns to the top.
4. [ ] Scroll down in Rankings, switch to another tab and back: the list should still be where you left it (it only resets when sort, filter or search changes).
5. [ ] **Tasting Notes:** scroll to a note in the middle (for example Thanisch Riesling), open the filter sheet, choose **Price (High)** or **Price (Low)** and close it. The list is at the top. Repeat with a type or status filter and with the search box.
6. [ ] In Tasting Notes, open a note, go back with nothing changed: the list is where you left it.

### Profile tab returns to the Profile page

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] Open **Profile → Settings → App Information**. Tap the **Cellar** tab, then the **Profile** tab. You see the standard Profile page (Price vs. Rating, Wine reviewers, Settings), not App Information.
2. [ ] Repeat from **Profile → Settings** (stop there), and from **Profile → Price vs. Rating**, leaving via **Notes**, **History** and **Rankings**.
3. [ ] From Settings, tap **Profile** directly: it still goes to the Profile page (unchanged).
4. [ ] The other tabs still remember where you were: scroll Cellar, go to Rankings and back, and Cellar is as you left it.

### Add Note from Wine Detail

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] Open a wine from **Cellar** (for example Thanisch Riesling 2016). Tap **+ → Add Note**. The editor opens with that wine and vintage already chosen.
2. [ ] Do the same from a non-vintage wine: the vintage shows NV.
3. [ ] Save the note: it appears under that wine in **Tasting Notes**.
4. [ ] From **Cellar**, **Rankings** or **History** (not a wine detail), tap **+ → Add Note**: the editor starts blank, as before.
5. [ ] On the **Tasting Notes** tab, tap **+**: the Add Tasting Note page opens straight away, with no submenu. Back returns to Tasting Notes. On Cellar the **+** still opens the submenu.

### History: restore to cellar and delete log entry

*Last run: never (update this line with the date, device/emulator and result after each run).*

Use a throwaway install or the debug seed, not data you can't replace.

1. [ ] Open **History**. Entries have no buttons or menu icons. Tapping a card still opens its note as before. **Press and hold** a card: you feel a short vibration and a bottom sheet slides up with the wine name, **Restore to cellar** and **Delete log entry** (red).
2. [ ] Note one wine's quantity in **Cellar**, then consume one bottle from its detail screen (**Mark as Consumed**, add a rating or note) so a History entry appears.
3. [ ] On that entry tap **press and hold → Restore to cellar → Restore**. The entry disappears, the wine's cellar quantity goes back up by one, and the tasting note (if you wrote one) is still in **Tasting Notes**.
4. [ ] Consume a bottle again, then **press and hold → Delete log entry → Delete**. The entry disappears, the cellar quantity does **not** change, and the note is still in **Tasting Notes**.
5. [ ] Tap **Cancel** in either dialog, or swipe the sheet down / tap outside it: nothing changes.
6. [ ] Restore an old entry that has no date (“Date not recorded”): it still restores. If the wine's cellar row was deleted earlier, the wine reappears in the cellar.
7. [ ] Right after consuming, tap the snackbar **UNDO**: it still restores the bottle and removes the note that consume created (unchanged behaviour).

### Keeping your data between test installs

*Last run: never (update this line with the date, device/emulator and result after each run).*

**Why the database can be empty after a run.** The app itself never wipes data on update (there is no destructive migration fallback). The data disappears only when Android **removes the app and installs it fresh**. The usual causes: the new build is signed with a different debug key than the one on the phone (for example Android Studio on your computer vs. Code on the Go, or two computers); a run of the instrumented tests (`connectedDebugAndroidTest`) on the phone, which uninstalls the app when it ends; a manual uninstall or “Clear storage”; or a Run configuration option that clears storage before deploying.

**Find the cause**
1. [ ] Open **Profile → Settings → App Information**. Note **First installed**, **Last updated** and **Signing key**.
2. [ ] Rebuild and reinstall with a trivial change. If **First installed** is unchanged, the install updated in place and your data should be intact. If **First installed** moved to “now”, Android did a fresh install.
3. [ ] After a fresh install, compare **Signing key** with the previous build. A different key means Android could not update in place. Do this once per tool (Android Studio, Code on the Go) and note which differs. Tell me the result; the fix is to make every tool sign debug builds with one shared debug keystore, which I can add to `app/build.gradle.kts`.
4. [ ] If the key is the same but the install was still fresh, check Android Studio **Run → Edit Configurations → Installation Options** for anything that clears app storage or reinstalls, and make sure you have not run `connectedDebugAndroidTest` on the phone.

**Automatic restore after a fresh install (debug builds only)**
5. [ ] In the app, **Profile → Settings → Export Data** and save a JSON backup. Copy it over `app/src/debug/assets/dev-seed/WineTracker_Backup.json` (a seed is already there, made from `_backup/WineTracker_Backup_SubRegionFix.json`: 139 wines, 192 bottles, 116 notes). Build and install the **debug** build.
6. [ ] On a fresh install (or after **Settings → Apps → Wine Manager → Storage → Clear storage**), open the app. Expect a toast “Dev seed restored: N wines, N notes” and your cellar already loaded.
7. [ ] Reinstall over the top without clearing storage: no toast, and the data you added since is untouched.
8. [ ] Add a wine, then **Settings → Reset Database**, close and reopen the app. The database must stay empty (the seed is not re-applied after a reset). **Clear storage** brings it back.
9. [ ] Remove the file and reinstall on a fresh phone state: the app opens empty with no error. A **release** build never restores anything.
10. [ ] The seed file contains your real cellar and is tracked in this private repo (like `_backup/`). Keep debug APKs built with it to yourself. Release builds do not contain it.
11. [ ] After editing data you want in the seed, export again and replace the file. A new file restores once on the next fresh install (existing data is never overwritten).

## Branch `arena/a8add687-wine-manager-android`

**PR #5, merged.** Build toolchain only; no app screens changed.

### Build in Android Studio and in Code on the Go

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] In Android Studio, sync Gradle and run `:app:assembleDebug`. It must resolve AGP 9.1.1, Kotlin 2.2.10, KSP 2.3.2 and Gradle 9.3.1 with **no** "built with AGP 9.3.1 but synced with 9.1.1" warning.
2. [ ] (Optional, expected to be at risk) In **Code on the Go** on the phone, open the project and run its assemble-debug task. With AGP 9.1.1 it can fail with "Could not find guava-33.3.1-jre.jar ... localMvnRepository"; that was the reason PR #5 moved to AGP 9.3.1.
3. [ ] The installed app opens on **Cellar**. In **App Information** the **Signing key** shows a fingerprint; note it for each tool (see *Keeping your data between test installs*).


## Branch `arena/77d08ab9-wine-manager-android`

**PR #4, merged.** Aroma wheel and tasting-note editor controls.

### Aroma wheel — tasting-note editor

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] Open **Add Note** (or edit an existing note) and confirm the **Aroma descriptors** section appears between the sensory sliders and **Common faults**, with the empty-state hint and a **Browse wheel** button.
2. [ ] Open the wheel: the full concentric wheel should fit the screen, showing the family ring (Primary / Secondary / Tertiary / Faults), the 19 coloured group sectors, the descriptor ring, and the outer repeating band labels.
3. [ ] Pinch to zoom (anchored under the fingers) and drag to pan; verify the view clamps so the wheel cannot be lost off-screen, and tapping the centre badge resets the view.
4. [ ] Tap a family, group, or outer-band label: the view animates to that sector; tapping the same label again zooms back out. Zoom animations should respect the system "remove animations" setting.
5. [ ] At the overview zoom, tap a small descriptor: the first tap should zoom to its group, and the next tap selects it. Zoomed in, a single tap should toggle directly.
6. [ ] Select several descriptors across families: each fills with a deep version of its group colour and its label turns white and bold, clearly distinct from its pale neighbours, and the header count updates. Try a 25th selection to confirm the limit notice appears.
7. [ ] Toggle to list view: verify the same selections are checked, group rows show counts, searching (including synonyms like "cassis", "petrol", "jalapeno") returns scored results with path labels, and toggling in either mode is reflected in the other.
8. [ ] Tap **Done**: verify group-coloured chips appear on the aroma wheel card under the overall score (blank card before choosing), each chip removes its descriptor, and saving a note persists the selection (reopen the editor to confirm restore, including after app restart).
9. [ ] On the card, long-press a chip and drag it across a neighbour: it should lift (scale + shadow) and chips should swap as it crosses their midpoints. Arrange three aromas most-to-least prevalent, save, and verify the order holds on the note card in wine details and after reopening the editor.
10. [ ] Verify the wine's detail screen shows the saved aromas as small group-tinted chips on the affected note cards.
11. [ ] Delete a note with aromas and confirm no other notes or inventory rows are affected; run a JSON backup + restore and confirm aromas round-trip.
12. [ ] Check the wheel and list at font scales 1.0 and 2.0, in light and dark themes, and on a small/low-end device if available: labels should stay readable, gestures smooth, and chips should not overflow their rows (the reference README specifically flags Brettanomyces, Secondary, and Tertiary cells as the tightest fits).
13. [ ] With TalkBack on, verify the canvas announces the selected count with Zoom in / Zoom out / Show whole wheel custom actions, and the list mode announces descriptor names, path labels, and checked state.
14. [ ] Confirm the unit tests for the aroma wheel taxonomy, layout, hit-testing, and the sensory JSON converter pass (`./gradlew :app:testDebugUnitTest`).

### Bug fixes in this branch

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] **Bug-022** (aroma launcher wrapping): reorder chips across rows; select 50 and 100 aromas; check large-font labels.
2. [ ] **Bug-023** (score thumb): drag 50 → 75 → 100; tap both endpoints.
3. [ ] **Bug-024** (duplicate aroma editor gone): only one aroma editor appears; **Common faults** follows the sliders.
4. [ ] **Bug-026** (“Recommended by” label): initial empty, open and dismiss, select, clear.


## Branches `arena/d055fead-wine-manager-android` and `arena/9d4919dd-wine-manager-android`

**PRs #2 and #3, merged.** The first large feature batch: label scan on Add Wine, reviewed Open Food Facts image import, Manage Offline Images, and the Price vs. Rating screen.

### Camera label scan — Add Wine

*Last run: not yet verified on a device since the still-photo flow replaced the live-preview flow.*

**Purpose:** Verify that one still photo is captured, read on-device (OCR plus barcode), and produces editable suggestions only; scanning must not bypass **Save to Cellar**. Use a readable physical label or a suitable emulator camera scene.

#### Main scan-and-review path

1. [ ] From **Cellar**, tap the floating **+** button at the bottom right, then tap **Add Wine**. Confirm the title is **Add New Wine** and a **Scan label with camera** button sits above the manual form.
2. [ ] Tap **Scan label with camera**. On first use grant camera permission. If permission is denied, confirm a message says you can still enter the wine manually and the manual form remains available; grant access later from Android app permissions to continue this path.
3. [ ] Confirm the full-screen **Scan wine label** view shows the camera with a portrait label frame and the hint **Fit the label in the frame, then take a photo.** Fit the label inside the frame and tap **Take Label Photo** once. Confirm **Taking high-quality photo…**, then **Enhancing the label and reading the photo… This may take a few seconds.** with a spinner. OCR must not run continuously on preview frames.
4. [ ] When details are found, confirm **Review scanned label** appears showing any Producer, Wine, Vintage, Type and Origin suggestions, plus **Wine dictionary correction** (only when the dictionary changed something) and **Original OCR text**. Verify nothing is applied until you choose **Use Suggestions**.
5. [ ] Tap **Cancel**. The dialog should close and any values already typed in the form should remain unchanged.
6. [ ] Scan again and tap **Use Suggestions**. The scanner closes and the form is filled with the suggested Producer, Wine Name, Vintage, Type and, when found, Country/Region/Appellation. Quantity, Price and Drinking Window must not be filled. Edit a suggestion and verify it behaves like ordinary editable text.
7. [ ] Verify scanning, reviewing and applying suggestions did not save a record. On the disposable emulator only, optionally finish the form and tap **Save to Cellar**; the app should return to **Cellar** and show the wine through the normal save flow.
8. [ ] **Barcode path (needs network):** Scan a bottle whose label has a readable EAN/UPC barcode that Open Food Facts knows. Confirm identity suggestions can appear in the review. Repeat the same bottle in airplane mode and confirm the cached result still appears. With no network and no cache, the scan must still produce OCR suggestions (a failed lookup is never fatal).

#### Failure, permission, and lifecycle checks

- [ ] **Unreadable/text-free view:** Photograph a deliberately obscured or text-free scene. Expect **No readable wine details were found. Try again or enter them manually.**, a **Try Again** button, and a usable manual form after closing the scanner.
- [ ] **Camera startup / capture failure:** If the camera fails or a photo cannot be taken, expect **Could not take the photo…** or **The photo took too long to capture. Try again.** without losing manual entry.
- [ ] **Retry:** Tap **Try Again** after an error and verify a fresh single scan can be requested.
- [ ] **Permission denied:** Deny/revoke permission in the Android prompt or system app settings, reopen **Cellar → + → Add Wine → Scan label with camera**, and confirm manual entry still works. Grant permission again before continuing camera checks.
- [ ] **Leave during scan:** Tap **Take Label Photo**, then immediately use system Back or the close (X) button. Confirm no crash. Reopen the scanner and verify there is no stale review dialog/result, and if **Finishing the previous scan…** shows it clears on its own.
- [ ] **Discard draft:** Apply suggestions, leave without tapping **Save to Cellar**, and reopen Add Wine. Confirm the unsaved scan did not create a cellar entry.

#### Regression and automated checks

- [ ] From **Cellar → + → Add Note**, confirm the tasting-note editor opens without any label-scanning camera flow (scanning is Add Wine only). Back out without saving; opening the editor must not add cellar inventory.
- [ ] Confirm the separate **Profile → Settings → Review Missing Images (Open Food Facts)** action remains available; scanning must not start that importer or save an image.
- [ ] Run the scanner unit tests (`OneShotScanGateTest`, `WineLabelParserTest`, `LabelScanSessionTest`, `LabelScanLadderTest`, `GtinTest` and the rest of `app/src/test/.../scanner/`) through `:app:testDebugUnitTest`. These cover pure logic, not live camera focus or OCR quality. `LabelOcrImageRegressionTest` (androidTest) runs the fixture photos on a device.

### Pareto screen — Price vs. Rating

*Last run: never (update this line with the date, device/emulator and result after each run).*

**Purpose:** Verify that each point represents a rated wine/vintage with a known positive price; charting and filtering are read-only. There is no Pareto-specific automated test suite yet, so use the disposable data below for deterministic visual expectations.

#### Create a deterministic test set in the app

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

#### Navigation, chart, details, filters, and dashboard

1. [ ] Tap **Profile** in the bottom navigation, then tap **Price vs. Rating**. Confirm the screen title, explanation, **Pareto frontier**, and **5 rated wines**. The high-rated **White No Price** record must be omitted because it has no positive recorded price.
2. [ ] Confirm the chart labels **Rating** and **Bottle price ($, logarithmic scale)**. Higher ratings should be higher and lower prices farther left. A **Price / rating trend** dashed-line legend should appear because the five plotted points have varied prices.
3. [ ] Check the frontier using the fixture values: the global efficient sequence is White Delta ($18/82), Red Alpha ($20/85), White Echo ($25/88), and Red Bravo ($30/90). Red Charlie ($40/80) is dominated and is not on the global frontier. The dashboard below should show separate Red and White cards, each with **2 frontier** wines: Red Alpha and Red Bravo; White Delta and White Echo. Red Alpha should rank ahead of Red Bravo by the displayed average-score-per-dollar heuristic; White Delta should rank ahead of White Echo. The dashboard's heuristic is not a universal definition of “best value.”
4. [ ] Tap the Red Alpha point. Its card should show `Arena Pareto Test — Red Alpha`, Vintage 2022, Red, **85.0 / 100**, **$20 / bottle**, **Based on 2 tastings**, and **Overall frontier**. Tap Red Charlie; its card should identify **Trade-off**. Tap outside a card to dismiss it; select another point. Change a type filter and verify any selected-point card is cleared. Press system Back with a card open and confirm it dismisses before leaving the screen.
5. [ ] From the default all-types view, tap the **White** chip. Expect **3 rated wines**, only Red points/frontier, and an empty White dashboard message (**No priced, rated wines with the current filters.**). Tap **All** to restore the five points. Then turn off both Red and White chips; expect **No wines match these filters**. Tap **All** again and confirm the full chart returns.
6. [ ] Scroll the page to verify the separate Red/White dashboard remains below the chart and can be reached without clipping. Use the app in light and dark system themes and increase Android's font size one step; verify chips, point cards, legends, and both dashboard cards remain readable and usable.
7. [ ] On the emulator, enable **Settings → Accessibility → TalkBack** and swipe through the Pareto screen. Confirm the chart meaning/frontier explanation is available as text and that plotted wines can be understood without relying on color alone. If individual Canvas points are not reachable or have no useful spoken description, record that as an accessibility failure. Turn TalkBack off and return to Profile with Back.

#### Small, tied, missing-price, and data-integrity checks

- [ ] The seeded **White No Price** note is the missing-price case: it must not increase the displayed point count. A fresh database with rated notes but no positive price should show **No wines to plot yet**. With one priced/rated wine, expect one dot and no trend-line legend.
- [ ] On a separate disposable database, create three rated Red wine/vintages at the same $20 price with scores 80, 90, and 90. The 80 point is dominated; both equal highest-score points are on the frontier. The equal high points may visually overlap, so also confirm the Red dashboard's frontier count is 2.
- [ ] Before and after viewing/filtering Pareto, compare `wine_bottles`, `tasting_notes`, and `consumption_events` in Database Inspector (or their visible counts/details in the app). The chart must not create, edit, delete, or repair definitions, bottles, notes, scores, or consumption history. Filters and selected points are transient screen state.

### Open Food Facts reviewed image import

*Last run: never (update this line with the date, device/emulator and result after each run).*

**Use a disposable app database.** This action makes live Open Food Facts queries. Candidate previews are temporary; only explicit approval downloads and saves a compressed offline copy. Use a real test wine likely to have an Open Food Facts product listing; if the provider has no match, record that as a no-result/provider check rather than approving a different wine.

#### Search, review, skip/cancel, and approve

1. [ ] Create or restore a test wine definition with no image. From the bottom navigation, open **Profile → Settings** and note the target producer/name.
2. [ ] Tap **Review Missing Images (Open Food Facts)**. While it searches, verify the progress dialog shows the target and offers **Cancel batch**. Search itself must not create image metadata or change the wine.
3. [ ] In **Review image candidates**, verify the displayed **Target** is the intended producer/name (and location when present). For each candidate, inspect its preview, product title, Open Food Facts source, contributor (or **not listed**), dimensions when available, and **View source listing** link. Decide whether it is the exact wine before choosing an image kind.
4. [ ] Exercise the non-approval path first. Tap **Skip this wine** and confirm no local file or associated candidate was saved. Run the search again and tap **Cancel batch** during search/review; confirm **Image review canceled** and verify no unapproved candidate remains.
5. [ ] Run the search again for a verified matching candidate. Tap **Use as bottle** or **Use as label** yourself. Confirm **Preparing approved image** and **Saving offline image** appear, then the batch completes with the appropriate imported/skipped/failed counts. No camera OCR action or Add Wine save action should have approved this image.
6. [ ] Open the wine in Cellar/detail and confirm the chosen image appears. Confirm detail still exposes the Open Food Facts source/contributor and source-listing link. Reopen Cellar, Notes/Rankings, and Wine Detail with airplane mode enabled; the selected local image should still render without a network download.

#### Storage, attribution, backup, and failure checks

- [ ] In Device Explorer, inspect `files/wine-images/`. The approved copy should be a compressed `.webp` derivative, no more than 500 KB; the original downloaded image must not be retained there. In Database Inspector, confirm its `wine_image_assets` row has a relative path, source/provenance, kind, dimensions, byte size, hash, and import time.
- [ ] Open **Profile → Settings → Offline wine image storage**. Confirm the default soft budget is 256 MiB and **Change** permits 64–1,024 MiB. Set it to 64 MiB for a disposable budget test. When a sufficiently large batch estimate or prepared image exceeds the budget, expect a warning and explicit **Continue/Keep this image** or **Cancel/Skip** choices; no existing image should be silently evicted. Restore the usual budget afterward. Do not expect one small image to cross the 64 MiB threshold.
- [ ] If feasible, disconnect the emulator before starting a search. Expect **Could not search this wine** with **Retry**, **Skip wine**, and **Cancel** choices; no image should be saved. Reconnect before the approval/offline-display test.
- [ ] From **Profile → Settings → Export Data**, save a JSON backup. Pull/open it for inspection and confirm source/provenance is retained but image bytes and app-private absolute paths are absent; exported `imageAssets` should have `relativePath: null`. For restore, import that JSON into a second disposable install with **Profile → Import Data (JSON)** and confirm **Replace and restore**. The restored source metadata should remain, no stale local path should be used, and no image should be fetched or re-saved without a fresh reviewed approval. The source link should remain available and **Review Missing Images** should offer a new candidate search.
- [ ] In Android Studio, open `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`; confirm `files/wine-images/` is excluded from cloud backup and device transfer.
- [ ] Verify that skip, batch cancel, provider failure, download/conversion failure, or low device space leaves the wine definition, bottles, tasting notes, ratings, and consumption events unchanged. For a batch/image budget warning, verify declining does not delete any existing image.
- [ ] Run `OpenFoodFactsWineImageProviderTest` with `:app:testDebugUnitTest`. This checks candidate mapping/filtering; the live search, reviewed approval, compressed file, and offline rendering require the manual device steps above.

> Open Food Facts use is treated as authorized per the user's direction. Keep source/contributor metadata visible for attribution.

### Manage Offline Images

*Last run: never (update this line with the date, device/emulator and result after each run).*

**Precondition:** On a disposable emulator, have at least two approved offline images for different wines (use the importer checks above), including one wine with a source URL/provenance and existing bottle, tasting-note, and consumption-history data. To create a consumption event for the test wine, add it with Quantity 2, open its Cellar detail, tap **Mark as Consumed**, uncheck **Add a tasting note**, and tap **Confirm**; add a separate tasting note with **Add Tasting Note**. This leaves one bottle in inventory while giving Database Inspector a consumption row to compare.

#### List, cancel, and confirm one removal

1. [ ] Open **Profile → Settings → Offline wine image storage → Manage**. Wait for loading to finish. Confirm the dialog says **Manage offline images**, gives a local-copy count and MiB usage, and offers **Review missing images** and **Done**.
2. [ ] For each row, inspect the local preview, producer/wine name, image kind, compressed size, import date, source/contributor, and **View source listing** when present. Note the total usage and the selected row's relative path/file size in Database Inspector/Device Explorer.
3. [ ] Tap that row's **Remove offline copy**. In **Remove offline copy?**, verify the text identifies that wine and says its wine/source/history stay. Tap **Cancel**. Reopen/inspect the manager and verify the file, relative path, provenance, copy count, and usage are unchanged.
4. [ ] Tap **Remove offline copy** for the same row again, then confirm the dialog with **Remove offline copy**. Wait for the operation to finish. Expect **Offline copy removed. Wine and source details were kept.**; the selected row disappears and the MiB usage refreshes (rounded to one decimal), with the exact `sizeBytes` total decreasing by that file's size in Database Inspector. The other image remains in the list and on disk.
5. [ ] In Database Inspector, confirm only the selected `wine_image_assets.relativePath` changed to null. Its asset/provenance row and other fields (`sourceName`, source URLs, attribution, kind, size/hash/import time) remain; its `wine_definitions.imageUrl` remains. Confirm `wine_bottles`, `tasting_notes`, ratings, and `consumption_events` are unchanged. In Device Explorer, confirm only the selected local file is gone.
6. [ ] Close/reopen the app and manager. The removed copy must stay absent; the other image must still load. Tap **Review missing images** and verify a new local copy would require fresh candidate review/explicit approval. Do not approve again unless the test explicitly requires it.
7. [ ] Turn on airplane mode. Removal must still work for another selected copy, and any remaining offline image must continue to display. The removed image's source/provenance should still be available in wine details even though its local file is gone.

#### Error recovery and repository instrumentation

- [ ] If a deletion failure can be induced safely on the disposable emulator, expect **The offline copy was unlinked, but storage cleanup will retry deleting its file.**; the Room path must remain cleared, the file must not be re-associated, and reopening the manager/app must recover without a crash. If the operation reports an error, confirm the stale path is still not restored. Do not induce filesystem faults on a personal device.
- [ ] Run the complete `WineImageImportRepositoryTest` connected instrumentation class. It checks: path-only clearing and preservation of source/provenance plus inventory, tasting-note, and consumption history; protection of a file still referenced by another asset; the management-list projection; refreshed stored-byte totals; repeated/idempotent removal; and a stale removal request that must not clear/delete a newer image. It also checks a removed path remains eligible for a fresh reviewed candidate search.
- [ ] Keep the Open Food Facts source/contributor metadata visible for attribution. The offline manager removes only a selected confirmed local copy; it is not a bulk delete, source-history delete, or automatic storage eviction.

### Older fixes to confirm

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] **Bug-005:** reselect the **Cellar** tab; the expanded card and filters reset.
2. [ ] **Bug-019:** a non-vintage wine shows “(NV)”, not “((NV))”, on **Tasting Notes**.
3. [ ] Legacy CSV imports (W-033 / W-034) and JSON restore, on a disposable install.


## Template for a new branch

Copy this block, fill it in, place it under the table at the top, and add a table row.

```markdown
## Branch `<branch-name>`

**Build it:** `git checkout <branch-name>`; emulator or disposable install.

**What changed:** one or two sentences.

### <Feature or fix name>

*Last run: never (update this line with the date, device/emulator and result after each run).*

1. [ ] First step, with the exact button or screen names.
2. [ ] Expected result written as something you can see.
```
