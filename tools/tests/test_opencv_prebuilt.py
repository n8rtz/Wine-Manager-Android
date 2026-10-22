import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prebuilt', Path(__file__).parents[1] / 'opencv-prebuilt.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class PrebuiltChecks(unittest.TestCase):
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
