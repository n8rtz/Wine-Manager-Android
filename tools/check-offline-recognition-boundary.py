#!/usr/bin/env python3
"""Source regression guard for project-owned recognition code, not a claim about SDK telemetry.

Normal Android/Google APIs are allowed. Only wine lookup / general HTTP clients must stay
outside the recognition path; the explicit image-import feature may still use networking.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java"
LOCAL_ROOT = JAVA / "com/yourapp/wine"
FACADE = JAVA / "com/n8rtz/winetracker/scanner/ScannerManager.kt"
VIEW_MODEL = JAVA / "com/n8rtz/winetracker/viewmodels/ScannerViewModel.kt"

# These are not a ban on com.google.*, Play Services, CameraX or ordinary Android APIs.
FORBIDDEN_IMPORT = re.compile(
    r"^(?:retrofit2|okhttp3|java\.net|javax\.net|io\.ktor\.client|"
    r"com\.android\.volley|com\.n8rtz\.winetracker\.(?:network|image))(?:\.|$)"
)
FORBIDDEN_CLIENT = re.compile(
    r"\b(?:OpenFoodFactsApi|WineImageBatchProcessor|Retrofit|OkHttpClient|"
    r"HttpURLConnection|HttpsURLConnection|LabelIdentityResolver|BarcodeIdentityCache)\b"
)


def code_only(source):
    """Mask comments/strings so a warning or URL in documentation cannot count as a call."""
    pattern = re.compile(r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
    return pattern.sub(lambda match: "\n" * match.group(0).count("\n") + " ", source)


def body(code, name):
    start = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", code)
    if start is None:
        raise AssertionError(f"Recognition entry point disappeared: {name}; update the explicit guard")
    opening = code.index("{", start.end())
    depth = 1
    for index in range(opening + 1, len(code)):
        depth += (code[index] == "{") - (code[index] == "}")
        if depth == 0:
            return code[opening + 1:index]
    raise AssertionError(f"Unbalanced method {name}")


def violations():
    errors = []
    sources = sorted(LOCAL_ROOT.rglob("*.kt")) + [FACADE]
    if len(sources) < 10:
        raise AssertionError("Missing recognition source tree")
    for path in sources:
        text = code_only(path.read_text())
        imports = re.findall(r"^\s*import\s+([\w.*]+)", text, re.M)
        for imported in imports:
            if FORBIDDEN_IMPORT.match(imported):
                errors.append(f"{path.relative_to(ROOT)}: network import {imported}")
        if FORBIDDEN_CLIENT.search(text):
            errors.append(f"{path.relative_to(ROOT)}: wine/HTTP client referenced by recognition code")

    # The ViewModel legitimately contains lazy Retrofit for startImageImport(), but scans must not use it.
    vm = code_only(VIEW_MODEL.read_text())
    for name in ("requestLabelScan", "onLabelPhotoCaptured", "warmLabelScanner", "finishScan", "onImageCaptured"):
        entry = body(vm, name)
        if FORBIDDEN_CLIENT.search(entry) or re.search(r"\b(?:api|retrofit|batchProcessor|startImageImport)\b", entry):
            errors.append(f"{VIEW_MODEL.relative_to(ROOT)}: network dependency inside {name}()")

    # Keep the bundled Latin coordinate, not the on-demand model-download coordinate, for offline first use.
    catalog = (ROOT / "gradle/libs.versions.toml").read_text()
    if not re.search(r'mlkit-text-recognition\s*=\s*\{\s*group\s*=\s*"com.google.mlkit",\s*name\s*=\s*"text-recognition"', catalog):
        errors.append("Expected the bundled ML Kit Latin dependency; an on-demand replacement needs offline setup validation")
    return errors, len(sources)


if __name__ == "__main__":
    errors, count = violations()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        raise SystemExit(1)
    print(f"PASS: {count} local recognition sources and 5 scan entry points have no project wine-network client dependency")
    print("Normal Android/Google dependencies and explicit image-import networking remain allowed.")
