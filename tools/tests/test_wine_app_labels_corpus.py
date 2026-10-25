import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REVIEW = ROOT / "tools/evaluation/wine-app-labels-review-v1"
ASSETS = ROOT / "app/src/main/assets/wine-app-labels-v1"


class WineAppLabelsCorpusTests(unittest.TestCase):
    def test_packaged_photos_and_target_metadata_are_complete_and_frozen(self):
        labels = json.loads((REVIEW / "candidate-labels.json").read_text())
        manifest = json.loads((ASSETS / "manifest.json").read_text())
        inputs_doc = json.loads((ASSETS / "inputs.json").read_text())
        selection = json.loads((ASSETS / "evaluation-set.json").read_text())
        scoring = json.loads((ASSETS / "scoring.json").read_text())
        inputs = inputs_doc["images"]
        targets = scoring["images"]
        self.assertEqual((len(labels["records"]), len(inputs), len(targets)), (125, 125, 125))
        self.assertEqual((selection["included_count"], selection["excluded_count"]), (125, 10))
        self.assertEqual({row["image"] for row in inputs}, {row["image"] for row in targets})
        self.assertEqual({row["image"] for row in selection["included_images"]}, {row["image"] for row in inputs})
        self.assertEqual({p.name for p in (ASSETS / "images").glob("*.jpg")}, {row["image"] for row in inputs})
        self.assertNotIn("IMG_2732.jpeg", {p.name for p in (ASSETS / "images").iterdir()})
        self.assertEqual(manifest["count"], 125)
        self.assertEqual(sum(row["source_repo"] == "AntoninLeroy/wine_label_reader_toolkit" for row in inputs), 103)
        self.assertEqual(sum(row["source_repo"] == "n8rtz/Wine-Reference-Drop" for row in inputs), 22)
        self.assertEqual(manifest["bytes"], sum((ASSETS / "images" / row["image"]).stat().st_size for row in inputs))
        self.assertEqual(hashlib.sha256((ASSETS / "inputs.json").read_bytes()).hexdigest(), manifest["inputs_sha256"])
        self.assertEqual(hashlib.sha256((ASSETS / "evaluation-set.json").read_bytes()).hexdigest(), manifest["evaluation_set_sha256"])
        self.assertEqual(hashlib.sha256((ASSETS / "scoring.json").read_bytes()).hexdigest(), manifest["scoring_sha256"])
        for row in inputs:
            self.assertEqual(hashlib.sha256((ASSETS / "images" / row["image"]).read_bytes()).hexdigest(), row["sha256"])
        for row in labels["records"]:
            source_path = ROOT / row["source_path"]
            self.assertEqual(hashlib.sha256(source_path.read_bytes()).hexdigest(), row["source_sha256"])
        self.assertEqual({row["review_number"] for row in targets}, {row["review_number"] for row in labels["records"]})
        self.assertFalse({14, 49, 52, 55, 64, 73, 133, 159, 168} & {row["review_number"] for row in targets})
        self.assertTrue(all(set(row["fields"]) == {"producer", "wine_name", "country", "region", "subregion", "vintage"} for row in targets))

    def test_gazetteer_coverage_counts_unique_known_labels_on_scoring_side(self):
        coverage = json.loads((ASSETS / "gazetteer-coverage.json").read_text())
        self.assertEqual(coverage["dataset_count"], 125)
        self.assertIn("never provided to the OCR runner", coverage["method"])
        rows = {row["id"]: row for row in coverage["databases"]}
        self.assertEqual(set(rows), {"lwin_producers", "lwin_wines", "appellations", "wine_geography"})
        self.assertEqual(rows["lwin_producers"]["matched_labels"], 86)
        self.assertEqual(rows["lwin_wines"]["matched_labels"], 42)
        self.assertEqual(rows["appellations"]["matched_labels"], 89)
        self.assertTrue(all(0 <= row["matched_labels"] <= 125 for row in rows.values()))
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        self.assertIn("Gazetteer database coverage", activity)
        self.assertIn("Known workbook values and accepted aliases", activity)

    def test_user_adjudications_are_applied_to_workbook_and_phone_targets(self):
        labels = json.loads((REVIEW / "candidate-labels.json").read_text())
        by_review = {row["review_number"]: row for row in labels["records"]}
        self.assertEqual(by_review[169]["wine_name"], "Gran Reserva 904")
        self.assertEqual(by_review[171]["wine_name"], "Triton")
        self.assertEqual((by_review[172]["producer"], by_review[172]["wine_name"]), ("Kirkland Signature", "Pauillac"))
        self.assertEqual((by_review[173]["producer"], by_review[173]["wine_name"], by_review[173]["subregion"]), ("Ciabot", "Baronetto", "Barbaresco"))
        self.assertEqual(by_review[179]["wine_name"], "Campo Eliseo")
        self.assertEqual(by_review[180]["wine_name_alternates"], ["Peppoli"])
        self.assertEqual(by_review[182]["country"], "California")
        self.assertEqual(by_review[182]["subregion_alternates"], ["Stags Leap"])
        self.assertEqual(by_review[189]["producer"], "Kirkland Signature")
        self.assertEqual(by_review[66]["producer"], "A. Kolb")
        self.assertIn("Club des Sommeliers", by_review[66]["notes"])
        self.assertEqual(by_review[190]["notes"].find("bottler, not substituted for producer") >= 0, True)
        scoring = json.loads((ASSETS / "scoring.json").read_text())
        cases = {row["review_number"]: row for row in scoring["images"]}
        self.assertEqual(cases[169]["fields"]["wine_name"]["target"], "Gran Reserva 904")
        self.assertEqual(cases[180]["fields"]["wine_name"]["accepted"], ["peppolichianticlassico", "peppoli"])
        self.assertIn("stagsleap", cases[182]["fields"]["subregion"]["accepted"])
        self.assertEqual(cases[176]["fields"]["vintage"]["status"], "unresolved")
        self.assertEqual(cases[188]["fields"]["region"]["status"], "expected_blank")
        self.assertNotIn("clubdessommeliers", cases[66]["fields"]["producer"]["accepted"])

    def test_red_rows_and_club_producer_removal_are_audited(self):
        audit = json.loads((REVIEW / "workbook-update-audit.json").read_text())
        red_ids = [49, 52, 55, 64, 73, 133, 159, 168]
        self.assertEqual(audit["red_rows_removed_from_both_tabs"], red_ids)
        self.assertEqual(audit["owner_removed_club_des_sommeliers_review_ids"], [14])
        self.assertEqual(audit["all_removed_review_ids"], sorted(red_ids + [14]))
        self.assertTrue((REVIEW / "reference-data-scrubbed-updated.xlsx").is_file())
        self.assertEqual(len(json.loads((ASSETS / "inputs.json").read_text())["images"]), 125)

    def test_main_profile_routes_to_the_125_label_test(self):
        profile = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/ProfileScreen.kt").read_text()
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        activity = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt").read_text()
        viewmodel = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsViewModel.kt").read_text()
        self.assertIn("com.yourapp.wine.phonecorpus.WineAppLabelsActivity", profile)
        self.assertIn("WineAppLabelsActivity\" android:exported=\"false\"", manifest)
        self.assertIn('Text("Large image dataset test"', activity)
        self.assertIn('"wine-app-labels-v1/images/$image"', activity)
        self.assertIn('PhoneCorpusRunner.runImage(', viewmodel)
        self.assertIn('"wine-app-labels-v1"', viewmodel)
        self.assertIn("125 retained workbook-linked wine labels", activity)
        producer_manifest = json.loads((ROOT / "app/src/main/assets/producer-gazetteer-v1/manifest.json").read_text())
        self.assertNotIn("support", producer_manifest)
        self.assertNotIn("support_attribution", producer_manifest)
        for name in ("ttb-support.sqlite", "osm-support.sqlite", "xwines-candidates.sqlite"):
            self.assertFalse((ROOT / "app/src/main/assets/producer-gazetteer-v1" / name).exists())
            self.assertFalse((ROOT / "app/src/debug/assets/producer-gazetteer-v1" / name).exists())
        self.assertIn("val columnWidth = 88.dp", activity)
        self.assertIn("Unverified TTB, OSM, and X-Wines name lists are excluded", activity)
        self.assertIn("Mode.entries.map { comparedMode ->", viewmodel)
        self.assertIn("Reuse this version's saved per-photo results", viewmodel)
        self.assertIn('"evaluation_set_sha256", "scoring_sha256"', viewmodel)
        self.assertIn('metadata.optString("pipeline") != mode.pipelineId', viewmodel)
        self.assertNotIn('dir.name.startsWith(folderPrefix(mode))', viewmodel)
        runner = (ROOT / "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt").read_text()
        for label in ("Lab v4", "Prod v3.0", "Prod v2.0", "Prod v1.0"):
            self.assertIn(f'("{label}"', runner)


if __name__ == "__main__":
    unittest.main()
