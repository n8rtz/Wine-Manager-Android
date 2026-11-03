# W-057 — Off-device backup & restore (phase brief)

Written 2026-11-03 on `arena/fff57f7b-wine-manager-android`. Supersedes only the open decisions in
[`BACKLOG.md`](../BACKLOG.md) §2 W-057 that the owner answered again for this branch; the rest of that
entry (destination semantics, retention, failure handling, restore) still applies.

## Owner decisions for this branch

| Decision | Answer |
| --- | --- |
| Self-hosted destination | **WebDAV** — TrueNAS SCALE's built-in WebDAV share or the Nextcloud app. Generic WebDAV, no vendor SDK. |
| Google Drive | **Android folder picker (SAF)** — pick a Drive folder once, persist the URI grant; no Google Cloud OAuth client or new auth dependency. |
| GitHub | Private repo + fine-grained PAT (W-057 decision, unchanged); refuses a public repo. |
| Scheduling | **No background work of any kind.** Record the last confirmed backup, flag when it is older than the user-set interval, and start the backup on the next app open. Rationale (owner): data cannot change while the app has not been opened, so a background timer adds nothing. |
| Build order | **Phased.** This turn: pure-Kotlin schedule/naming/retention logic, the **Backup & Restore** submenu, and a working automatic **Local** backup. Off-device uploads follow in later turns. |
| Submenu name | **Backup & Restore** (replaces the W-057 working name "Backup"). |

Consequences: no WorkManager/AlarmManager dependency, no service, no receiver, no new permission,
no `AndroidManifest.xml` change, and no periodic work. `MainActivity.onResume()` calls
`BackupViewModel.onAppOpened()`, which runs the due check; an in-progress upload is not kept alive
after the app is closed, and the next open simply retries (status stays "needs attention" until a
copy is confirmed).

## What shipped in this phase

* `backup/BackupSchedule.kt` — pure Kotlin: `BackupDestinationId`, `BackupInterval`, `BackupSettings`,
  calendar-aware `isDue` / `daysOverdue`, `health`, `protection`, snapshot naming (`yyyy-MM-dd_HHmmss`)
  and `namesToPrune`. JVM tests in `app/src/test/.../backup/BackupScheduleTest.kt`.
* `backup/BackupDestination.kt` — the contract the later Drive/GitHub/WebDAV implementations plug into,
  plus `BackupSnapshot`.
* `backup/BackupSettingsStore.kt` — SharedPreferences settings and per-destination confirmed status.
* `backup/LocalBackupDestination.kt` — `Documents/WineManager/` through
  `MediaStore.Files.getContentUri("external")` (MediaStore.Downloads only allows `Download/`), with
  read-back SHA-256 verification and name-scoped listing/deletion.
* `backup/BackupCoordinator.kt` — existing `JsonExporter` snapshot → verify → prune, per destination,
  with one `Mutex` and no silent fallbacks; `runAutomaticCheck()` and `backUpNow()`.
* `backup/BackupViewModel.kt` + `ui/screens/BackupRestoreScreen.kt` — toggles, frequency, keep-N,
  status, **Back up now**, **Restore from backup…** (feeds the existing validated `DataImporter`
  through `WineViewModel.importJsonFile`).
* `ProfileScreen` gains one Settings row; `MainActivity` gains the `backup_restore` route,
  `BackupViewModel`, and the `onResume` due-check.

Defaults follow W-057: automatic on, every **1 month**, **Local** on, keep newest **5**, delete old
backups on. Fresh installs write their first local copy on first open. No Room change and no change
to `BackupData`, `JsonExporter` or `DataImporter`.

## Deliberate deviations from the W-057 design

1. **No WorkManager / one-off enqueued job.** The owner explicitly removed all background work. The
   due check is a pure function called from `onResume`.
2. **Snapshot names include seconds** (`WineTracker_2026-11-03_211730.json` instead of `_2117.json`)
   so two backups inside one minute cannot collide. Retention only matches this exact pattern.
3. **Off-device toggles are visible but disabled** in this phase, labelled "Planned next pass". Only
   `LOCAL` uploads. A stored enabled destination without an implementation is refused with a log
   warning, not silently skipped by the UI.
4. **Priority order is stored** (`BackupSettings.destinationPriority`) but has no reorder UI yet; the
   screen lists destinations in the fixed W-057 order.
5. **Encryption, Wi-Fi-only, notifications/banner, the 30-day manual reminder, "no backup for N
   days" and the Auto Backup XML rules are not in this phase.** They are suggestions, not decisions.
6. The reviewer list and offline-image budget are still absent from `BackupData` (W-057 Phase 2).

## Next phases

1. **GitHub** (private repo + fine-grained PAT, refuse public repos, SHA verification, encrypted
   backups recommended; old files stay in git history).
2. **Google Drive** via SAF tree URI with persisted permission; verify by reading back.
3. **WebDAV** for TrueNAS SCALE / Nextcloud: URL + credentials, PUT/PROPFIND/GET/DELETE.
4. Encryption (passphrase, AES-GCM, Keystore) before or with the first off-device destination, plus
   Wi-Fi-only and visible failure warnings.
5. W-057 Phase 2: reviewer list + image-storage budget as optional fields in `BackupData`.

## Not re-proposed (declined in `ARCHIVE.md` Part D)

CSV/ZIP export and bundling image bytes into a backup stay declined. Backups remain JSON only.

## Verification status

No JDK/Android SDK in the authoring sandbox: `./gradlew :app:assembleDebug` and
`:app:testDebugUnitTest` were **not** run here. The pure Kotlin core was compiled with a host Kotlin
compiler and its rules exercised by a 42-assertion harness; all new Kotlin files type-checked
against host stubs (this checks signatures and syntax, not Android/Compose runtime behaviour).
Device checks are listed in this branch's section of `THINGS_TO_CHECK.md`.
