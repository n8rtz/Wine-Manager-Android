"""Resolve/install the real pinned Android native toolchain; never substitute native binaries."""
from dataclasses import dataclass
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Optional

NDK_VERSION = "28.2.13676358"
CMAKE_VERSION = "3.22.1"
WINDOWS = os.name == "nt"
PACKAGES = (f"ndk;{NDK_VERSION}", f"cmake;{CMAKE_VERSION}")


class ToolchainSetupError(RuntimeError):
    """A missing/invalid native toolchain with local, actionable installation instructions."""


def _android_host() -> bool:
    """Detect an Android build host, not an Android target ABI or a desktop Android SDK."""
    return (sys.platform == "android" or hasattr(sys, "getandroidapilevel") or
            (not WINDOWS and os.environ.get("ANDROID_ROOT") == "/system" and
             os.environ.get("ANDROID_DATA") == "/data"))


def _android_host_instructions(sdk: Path) -> str:
    return ("Android-hosted build detected.\n"
            f"Selected SDK: {sdk}\n"
            "Desktop SDK auto-install is disabled on Android: desktop NDK/CMake executables "
            "are not Android-host binaries.\n"
            f"This build requires NDK {NDK_VERSION} and CMake {CMAKE_VERSION}.\n"
            "Use a supported desktop build host or CI with the project's pinned tools. "
            "Native source compilation on an Android phone has not been validated.\n"
            "Do not rename SDK directories or edit source.properties to impersonate another revision.")


@dataclass(frozen=True)
class NativeToolchain:
    sdk: Path
    ndk: Path
    cmake: Path
    ninja: Path


def _revision(directory: Path) -> str:
    try:
        match = re.search(r"^\s*Pkg\.Revision\s*=\s*(\S+)\s*$",
                          (directory / "source.properties").read_text(encoding="utf-8"), re.MULTILINE)
        return match.group(1) if match else "unknown"
    except OSError:
        return "missing"


def _problems(toolchain: NativeToolchain) -> dict:
    problems = {}
    ndk_revision = _revision(toolchain.ndk)
    if ndk_revision != NDK_VERSION or not (toolchain.ndk / "build/cmake/android.toolchain.cmake").is_file():
        problems[PACKAGES[0]] = f"NDK {NDK_VERSION} is missing/incomplete at {toolchain.ndk} (revision: {ndk_revision})"
    cmake_directory = toolchain.cmake.parent.parent
    if (_revision(cmake_directory) != CMAKE_VERSION or not toolchain.cmake.is_file()
            or not toolchain.ninja.is_file()):
        problems[PACKAGES[1]] = f"SDK CMake {CMAKE_VERSION}/Ninja is missing/incomplete at {cmake_directory}"
    return problems


def _sdkmanager(sdk: Path) -> Optional[Path]:
    suffix = ".bat" if WINDOWS else ""
    command_line_tools = sdk / "cmdline-tools"
    latest = command_line_tools / "latest/bin" / f"sdkmanager{suffix}"
    if latest.is_file():
        return latest
    # Android Studio may install a numbered package instead of the 'latest' alias.
    candidates = list(command_line_tools.glob(f"*/bin/sdkmanager{suffix}"))
    def version(path):
        return tuple(int(part) for part in re.findall(r"\d+", path.parent.parent.name))
    return max(candidates, key=version) if candidates else None



def _android_cli(sdk: Path) -> Optional[Path]:
    """Find the current CLI beside SDK launchers, never the retired tools/android UI launcher."""
    root = sdk / "cmdline-tools"
    bins = [root / "latest/bin", root / "bin", root / "latest", root]
    numbered = [path for path in root.glob("*/bin") if path.parent.name != "latest"]
    numbered.sort(key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)), reverse=True)
    bins.extend(numbered)
    names = ("android.exe", "android.bat", "android.cmd", "android") if WINDOWS else ("android",)
    for directory in bins:
        for name in names:
            launcher = directory / name
            if launcher.is_file():
                return launcher
    return None


def _run_sdk_command(command: list) -> None:
    """Run native argv directly; quote every argument when a Windows batch launcher needs cmd.exe."""
    if WINDOWS and Path(command[0]).suffix.lower() in (".bat", ".cmd"):
        # A shell cannot safely preserve arbitrary expansions through a batch file. Refuse those
        # exceptional paths instead of running a different command/SDK; normal spaces and '&' work.
        if any(any(char in arg for char in ('"', "%", "!", "\r", "\n")) for arg in command):
            raise ToolchainSetupError("Cannot safely launch an Android CLI batch file with this path. Use Android Studio SDK Manager.")
        interpreter = os.environ.get("COMSPEC", "cmd.exe")
        quoted = " ".join(f'"{arg}"' for arg in command)
        line = f'"{interpreter}" /d /v:off /s /c "{quoted}"'
        subprocess.run(line, executable=interpreter, check=True, stdin=subprocess.DEVNULL)
    else:
        subprocess.run(command, check=True, stdin=subprocess.DEVNULL)


