#!/bin/sh
# One-tap helper (also wired as the Gradle task :switchToMainBranch):
# force-switch to main and reset it to the remote state.
# WARNING: discards local commits and uncommitted tracked changes on main.
# Untracked files (photos, notes, local.properties, build outputs) are left alone.
set -e
cd "$(dirname "$0")/.."
git fetch origin
git checkout -f main
git reset --hard origin/main
git log --oneline -3
