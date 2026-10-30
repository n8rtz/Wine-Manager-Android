# BACKLOG.md — Single list of open work

**This is the one place for wishes, bugs, pending verification and cleanup.** Finished items move to [`ARCHIVE.md`](ARCHIVE.md) (and a one-line summary goes into [`CHANGELOG.md`](CHANGELOG.md)).

*Consolidated 2026-10-15 from `wishes.md`, `wishes-v2.md`, `bugs.md`, `PLAN.md`, the “open decisions / future” notes in `major-feature-planning/`, and the verification status in `THINGS_TO_CHECK.md`. Status was checked against the source tree and `CHANGELOG.md` only — **nothing was built, run or tested on a device.***

## How to use (rules for humans and AI agents)
1. **One list.** Log new wishes, bugs, decisions and cleanup items here — not in new files.
2. **Wishes:** `W-nnn`, next free number **W-060**. Use *Goal / UI-Trigger / Location / Status*. UI styling rules are in `ARCHIVE.md` (W-001–W-003, W-007, W-008, W-024).
3. **Bugs:** `Bug-nnn`, next free number **Bug-031**. Add new bugs at the top of *Active bugs* using the template below. Be concise and note the technical root cause if known. Do **not** fix a logged bug unless asked to “fix Bug-nnn”.
4. **Don’t silently edit other entries.** Change an entry’s *status* freely; don’t rewrite or delete its description unless asked.
5. **When something ships:** move the entry to `ARCHIVE.md` (Part A for wishes, Part B for bugs), add a line to `CHANGELOG.md` under `[Unreleased]`, and update `THINGS_TO_CHECK.md` if it needs a manual check.
6. **Status words:** `Open` · `Partial` · `Needs decision` · `Planned (brief only)` · `Fixed – awaiting device check`.

### Bug template
```
### **[Bug-0xx]**: Short Title
* **Date Logged:** YYYY-MM-DD
* **Component/File:** `ui/screens/ExampleScreen.kt`
* **Severity:** [Low | Minor UI | Edge Case]
* **Description:** Brief description of what is happening.
* **Steps to Reproduce:**
    1. Step one
    2. Step two
* **Known Workaround / Notes:** Any temporary fix or notes on why it happens.
```

## Where everything else lives
| File | Purpose |
| --- | --- |
| `BACKLOG.md` (this file) | Open wishes, bugs, decisions, verification, cleanup |
| `ARCHIVE.md` | Completed wishes (full specs), resolved bugs, completed plan items |
| `CHANGELOG.md` | Release-style summary of what changed |
| `THINGS_TO_CHECK.md` | Step-by-step manual/emulator test checklists (kept separate; summary below) |
| `LESSONS_LEARNED.md` | Post-mortems and engineering rules |
| `ARCH.md` | Architecture rules and the directory map |
| `PIPELINE.md` | Development pipeline: work-item lifecycle, quality gates, release process, release plan |
| `AGENTS.md`, `../.agents/instructions.md` | Product/stack overview (`AGENTS.md`) and the single AI-agent operating-rules file |
| `major-feature-planning/` | Design/WBS briefs for larger features (index in its README) |

---

## 1. Active bugs

### **[Bug-030]**: Device wine label audit/test closes when scrolling to the bottom (cannot save results)
* **Date Logged:** 2026-10-30
* **Component/File:** `ui/screens/DevicePhotoLabScreen.kt`
* **Severity:** Minor UI (blocks saving audit results)
* **Description:** Owner report: the audit screen auto-closes when scrolling to the bottom, where the Save buttons live. Probable root cause: each photo card is a single LazyColumn item, so scrolling it into view composes the photo plus every raw-OCR Text line at once (hundreds of nodes per card, added with the raw-OCR feature); composing several heavy cards while scrolling can kill the screen/process before the bottom Save controls are reachable. Only onClose/system-back can finish the activity in code, so this presents as a crash/process death, not a navigation.
* **Steps to Reproduce:**
    1. Run the audit on ~20 photos.
    2. Scroll toward the bottom to reach Save — screen closes.
* **Known Workaround / Notes:** Fix applied 2026-10-30 (awaiting device check): rendered raw OCR per card capped at 40 lines (full text remains in Copy-all and the diagnostic export), and a "Save results" block (Copy all + export Save buttons) now sits at the top of the results, reachable without scrolling. If it still closes, note whether it returns to the dataset screen or the launcher (distinguishes finish() vs process death) and report.
* **Update 2026-10-30 (later):** owner confirms the exit persists after the cap/top-save fix. Code inspection: the only `finish()` is the top Back button, so the exit is a crash or process death, not navigation. Shipped crash capture (uncaught-exception handler in `DevicePhotoLabActivity` writes `device-wine-label-audit-crash.txt`; the next session shows "Previous session ended unexpectedly" and appends the report to Copy-all) plus lighter cards (OCR text moved out of SelectionContainer, image decode 1000->800px). Awaiting the captured report or the owner's landing-spot observation.


### **[Bug-027]**: Duplicate merged Gradle build-commit declaration
* **Date Logged:** 2026-10-24
* **Component/File:** `app/build.gradle.kts`
* **Severity:** Build configuration
* **Status:** Fixed – awaiting device check
* **Description:** The synced script declared `val buildCommit` twice in the same scope, a Kotlin DSL redeclaration. Removed only the redundant legacy short-SHA declaration, preserving provider-backed 12-character metadata, ABI selection and all six BuildConfig fields.
* **Steps to Reproduce:**
    1. Evaluate the pre-correction merged app Gradle script.
    2. Both same-scope declarations are present; the source cannot resolve them as separate variables.
* **Known Workaround / Notes:** Structural regression check passes. Full Gradle evaluation/Android build has not run: the Gradle 9.3.1 distribution download fails TLS in this workspace, before script compilation. Verify with the real Android toolchain; do not upgrade pinned versions to bypass it.

Every earlier logged bug has been resolved (see `ARCHIVE.md` Part B). Bug-005 and Bug-019 are already fixed in source and retain device-confirmation checks.

*(Add new bugs here, newest first.)*

---

## 2. Open wishes — in priority order

*Rewritten 2026-10-15 against the current architecture (schema v21, `WineCard` shared by Cellar/Rankings/Notes, in-memory filter states, still-photo scanner, aroma wheel, Open Food Facts importer). Several entries originally described an app that no longer exists; the original lists (`wishes.md`, `wishes-v2.md`, `PLAN.md`) remain in git history at `d4ba796`, and where the old wording still helps it is kept in a collapsed “Original specification” block.*
*How the order is used, how complexity is defined, and the release plan: [`PIPELINE.md`](PIPELINE.md).*

**Complexity:** **S** = one component/screen, no schema change · **M** = several files across UI + ViewModel, new pure-Kotlin logic with JVM tests, no schema change · **L** = Room migration or a new subsystem; needs a brief · **XL** = multi-release program with external content/licensing.