def _manager_command(manager: Path) -> list:
    if not WINDOWS:
        return [str(manager)]
    # Use the installed sdkmanager's genuine Java entry point on Windows. A .bat via cmd.exe
    # re-parses paths containing spaces, '&' or '%'; a Java argv preserves SDK paths literally.
    tools = manager.parent.parent
    classpath = tools / "lib/sdkmanager-classpath.jar"
    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home) / "bin/java.exe" if java_home else None
    executable = str(java) if java and java.is_file() else shutil.which("java")
    if not classpath.is_file() or not executable:
        raise ToolchainSetupError("SDK Command-line Tools and a full JDK are required to run sdkmanager on Windows.")
    return [executable, f"-Dcom.android.sdklib.toolsdir={tools}", "-classpath", str(classpath),
            "com.android.sdklib.tool.sdkmanager.SdkManagerCli"]


def _instructions(sdk: Path, manager: Optional[Path], android_cli: Optional[Path] = None) -> str:
    header = (f"Android SDK root: {sdk}\n"
              "Android Studio: Tools > SDK Manager > SDK Tools > Show Package Details.\n"
              f"Install NDK (Side by side) {NDK_VERSION} and CMake {CMAKE_VERSION}.\n"
              "Review/accept the licenses in SDK Manager; then rebuild.\n")
    if android_cli:
        # Modern package paths use '/', not the legacy SDK manager's ';'. No generic ndk@version:
        # the SDK package path itself includes the NDK version, preserving other side-by-side NDKs.
        call = "& " if WINDOWS else ""
        packages = " ".join(f'"{package.replace(";", "/")}"' for package in PACKAGES)
        return header + (f'Android CLI (PowerShell on Windows):\n'
                         f'  {call}"{android_cli}" "--sdk={sdk}" --no-metrics sdk install {packages}')
    executable = str(manager) if manager else ("sdkmanager.bat" if WINDOWS else "sdkmanager")
    prefix = f'"{executable}" "--sdk_root={sdk}"'
    return header + ("Install Android SDK Command-line Tools (latest) to enable automatic setup.\n"
                     "For older Java-based SDK command-line tools only, accept licenses interactively:\n"
                     f"  {prefix} --licenses\n"
                     f'  {prefix} "{PACKAGES[0]}" "{PACKAGES[1]}"\n'
                     "If sdkmanager redirects to Android CLI, use android --sdk=<SDK> sdk install "
                     f'"ndk/{NDK_VERSION}" "cmake/{CMAKE_VERSION}" instead.')


def ensure_native_toolchain(sdk: Path, ndk: Optional[Path] = None,
                            install_missing: bool = False) -> NativeToolchain:
    """Resolve real SDK paths, optionally install missing pins, then verify installation; no image coordinates."""
    sdk = sdk.expanduser().resolve()
    if not sdk.is_dir():
        raise ToolchainSetupError(f"Android SDK root does not exist: {sdk}. Set sdk.dir in local.properties or ANDROID_HOME.")
    pinned_ndk = sdk / "ndk" / NDK_VERSION
    suffix = ".exe" if WINDOWS else ""
    cmake_bin = sdk / "cmake" / CMAKE_VERSION / "bin"
    toolchain = NativeToolchain(sdk, ndk.expanduser().resolve() if ndk else pinned_ndk,
                               cmake_bin / f"cmake{suffix}", cmake_bin / f"ninja{suffix}")
    problems = _problems(toolchain)
    if not problems:
        return toolchain
    if _android_host():
        # Google SDK downloads are desktop-host packages. Never fill a phone's SDK with
        # non-executable desktop binaries, or send a phone user to Windows/Studio setup.
        detail = "\n".join(problems.values())
        raise ToolchainSetupError(f"{detail}\n\n{_android_host_instructions(sdk)}")
    android_cli = _android_cli(sdk)
    manager = _sdkmanager(sdk) if android_cli is None else None
    help_text = _instructions(sdk, manager, android_cli)
    detail = "\n".join(problems.values())
    if not install_missing:
        raise ToolchainSetupError(f"{detail}\n\n{help_text}")
    if PACKAGES[0] in problems and toolchain.ndk != pinned_ndk:
        raise ToolchainSetupError(f"{detail}\nCannot install into a custom --ndk directory. Use the pinned SDK NDK.\n{help_text}")
    if android_cli is None and manager is None:
        raise ToolchainSetupError(f"{detail}\nSDK Command-line Tools (latest) are not installed.\n\n{help_text}")
    print(f"Installing missing Android build packages into {sdk}: {', '.join(problems)}", flush=True)
    print("SDK licenses must already be accepted; setup will not silently accept them.", flush=True)
    try:
        if android_cli:
            command = [str(android_cli), f"--sdk={sdk}", "--no-metrics", "sdk", "install",
                       *(package.replace(";", "/") for package in problems)]
        else:
            command = _manager_command(manager) + [f"--sdk_root={sdk}", *problems]
        _run_sdk_command(command)
    except (OSError, subprocess.CalledProcessError, ToolchainSetupError) as error:
        raise ToolchainSetupError(f"Could not install the pinned Android native toolchain: {error}\n\n{help_text}") from error
    remaining = _problems(toolchain)
    if remaining:
        # SDK installers can exit zero after declining unaccepted licenses. Never report success on exit code alone.
        raise ToolchainSetupError(f"SDK installation did not provide a complete toolchain:\n"
                                  f"{chr(10).join(remaining.values())}\n\n{help_text}")
    return toolchain
