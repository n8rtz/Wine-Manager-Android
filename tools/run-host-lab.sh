#!/usr/bin/env bash
# One host Lab iteration: prepare cached OCR -> replay real Kotlin pipeline ->
# score against frozen references. Usage:
#   bash tools/run-host-lab.sh [modules-signature-key] [output-tag]
# Defaults reproduce the phone baseline module set from the a709ad6 diagnostic.
# Set BASELINE_OUTPUT to a previous output.tsv for a per-label gains/losses diff.
set -euo pipefail
cd "$(dirname "$0")/.."
modules=${1:-blank_subregion_fuzzy+vintage_recovery+final_producer_near_match+producer_role_particle+spatial_semantic_guards}
tag=${2:-baseline}
out=tools/host-lab/out/$tag
mkdir -p "$out"
python3 tools/host-lab/host_lab.py prepare --output "$out/input.tsv"
bash tools/run-host-lab-replay.sh "$out/input.tsv" "$out/output.tsv" "$modules"
if [[ -n "${BASELINE_OUTPUT:-}" ]]; then
    python3 tools/host-lab/host_lab.py score "$out/output.tsv" --report "$out/report.md" --baseline-output "$BASELINE_OUTPUT"
else
    python3 tools/host-lab/host_lab.py score "$out/output.tsv" --report "$out/report.md"
fi