| Rank | ID | Title | Complexity | Status | Target release |
| --- | --- | --- | --- | --- | --- |
| Owner priority | W-058 | Scanner accuracy diagnosis and per-label pipeline-bottleneck table | M diagnostics; L phased recognition follow-up | Partial — table wired and host-tested; Android verification pending; recognition plan not implemented | Experimental debug Lab; no normal Add Wine promotion |
| 1 | W-056 | Automated build-and-test (CI) and tag-based releases | S | Open | 0.5.x |
| 2 | W-057 | Automatic backup with selectable destinations and options (Settings → Backup: Local/Google Drive/GitHub toggles, auto or manual with “every N days/weeks/months” maximum interval, encryption, keep-newest-N, failure warnings), confirmation, restore | L (Phase 0 = S; 1a = L; 1b and each destination = M) | Open — decisions closed 2026-10-16 | 0.5.x (Phase 0), 0.6.0 (Phase 1) |
| 3 | W-050 | Drinking window: offline estimates (appellation / grape / designation aware) now, pulled-in data later | M + M per source + L | **Phase 1 written; estimator tests pass standalone, Gradle build and screen not yet run**; the owner reviews the ageing table; Phase 2 (pulled-in data) is next after W-057 | Phase 1: 0.5.6 (this branch); Phase 2: 0.7.0 |
| 4 | W-043 + W-025R | Vintage filter/sort and complete filter controls on Cellar, Rankings and Notes | M | Open | 0.7.0 |
| 5 | W-035 | Country flags on wine cards | S | Open | 0.7.0 |
| 6 | W-053 | Filter tasting notes by aroma | M | Open | 0.8.0 |
| 7 | W-052 | Wine Resources page (glossary, grapes, regions, map) | XL (Phase A = L) | Planned (brief only) | 0.9.0+ |

*(W-017, W-018 and W-031 were never written down in the original lists.)*

**Why this order.** `THINGS_TO_CHECK.md` records no build or device run for the large unreleased batch, and the 2026-10-15 cleanup was never compiled, so protecting the build comes first (W-056). The next priority is protecting the data itself (W-057): every wine, note and consumption record exists only on one phone, so a loss or failure would be unrecoverable. Then come the features that fix daily-use gaps in screens that already exist (filters), then the feature that delivers the original brief's “is it ready to drink?” promise (W-050: the owner ordered it directly after the backup work, and its Phase 1 estimator is already written in the current branch). Flags are cheap but cosmetic, so they ride along with the filter release as a low-risk addition. Aroma filtering builds on the shipped wheel. The Resources page is the biggest and most speculative, so it is last and phased.

---

