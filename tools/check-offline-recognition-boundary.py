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
SCAN_SESSION = JAVA / "com/n8rtz/winetracker/scanner/ProductionLabelScanSession.kt"

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
    debug_root = ROOT / "app/src/debug/java/com/yourapp/wine"
    gliner_root = ROOT / "gliner-workflow/src"
    granite_root = JAVA / "com/n8rtz/winetracker/debug/graniteworkflow"
    sources = (sorted(LOCAL_ROOT.rglob("*.kt")) + sorted(debug_root.rglob("*.kt")) +
               sorted((gliner_root / "main").rglob("*.kt")) +
               sorted((gliner_root / "appIntegration").rglob("*.kt")) +
               sorted(granite_root.rglob("*.kt")) +
               sorted((JAVA / "com/n8rtz/winetracker/scanner").glob("LabelScan*.kt")) +
               [SCAN_SESSION, JAVA / "com/n8rtz/winetracker/scanner/PendingLabelScanStore.kt", FACADE])
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

    # The ViewModel contains lazy Retrofit for unrelated image features. Guard the scan-only
    # delegation section as well as the entire independently-owned session above.
    vm = code_only(VIEW_MODEL.read_text())
    if "val labelScanState" not in vm or "fun clearScannedResult" not in vm:
        raise AssertionError("Scanner delegation section disappeared")
    scan_vm = vm.split("val labelScanState", 1)[1].split("fun clearScannedResult", 1)[0]
    for name in ("requestLabelScan", "onLabelPhotoCaptured", "onLabelPhotoSelected", "warmLabelScanner"):
        if not re.search(r"\bfun\s+" + name + r"\s*\(", scan_vm):
            raise AssertionError(f"Recognition entry point disappeared: {name}")
    if "ProductionLabelScanSession" not in scan_vm or FORBIDDEN_CLIENT.search(scan_vm) or re.search(r"\b(?:api|retrofit|batchProcessor|startImageImport)\b", scan_vm):
        errors.append(f"{VIEW_MODEL.relative_to(ROOT)}: invalid or network-dependent scan delegation")
    session = code_only(SCAN_SESSION.read_text())
    for name in ("requestCapture", "captured", "selected", "launchPhotoScan", "recover"):
        body(session, name)
    if "ProductionLabelScanner.scan" not in session:
        errors.append(f"{SCAN_SESSION.relative_to(ROOT)}: production scan routing disappeared")

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
    print(f"PASS: {count} local recognition sources and camera/picker background scan entry points have no project wine-network client dependency")
    print("Normal Android/Google dependencies and explicit image-import networking remain allowed.")
