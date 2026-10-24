import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('export',Path(__file__).resolve().parents[1]/'corpus/export_android_run.py')
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SHA='0123456789abcdef0123456789abcdef01234567'


class AndroidCorpusExportTests(unittest.TestCase):
    def fixture(self, root):
        (root/'results').mkdir()
        (root/'selected-images.txt').write_text('/temporary/images/a.jpg\n')
        (root/'input-hashes.tsv').write_text('a.jpg\thash\thash\n')
        (root/'acquisition-manifest.json').write_text(json.dumps({'records':[{'file':'images/a.jpg','image_sha256':'hash'}]}))
        (root/'corpus-summary.json').write_text(json.dumps({'accuracy':None}))
        (root/'results/a-production.json').write_text(json.dumps({'image':'a.jpg','build':SHA[:12]}))
        with (root/'results/a.csv').open('w',newline='') as file:
            writer=csv.DictWriter(file,fieldnames=['image_name','build','input_sha256','stage','status','raw_text','ctc_posterior'])
            writer.writeheader()
            for stage in ['recognition','image_summary','form_summary','evidence_summary']:
                writer.writerow(dict(image_name='a.jpg',build=SHA[:12],input_sha256='hash',stage=stage,status='complete',raw_text='fixture, not benchmark truth',ctc_posterior='0.42'))

    def test_export_preserves_raw_text_and_score_without_accuracy(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root)
            module.export(root,root/'export','run',SHA)
            audit=json.loads((root/'export/audit.json').read_text())
            self.assertEqual(audit['verified_inputs'],1)
            self.assertEqual(audit['missing_or_duplicate_summaries'],{})
            self.assertIsNone(audit['summary']['accuracy'])
            with (root/'export/ocr-comparison.csv').open(newline='') as file:
                row=next(csv.DictReader(file))
            self.assertEqual(row['ctc_posterior'],'0.42')
            self.assertEqual(row['raw_text'],'fixture, not benchmark truth')

    def test_corrupt_staging_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root)
            (root/'input-hashes.tsv').write_text('a.jpg\thash\tchanged\n')
            with self.assertRaisesRegex(ValueError,'hashes'):module.export(root,root/'export','run',SHA)

    def test_missing_image_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root)
            (root/'results/a-production.json').unlink()
            with self.assertRaisesRegex(ValueError,'every selected'):module.export(root,root/'export','run',SHA)

    def test_different_scan_build_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root)
            (root/'results/a-production.json').write_text(json.dumps({'image':'a.jpg','build':''}))
            with self.assertRaisesRegex(ValueError,'wrong build'):module.export(root,root/'export','run',SHA)
