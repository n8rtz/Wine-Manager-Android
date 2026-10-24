import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('evaluate_evidence', ROOT / 'tools/evaluate-evidence.py')
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class EvidenceEvaluationTests(unittest.TestCase):
    def test_normalization_does_not_complete_identity_or_year(self):
        self.assertEqual(evaluation.norm("La Réserve de l'Enclave"), evaluation.norm("LA RESERVE DE L’ENCLAVE"))
        self.assertNotEqual(evaluation.norm('ALTOSde'), evaluation.norm('Altos de Valdoso'))
        self.assertNotEqual(evaluation.norm('019'), evaluation.norm('2019'))

    def fixtures(self, folder, mutate=False):
        targets = json.loads((ROOT / 'tools/evaluation/evidence-targets.json').read_text())
        for batch, bottles in targets['batches'].items():
            rows = []
            for i, bottle in enumerate(bottles, 1):
                values = {k: v or '' for k, v in zip(targets['fields'], targets['bottles'][bottle])}
                for stage in ['form_summary', 'evidence_summary']:
                    row = dict(values, image_index=str(i), stage=stage, status='suggestions')
                    if mutate and batch == 'architecture' and i == 4 and stage == 'evidence_summary':
                        row['producer'] = 'Wrong'
                        row['vintage'] = ''
                    # An unconfirmed year must not become a scored target.
                    if bottle == 'blackstone':
                        row['vintage'] = '1901'
                    rows.append(row)
            with (folder / f'{batch}-preview-replay.csv').open('w') as stream:
                writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
                writer.writeheader()
                writer.writerows(rows)

    def test_counts_and_unknown_exclusions(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            self.fixtures(folder)
            report = evaluation.evaluate(folder)
            self.assertEqual(report['totals']['baseline']['correct'], 160)
            self.assertEqual(report['totals']['experimental']['wrong'], 0)
            self.assertEqual(report['differences'], [])

    def test_wrong_fills_distinguished_from_abstentions(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            self.fixtures(folder, mutate=True)
            report = evaluation.evaluate(folder)
            self.assertEqual(report['totals']['experimental']['wrong'], 1)
            self.assertEqual(report['totals']['experimental']['abstained'], 1)
            self.assertEqual(len(report['differences']), 2)
