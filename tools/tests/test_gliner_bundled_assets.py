"""Verify the normal-build assets reconstruct to the exact phone-tested INT4 bundle."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/gliner-lab-v1'
PINS = {
    'gliner_small_int4.onnx': (131313484, 'a4d97745ba14a2b5f3606b30fa46a4c9a78e7b78a5f8b6c9e9fb35078d7c4a6d'),
    'tokenizer.json': (8340684, '1731c431e5e851a4a73487b5bbfbef265b29ab64de190c402b4e6df9ce2cb0ab'),
    'gliner_config.json': (2310, '74fa245ddcaf2ad5ec1c611d197b829a352ada033d14c797564a67ffe2902365'),
}

class SplitStream(io.RawIOBase):
    def __init__(self, paths):
        self.paths = iter(paths)
        self.current = None
    def readable(self): return True
    def readinto(self, buffer):
        while True:
            if self.current is None:
                try: self.current = next(self.paths).open('rb')
                except StopIteration: return 0
            n = self.current.readinto(buffer)
            if n: return n
            self.current.close()
            self.current = None
    def close(self):
        if self.current is not None: self.current.close()
        super().close()

class BundledAssetsTest(unittest.TestCase):
    def test_exact_model_tokenizer_and_config_from_normal_assets(self):
        manifest = json.loads((ASSETS / 'model-manifest.json').read_text())
        files = {entry['name']: entry for entry in manifest['files']}
        self.assertEqual(set(files), set(PINS))
        for name, (size, digest) in PINS.items():
            with self.subTest(name=name):
                entry = files[name]
                self.assertEqual((entry['bytes'], entry['sha256']), (size, digest))
                parts = entry['asset_parts']
                self.assertTrue(parts)
                for part in parts:
                    self.assertEqual(Path(part).name, part)
                    self.assertLess((ASSETS / part).stat().st_size, 90_000_000)
                sha = hashlib.sha256()
                length = 0
                with io.BufferedReader(SplitStream([ASSETS / p for p in parts])) as raw:
                    stream = gzip.GzipFile(fileobj=raw) if entry['asset_encoding'] == 'gzip' else raw
                    while chunk := stream.read(1024 * 1024):
                        sha.update(chunk)
                        length += len(chunk)
                self.assertEqual((length, sha.hexdigest()), (size, digest))

if __name__ == '__main__': unittest.main()
