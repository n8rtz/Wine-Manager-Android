#!/bin/sh
# One-tap helper (also wired as the Gradle task :switchToArenaBranch):
# force-switch to the active Arena session branch and reset it to the remote state.
# WARNING: discards local commits and uncommitted tracked changes on this branch.
# Untracked files (photos, notes, local.properties, build outputs) are left alone.
set -e
cd "$(dirname "$0")/.."
git fetch origin
git checkout -f arena/a89d1ab7-wine-manager-android
git reset --hard origin/arena/a89d1ab7-wine-manager-android
git log --oneline -3
