"""Integrity of frozen execution evidence; these are not wine-correctness assertions."""
import csv
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class FrozenCorpusExecutionTests(unittest.TestCase):
    def test_all_inputs_and_model_provenance_are_preserved(self):
        root = ROOT/'tools/evaluation/public-corpus-run-39716483937'
        audit = json.loads((root/'audit.json').read_text())
        inventory = json.loads((ROOT/'tools/corpus/public-source-inventory.json').read_text())
        expected = {r['source_blob_sha1']+'.jpg': r['image_sha256'] for r in inventory['records']}
        production = json.loads((root/'production.json').read_text())
        self.assertEqual(len(expected), 168)
        self.assertEqual(set(production), set(expected))
        self.assertEqual(audit['verified_inputs'], 168)
        self.assertFalse(audit['missing_or_duplicate_summaries'])
        self.assertIsNone(audit['summary']['accuracy'])
        seen = set()
        summaries = {'image_summary': [], 'form_summary': [], 'evidence_summary': []}
        with (root/'ocr-comparison.csv').open(newline='', encoding='utf-8') as file:
            for row in csv.DictReader(file):
                name = row['image_name'];seen.add(name)
                self.assertEqual(row['input_sha256'], expected[name])
                self.assertEqual(row['build'], audit['source_commit'][:12])
                if row['stage'] in summaries: summaries[row['stage']].append(name)
                if row['candidate'] == 'candidate_3':
                    self.assertEqual(row['detector_sha256'], 'eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1')
                    self.assertEqual(row['recognizer_sha256'], '9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba')
        self.assertEqual(seen, set(expected))
        for names in summaries.values():
            self.assertEqual(len(names),168)
            self.assertEqual(set(names),set(expected))
        self.assertEqual(sum(r['deadlineCancelled'] for r in production.values()),audit['summary']['production_deadlines'])