### Owner priority: W-058 — Scanner accuracy diagnosis and pipeline bottlenecks
**Complexity:** M diagnostics; L phased recognition follow-up · **Status:** Partial
* **Goal:** Diagnose actual phone results toward strictly >80% completely correct forms and <5% with 2+ errors; expose the number of distinct wine labels passing each pipeline gate, including separate ML Kit/Paddle branches, in the in-app benchmark and both CSV outputs.
* **UI-Trigger / Location:** Debug phone benchmark → **Pipeline bottlenecks**, **Copy scored stats**, scored/raw CSV exports (`row_type=pipeline_step`). New compact per-label checkpoint telemetry preserves stage outcomes; old missing evidence remains unknown.
* **Scope now:** Owner requested syncing merged main first (done at `8fce0c6`/PR #9, no duplicate lookup fix), then implementing the table. 80 step definitions; latest-attempt label counts; passed/blocked/not reached/not recorded/N/A states; resolver setup, precision/protection gates and final coverage/correctness clearly separated. No OCR/model/budget/reference-asset or normal Add Wine behavior changes.
* **Evidence / next steps:** [Audited phone results and proposed follow-up plan](../docs/scanner-accuracy-plan.md). Supplied f9 run remains 34/160 complete and 78/160 multi-error; no new OCR result. At 160 eligible forms the strict goal requires ≥129 complete and ≤7 multi-error. Correctness/helper threshold alignment is still pending. Android build/native/UI validation remains open in this branch's `THINGS_TO_CHECK` section. Broader recognition work is proposed, not authorized/implemented by the table request; no guaranteed gains.
* **Priority:** Owner-directed scanner investigation/table interrupts other wishes; their ordering is otherwise unchanged. Do not promote latest Lab into normal Add Wine without separate authorization.

### 8b. W-059 — Trigram/FTS appellation resolver built from `origins.csv` *(parked, owner decision 2026-10-27)*
**Complexity:** M · **Status:** Needs decision — parked behind W-058's measurement
* **Goal:** widen lab-only appellation repair beyond the bounded edit distance shipped in `PlaceRepair` (one to two edits, at most 20 % length change, so a dropped or reordered word in a long name never resolves).
* **Shape:** a `debug`-only FTS5 trigram asset built deterministically from `data/wine-knowledge/origins.csv` (licensing is clean: project-authored plus pinned CC0 UC Davis metadata), with manifest + SHA-256 + record count, a `--check` freshness test, and the existing `TrigramGazetteer` shortlist→rerank policy (≤64 candidates, similarity ≥0.82, ≥0.06 margin, abstain on ambiguity).
* **Why parked:** a second copy of the catalog in another format is a new source of truth to keep honest, and the owner's rule is that Lab v4 should stay comparable to Prod, which repairs with bounded spelling. Wiring `PlaceRepair` first keeps the Prod/Lab delta about the producer index and solver, not about a third matching engine.
* **Revisit when:** measurement over the 124 labels shows anchor-gated near-misses failing on a meaningful share, those failures are mainly word-order/dropped-word cases, or the catalog passes roughly 4,000 names and the in-memory scan starts to matter.

### 1. W-056 — Automated build-and-test (CI) and tag-based releases *(new)*
**Complexity:** S · **Status:** Open
* **Goal:** Every PR is compiled and unit-tested automatically, and each release is a Git tag, so a broken build or failing test is caught before merge. The repo has no `.github/` workflows. Tags exist up to `v0.5.5` (the `CHANGELOG.md` compare links use `v*` tags); the CI work adds tagging as part of the release step.
* **Scope:** one GitHub Actions workflow on pull requests and pushes to `main`: JDK 17, `./gradlew :app:assembleDebug :app:testDebugUnitTest`, upload the test report. Instrumented tests (`connectedDebugAndroidTest`) stay manual until an emulator job is justified. Release steps are in `PIPELINE.md`.
* **Watch-outs:** confirm every test under `app/src/test` runs on a plain JVM (the scanner OCR regression test must not need a device); the 13 label fixtures (~45 MB) make checkout slower.
* **Done when:** a PR shows a green/red check from the workflow, and `v0.5.6` exists as a tag.

### 2. W-057 — Automatic backup with selectable destinations *(new)*
**Complexity:** L (phased: Phase 0 = S, 1a = L, 1b = M, 1c = M per destination, Phase 2 = M) · **Status:** Open — decisions closed 2026-10-16; build after W-056 (Phase 0 first)
* **Goal:** If the phone is lost, broken or reset, the cellar, notes and history survive without the owner remembering to do anything. **A backup only counts as protection once it is confirmed to be off the device** — a copy on the same phone protects against nothing. Backups run on their own; the app shows when the last *confirmed off-device* copy was made, warns when that stops happening, and restores in a few taps on a new phone. Manual **Export Data** (JSON) stays as the escape hatch.
* **UI (owner-specified):** **Profile → Settings → Backup** is a submenu of toggles and a few options, in four groups:
  1. **Destinations** — one **toggle** per destination, which also decides *where* backups go:
     * **Local backup** — a folder on the phone: by default `Documents/WineManager/` (no setup), or a folder the owner picks with the Android folder picker. Labelled “Local copy — does not protect against losing the phone”.
     * **Google Drive**, **GitHub**, and later WebDAV/Nextcloud, Dropbox, OneDrive…
     * **Priority order (owner, 2026-10-16):** when more than one destination is switched on, the list below the toggles can be **reordered** (drag, or up/down buttons for accessibility) and the order is the priority. A backup goes to **every** enabled destination, each on its own clock and independent of the others. Priority decides: (1) the order in which they are attempted; (2) which destination **Restore from backup…** offers first; (3) which destination's status heads the summary line. If the top-priority destination fails, the others still run and Restore offers the next one. *If the owner meant “try only the first one that works”, that is a one-line change to the pipeline; this is the interpretation to confirm on first use.*
     * Switching a toggle on starts that destination's one-time setup; switching it off stops its uploads but never deletes existing backups. Each toggle has a one-line status (“Last confirmed 14 Oct, 9:02 pm” / “Needs attention: reconnect”).
  2. **Schedule** — **Automatic backup** switch.
     * *On:* a calendar-style recurrence row, **Every [number box] [days ▾ / weeks ▾ / months ▾]** (the same pattern as Android Calendar's “repeat every” control; e.g. “Every 2 months”). The interval is the **maximum time allowed since the last successful backup** (see *Fuzzy scheduling* below), not a fixed clock time. Backups go to every enabled destination. **If the data has not changed since a destination's last backup, nothing is uploaded** (the status reads “Up to date — no changes since 14 Oct”).
     * *Off = manual only:* nothing runs by itself; **Back up now** sends to the enabled destinations, and a gentle reminder appears when there are unbacked-up changes and the last confirmed backup is old (proposed threshold: 30 days). If nothing has changed, *Back up now* says “Already up to date — last backup 14 Oct” and offers **Back up anyway**.
     * **Only on Wi-Fi** (default on) — *my addition, confirm*.
  3. **Encryption** — **Encrypt backups** switch with a passphrase (set once, entered twice; **Change passphrase**). Applies to every destination, including Local. Warning shown at setup: a forgotten passphrase makes backups unreadable.
  4. **Old backups** — **Delete old backups** switch with **Keep the newest N** (stepper, default 5, range 1–50). *Off* keeps everything.
  * A summary line at the top: **“Off-device protection: active”** when at least one *online* toggle is on and confirmed recently (or, in manual mode, “Manual — last confirmed …”), otherwise a clear warning (“Only a local copy exists” / “No backup destination enabled”).
  * **My addition to confirm:** two plain action rows at the bottom, **Back up now** and **Restore from backup…** (a backup system with no restore path is not usable). The existing manual Export/Import rows stay under **Backup & data** for now.
* **Current state (verified in source):**
  * Backup today is manual only: **Profile → Settings → Export Data** writes `WineTracker_Backup.json` through `JsonExporter.exportDataToJson` (one consistent Room snapshot inside a transaction, UTF-8), and **Import Data (JSON)** restores it through the validated, transactional `DataImporter`. Backup stays JSON-only; bundling images and CSV/ZIP export were declined (`ARCHIVE.md` Part D).
  * `AndroidManifest.xml` already has `allowBackup="true"` with `backup_rules.xml` / `data_extraction_rules.xml`, which exclude only `wine-images/` and are otherwise the Android Studio templates. Android's **Auto Backup** may therefore already be copying the Room database and both SharedPreferences files to the owner's Google account. **Nobody has verified that it runs or that a restore on a fresh install works.** It is an OS feature, so it cannot be a toggle in the app; the Backup screen can show it as an info row linking to Android's backup settings.
  * No WorkManager dependency, no scheduled work, no network backup code.
  * **Gap:** the reviewer list (`TastingNoteViewModel` SharedPreferences) and the offline-image storage budget (`WineImageImportViewModel` SharedPreferences) are **not** in `BackupData`, so a JSON restore on a new device resets them.
* **Destinations**

| Destination | Off-device? | App can confirm it? | Setup when toggled on | Notes |
| --- | --- | --- | --- | --- |
| **Local backup** | **No** (unless some other app syncs that folder; the app cannot tell) | No | None by default (`Documents/WineManager/` via MediaStore); optional **Change folder…** (Storage Access Framework, permission persisted) | Quick restore if the app data is corrupted; never counted as protection. |
| **Google Drive** (app-data folder) | Yes | **Yes** — read back size/checksum after upload | Choose a Google account on the device, grant the non-sensitive `drive.appdata` scope | Needs Play services and a one-time owner-side Google Cloud OAuth setup tied to the app's package name and signing key. Hidden from the Drive file list, readable by Google. |
| **GitHub** | Yes | **Yes** — GitHub returns the stored blob's SHA; compare it with the SHA computed locally | Enter `owner/repo` and a fine-grained personal access token limited to that one repo (Contents: read and write); the app tests the token and **refuses a public repo** | Each backup is a commit, so history is built in. Tokens expire, so show an expiry warning. Personal data lands in a third-party repo: it **must** be private and encryption is strongly recommended. **Deleting old backups only removes the files from the current branch; earlier commits in git history still contain them** (true deletion needs rewriting history or recreating the repo), so the app says this next to the toggle and encrypted backups are the real protection there. |
| **Later: WebDAV/Nextcloud, Dropbox, OneDrive** | Yes | Per provider | Per provider | Added one at a time behind the same interface. |

* **Design (Phase 1):**
  1. **`BackupDestination` interface** (`id`, `isConfigured`, `upload`, `verify`, `list`, `download`, `delete`), implemented per destination. The scheduler and UI only know the interface, so adding a destination does not touch them.
  2. **`BackupSettings`** (pure Kotlin, JVM-testable): `destinationPriority` (ordered list of enabled destination ids), `autoEnabled`, `interval` (`count: Int`, `unit: DAYS|WEEKS|MONTHS`), enabled destination ids, `wifiOnly`, `encryptionEnabled`, `pruneEnabled`, `keepCount`. Plus snapshot naming (`WineTracker_yyyy-MM-dd_HHmm.json`, `.json.enc` when encrypted), the retention rule, the due-for-more-than-3-days warning rule, the manual-mode reminder rule and per-destination status types.
  3. **One pipeline:** snapshot (existing `JsonExporter`, one Room transaction) → **encrypt if enabled** → offer to each *due or requested* destination **independently** (one failing destination never blocks the others) → verify → prune. **No background polling:** data only changes while the app is open, so in automatic mode the **due-check runs whenever the app is opened or returns to the foreground** (and nothing is scheduled while the app is closed). If something is due, the check enqueues a one-off `androidx.work` job so the upload still finishes if the user leaves the app mid-backup, survives a process kill, and waits for its constraints (network; unmetered when *Only on Wi-Fi* is on). *Back up now* enqueues the same expedited one-off. Network is required only for online destinations.
  3a. **Fuzzy scheduling (interval = maximum age of the last successful backup):**
     * Each destination is due when it has **unbacked-up changes** (see *Change detection*) **and** `now − lastConfirmedSuccess ≥ interval`, or when it has never succeeded. The interval therefore stays the **maximum time changes may sit unprotected**: data edited the day after a backup waits until the interval has passed, and data that has not changed is never re-uploaded however old the last backup is. “Every 2 months” with changed data and a last backup 2 months and 2 days ago, or 3 months ago, is due immediately; one made 6 weeks ago is not. There is no fixed time of day and no need to run exactly on a deadline.
     * **Calendar arithmetic:** months and weeks use the device's calendar and time zone (`java.time`; “1 month” after 31 Jan is 28/29 Feb), not 30-day approximations. The due rule is a pure function `isDue(now, lastSuccess, interval, zone)` with JVM tests for month ends, leap years, DST changes, “never backed up” and clock changes (a last-success time in the future counts as due).
     * **What counts as the last success:** a *confirmed* backup at that destination, whether it was automatic or manual. A manual backup therefore resets that destination's clock. Each destination keeps its own clock, so a destination that has been failing is retried while healthy ones stay quiet.
     * **When the check runs:** on every app open/foreground only. Because the data cannot change while the app is closed, a closed app never needs a check. A due backup that cannot run yet (for example *Only on Wi-Fi* with no Wi-Fi) stays due, shows “Waiting for Wi-Fi”, and the queued one-off runs as soon as the condition is met, even if the app has been closed in the meantime. The guarantee is “started on the first app open after it becomes due, and completed as soon as conditions allow”.
     * **Retries:** transient failures retry with backoff a few times within one run; anything still failing stays due and is retried at the next app open.
     * **Limits:** number box accepts 1–99; days up to 365, weeks up to 52, months up to 24. Invalid input is rejected inline, not clamped silently.
  3b. **Change detection (skip when unchanged):**
     * **Fingerprint, not a dirty flag.** Before deciding, the app builds the same canonical snapshot it would upload and computes a **SHA-256 fingerprint of the plain (pre-encryption) JSON**. A flag set by “something changed” could be lost, miss edits, or go stale; recomputing the fingerprint is self-correcting. The snapshot is small (tens of KB), so this is cheap. The fingerprint function sorts every list by primary key and the snapshot contains no timestamps, so identical data always gives the identical fingerprint (JVM-tested, including different insertion order).
     * **Per destination:** each destination stores `lastBackedUpFingerprint`, `lastBackupName` and `lastSuccessAt`, written **only after a verified upload**. A failed or unverified backup leaves the old fingerprint, so the destination stays due. Enabling a new destination always backs up once, even if another destination is current.
     * **The fingerprint covers what is actually in the backup file**, so image-provenance changes count, and once Phase 2 adds the reviewer list and storage budget to `BackupData`, changing those counts too. Until then they are not backed up and do not trigger a backup.
     * **Format changes count as changes.** Turning encryption on or off, or changing the passphrase, is mixed into the fingerprint, otherwise unchanged data would skip the backup and leave only plain-text copies behind. Changing the retention number does not trigger a backup.
     * **Never skip on a missing backup (rule 15):** before skipping, the app checks that the last backup file still exists at that destination (a file-existence check locally, a lookup for the file by name online, run only when the interval has elapsed). If it was deleted or is unreachable-but-known-missing, the destination is treated as changed and backs up. If the destination cannot be reached at all, the status says “Couldn't confirm the last backup” instead of claiming “Up to date”.
     * **A restore makes the data match** the restored backup, so no extra backup is taken right after a restore unless the user's data actually differs.
     * **Backup-state preferences** (fingerprints, names, times) are excluded from Auto Backup, so a restored phone treats every destination as needing a fresh backup.
     * Skipping is silent only in the sense that nothing is uploaded; the unchanged state is always visible in the status line, and it never raises a warning.
  4. **Encryption:** the passphrase is turned into a key with PBKDF2-HMAC-SHA256 (high iteration count, random salt) and the snapshot is sealed with AES-256-GCM. The file starts with a small versioned header (magic, format version, KDF parameters, salt, nonce) so restore can tell encrypted from plain files and mixed histories still work. The passphrase itself is never stored; so unattended backups can run, the derived key is kept wrapped by an Android Keystore key and excluded from Auto Backup. On a new phone the user types the passphrase to restore. **If encryption is on and the key is unavailable, the backup fails visibly — it never falls back to plain text** (agent rule 15). Changing the passphrase affects new backups only; old ones still need the old passphrase.
  5. **Retention:** after a *verified* upload, each destination deletes its oldest backups beyond `keepCount`, counting plain and encrypted files together. Only files matching the app's own naming pattern are ever deleted (never anything else in a user-picked folder or repo), the newest backup is never deleted, and a prune failure is logged and shown but does not fail the backup. With the switch off nothing is pruned.
  6. **Failure warnings (manual and automatic) — never silent** (agent rule 15):
     * **Manual *Back up now*:** on any failure the user sees a dialog straight away that names each destination and its result (“Local: done · GitHub: failed — token expired”). If the app was left while the job ran, a notification says the same.
     * **Automatic:** a failed run posts a notification (one per destination per failure episode, repeated at most every 3 days while it keeps failing) and shows a persistent warning banner on the **Profile** tab and the **Backup** screen until that destination succeeds. Success clears it.
     * **Partial failures count:** if Local succeeds but GitHub fails, the warning still appears; success at one destination never hides failure at another.
     * **Due but not completing:** if a destination has **unbacked-up changes**, has been due for more than 3 days and still has no successful backup (for example it keeps waiting for Wi-Fi, or the app is rarely opened), the same warning appears (“No backup for N days”). An unchanged destination is never overdue.
     * **Specific, actionable reasons:** no network / Wi-Fi unavailable, permission or token revoked or expired, target full or folder missing, Drive/GitHub unreachable or rejected, verification mismatch, encryption key unavailable, snapshot failed, timed out. Each offers the right action (“Reconnect”, “Choose folder”, “Retry now”). A worker cannot show consent screens, so those actions open the app.
     * A failed upload or verification never deletes the previous good backups.
  7. **Secrets stay local:** the GitHub token, provider credentials and the derived encryption key use Keystore-backed storage and are **excluded** from Auto Backup and from the JSON export. Keystore keys do not transfer between phones, so a restored phone just asks to reconnect.
  8. **Restore:** *Restore from backup…* lets the user pick a destination, lists its snapshots, asks for the passphrase if the file is encrypted, and runs it through the existing confirm-then-replace `DataImporter`. A restore never silently merges.
  9. **No Room schema change.** Backup settings live in SharedPreferences or DataStore.
* **Phase 0 — verify and harden Auto Backup (S, do first, no new UI):** replace the template comments in the two XML files with an explicit decision (include `databases/` and the reviewer/budget preference files; keep excluding `wine-images/`, the barcode cache, and — once it exists — the credential store), then test with `adb shell bmgr` on an emulator: back up, uninstall, reinstall, confirm the cellar returns. Record the result in `THINGS_TO_CHECK.md`.
* **Phase 1a (L):** the Backup submenu, `BackupSettings`, scheduler (automatic/manual, “every N days/weeks/months” maximum-interval due-check on app open, Wi-Fi only), retention, failure warnings, status, restore, and the **Local** destination.
* **Phase 1b (M):** encryption (passphrase, header format, key handling, restore prompt). Must land before or with GitHub.
* **Phase 1c (M per destination):** online destinations behind the interface — recommended **GitHub first** (no Google Cloud setup), then Google Drive, then WebDAV/others as wanted.
* **Phase 2 — backup completeness (M):** add the reviewer list and storage budget to `BackupData` as optional fields so older files still import (round-trip tests); image bytes stay out (images can be re-imported from the stored provenance).
* **Decisions (owner, 2026-10-16):**
  1. **Destinations:** the owner chooses which toggles to turn on and their priority order (above). Build order is my call: **GitHub first** (no Google Cloud setup), then Google Drive, then the rest.
  2. **GitHub without encryption:** the owner's choice, not forced. Turning GitHub on without encryption shows a plain warning (“Old versions stay in git history and cannot be fully erased”) that must be accepted, and the app still refuses a public repo. Encryption stays recommended.
  3. **Fresh-install defaults** (owner, 2026-10-16: *backup once a month, to the phone, since no account is connected at that point*): **Automatic, Every 1 month, Local backup ON, no online destination, encryption off, delete old backups on, keep newest 5, Only on Wi-Fi on (it only matters once an online destination is added).** Every option stays changeable. Consequences:
     * **Local needs no setup by default.** A destination that opens a folder picker on first launch would not work as a default, so the default Local location is the public **`Documents/WineManager/`** folder written through `MediaStore` (no permission needed on Android 10+ for files the app creates itself). A **Change folder…** action switches to a Storage-Access-Framework folder the owner picks. *To confirm on a device:* files in `Documents/` survive an uninstall, which is the point (it protects against the “empty database after reinstall” problem), but after a reinstall the app does not own them, so it cannot list or prune them. **Restore from backup…** therefore also offers the system file picker opened at that folder, and pruning only touches files this install created.
     * **It is still not protection.** The summary line stays “Only a local copy exists — it does not protect against losing the phone” with a prompt to add Google Drive or GitHub, until an online destination is confirmed. Android's own Auto Backup (Phase 0) is the only off-device copy before that.
     * The first backup runs the first time the app is opened after install (nothing has ever succeeded, so it is due), then at most once a month when data has changed.
  4. **Alerts:** notification **plus** in-app banner (owner confirmed).
  5. **Extra rows** (*Back up now*, *Restore from backup…*, *Only on Wi-Fi*, the 30-day manual reminder, “No backup for N days” after 3 days overdue, dropping “after changes”): approved; built at my discretion.
* **Out of scope:** image bytes in the backup, CSV/ZIP export, live multi-device sync.
* **Done when:** each toggle sets up, runs unattended in automatic mode (or only on demand in manual mode) and reports its own confirmed status; an encrypted backup restores only with the right passphrase and never silently falls back to plain text; with *keep N* set, exactly the N newest of the app's own backups remain at each destination and nothing else is touched; the summary warns when no online destination is confirmed; a failed manual or automatic backup always produces a visible warning (dialog or notification plus banner), including partial failures, and a deliberately broken destination (revoked grant, expired token, no network for days) warns while the others keep working and old snapshots survive; “Every N days/weeks/months” triggers a backup at the first app open once the last confirmed success is N units old or older (including 2 months + 2 days and 3 months), never earlier, nothing runs while the app is closed except an already-queued upload, a manual backup resets the clock, and unchanged data is not uploaded again (while a deleted last backup, a newly enabled destination, or a changed encryption setting still triggers one); a restore on a clean install after reconnecting reproduces the same counts and the reviewer list; no credentials appear in the JSON export or the Auto Backup rules; JVM tests cover naming, retention, `isDue`/calendar arithmetic, the fingerprint (stable across ordering, changes with data, encryption setting and passphrase), skip-when-unchanged, warning/reminder rules and per-destination status logic; an instrumented test covers snapshot→restore; checklist added.

### 3. W-050 — Drinking window: offline estimates now, real data later
**Complexity:** Phase 1 = M · Phase 2 = M per source · Phase 3 = L · **Status:** Owner wants it built (2026-10-16). **Phase 1 is written in branch `arena/814c691f-…`. The estimator package and its tests compile and pass (15 of 15) under a standalone Kotlin compiler, but the Android/Gradle build and the Wine Detail line have not been run** (no Android SDK in the agent sandbox): package `drinkingwindow/` (estimator + a draft table of 61 rules with source notes, all unreviewed), JVM tests, and a read-only “Est.” line on Wine Detail. Owner decisions are recorded below; the owner still has to review the table. Phases 2 and 3 are unbuilt. Archive this entry when Phase 1 is verified and Phase 2 has shipped or been split into its own entry.
* **Why:** every wine can carry a “drink between year X and year Y” window, but only if the owner types it in. A label never shows one and the owner often does not know it. In the owner's data **none of the 139 wines has a window**, so the Ready Now / Too Early / Past Peak filters and sorts (W-038/W-040/W-042) have nothing to work on.
* **Owner requirements (2026-10-16):** (1) the feature is wanted; (2) the first problem is **how to pull the data in**; (3) the rules must be overhauled **now**, and must aim at **not giving generic suggestions based only on wine type** — wines from the same region or grape differ a lot in ageability, and vintage and location matter too.
* **Withdrawn:** the first proposal (a type-only table: Red +3 to +10, White +1 to +5, …). It labelled a 2016 Spätlese Riesling “past peak” and cannot tell an everyday Côtes du Rhône from a Châteauneuf-du-Pape.
* **Findings from the owner's real cellar (84 wines in stock), which shape the design:**
  1. **Region alone is not enough.** The 20 “Rhone” bottles are three different wines; the words that separate them are in the **wine name**:

| Wine (all region “Rhone”) | Vintage | Price | What it really is |
| --- | --- | --- | --- |
| Kirkland Signature Cotes Du Rhone Villages | 2024 | $7.49 | everyday red, meant to be drunk young (a few years) |
| Kirkland Signature Vacqueyras | 2023 | $11.49 | structured village wine, mid-term |
| Kirkland Signature Chateauneuf-du-Pape | 2023 | $18.99 | classic long-lived appellation (many years) |

  The same happens with Rioja (plain Rioja vs. **Reserva**) and Bordeaux (a village such as Pauillac vs. a generic Bordeaux). So the matcher must read appellation and designation words from the *name* as well as the region fields.
  2. **The location fields are inconsistent.** `country` holds US states (California, Washington, Oregon) and sometimes the wrong one (a wine filed as Washington / Sonoma County / Alexander Valley). Region spellings vary: Rhone / Rhone Valley / Cotes Du Rhone, Napa Valley / Napa valley, Tuscany / Tuscana / Toscana, Williamette, “South Easern”. Matching must be accent-, case- and spelling-tolerant, use alias tables (the scanner's `WineReferenceDictionary` already has aliases and region→grape profiles to reuse) and must not trust a single field.
  3. **Grape is not stored** (no field; adding one is W-052 Phase C). It has to be inferred from the name and region until then.
* **Where a window can come from** (a wine uses the **first** source that has one; begin and end are never mixed across sources — CellarTracker applies the same rule):
  1. **Entered by the owner** (exists today). Always wins, always shown plainly, and the only source the filters use.
  2. **A window pulled in from a named source** (Phase 2): stored separately with the source name and date, shown as “Suggested by <source>”, never written into the entered fields without an explicit “Use this window”. Needs one small new table (Room migration 22, optional field in `BackupData` so older backups still import).
  3. **A computed estimate** from bundled ageing profiles (Phase 1, offline, nothing stored): shown as “Est.” with just the years (owner, 2026-10-16: no grape or appellation text); a type-only estimate adds “(Estimate by type)”.
* **How data could be pulled in** (searched 2026-10-16; vendor pages are marketing, so each needs a real test before it is relied on):

| Option | What it gives | Limits and risks | Verdict |
| --- | --- | --- | --- |
| Label / back-label / shelf-tag text | Phrases like “drink by 2030” or “best within 5 years”, read by the existing on-device OCR | Few labels print one; very cheap (the scanner already reads text) | Do first among the data pulls (S) |
| **Grapeminds API** (`/drinking-periods/{wineId}`) | Per-wine window (year range, young/mature notes) for 311k wines | Needs a signup and API key; **free plan 250 requests/month**, paid from €24.37/month (the pages also mention a 14-day trial, so confirm); content may be generated on first request (“generating” reply); coverage of US/Costco wines unknown; terms for storing results not read; the key must stay out of the repo and would sit in the APK | Worth a **10-wine trial** with the owner's key before building; ~280 calls would cover the whole cellar once |
| **CellarTracker** | Community and critic windows (`BeginConsume`/`EndConsume`) | Needs the owner's CellarTracker login; a third-party guide reports that **free accounts get `9999` (empty) in the export** and that windows are community averages shown live; the web-query endpoint is unofficial; not verified | Only if the owner already has a (paid) account |
| Vintage charts / critic scores | Vintage-quality modifier (strong vintages extend a window, weak ones shorten it) | The **Wine Enthusiast 2026 Vintage Chart** is online but gives scores only (no windows) and is a copyrighted compilation, so it is an owner decision. The owner's old **Resources** sheet held ~24 “top rated vintages” (Wine Spectator / Robert Parker), still readable in git history at `d4ba796` | Phase 3; the small table could seed a bonus for top vintages |
| LLM-generated windows | Plausible-sounding numbers | Cannot be checked; would present invented data as fact (agent rule 15) | Not recommended |
| The owner's own notes | When wines were opened and how they were rated | Needs enough history; could calibrate windows per person | Phase 3 idea |

* **Phase 1 design — offline estimator v2 (pure Kotlin, JVM-tested, in its own package like `aromawheel`):**
  1. Inputs: type, country, region, subregion, **maker and wine name**, vintage. **Price paid is not an input** (owner decision 2026-10-16: what was paid says nothing reliable about age-worthiness).
  2. **Normalise** the text (case, accents, punctuation, spelling aliases, state-vs-country).
  3. **Find the most specific ageing profile**: appellation or named style (Châteauneuf-du-Pape, Pauillac, Brunello di Montalcino, Barolo, Rioja Crianza/Reserva/Gran Reserva, Mosel Riesling by Prädikat, Vouvray, Chablis…) → region plus inferred grape (Napa Cabernet, Mendoza Malbec, Jumilla Monastrell…) → grape or style alone → type alone.
  4. **Adjust** for designation words in the name (Reserva, Riserva, Grand/Premier Cru, Spätlese/Auslese, Old Vine…) (only a few profiles use “Reserva/Riserva”). Non-vintage wines get no estimate.
  5. **Output** `EstimatedWindow(start, end, basis, confidence)`: **High** = an appellation or named style matched, **Medium** = region plus grape, **Low** = type only. The screen shows only the years (“Est. 2029 – 2038”); the matched rule is kept in the log. A type-only match is **still shown**, worded “Est. … (Estimate by type)” (owner decision 2026-10-16), so it is never mistaken for a wine-specific figure.
  6. **Data, not code:** profiles live in a table with an id, a plain-language source note and a **reviewed** flag per entry (the `AromaTaxonomy` pattern), with JVM integrity tests. Every entry carries the web pages it was drafted from; where a source gave only a maximum, the start year is an assumption and the note says so. Every entry starts *unreviewed* until the owner confirms it.  “Reserva/Riserva” extends a few profiles; “Reserve” is ignored because it is a brand word and unregulated in the US. The first entries come from what is actually in the cellar: Rhône (Châteauneuf, Vacqueyras, Gigondas, Côtes du Rhône tiers), Rioja tiers, Bordeaux left and right bank, Chianti/Brunello/Piedmont, Mosel Riesling, Vouvray, Chablis, Napa/Sonoma Cabernet, Russian River Pinot, Lodi Zinfandel, Mendoza Malbec, Jumilla, Australian Shiraz and Chardonnay, Marlborough Sauvignon Blanc, Douro/Dão, Rueda, Vinho Verde.
  7. **Display-only and derived:** never written to the entered fields, never exported, never used by Ready Now / Too Early / Past Peak (agent rule 15), shown on Wine Detail only when the owner's own window is blank. Optional **Use this estimate** in edit mode copies the values into the editable fields; it persists only when saved.
* **Phase 2 — pull data in (M per source):** the new suggestion table, “Use this window”, then the sources the owner picks, in order (label text first; Grapeminds or CellarTracker if wanted). A looked-up result shows its source and date and never overwrites an entered window.
* **Phase 3 — long term (L):** a grape field (W-052 Phase C), vintage-quality modifiers once a licensed source exists, producer-level data, personal calibration from the owner's own consumption and ratings. Region, grape, vintage and location all feed the result; the type-only fallback fades out as data arrives.
* **Decisions made (owner, 2026-10-16):** (1) no pulled-in data source yet, estimator only; (2) type-only wines get a window with the note “Estimate by type”; (3) the assistant drafts the table from sources it finds and the owner reviews it after implementation; (4) the work is ordered after the backup (W-057).
* **Decision needed (owner):** the Wine Enthusiast 2026 Vintage Chart (checked 2026-10-16) gives per-region, per-variety **vintage scores only**. Its “Hold / Ready / Past peak” colour key was not in the readable text, so it yields **no drinking windows**, and it is a copyrighted compilation. A vintage modifier (strong year extends, weak year shortens) is therefore **not in Phase 1**. Whether to use its scores in the app, or a small table of your own vintage ratings (the old Resources sheet had ~24 top vintages, see git `d4ba796`), is a Phase 3 decision for you.
* **Known gaps in the draft table (found by running a Python port of the matcher over the owner's cellar; the Kotlin itself was not run):** Chianti Classico Gran Selezione falls into plain Chianti (no source for the top tier); Georgian, Setúbal, Lisboa and Azores reds, Central Coast blends and Penfolds “Max's Cab” fall back to type; wines with no vintage show nothing; no Rueda or Vinho Verde figure was found, so “fresh whites” uses an analogy marked as an assumption.
* **Done when (Phase 1):** `testDebugUnitTest` passes with the estimator tests (they pass under a standalone runner; Gradle not yet run), including the Rhône, Rioja and Pauillac examples above, misspelled and state-as-country inputs, non-vintage and unknown-wine cases; every profile has a source note; an estimate appears only where no entered window exists and is never saved unless the owner taps Use this estimate and saves; the checklist is added to `THINGS_TO_CHECK.md`.

### 4. W-043 + W-025R — Vintage filter/sort and complete filter controls
**Complexity:** M · **Status:** Open · *Merged because both need the same vintage control.* The “v0.5.4 priority” note in the original text is obsolete.
* **Goal:** Let a collector narrow and order all three list screens by vintage, and make the Cellar Filter & Sort sheet expose every filter its state already supports.
* **Current state (verified in source):**
  * `CellarFilterState` already declares `vintageRange`, `ratingRange` and `makers`; `CellarScreen` applies `vintageRange`, but nothing in the sheet sets any of the three.
  * `RankingsFilterState` has `ratingRange` but no vintage; `TastingNotesFilterState` has neither.
  * Sorts: Cellar has six `CellarSortField` values (arrow buttons, no vintage, no country); Rankings and Notes use plain strings (`"Rating (High)"`, `"Newest"`, price only on Notes).
  * Filtering is in memory on each screen/ViewModel, so no schema change or migration is needed.
* **Design:**
  1. One reusable `VintageRangeFilter` component in `ui/components` (RangeSlider plus min/max text boxes). Slider bounds come from the vintages actually present, not a hard-coded 1900–2024. Non-vintage wines are excluded while a range is set (use the existing `normalizeVintage` aliases) and the control says so.
  2. Add `vintageRange` to the Rankings and Notes filter states and apply it in their existing predicates.
  3. Add a vintage sort (newest/oldest, non-vintage and blank last, matching the “unrated last” rule) to all three lists, and a **Country (A–Z/Z–A)** sort to the Cellar.
  4. Cellar sheet: add the missing **Maker** multi-select (distinct makers of active bottles) and **Rating range** (reuse the Rankings control).
  5. Include the new fields in the “filters active” indicator, **Clear All**, and the Cellar tab-reselect reset (see Changelog, “Cellar filter reset”).
  6. Extract the vintage predicate/comparator into a small pure-Kotlin helper so a JVM test can cover boundaries, NV and blank vintages.
* **Out of scope:** changing how vintage is stored; any new list screens.
* **Done when:** each list filters and sorts by vintage; every control in `CellarFilterState` is reachable from the sheet; Clear All and tab re-select reset them; unit tests for the helper pass; manual checklist added to `THINGS_TO_CHECK.md`.

<details><summary>Original specification — W-043</summary>

* **Goal:** Provide detailed temporal filtering and sorting for serious collectors.
* **UI/Trigger:** a `RangeSlider` for **Vintage** (e.g., 1900 – 2024+) in the filter bottom sheet for Cellar, Tasting Notes, and Rankings, with side-by-side Min/Max text boxes below the slider; “Vintage (Newest)” and “Vintage (Oldest)” sort options across all lists.

</details>

### 5. W-035 — Country flags on wine cards
**Complexity:** S · **Status:** Open (no flag rendering exists)
* **Goal:** A quick visual cue for origin on every wine card.
* **Design:**
  1. `WineCard` is shared by Cellar, Rankings and Notes, so one change in `ui/components/WineCard.kt` covers all three; also show it on Wine Detail. `HistoryWineCard` is optional.
  2. A pure `flagEmoji(country: String): String?` helper (regional-indicator emoji; no image assets) keyed on the country names in `WineRepository.locationData`, matched case-insensitively and trimmed. **`locationData` lists “California” as a country**, so map it (and any other US state added later) to the US flag. Unknown or free-typed countries show no flag, never a wrong one.
  3. The country name stays visible; the flag is decoration, so it must not be the only carrier of meaning (TalkBack reads the country text).
  4. JVM test: every key of `WineRepository.locationData` resolves to a flag, so a newly added country cannot ship without one.
* **Out of scope:** flag images, regional/sub-national flags.
* **Done when:** flags show on all three lists and the detail screen, unknown countries show none, the coverage test passes.

<details><summary>Original specification — W-035</summary>

* **Goal:** Provide a quick visual geographic aid for the wine's origin.
* **UI/Trigger:** Add a small country flag emoji or icon to each wine card.
* **Navigation/Location:** Visible on both the **Cellar screen** and **Rankings screen** list items.

</details>

### 6. W-053 — Filter tasting notes by aroma
**Complexity:** M · **Status:** Open
* **Goal:** Find notes by what the wine smelled like (e.g. every note tagged “cherry”), using the aroma wheel vocabulary users already record.
* **Current state:** aromas are stored as stable IDs in `SensoryData.aromas`, which lives inside each note's JSON `sensoryData` column. SQL cannot filter it, and a migration is unnecessary: filtering is done in memory like every other Notes filter.
* **Design:**
  1. Add `aromas: Set<String>` and a match mode (any / all) to `TastingNotesFilterState`; apply it in the existing Notes predicate.
  2. Picking a family or group selects all its descendant descriptors via `AromaTaxonomy`; unknown or retired IDs are matched by raw ID and shown via `describe()` so old notes never vanish.
  3. UI: an “Aromas” section in the Notes filter sheet. Reuse the wheel dialog's list + search mode with a filter-selection variant, or a simple grouped chip picker; choose when planning (a plan is required by rule 8).
  4. Include in the filters-active indicator and Clear All.
* **Out of scope:** taxonomy localisation/synonyms (stay in the aroma-wheel brief's Future list); CSV export of aromas (declined).
* **Done when:** notes filter by descriptor, group and family in both match modes; helper tests pass; checklist added.

### 7. W-052 — Wine Resources page
**Complexity:** XL overall; Phase A = L · **Status:** Planned (brief only) — `major-feature-planning/wine-resources-page.md` (WBS 5.0)
* **Goal:** A read-only learning area (glossary, grape profiles, regions) that never writes to wines, notes or inventory.
* **Phasing (replaces the original map-first scope):**
  * **Phase 0 — decisions (no code):** content source and licence for every dataset; entry point (**decided 2026-10-16: a row on the Profile tab**, which now hosts Price vs. Rating, reviewers and a Settings submenu); who maintains content; whether to add a grape-variety field to wines.
  * **Phase A — list-first (L):** bundled, versioned local data using the `AromaTaxonomy` pattern (stable IDs, source and review date per entry, JVM integrity tests); searchable glossary, grape index/detail, and region list/detail. Seed regions/grapes from the existing `RegionalGrapeProfile` table in `scanner/WineReferenceDictionary.kt` and the `WineRepository.locationData` hierarchy rather than a second hand-built list. Cross-links to aroma descriptors that never edit a note.
  * **Phase B — interactive map (XL):** only after Phase A ships. Needs a licensed boundary source and a rendering approach (the app has no map dependency; a custom Canvas like the aroma wheel is one option). The list view stays as the accessible equivalent.
  * **Phase C — link to cellar wines (L, optional):** a grape-variety field on `WineDefinition` (Room migration v22, backup/restore and importer updates, per rules 14–16) would also unlock grape-aware W-050 rules. Do not start without an explicit decision.
* **Open decisions (from the brief):** initial region/grape coverage; map and educational-data sources and their licences/offline needs; content review/update ownership.

---

### Decisions closed (2026-10-16, owner)
* **Camera recognition:** closed as **OCR + Open Food Facts barcode, review-before-fill, no paid recognition provider**. The brief (`camera-wine-recognition.md`) now records it. Manual crop/retake only if device testing shows it is needed.
* **Pareto dashboard:** closed as built (average score per dollar, Profile entry point). Revisit after the device check in section 3.
* **Wine Resources entry point:** a destination on the **Profile** tab (W-052).
* **W-057 backup:** decisions recorded inside the W-057 entry.
* **Image importer:** validating the 256 MiB default budget and Open Food Facts preview quality is a device-verification task (section 3), not a feature.
* **Test-fixture photos:** kept.
* **W-050 drinking window:** answered 2026-10-16 (see its entry): no data source is wired in yet; a type-only estimate is shown, labelled “Estimate by type”; the ageing table is drafted from sources the assistant found and the owner reviews it afterwards; it is ordered after W-057.
* **Still open:** whether the Wine Enthusiast vintage-chart scores may be used (licensing, owner decision); which pulled-in source comes first after Phase 1.

---

## 3. Pending verification (fixed / built, not yet confirmed on a device)

`THINGS_TO_CHECK.md` states that Android Studio builds, unit tests and device/emulator checks have **not** been run for the camera OCR, image importer, Manage Offline Images, Pareto and aroma-wheel work. Treat those checklists as the open test plan; they are not copied here.

* [ ] **Build + tests:** `./gradlew :app:assembleDebug`, `:app:testDebugUnitTest`, and (emulator only — it uninstalls the app afterward, wiping a phone's data) `:app:connectedDebugAndroidTest`, including `WineImageImportRepositoryTest`.
* [ ] **W-058 pipeline table / Bug-027 metadata:** native `PipelineFunnelDeviceTest` and screen/scored/raw parity, retries/stop-resume/legacy unknowns in the `arena/259cc0eb-wine-manager-android` checklist. Host logic is tested; Android/Compose/SAF remains unverified. Do not run instrumentation on the owner's cellar installation.
* [ ] **Camera scan on Add Wine** — `THINGS_TO_CHECK.md` → *Camera label scan — Add Wine* (rewritten 2026-10-15 for the Take Label Photo flow; not yet run).
* [ ] **Pareto screen** — *Pareto screen — Price vs. Rating* (deterministic test set; no automated tests exist).
* [ ] **Open Food Facts reviewed image import** and **Manage Offline Images**.
* [ ] **Aroma wheel** — *Aroma wheel — tasting-note editor* (checklist numbering fixed).
* [ ] **Bug-022** aroma launcher wrapping: reorder chips across rows; select 50 and 100; large-font labels.
* [ ] **Bug-023** score thumb: drag 50 → 75 → 100; tap both endpoints.
* [ ] **Bug-024** duplicate aroma editor gone; Common faults follows the sliders.
* [ ] **Bug-026** “Recommended by” label: initial empty, open/dismiss, select, clear.
* [ ] **Bug-005 / Bug-019** (found already fixed in source): reselect Cellar tab resets expanded card/filters; an NV wine shows “(NV)” not “((NV))” on Tasting Notes.
* [ ] Legacy CSV imports (W-033/W-034) and JSON restore on a disposable install.
* [ ] **Android Auto Backup** (already enabled in the manifest, never verified): see W-057 Phase 0.

---

## 4. Cleanup and technical debt

* [ ] **Existing resolver-policy source contracts (2026-10-29).** Full host discovery has failures in `test_exact_geography_fast_path_is_lab_only_and_feeds_context` and `test_span_fuzzy_supplemental_guard_and_final_value_pass_are_separate`. Both reproduce using unmodified `497ad55` source/tests; not changed by device-photo work. Reconcile the tests with the actual resolver contracts in a separately authorized task.

* [ ] **Compile/device-verify device-photo Lab v5 sanity checks (2026-10-29).** Host wiring checks pass; this sandbox has no JDK/Android SDK. Run `:app:assembleDebug`, `:app:testDebugUnitTest` (including `DevicePhotoLabTest`) and the multi-photo checklist for `arena/0a282176-wine-manager-android` before declaring device behavior verified.

* [ ] **Compile and test the 2026-10-15 cleanup.** Dead code was removed without a build (no JDK in the authoring sandbox). Run `:app:assembleDebug`, `:app:testDebugUnitTest` and `:app:connectedDebugAndroidTest`, and fix any leftover reference.
* [ ] **Unused leftovers from that cleanup** (left alone on purpose): legacy `ScannerViewModel` members (`scannedResult`, `onImageCaptured`, `clearScannedResult`), its unused `db` field and unused imports, and the unused `OpenFoodFactsApi` import in `WineViewModel`. The DAO query `getAllWithoutImages` stays because `WineImageImportRepositoryTest` uses it.
* [x] **Test-fixture photos** *(decided 2026-10-16)*: kept as they are. `app/src/test/resources/scanner/labels/` holds 13 JPEGs (~45 MB) of the owner's own wine-label photos for the scanner regression test (see its README).
* [x] **`_backup/`** *(decided 2026-10-16)*: kept. The CSV was deleted; the three JSON backups stay tracked. `WineTracker_Backup_SubRegionFix.json` is the source of the debug seed (`app/src/debug/assets/dev-seed/`). All three files contain one blank placeholder wine (id 1918, empty producer and name, two zero-quantity bottles) that the importer rejects (“a wine without a valid ID, producer, or name”), so the seed has that wine and its two bottles removed. `convert_csv.py` is still there but cannot run without its CSV.
* [ ] **Release readiness (pre-production audit, 2026-10-15):** none of this blocks the cleanup PR. (a) `app/build.gradle.kts` has no release `signingConfig` (keep the keystore outside the repo), `isMinifyEnabled = false`, and `proguard-rules.pro` is the empty template. Turning on R8 needs keep rules first: Gson serialises `BackupData` and the Room entities by reflection, and Retrofit/Gson response models (`OpenFoodFactsProductResponse`) also need them, or backup/restore and the image import break in release. (b) 34 `Log.d/v/i` calls ship in release builds (agent rule 11 wants debug logging); gate them behind `BuildConfig.DEBUG` or strip them with R8. (c) ~~No root `README.md`~~ added 2026-10-16. (d) ~~Tracked `.idea/` files~~ untracked and ignored 2026-10-16.
* [x] **Version** *(done 2026-10-16, 0.5.6 / 56; tag `v0.5.6` pending the merge of this bump)*: after the cleanup PR (#6) was merged to `main`, the next version was cut: bump `app/build.gradle.kts` (versionName/versionCode, currently 0.5.5 / 55) to **0.5.6 / 56** (a patch bump: owner decision 2026-10-16, the change is not significant enough for 0.6.0) and turn `[Unreleased]` in `CHANGELOG.md` into a dated heading. Approved by the owner; waiting on the merge.

## 5. Documentation debt

* [ ] **`AGENTS.md`:** revisit after W-050 is decided.
* [ ] **`CHANGELOG.md`:** compare links assume `v*` Git tags, and the repo has none. Fixed by creating `v*` tags (W-056 / first release); otherwise drop the links.

### Span-safety verification follow-up (2026-10-29)
- [ ] Build Android and run the focused suite plus unseen device-photo sanity checks after the span-provenance/terminal-support fixes. Actual Kotlin host safety checks pass using a local JDK/Kotlin toolchain; this is not an Android build or native OCR run.
- [ ] Reconcile broader host harness debt separately: `run-logic-checks.sh` has unresolved existing source/test references (ScanStats, TextLabStats/TextLabResidualPreview, obsolete architecture test helpers). The spatial suite's check 29 also fails on pre-change `1f55994` after only correcting its two budget-callback argument spellings. The dedicated `--span-safety-only` mode is explicit and does not report those broader suites as passed.
