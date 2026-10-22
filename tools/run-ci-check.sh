#!/usr/bin/env bash
# Run a real command without masking its exit code; surface a bounded failure tail in GitHub check annotations.
set -euo pipefail
if [[ $# -lt 2 ]]; then
    echo "Usage: run-ci-check.sh 'Check title' command [args...]" >&2
    exit 64
fi
title="$1"
shift
log="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/wine-ci-check.XXXXXX")"
trap 'rm -f "$log"' EXIT
if "$@" 2>&1 | tee "$log"; then
    exit 0
else
    status=$?
    if [[ "${GITHUB_ACTIONS:-false}" == true ]]; then
        python3 - "$title" "$log" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

def escape(value):
    return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')

title = escape(sys.argv[1]).replace(':', '%3A').replace(',', '%2C')
lines = Path(sys.argv[2]).read_text(errors='replace').splitlines()
context = []
for index, line in enumerate(lines):
    if line.startswith(('* What went wrong', '* Where:', 'Script compilation error')):
        context.extend(lines[index:index + 25])
diagnostics = []
for index, line in enumerate(lines):
    if re.search(r'^e:| error:|^Caused by:|^A problem|^Could not', line):
        diagnostics.extend(lines[index:index + 3])
test_failures = []
if 'Robolectric' in sys.argv[1]:
    for report in sorted(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
        try:
            for case in ET.parse(report).getroot().iter('testcase'):
                failure = case.find('failure')
                if failure is None:
                    failure = case.find('error')
                if failure is not None and len(test_failures) < 4:
                    test_failures.append(f"{case.get('classname')}.{case.get('name')}: " +
                                         (failure.text or failure.get('message', ''))[:900])
        except (OSError, ET.ParseError):
            pass
tail = '\n'.join(test_failures + context[:60] + diagnostics[:25] + lines[-12:])[:6000]
print(f'::error title={title}::{escape(tail or "Command failed without output; inspect the CI step")}', flush=True)
PY
    fi
    exit "$status"
fi
