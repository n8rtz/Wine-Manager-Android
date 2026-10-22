# Testing without Actions artifact storage

## Default policy

All artifact upload steps are now **off by default**. Gradle caches are read-only,
and explicit native-cache saves are also off. Existing caches and artifacts are
not deleted. No billing settings are changed and no paid storage is enabled.

The opt-in repository variables are:

- `WINE_UPLOAD_ACTIONS_ARTIFACTS=true`: permit artifact archives, retained one day.
- `WINE_ENABLE_CI_CACHE_WRITES=true`: permit Gradle/native cache writes.

Leave these unset (or set to `false`) while storage is constrained. Restoring an
existing cache is permitted; a cache miss downloads normal build dependencies
into the disposable runner, not a new persistent Actions cache. Shorter retention
only affects future opted-in uploads; it does not reclaim existing storage.

## Development loop

1. Implement and run `bash tools/run-host-checks.sh` in this workspace. This runs
   Python tooling/regression tests, offline-recognition boundary checks and the
   SQLite import check. It needs neither Actions nor an Android SDK.
2. Reuse the committed frozen OCR and prior predictions for offline scoring and
   candidate analysis. Do not rerun baseline OCR or pretend a Python surrogate
   validates the Kotlin implementation.
3. Batch Android changes, then run the relevant existing Android workflow only
   when compilation/device evidence is needed. Prefer the targeted producer
   validation over the full corpus/scanner workflow for producer-only changes.
4. The producer workflows commit compact scored results to this branch before
   the optional archive step. Their job summaries and always-run diagnostic
   checks expose bounded logs and validation details even on test failure.
   `gh run view RUN_ID --json jobs` distinguishes test failures from publication
   failures. Fetch the approved branch explicitly to retrieve committed results.
5. Preserve reports, scores and necessary small prediction traces—not APKs,
   photographs, SDKs, build directories or entire runner archives—in Git. Other
   workflows that formerly relied only on artifacts retain their console logs;
   they do not automatically gain durable raw-output publication from this change.

The last producer validation (39861083810) already passed Android compilation,
synthetic routing, and 168/168 parity comparisons. It needs **no rerun** just to
obtain a green archive-upload step. Its report is committed in
`tools/evaluation/producer-lab-validation-39861083810/`.

## If compute minutes are exhausted too

Artifact storage and hosted-runner compute are different limits. This policy
removes our upload dependency, not the need for available compute. Host checks,
implementation, frozen-output analysis and report generation can continue here.
Real Android execution would then require an existing Android build/device setup
(e.g. the user's Code on the Go workflow), a separately provided runner, or waiting
for available hosted compute. This workspace currently has no Java/Android SDK,
ADB/emulator or exposed `/dev/kvm`, and its outbound host allowlist does not cover
the normal Google Android SDK repositories. It is **not** an established local
Android replacement. Do not claim device validation for host-only changes.

The migration commit uses `[skip ci]` to avoid launching every historical
experiment whose workflow file changed. No corpus rerun is needed for this
storage-policy change. Host regression tests enforce the opt-in upload/cache
policy; actual hosted execution of the changed workflow remains untested until
its next needed run.
