import importlib.util
import hashlib
from pathlib import Path, PureWindowsPath, PurePosixPath
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prebuilt', Path(__file__).parents[1] / 'opencv-prebuilt.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class PrebuiltChecks(unittest.TestCase):
    def test_manifest_keys_are_platform_independent(self):
        relative = 'java/org/opencv/core/Mat.java'
        for cls, root in ((PureWindowsPath, 'C:/Users/Test User/Wine/prebuilt'),
                          (PurePosixPath, '/tmp/Wine/prebuilt')):
            directory = cls(root)
            self.assertEqual(module.manifest_key(directory / relative, directory), relative)

    def test_matching_manifest_accepted(self):
        module.verify_manifest({'files': {'java/Test.java': 'abc'}, 'recipes': {'build.py': 'def'}},
                               {'java/Test.java': 'abc'}, {'build.py': 'def'})

    def test_changes_missing_and_extra_files_still_rejected(self):
        record = {'files': {'java/Test.java': 'abc'}, 'recipes': {'build.py': 'def'}}
        for files, recipes in (({'java/Test.java': 'changed'}, {'build.py': 'def'}),
                               ({}, {'build.py': 'def'}),
                               ({'java/Test.java': 'abc', 'extra': 'x'}, {'build.py': 'def'}),
                               ({'java/Test.java': 'abc'}, {'build.py': 'changed'})):
            with self.subTest(files=files, recipes=recipes):
                with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                    module.verify_manifest(record, files, recipes)

    def test_crlf_is_not_silently_normalized_by_verifier(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'Test.java'
            path.write_bytes(b'line1\nline2\n')
            original = module.digest(path)
            path.write_bytes(b'line1\r\nline2\r\n')
            with self.assertRaisesRegex(ValueError, 'CRLF'):
                module.verify_manifest({'files': {'Test.java': original}, 'recipes': {}},
                                       {'Test.java': module.digest(path)}, {})

    def test_explicit_repair_reproduces_pinned_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / 'prebuilt'
            directory.mkdir()
            original = b'class Test {\n}\n'
            java = directory / 'Test.java'
            java.write_bytes(original.replace(b'\n', b'\r\n'))
            script = root / 'build.py'
            script.write_bytes(b'print(1)\r\n')
            record = {'files': {'Test.java': hashlib.sha256(original).hexdigest()},
                      'recipes': {'build.py': hashlib.sha256(b'print(1)\n').hexdigest()}}
            self.assertEqual(module.repair_line_endings(record, directory, root), 2)
            self.assertEqual(java.read_bytes(), original)
            self.assertEqual(script.read_bytes(), b'print(1)\n')
            self.assertEqual(module.repair_line_endings(record, directory, root), 0)

    def test_repair_preflight_preserves_real_edits_and_other_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            a = root / 'A.java'
            b = root / 'B.java'
            a.write_bytes(b'original\r\n')
            b.write_bytes(b'intentional edit\r\n')
            expected = hashlib.sha256(b'original\n').hexdigest()
            record = {'files': {'A.java': expected, 'B.java': expected}, 'recipes': {}}
            with self.assertRaisesRegex(ValueError, 'No files repaired'):
                module.repair_line_endings(record, root, root)
            self.assertEqual(a.read_bytes(), b'original\r\n')
            self.assertEqual(b.read_bytes(), b'intentional edit\r\n')

    def test_repair_never_transforms_native_library(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / 'native.so'
            path.write_bytes(b'not-a-real-library\r\n')
            record = {'files': {'native.so': hashlib.sha256(b'not-a-real-library\n').hexdigest()}, 'recipes': {}}
            with self.assertRaisesRegex(ValueError, 'No files repaired'):
                module.repair_line_endings(record, root, root)
            self.assertEqual(path.read_bytes(), b'not-a-real-library\r\n')

    def test_missing_artifact_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, 'missing'):
                module.inspect(Path(tmp))

    def test_elf_header_contract(self):
        # Header-only synthetic fixture, never an app library.
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'header'
            data = bytearray(120)
            data[:6] = b'\x7fELF\x02\x01'
            struct.pack_into('<H', data, 18, 183)
            struct.pack_into('<Q', data, 32, 64)
            struct.pack_into('<HH', data, 54, 56, 1)
            struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, 0, 0, 16384)
            path.write_bytes(data)
            module.verify_native(path)
            struct.pack_into('<Q', data, 112, 4096)
            path.write_bytes(data)
            with self.assertRaisesRegex(ValueError, '16 KiB'):
                module.verify_native(path)
            struct.pack_into('<H', data, 18, 62)
            path.write_bytes(data)
            with self.assertRaisesRegex(ValueError, 'AArch64'):
                module.verify_native(path)
