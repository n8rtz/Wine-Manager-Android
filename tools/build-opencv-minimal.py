#!/usr/bin/env python3
"""Build genuine OpenCV 4.12.0 core/imgproc/Java for arm64. No binary assets are manufactured."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request

from android_native_toolchain import ToolchainSetupError, ensure_native_toolchain

VERSION = "4.12.0"
SHA256 = "44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d"
URL = f"https://codeload.github.com/opencv/opencv/tar.gz/refs/tags/{VERSION}"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sdk", type=Path, required=True)
    p.add_argument("--ndk", type=Path, help="Optional explicit path to the pinned NDK")
    p.add_argument("--build", type=Path)
    p.add_argument("--prepare-only", action="store_true", help="Check/install native build tools without building OpenCV")
    p.add_argument("--install-missing", action="store_true", help="Install missing desktop pins via the real SDK manager; licenses must be accepted")
    args = p.parse_args()
    if sys.version_info < (3, 9):
        p.exit(2, "Python >=3.9 is required.\n")
    if not args.prepare_only and args.build is None:
        p.error("--build is required unless --prepare-only is supplied")
    try:
        tools = ensure_native_toolchain(args.sdk, args.ndk, install_missing=args.install_missing)
    except ToolchainSetupError as error:
        p.exit(2, f"{error}\n")
    if args.prepare_only:
        print(f"Android native toolchain ready: NDK={tools.ndk}; CMake={tools.cmake}")
        return
    sdk, ndk, build = tools.sdk, tools.ndk, args.build.resolve()
    cmake, ninja = tools.cmake, tools.ninja
    toolchain = ndk / "build/cmake/android.toolchain.cmake"
    vendor = build / "vendor"
    vendor.mkdir(parents=True, exist_ok=True)
    archive = vendor / f"opencv-{VERSION}.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(URL, timeout=120) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        sys.exit("OpenCV source SHA-256 mismatch; remove the cached archive and retry.")
    source = vendor / f"opencv-{VERSION}"
    if not source.exists():
        with tarfile.open(archive) as tar:
            for member in tar.getmembers():
                target = (vendor / member.name).resolve()
                if not target.is_relative_to(vendor) or member.issym() or member.islnk():
                    sys.exit("Unsafe path/link in upstream OpenCV archive")
            tar.extractall(vendor)
    native = build / "native/arm64-v8a"
    options = {
        "CMAKE_TOOLCHAIN_FILE": str(toolchain), "CMAKE_MAKE_PROGRAM": str(ninja),
        "CMAKE_BUILD_TYPE": "Release", "ANDROID_ABI": "arm64-v8a", "ANDROID_PLATFORM": "android-26",
        "ANDROID_STL": "c++_static", "ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES": "ON",
        "CMAKE_SHARED_LINKER_FLAGS": "-Wl,-z,max-page-size=16384",
        "ANDROID_SDK": str(sdk), "ANDROID_PROJECTS_BUILD_TYPE": "GRADLE",
        "BUILD_LIST": "core,imgproc,java", "BUILD_SHARED_LIBS": "OFF",
        "BUILD_JAVA": "ON", "BUILD_FAT_JAVA_LIB": "ON", "BUILD_ANDROID_PROJECTS": "OFF",
        "BUILD_ANDROID_EXAMPLES": "OFF", "BUILD_TESTS": "OFF", "BUILD_PERF_TESTS": "OFF",
        "BUILD_EXAMPLES": "OFF", "BUILD_DOCS": "OFF", "BUILD_opencv_apps": "OFF",
        "BUILD_KOTLIN_EXTENSIONS": "OFF", "BUILD_opencv_python2": "OFF", "BUILD_opencv_python3": "OFF",
        "WITH_OPENCL": "OFF", "WITH_IPP": "OFF", "WITH_ITT": "OFF", "WITH_TBB": "OFF",
        "WITH_PROTOBUF": "OFF", "WITH_FFMPEG": "OFF", "WITH_GSTREAMER": "OFF",
        "WITH_JPEG": "OFF", "WITH_PNG": "OFF", "WITH_TIFF": "OFF", "WITH_WEBP": "OFF",
        "PYTHON_DEFAULT_EXECUTABLE": sys.executable,
    }
    subprocess.run([str(cmake), "-S", str(source), "-B", str(native), "-G", "Ninja"] +
                   [f"-D{k}={v}" for k, v in options.items()], check=True)
    subprocess.run([str(cmake), "--build", str(native), "--target", "opencv_java", "--parallel",
                    str(min(os.cpu_count() or 2, 4))], check=True)
    generated = build / "generated/opencv"
    java = generated / "java/org/opencv"
    if java.exists():
        shutil.rmtree(java)
    java.mkdir(parents=True)
    upstream = native / "modules/java_bindings_generator/gen/java/org/opencv"
    for package in ("core", "imgproc", "utils"):
        shutil.copytree(upstream / package, java / package, ignore=shutil.ignore_patterns("*.kt"))
    # Upstream Utils' resource loader depends on imgcodecs. Keep ONLY the unchanged bitmap JNI methods.
    utils = (source / "modules/java/generator/android/java/org/opencv/android/Utils.java").read_text(encoding="utf-8")
    methods = utils[utils.index("    /**\n     * Converts Android Bitmap"):]
    android = java / "android"
    android.mkdir()
    (android / "Utils.java").write_text(
        "// OpenCV upstream bitmap-only excerpt; Apache-2.0 (see META-INF/licenses/opencv-LICENSE.txt).\n"
        "package org.opencv.android;\nimport android.graphics.Bitmap;\nimport org.opencv.core.Mat;\n"
        "public class Utils {\n" + methods, encoding="utf-8")
    jni = generated / "jniLibs/arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    # Newer NDK toolchains do not always expose the legacy ANDROID_NDK_ABI_NAME.
    libraries = list((native / "jni").rglob("libopencv_java4.so"))
    if len(libraries) != 1:
        sys.exit(f"Expected exactly one real arm64 OpenCV JNI output, found {libraries}")
    shutil.copy2(libraries[0], jni / "libopencv_java4.so")
    license_dir = generated / "resources/META-INF/licenses"
    license_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / "LICENSE", license_dir / "opencv-LICENSE.txt")
    print("Built real OpenCV core + imgproc + Java JNI; no dnn/imgcodecs/video/calib3d/world modules.")


if __name__ == "__main__":
    main()
