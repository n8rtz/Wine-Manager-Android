"""Toolchain setup policy tests only: no real/made-up native binary, SDK download or compiler is run."""
import contextlib
import io
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_native_toolchain as setup


class AndroidNativeToolchainTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="native setup ")
        self.addCleanup(temporary.cleanup)
        self.sdk = Path(temporary.name) / "SDK for Jane & Zoe"
        self.sdk.mkdir()
        self.manager = self.sdk / "cmdline-tools/latest/bin/sdkmanager"
        self.missing = {setup.PACKAGES[0]: "NDK missing", setup.PACKAGES[1]: "CMake missing"}
        self.addCleanup(patch.stopall)
        patch.object(setup, "WINDOWS", False).start()
        self.run = patch.object(setup.subprocess, "run").start()
        # An unexpected native build/download must fail the test, never leave a background process.
        self.run.side_effect = AssertionError("Unexpected subprocess")

    def ensure(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return setup.ensure_native_toolchain(self.sdk, **kwargs)

    def test_installed_toolchain_is_reused_without_sdkmanager_or_network(self):
        with patch.object(setup, "_problems", return_value={}):
            result = self.ensure(install_missing=True)
        self.assertEqual(self.sdk / "ndk" / setup.NDK_VERSION, result.ndk)
        self.assertEqual(self.sdk / "cmake" / setup.CMAKE_VERSION / "bin/cmake", result.cmake)
        self.run.assert_not_called()

    def test_installs_exact_pins_into_the_selected_sdk_then_verifies(self):
        self.run.side_effect = None
        with patch.object(setup, "_problems", side_effect=[self.missing, {}]), \
                patch.object(setup, "_sdkmanager", return_value=self.manager):
            self.ensure(install_missing=True)
        args, options = self.run.call_args
        self.assertEqual([str(self.manager), f"--sdk_root={self.sdk}", *setup.PACKAGES], args[0])
        self.assertTrue(options["check"])
        self.assertEqual(subprocess.DEVNULL, options["stdin"])
        self.assertNotIn("--licenses", args[0])  # Never automatically agree to licenses.
        self.assertNotIn("shell", options)

    def test_only_missing_package_is_installed(self):
        self.run.side_effect = None
        for package in setup.PACKAGES:
            with self.subTest(package=package), \
                    patch.object(setup, "_problems", side_effect=[{package: "missing"}, {}]), \
                    patch.object(setup, "_sdkmanager", return_value=self.manager):
                self.ensure(install_missing=True)
                self.assertEqual([str(self.manager), f"--sdk_root={self.sdk}", package], self.run.call_args.args[0])

    def test_offline_mode_has_actionable_sdk_path_versions_and_licenses(self):
        with patch.object(setup, "_problems", return_value=self.missing), \
                self.assertRaises(setup.ToolchainSetupError) as error:
            self.ensure(install_missing=False)
        for expected in (str(self.sdk), setup.NDK_VERSION, setup.CMAKE_VERSION, "Show Package Details", "--licenses"):
            self.assertIn(expected, str(error.exception))
        self.run.assert_not_called()

    def test_absent_command_line_tools_has_android_studio_instructions(self):
        with patch.object(setup, "_problems", return_value=self.missing), \
                self.assertRaisesRegex(setup.ToolchainSetupError, "Command-line Tools"):
            self.ensure(install_missing=True)
        self.run.assert_not_called()

    def test_zero_sdkmanager_exit_does_not_mean_packages_were_installed(self):
        self.run.side_effect = None
        with patch.object(setup, "_problems", return_value=self.missing), \
                patch.object(setup, "_sdkmanager", return_value=self.manager), \
                self.assertRaisesRegex(setup.ToolchainSetupError, "did not provide a complete toolchain"):
            self.ensure(install_missing=True)

    def test_failed_sdkmanager_returns_installation_and_license_guidance(self):
        self.run.side_effect = subprocess.CalledProcessError(1, "sdkmanager")
        with patch.object(setup, "_problems", return_value=self.missing), \
                patch.object(setup, "_sdkmanager", return_value=self.manager), \
                self.assertRaises(setup.ToolchainSetupError) as error:
            self.ensure(install_missing=True)
        self.assertIn("Could not install", str(error.exception))
        self.assertIn("--licenses", str(error.exception))

    def test_missing_sdk_root_is_not_created_or_guessed(self):
        with self.assertRaisesRegex(setup.ToolchainSetupError, "SDK root does not exist"):
            setup.ensure_native_toolchain(self.sdk / "absent", install_missing=True)
        self.assertFalse((self.sdk / "absent").exists())
        self.run.assert_not_called()

    def test_invalid_custom_ndk_is_not_silently_replaced(self):
        with patch.object(setup, "_problems", return_value=self.missing), \
                self.assertRaisesRegex(setup.ToolchainSetupError, "custom --ndk"):
            self.ensure(ndk=self.sdk / "custom NDK", install_missing=True)
        self.run.assert_not_called()

    def test_reads_actual_package_revision_metadata(self):
        (self.sdk / "source.properties").write_text("Pkg.Desc = Unit-test metadata only\nPkg.Revision = 28.2.13676358\n")
        self.assertEqual(setup.NDK_VERSION, setup._revision(self.sdk))
        self.assertEqual("missing", setup._revision(self.sdk / "absent"))

    def test_other_installed_ndk_version_cannot_satisfy_the_pin(self):
        tools = setup.NativeToolchain(self.sdk, self.sdk / "ndk/other", self.sdk / "cmake/bin/cmake", self.sdk / "cmake/bin/ninja")
        with patch.object(setup, "_revision", return_value="27.0.0"), patch.object(Path, "is_file", return_value=True):
            problems = setup._problems(tools)
        self.assertIn(setup.PACKAGES[0], problems)
        self.assertIn("revision: 27.0.0", problems[setup.PACKAGES[0]])

    def test_version_metadata_alone_does_not_satisfy_missing_tool_files(self):
        tools = setup.NativeToolchain(self.sdk, self.sdk / "ndk", self.sdk / "cmake/bin/cmake", self.sdk / "cmake/bin/ninja")
        with patch.object(setup, "_revision", side_effect=[setup.NDK_VERSION, setup.CMAKE_VERSION]):
            self.assertEqual(set(setup.PACKAGES), set(setup._problems(tools)))

    def test_sdkmanager_prefers_latest_then_numerically_highest_numbered_tools(self):
        for version in ("9.0", "12.0"):
            manager = self.sdk / f"cmdline-tools/{version}/bin/sdkmanager"
            manager.parent.mkdir(parents=True)
            manager.write_text("# Unit-test path fixture; never executed\n")
        self.assertIn("12.0", str(setup._sdkmanager(self.sdk)))
        self.manager.parent.mkdir(parents=True)
        self.manager.write_text("# Unit-test path fixture; never executed\n")
        self.assertEqual(self.manager, setup._sdkmanager(self.sdk))

    def test_windows_uses_java_argv_not_a_batch_shell_with_sdk_paths(self):
        with patch.object(setup, "WINDOWS", True), \
                patch.dict(os.environ, {"JAVA_HOME": str(self.sdk / "Gradle JDK")}), \
                patch.object(Path, "is_file", return_value=True):
            command = setup._manager_command(self.manager.with_suffix(".bat"))
        self.assertEqual(self.sdk / "Gradle JDK/bin/java.exe", Path(command[0]))
        self.assertIn(f"-Dcom.android.sdklib.toolsdir={self.manager.parent.parent}", command)
        self.assertEqual("com.android.sdklib.tool.sdkmanager.SdkManagerCli", command[-1])
        self.assertNotIn("cmd.exe", command)
        self.assertNotIn("/c", command)

    def test_windows_missing_java_is_actionable(self):
        with patch.object(setup, "WINDOWS", True), patch.dict(os.environ, {}, clear=True), \
                patch.object(setup.shutil, "which", return_value=None), \
                self.assertRaisesRegex(setup.ToolchainSetupError, "full JDK"):
            setup._manager_command(self.manager.with_suffix(".bat"))

    def test_modern_android_cli_uses_slash_package_paths_and_selected_sdk(self):
        self.run.side_effect = None
        android = self.manager.with_name("android")
        with patch.object(setup, "_problems", side_effect=[self.missing, {}]), \
                patch.object(setup, "_android_cli", return_value=android), \
                patch.object(setup, "_sdkmanager", side_effect=AssertionError("Do not invoke the deprecated redirect")):
            self.ensure(install_missing=True)
        self.assertEqual([str(android), f"--sdk={self.sdk}", "--no-metrics", "sdk", "install",
                          "ndk/28.2.13676358", "cmake/3.22.1"], self.run.call_args.args[0])
        self.assertNotIn("--licenses", self.run.call_args.args[0])
        self.assertFalse(any(";" in arg for arg in self.run.call_args.args[0]))

    def test_modern_windows_native_launcher_is_not_sent_to_java_or_cmd(self):
        self.run.side_effect = None
        android = self.manager.with_name("android.exe")
        with patch.object(setup, "WINDOWS", True), \
                patch.object(setup, "_problems", side_effect=[self.missing, {}]), \
                patch.object(setup, "_android_cli", return_value=android), \
                patch.object(setup, "_manager_command", side_effect=AssertionError("No legacy Java invocation")):
            self.ensure(install_missing=True)
        self.assertEqual(str(android), self.run.call_args.args[0][0])
        self.assertEqual(f"--sdk={self.sdk}", self.run.call_args.args[0][1])
        self.assertNotIn("shell", self.run.call_args.kwargs)
        self.assertNotIn("executable", self.run.call_args.kwargs)

    def test_modern_windows_batch_launcher_quotes_each_argument(self):
        self.run.side_effect = None
        android = self.manager.with_name("android.bat")
        with patch.object(setup, "WINDOWS", True), patch.dict(os.environ, {"COMSPEC": "cmd.exe"}):
            setup._run_sdk_command([str(android), f"--sdk={self.sdk}", "--no-metrics", "sdk", "install", "ndk/28.2.13676358"])
        command = self.run.call_args.args[0]
        self.assertIn('/d /v:off /s /c ""', command)
        self.assertIn(f'"--sdk={self.sdk}"', command)
        self.assertTrue(command.endswith('"ndk/28.2.13676358""'))
        self.assertEqual("cmd.exe", self.run.call_args.kwargs["executable"])

    def test_batch_launcher_does_not_expand_unsafe_path_characters(self):
        with patch.object(setup, "WINDOWS", True), \
                self.assertRaisesRegex(setup.ToolchainSetupError, "Cannot safely launch"):
            setup._run_sdk_command([str(self.manager.with_name("android.bat")), "--sdk=C:/SDK/%USERNAME%"])
        self.run.assert_not_called()

    def test_modern_cli_finder_prefers_native_exe_in_latest_bin(self):
        root = self.sdk / "cmdline-tools/latest/bin"
        with patch.object(setup, "WINDOWS", True), \
                patch.object(Path, "is_file", lambda path: path.parent == root and path.name in ("android.exe", "android.bat")):
            self.assertEqual(root / "android.exe", setup._android_cli(self.sdk))

    def test_modern_install_success_requires_the_real_pinned_tool_files(self):
        self.run.side_effect = None
        with patch.object(setup, "_problems", return_value=self.missing), \
                patch.object(setup, "_android_cli", return_value=self.manager.with_name("android")), \
                self.assertRaises(setup.ToolchainSetupError) as error:
            self.ensure(install_missing=True)
        self.assertIn("did not provide a complete toolchain", str(error.exception))
        self.assertIn("ndk/28.2.13676358", str(error.exception))
        self.assertIn("--no-metrics sdk install", str(error.exception))

    def test_android_host_missing_tools_never_launches_desktop_sdk_installers(self):
        for install in (False, True):
            with self.subTest(install_missing=install), \
                    patch.object(setup, "_android_host", return_value=True), \
                    patch.object(setup, "_problems", return_value=self.missing), \
                    patch.object(setup, "_android_cli", side_effect=AssertionError("No desktop CLI on phone")), \
                    patch.object(setup, "_sdkmanager", side_effect=AssertionError("No desktop SDK manager on phone")), \
                    self.assertRaises(setup.ToolchainSetupError) as error:
                self.ensure(install_missing=install)
            message = str(error.exception)
            for expected in (str(self.sdk), "Android-hosted", "Desktop SDK auto-install", setup.NDK_VERSION,
                             setup.CMAKE_VERSION, "desktop build host", "Do not rename"):
                self.assertIn(expected, message)
        self.run.assert_not_called()

    def test_android_host_does_not_reject_existing_pin_metadata_or_download_other_versions(self):
        # This tests only installed-package policy, not executable compatibility of phone compilers.
        with patch.object(setup, "_android_host", return_value=True), \
                patch.object(setup, "_problems", return_value={}):
            tools = self.ensure(install_missing=True)
        self.assertEqual(self.sdk / "ndk" / setup.NDK_VERSION, tools.ndk)
        self.run.assert_not_called()

    def test_android_host_detection_does_not_confuse_desktop_sdk_with_android_os(self):
        for platform, root, data, expected in (("linux", "", "", False),
                                                ("linux", "/system", "/data", True),
                                                ("android", "", "", True),
                                                ("darwin", "", "", False)):
            with self.subTest(platform=platform, android_root=root), \
                    patch.object(setup, "sys", SimpleNamespace(platform=platform)), \
                    patch.dict(os.environ, {"ANDROID_ROOT": root, "ANDROID_DATA": data,
                                           "ANDROID_HOME": str(self.sdk), "ANDROID_SDK_ROOT": str(self.sdk)}):
                self.assertEqual(expected, setup._android_host())

    def test_android_python_api_level_marker_detects_android_even_with_linux_platform(self):
        with patch.object(setup, "sys", SimpleNamespace(platform="linux", getandroidapilevel=lambda: 35)):
            self.assertTrue(setup._android_host())

    def test_gradle_task_prepares_sdk_before_resolving_any_ndk_provider(self):
        gradle = (Path(__file__).resolve().parents[2] / "opencv-minimal/build.gradle.kts").read_text()
        self.assertIn(f'val openCvNdkVersion = "{setup.NDK_VERSION}"', gradle)
        self.assertIn("dependsOn(prepareOpenCvToolchain)", gradle)
        self.assertNotIn(".ndkDirectory.get()", gradle)
        self.assertIn('environment("JAVA_HOME", System.getProperty("java.home"))', gradle)

    def test_generated_opencv_sources_use_variant_api_instead_of_legacy_source_sets(self):
        gradle = (Path(__file__).resolve().parents[2] / "opencv-minimal/build.gradle.kts").read_text()
        self.assertNotRegex(gradle, r"\bsourceSets\s*[.({]")
        self.assertNotIn("addStaticSourceDirectory", gradle.split("// This custom Exec native build", 1)[1])  # Generated inputs need their producer task.
        self.assertIn('addStaticSourceDirectory("prebuilt/java")', gradle)
        self.assertIn("androidComponents.onVariants { variant ->", gradle)
        for source, output in (("java", "javaSourcesDirectory"), ("jniLibs", "jniLibsDirectory"),
                               ("resources", "resourcesDirectory")):
            self.assertIn(f"variant.sources.{source}?.addGeneratedSourceDirectory(buildOpenCv) "
                          f"{{ it.{output} }}", gradle)

    def test_opencv_task_declares_generated_outputs_at_the_builder_paths(self):
        gradle = (Path(__file__).resolve().parents[2] / "opencv-minimal/build.gradle.kts").read_text()
        self.assertIn("abstract class BuildOpenCvTask : Exec()", gradle)
        self.assertIn("val buildOpenCv by tasks.registering(BuildOpenCvTask::class)", gradle)
        for output, directory in (("javaSourcesDirectory", "java"), ("jniLibsDirectory", "jniLibs"),
                                  ("resourcesDirectory", "resources")):
            self.assertRegex(gradle, rf"@get:OutputDirectory\s+abstract val {output}: DirectoryProperty")
            self.assertIn(f'{output}.set(layout.buildDirectory.dir("generated/opencv/{directory}"))', gradle)
        self.assertIn('tasks.named("preBuild") { dependsOn(buildOpenCv) }', gradle)

    def load_builder(self):
        script = Path(__file__).resolve().parents[1] / "build-opencv-minimal.py"
        spec = importlib.util.spec_from_file_location("opencv_builder_for_setup_tests", script)
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        return builder

    def test_prepare_only_never_downloads_or_compiles_opencv(self):
        builder = self.load_builder()
        tools = setup.NativeToolchain(self.sdk, self.sdk / "ndk", self.sdk / "cmake", self.sdk / "ninja")
        with patch.object(builder, "ensure_native_toolchain", return_value=tools) as ensure, \
                patch.object(builder.urllib.request, "urlopen", side_effect=AssertionError("No source download during setup")), \
                patch.object(sys, "argv", ["builder", "--sdk", str(self.sdk), "--prepare-only", "--install-missing"]), \
                contextlib.redirect_stdout(io.StringIO()):
            builder.main()
        ensure.assert_called_once_with(self.sdk, None, install_missing=True)
        self.run.assert_not_called()

    def test_prepare_cli_returns_actionable_error_not_agp_provider_trace(self):
        builder = self.load_builder()
        error_text = io.StringIO()
        with patch.object(sys, "argv", ["builder", "--sdk", str(self.sdk), "--prepare-only"]), \
                contextlib.redirect_stderr(error_text), self.assertRaises(SystemExit) as error:
            builder.main()
        self.assertEqual(2, error.exception.code)
        self.assertIn(setup.NDK_VERSION, error_text.getvalue())
        self.assertIn("SDK Manager", error_text.getvalue())
        self.assertNotIn("Traceback", error_text.getvalue())
        self.run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
