# Development Pipeline

How work moves from idea to release in this repo. Written 2026-10-15 for a one-owner project built with AI-agent help. Open work and its priority order live in [`BACKLOG.md`](BACKLOG.md); the rules agents must follow while coding live in [`../.agents/instructions.md`](../.agents/instructions.md). This file is the process that connects them.

## 1. Principles
1. **One list.** Every wish, bug and cleanup item goes into `BACKLOG.md`; finished items move to `ARCHIVE.md` and get one `CHANGELOG.md` line.
2. **Decide before building.** An item with open decisions stays `Needs decision` until the owner answers; nobody codes around it (agent rules 1, 6, 8).
3. **Small, verifiable steps.** Pure-Kotlin logic first with JVM tests (the pattern used by `aromawheel/` and `scanner/`), UI second, so most behaviour is testable without a device (rules 4, 10).
4. **Honest status.** An item is *done* only after it compiles, its tests pass and its manual checklist has been run on a device. Until then it is `Fixed – awaiting device check` (rule 9).
5. **Data safety beats speed.** Anything touching Room, backup/restore or importers follows `ARCH.md` §5–6, `LESSONS_LEARNED.md` and rules 14–16.
6. **Declined stays declined.** Ideas rejected by the owner are in `ARCHIVE.md` Part D and are not re-proposed.

## 2. Work-item lifecycle

| Stage | What happens | Gate to move on |
| --- | --- | --- |
| **1. Intake** | Add a `W-nnn` (or `Bug-nnn`) entry to `BACKLOG.md` with Goal, Trigger/Location, Current state, Complexity, Status. | Owner agrees it belongs and where it ranks. |
| **2. Decide** | List open decisions with a recommended default. For **L/XL** items, write a brief in `major-feature-planning/` first. | Decisions answered; status is `Open`. |
| **3. Plan** | Agent posts a short plan: files touched, schema impact, tests, risks (rule 8). Reads `ARCH.md` and, for data work, §5 and `LESSONS_LEARNED.md` (rule 16). | Owner OKs the plan for M and above. |
| **4. Build** | Work on a branch. Pure logic + JVM tests first, then UI. Keep every commit compilable (rule 10). Update the `ARCH.md` map if files change (rule 12). | `:app:assembleDebug` and `:app:testDebugUnitTest` pass. |
| **5. Verify** | Run the matching checks in your branch's section of `THINGS_TO_CHECK.md` (grouped by branch) on an emulator/device (disposable install for anything that writes data). Record the date and result on its **Last run** line. | Checklist passes; failures become `Bug-nnn` entries. |
| **6. Merge** | Open a PR to `main`; CI (W-056) must be green. Prefer one PR per backlog item (or two tightly coupled ones). Merge. | PR merged. |
| **7. Record** | Move the entry to `ARCHIVE.md`, add the `CHANGELOG.md` line under `[Unreleased]`, update `ARCH.md`, `THINGS_TO_CHECK.md`. | Docs match the code. |
| **8. Release** | Cut a version when a release-plan milestone (section 6) is reached (section 5). | Tag exists. |

**Bugs** skip stages 2–3 unless the fix needs a design choice or touches data. Fix a logged bug only when asked to “fix Bug-nnn” (BACKLOG rule 3).

## 3. Sizing

| Size | Meaning | Extra requirements |
| --- | --- | --- |
| **S** | One component or screen, no schema change. | Unit test if there is logic; checklist lines. |
| **M** | Several files across UI + ViewModel, new pure-Kotlin logic, no schema change. | Written plan; JVM tests for the logic; checklist section. |
| **L** | Room migration or a new subsystem. | Brief in `major-feature-planning/`; migration + `MIGRATION_x_y` test; backup/restore and importer impact reviewed; `connectedDebugAndroidTest` run (on an emulator). |
| **XL** | Multi-release program, external content or licensing. | Split into phases that each ship alone; licences checked before any data is bundled. |

**WIP limit:** at most one L/XL item in flight; S/M items may run alongside it.

## 4. Quality gates

