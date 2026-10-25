import csv
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / "tools/evaluation/wine-name-audit-v1"

class WineNameAuditTests(unittest.TestCase):
    def test_reproducible_queue_covers_all_labels_and_keeps_review_pending(self):
        subprocess.run([sys.executable, str(AUDIT / "build.py")], cwd=ROOT, check=True, capture_output=True, text=True)
        with (AUDIT / "wine-name-review.csv").open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(len(rows), 168)
        self.assertEqual(len({r["image"] for r in rows}), 168)
        self.assertEqual(sum(r["audit_status"] == "USER_CONFIRMED_VISIBLE_LABEL" for r in rows), 1)
        self.assertEqual(sum(r["audit_status"] == "PENDING_VISUAL_REVIEW" for r in rows), 167)
        corrected=next(r for r in rows if r["review_number"]=="63")
        self.assertEqual(corrected["imported_reference_target"],"Volcanie Rosé")
        self.assertEqual(corrected["reference_target"],"Volcanic Rosé")
        self.assertIn("visual reading",corrected["reviewer_notes"])
        self.assertTrue(all(r["reviewer_decision"] == "" and r["reviewer_notes"] == "" for r in rows if r["review_number"]!="63"))
        summary = json.loads((AUDIT / "summary.json").read_text())
        self.assertEqual(summary["user_confirmed_adjudications"],1)
        self.assertEqual(summary["saved_v2_outcomes"], {
            "correct_value": 77, "wrong_value": 44, "missing_value": 36,
            "correct_blank": 5, "unsupported_fill": 3,
            "unscored_blank": 1, "unscored_filled": 2,
        })
        self.assertIn("not a fresh independent image adjudication", summary["purpose"])
        subprocess.run([sys.executable, str(AUDIT / "export_reference_data.py")], cwd=ROOT, check=True, capture_output=True, text=True)
        with (AUDIT / "reference-data-168.csv").open(encoding="utf-8-sig", newline="") as stream:
            reference_rows = list(csv.DictReader(stream))
        self.assertEqual(len(reference_rows), 168)
        self.assertEqual(len({r["image"] for r in reference_rows}), 168)
        self.assertEqual(reference_rows[0]["wine_name_original_value"], "Médoc")
        self.assertEqual(reference_rows[0]["wine_name_scored_target"], "Médoc")
        self.assertEqual(reference_rows[0]["producer_original_accepted"], '["La Cave d\'Augustin Florent", "Cave d\'Augustin Florent", "Augustin Florent"]')
        corrected_reference=next(r for r in reference_rows if r["review_number"]=="63")
        self.assertEqual(corrected_reference["wine_name_original_value"],"Volcanie Rosé")
        self.assertEqual(corrected_reference["wine_name_scored_target"],"Volcanic Rosé")
        self.assertEqual(corrected_reference["scored_adjudication_status"],"USER_CONFIRMED")

if __name__ == "__main__": unittest.main()
