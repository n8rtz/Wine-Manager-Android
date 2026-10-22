# Phone-hosted builds

Normal builds consume `opencv-minimal/prebuilt/`: genuine OpenCV 4.12.0 core/imgproc,
arm64 JNI, matching Java bindings and the upstream Apache-2.0 license. These files
are produced by `Build branch OpenCV prebuilt` on GitHub's Ubuntu runner and
committed to the same experimental branch. No desktop is required by the user.
Wait for that workflow's artifact commit, pull, then run `:app:assembleDebug` in
Code on the Go. Python 3 is required for local hash verification; no NDK/CMake is
used for this OpenCV build path. This does not guarantee every other IDE/toolchain
issue is resolved, or substitute for device testing.

`manifest.json` records file SHA-256 hashes, recipe hashes, the producer commit and
CI run. Verification rejects corruption, stale recipes, missing license/bindings,
wrong ELF architecture, and non-16-KiB LOAD alignment. This is integrity checking,
not an independent cryptographic signature. Files are ordinary Git blobs, not LFS
pointers or expiring CI download links. Only arm64 devices are supported.

Desktop/CI developers can explicitly rebuild with `-PopencvFromSource=true`.
Changing either native build script requires a new CI-built artifact. The workflow
pushes only to `arena/fd8dc3be-wine-manager-android`, never force-pushes, and does not
merge main. If the branch advances during generation, rerun against its new HEAD.
The normal scanner CI still includes independent native toolchain preparation;
its APK compilation uses the bundled artifact. Full APK size limits remain in
`checkArm64ApkBudget`; the producer additionally rejects artifacts over 32 MiB.
