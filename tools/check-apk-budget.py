#!/usr/bin/env python3
"""Check measured release APK code/native size and ABI; never claim an unbuilt APK meets budget."""
import sys
import os
from pathlib import Path
from zipfile import ZipFile

apk = Path(sys.argv[1])
with ZipFile(apk) as z:
    libs = [i for i in z.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
    assert libs, "No native libraries in APK"
    assert all(i.filename.startswith("lib/arm64-v8a/") for i in libs), "Non-arm64 ABI found"
    entries = [i for i in z.infolist() if i in libs or (i.filename.startswith("classes") and i.filename.endswith(".dex"))]
    packed = sum(i.compress_size for i in entries)
    unpacked = sum(i.file_size for i in entries)
    print(f"APK: {apk.stat().st_size / 1024**2:.2f} MiB; code+libs packed={packed / 1024**2:.2f} MiB, installed={unpacked / 1024**2:.2f} MiB")
    assert packed < 40 * 1024**2, "Code + libs exceed 40 MiB APK target"
    assert apk.stat().st_size < 40 * 1024**2, "Total APK exceeds 40 MiB target; inspect APK Analyzer"
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::notice title=Measured arm64 release APK::APK={apk.stat().st_size / 1024**2:.2f} MiB; code+libs packed={packed / 1024**2:.2f} MiB; installed={unpacked / 1024**2:.2f} MiB; arm64 only")
