# Windows: verifyOpenCvPrebuilt fails

Gradle's `Process 'command python' finished with non-zero exit value 1` is only a wrapper error. The Python traceback/output above it identifies the actual failure.

The verifier previously constructed manifest keys with `str(relative_path)`. On Windows that produces backslashes, while the sealed CI manifest and required-file list use forward slashes. Verification now uses `as_posix()` on all hosts. The bytes/hashes of the native library, Java bindings, license and build recipes are unchanged.

The sealed subtree and two hashed build recipes are also marked `-text` in `.gitattributes` to prevent Git's Windows CRLF conversion. The verifier still rejects any byte mismatch; it does not normalize changed files or reseal them. The prebuilt verification task enables Python UTF-8 mode.

## Updating an existing Windows checkout

In Android Studio's PowerShell terminal at the repository root:

```powershell
git pull --ff-only origin arena/fd8dc3be-wine-manager-android
python tools/opencv-prebuilt.py
```

If this reports a hash mismatch after updating, inspect the sealed paths:

```powershell
git status --short -- opencv-minimal/prebuilt tools/build-opencv-minimal.py tools/android_native_toolchain.py
```

**Only if you have not intentionally edited those files** (save any real edits first), restore their committed, byte-exact contents. This is limited to the sealed prebuilt subtree and its two recipes, not the rest of the app:

```powershell
git -c core.autocrlf=false restore --source=HEAD --worktree -- opencv-minimal/prebuilt tools/build-opencv-minimal.py tools/android_native_toolchain.py
python tools/opencv-prebuilt.py
.\gradlew.bat :opencv-minimal:verifyOpenCvPrebuilt --console=plain
```

Expected success: `Verified minimal OpenCV: 46 files, arm64 ELF, 16 KiB LOAD alignment, recipe hashes and license`.

Then build normally in Android Studio. No native OpenCV rebuild or verification bypass is required for these checkout portability fixes.

If `python` is not a working Python 3 installation, check `python --version`. The build already supports `-PpythonExecutable=C:/path/to/python.exe`, or `pythonExecutable=C:/path/to/python.exe` in your local Gradle user properties (do not commit your machine-specific path). Paths with spaces must be quoted when passed on the command line. A command alias such as `py -3` is not an executable path for this setting.

If verification still fails, provide the output of `python tools/opencv-prebuilt.py`, not only Gradle's wrapper stack trace. Do not use `--seal` locally, edit the pinned hashes or skip the task.

Validation performed on Linux: platform-independent path tests include `PureWindowsPath` with a spaced path; matching, changed, missing, extra and CRLF-modified files are covered; the existing genuine 46-file artifact verifies successfully. This is not an executed Windows Android Studio build.