| Gate | Command / action | When |
| --- | --- | --- |
| Compile | `./gradlew :app:assembleDebug` | Every change; CI on every PR |
| JVM tests | `./gradlew :app:testDebugUnitTest` | Every change; CI on every PR |
| Instrumented tests | `./gradlew :app:connectedDebugAndroidTest` — emulator only (Gradle uninstalls the app afterward, which wipes its data on a phone) | Any change under `data/`, migrations, backup/restore, importers |
| Manual checklist | Matching section of `THINGS_TO_CHECK.md` | Before an item is called done |
| Schema change | Bump Room version, add the migration and its test, update `ARCH.md` and the changelog schema note | Any entity change |
| Backup round-trip | Export JSON, restore on a disposable install, compare counts (and, once W-057 ships, check an off-device snapshot restores too) | Any change to entities, `BackupData` or backup settings |
| W-061 structured notes | `TastingNoteStructureTest`, host `test_w061_structured_notes.py`, the W-061 `THINGS_TO_CHECK.md` checklist and a Room v21→v22 migration check | Any change to `SensoryData`, phase aromas, the color picker or Room v22 |

Until CI exists (W-056) the owner runs the first two gates locally in Android Studio. Agent sandboxes may have no JDK or Android SDK; when a change could not be compiled, say so in the hand-off and log it in `BACKLOG.md` §4.

## 5. Releases (Semantic Versioning, 0.x)
* **MINOR** (0.5.6 → 0.6.0): a shipped feature batch or any schema migration. **PATCH** (0.5.6 → 0.5.7): bug fixes and verification-driven corrections only.
* **`1.0.0`** when every `THINGS_TO_CHECK.md` section has a passing **Last run** and there are no open bugs.
* **Steps to cut a release** (after the PR is merged to `main`):
  1. Bump `versionName` and `versionCode` (+1) in `app/build.gradle.kts`.
  2. Turn `[Unreleased]` in `CHANGELOG.md` into `## [x.y.z] - YYYY-MM-DD` and leave a fresh empty `[Unreleased]` above it. Add the compare link at the bottom.
  3. Commit (`Release x.y.z`), tag `vx.y.z`, push the tag, and publish a GitHub release using that changelog section as the notes.
  4. Confirm **App info** shows the new version and the correct schema version.

## 6. Release plan

| Release | Contents | Trigger |
| --- | --- | --- |
| **0.5.6** | Everything currently under `[Unreleased]` plus the 2026-10-15 cleanup (dead code removed, docs reorganised) and W-050 Phase 1 (offline drinking-window estimate on Wine Detail). | Cut right after the cleanup PR merges (owner-approved). |
| **0.5.x** | W-056 CI and tag history, W-057 Phase 0 (verify Auto Backup), and fixes found while running the pending device checklists (`BACKLOG.md` §3). | As needed. |
| **0.6.0** | W-057 Phase 1a–1c (Settings → Backup: destination toggles, auto/manual and “every N days/weeks/months”, failure warnings, encryption, keep-newest-N, Local + first online destination, status, restore) and Phase 2 (settings in the backup file); further destinations as 0.6.x. | After 0.5.x checklists are green and the W-057 decisions are answered. |
| **0.7.0** | W-050 Phase 2 (pulled-in drinking-window data, source chosen by the owner), W-043 + W-025R (vintage and complete filters), W-035 (flags). | After 0.6.0. |
| **0.8.0** | W-053 (aroma filter). | After 0.7.0. |
| **0.9.0+** | W-052 Resources page, Phase A, then Phase B/C only if still wanted. | After 0.8.0; needs Phase 0 decisions. |
| **1.0.0** | Verification complete, no open bugs. | When the 1.0 criteria in section 5 are met. |

The plan is a default order, not a promise: re-rank in `BACKLOG.md` whenever priorities change, and keep this table in sync.

## 7. Definition of Done
- [ ] Compiles; `testDebugUnitTest` passes (and instrumented tests when data code changed).
- [ ] Manual checklist added or updated and run on a device; **Last run** line updated.
- [ ] No silent fallbacks or invented data (rule 15); schema/backup impact reviewed when relevant.
- [ ] `ARCH.md` map, `CHANGELOG.md`, `ARCHIVE.md` and `BACKLOG.md` updated.
- [ ] Nothing declined in `ARCHIVE.md` Part D was reintroduced.
