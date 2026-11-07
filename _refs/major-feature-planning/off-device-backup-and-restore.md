# W-057 — Off-device backup & restore (phase brief)

Written 2026-11-03 on `arena/fff57f7b-wine-manager-android`. Supersedes only the open decisions in
[`BACKLOG.md`](../BACKLOG.md) §2 W-057 that the owner answered again for this branch; the rest of that
entry (destination semantics, retention, failure handling, restore) still applies.

> **Update 2026-11-04:** the remaining phases shipped on the same branch. The "Owner decisions",
> "What shipped in this phase", "Deliberate deviations", "Next phases" and "Verification status"
> sections below describe **Phase 1a only** and are kept as the historical record. See
> **[Final pass — all remaining W-057 work](#final-pass--all-remaining-w-057-work)** at the end for the
> current state. In particular: all four destinations now upload, no backup encryption was added, and
> failure alerts are an app-open popup (no notification or Wi-Fi permissions).

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

## Final pass — all remaining W-057 work

Written 2026-11-04 on the same branch. The owner asked to "implement the rest of the backup and
restore" and chose **all of W-057** with two explicit changes:

| Decision | Final answer |
| --- | --- |
| Scope | Everything still outstanding in W-057: three off-device destinations + restore from any of them, priority reorder UI, failure alerts, Auto Backup Phase 0 rules, Phase 2 preferences in `BackupData`. |
| Backup encryption | **No encryption.** Backups stay plain JSON. The setup dialogs and the screen state this plainly; GitHub enabling requires accepting the git-history warning. |
| Alerts | **Popup dialog every time the app opens while a backup is failing**, plus a persistent in-app banner. **No** POST_NOTIFICATIONS, **no** ACCESS_NETWORK_STATE, **no** Wi-Fi-only toggle. |
| Scheduling | Unchanged: app-open due check only, no background work. |

### Shipped

* **`BackupConfigRules.kt`** — pure validation of `owner/repo` and absolute http(s) WebDAV URLs; JVM tests in `BackupConfigRulesTest.kt`.
* **`BackupCredentialStore.kt`** — AES-256-GCM with an Android Keystore key protects the GitHub PAT, DAV password and Drive tree URI. Values throw an actionable "reconnect" error if the Keystore key cannot be unlocked after a device transfer; no silent fallback. `backup_credentials.xml` is excluded in both Auto Backup rule files, which now carry explicit include/exclude decisions (no `<include>`, so Android's default set is preserved). No credential appears in `BackupData` or a JSON export.
* **`GitHubDestination.kt`** — GitHub contents API. `testConnection()` verifies `private == true` and refuses a public repo; upload/verify/list/download/delete; old versions stay in git history, shown as a warning that must be accepted before enabling.
* **`GoogleDriveDestination.kt`** — SAF `OpenDocumentTree` with `takePersistableUriPermission`; `isConfigured()` checks the persisted grant, so a revoked grant becomes "needs reconnect" instead of a failed upload.
* **`WebDavDestination.kt`** — TrueNAS SCALE share or Nextcloud: MKCOL, PUT, Depth-1 PROPFIND parsed with `android.util.Xml`, GET, DELETE; plain `http://` is allowed on a LAN but flagged in setup.
* **`BackupCoordinator.kt` / `BackupViewModel.kt` / `BackupRestoreScreen.kt`** — all four destinations built fresh per run, independent verify/prune, `needsSetup` results kept separate from failed uploads, setup/connection-test dialogs, priority ↑/↓, restore destination chooser + snapshot list, `NEEDS_SETUP` health, 3-day overdue warning and 30-day manual reminder.
* **`MainActivity.kt`** — app-open **Backup failed** popup at scaffold level (so it shows on any screen) and `refreshAfterRestore()` so the reviewer list and image budget update immediately after a restore.
* **W-057 Phase 2** — `BackupData` gains optional `reviewerNames` / `imageStorageBudgetBytes`; `JsonExporter` writes them; `DataImporter` validates them **before** the destructive transaction and leaves current values untouched when an older backup omits them. `BackupPreferences.kt` is the canonical key holder; `tools/tests/test_backup_preferences.py` asserts its literals match the two ViewModels.

### Deliberately not done

* Backup-file encryption (passphrase, AES-GCM header, Keystore-wrapped derived key) — declined by owner; the JSON is plaintext, so only trusted destinations should be enabled.
* System notifications, Wi-Fi-only uploads, `POST_NOTIFICATIONS` / `ACCESS_NETWORK_STATE` permissions, any manifest change.
* Change detection / "skip unchanged" fingerprinting — the owner's scheduling rule is a simple due check; every due run uploads a new snapshot.
* WorkManager/AlarmManager/service/background polling, dependency or version changes, Room schema changes.
* CSV/ZIP export, image-byte bundling, live multi-device sync (declined in `ARCHIVE.md` Part D).

### Verification

No JDK/Android SDK is available in the authoring sandbox, so `./gradlew :app:assembleDebug`,
`:app:testDebugUnitTest`, JUnit execution and all device/emulator checks remain outstanding. Host
checks that did run: 25 pure-logic runtime assertions, JVM test-source compilation for
`BackupScheduleTest` / `BackupConfigRulesTest`, a stub-based signature/syntax compile of every new
Kotlin file (exit 0), syntax-only parses of the seven modified existing Kotlin files, and
`python3 tools/tests/test_backup_preferences.py` (5 tests, OK). The full device checklist — including
real MediaStore/SAF/GitHub/WebDAV behaviour, Keystore survival and Auto Backup payload inspection —
is in this branch's section of `THINGS_TO_CHECK.md`.
