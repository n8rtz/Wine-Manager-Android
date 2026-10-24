import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('validate_corpus',ROOT/'tools/corpus/validate_corpus.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class PublicCorpusTests(unittest.TestCase):
    def setup_corpus(self,folder):
        image=folder/'photo.jpg';image.write_bytes(b'test integrity fixture, not an image benchmark')
        row=dict(id='one',file='photo.jpg',image_sha256=hashlib.sha256(image.read_bytes()).hexdigest(),source_sha256='source',
                 fields={k:None for k in module.FIELDS},split='unassigned',bottle_group=None,
                 annotation_status='unreviewed',license_status='unknown')
        self.write(folder,[row]);return row
    def write(self,folder,rows):
        (folder/'manifest.json').write_text(json.dumps(dict(records=rows)))
    def test_integrity_and_not_evaluation_ready(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);self.setup_corpus(folder)
            self.assertEqual(module.validate(folder)['records'],1)
            with self.assertRaisesRegex(ValueError,'rights'):module.validate(folder,True)
            (folder/'photo.jpg').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'hash'):module.validate(folder)
    def test_group_and_duplicate_leakage(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);r=self.setup_corpus(folder)
            r.update(split='development',bottle_group='bottle-a')
            other=dict(r,id='two',split='holdout')
            self.write(folder,[r,other])
            with self.assertRaisesRegex(ValueError,'leaks'):module.validate(folder)
    def test_missing_group_and_duplicate_id(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);r=self.setup_corpus(folder);r['split']='holdout';self.write(folder,[r])
            with self.assertRaisesRegex(ValueError,'group'):module.validate(folder)
            r['split']='unassigned';self.write(folder,[r,r])
            with self.assertRaisesRegex(ValueError,'Duplicate'):module.validate(folder)

    def test_private_test_retains_reference_and_split_gates(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d);r=self.setup_corpus(folder)
            with self.assertRaisesRegex(ValueError,'reviewed'):module.validate(folder,True,True)
            r.update(annotation_status='reviewed',bottle_group='bottle-a',split='development')
            self.write(folder,[r])
            self.assertEqual(module.validate(folder,True,True)['reviewed'],1)
            with self.assertRaisesRegex(ValueError,'rights'):module.validate(folder,True)
