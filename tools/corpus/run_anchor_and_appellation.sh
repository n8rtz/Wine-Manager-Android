#!/usr/bin/env bash
# One emulator boot/build, two distinct new selector batches; never re-execute old components.
set -euo pipefail
APPELLATION_ITERATION=true REPLAY_ROOT="$RUNNER_TEMP/replay/appellation" bash tools/corpus/run_saved_evidence_replay.sh
ANCHOR_ITERATION=true REPLAY_ROOT="$RUNNER_TEMP/replay/anchor" bash tools/corpus/run_saved_evidence_replay.sh
