import csv
import importlib.util
import json
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVIEW_DIR = ROOT / "evaluation/one-error-review-lab-7b69cedff5bf"
ANNOTATIONS = REVIEW_DIR / "ocr-evidence-annotations.json"
REPORT_CSV = REVIEW_DIR / "ocr-evidence-review.csv"

spec = importlib.util.spec_from_file_location("diagnose_one_error_ocr", REVIEW_DIR / "diagnose_one_error_ocr.py")
diagnosis = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(diagnosis)


class OneErrorOcrDiagnosisTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.annotations = json.loads(ANNOTATIONS.read_text(encoding="utf-8"))
        with REPORT_CSV.open(encoding="utf-8-sig", newline="") as stream:
            cls.rows = list(csv.DictReader(stream))

    def test_review_covers_the_37_one_error_fields_and_separates_causes(self):
        self.assertEqual(len(self.rows), 37)
        counts = Counter(row["evidence_status"] for row in self.rows)
        self.assertEqual(counts, {
            "sufficient": 20,
            "insufficient": 12,
            "mapping_required": 2,
            "policy_blank": 3,
        })
        self.assertEqual(len({row["review_number"] for row in self.rows}), 37)

    def test_twelve_recognition_gap_cases_are_frozen_as_regressions(self):
        gaps = {number: case for number, case in self.annotations["cases"].items()
                if case["evidence_status"] == "insufficient"}
        self.assertEqual(set(gaps), {"12", "18", "31", "45", "55", "63", "75", "114", "117", "133", "159", "168"})
        row_by_id = {row["review_number"]: row for row in self.rows}
        for number, case in gaps.items():
            with self.subTest(review_number=number):
                self.assertTrue(case["label_reading"])
                self.assertTrue(case["missing_component"])
                row = row_by_id[number]
                self.assertEqual(row["evidence_status"], "insufficient")
                observed = diagnosis.key(row["raw_evidence"])
                # The review must not claim full transcription from a near-match or partial OCR.
                self.assertNotIn(diagnosis.key(case["label_reading"]), observed)
                self.assertTrue(row["raw_evidence"])

    def test_sufficient_cases_preserve_engine_provenance_and_fragments(self):
        cases = self.annotations["cases"]
        for row in self.rows:
            with self.subTest(review_number=row["review_number"]):
                case = cases[row["review_number"]]
                self.assertTrue(case["evidence"])
                self.assertIn(row["evidence_status"], diagnosis.ALLOWED)
                for fragment in case["evidence"]:
                    self.assertIn(fragment["engine"], {"mlkit_reference", "candidate_3"})
                    self.assertTrue(fragment["text"])
                    self.assertIn(diagnosis.key(fragment["text"]), diagnosis.key(row["raw_evidence"]))

    def test_raw_comparison_includes_all_twelve_complete_engine_traces(self):
        text = (REVIEW_DIR / "12-recognition-gap-raw-comparison.md").read_text(encoding="utf-8")
        self.assertEqual(text.count("**Raw ML Kit (`mlkit_reference`)**"), 12)
        self.assertEqual(text.count("**Raw PP-OCR (`candidate_3`)**"), 12)
        self.assertEqual(text.count("| Field | Imported reference CSV | Final Lab output |"), 12)
        for number in (12, 18, 31, 45, 55, 63, 75, 114, 117, 133, 159, 168):
            with self.subTest(review_number=number):
                self.assertIn(f"## Review #{number} —", text)

    def test_normalization_handles_case_accents_and_punctuation_without_fuzzy_matching(self):
        self.assertEqual(diagnosis.key("Saint-Émilion"), diagnosis.key("SAINT EMILION"))
        self.assertNotEqual(diagnosis.key("La Tour de By"), diagnosis.key("La Cour de By"))
        self.assertNotEqual(diagnosis.key("Calon"), diagnosis.key("Caler"))


if __name__ == "__main__":
    unittest.main()
