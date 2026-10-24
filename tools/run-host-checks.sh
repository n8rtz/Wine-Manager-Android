#!/usr/bin/env bash
# No Actions, Android SDK, downloads, OCR, or baseline execution.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
python3 -m unittest discover -s tools/tests -p 'test_*.py'
python3 tools/check-offline-recognition-boundary.py
python3 tools/check-kb-import-sql.py
git diff --check
printf '\nHost checks passed. These do NOT compile Kotlin or validate Android/MLKit/native execution.\n'
