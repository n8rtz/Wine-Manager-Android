#!/usr/bin/env python3
"""Host evaluation fixture from saved exports (or bundled saved v2). No OCR or matching."""
import argparse
import csv
import hashlib
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIELDS = ("producer", "wine_name", "country", "region", "subregion", "vintage")
PIPELINE = "lab-v4-expanded-producers"


def prepare(scored=None, raw=()):
    metadata = {"analysis": "saved evidence evaluation only; no OCR, matching or baseline rerun"}
    if scored is None:
        saved = json.loads((ROOT / "app/src/debug/assets/phone-corpus-v1/saved-production-v2.json").read_text())
        predictions = [{"image": image, "attempt": 1, "status": "complete", "pipelines": [
            {"pipeline": PIPELINE, "status": "complete", "fields": fields}]} for image, fields in saved["predictions"].items()]
        metadata["fixture_origin"] = "bundled saved v2 values; no stage evidence, NOT a measured latest Lab run"
        expected = dict(complete="34/160", one_error="43", multi_error="83", producer_changed=0, wine_changed=0, producer_invoked=0, wine_invoked=0)
    else:
        scored = Path(scored)
        with scored.open(encoding="utf-8-sig", newline="") as stream:
            scores = list(csv.DictReader(stream))
        selected = [r for r in scores if r["row_type"] == "field" and r["pipeline"] == PIPELINE]
        if len(selected) != 1008 or len({(r["image"], r["field"]) for r in selected}) != 1008:
            raise ValueError("Expected 168 distinct latest Lab six-field predictions")
        if len({r["run_id"] for r in selected}) != 1:
            raise ValueError("Mixed scored run IDs")
        groups = defaultdict(list)
        schema = None
        for source in map(Path, raw):
            with source.open(encoding="utf-8-sig", newline="") as stream:
                reader = csv.DictReader(stream)
                if schema is not None and reader.fieldnames != schema:
                    raise ValueError("Raw CSV part schemas differ")
                schema = reader.fieldnames
                for row in reader:
                    groups[(row["image"], row["attempt"])].append(row)
            metadata[source.name + "_sha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
        metadata["source_scored_csv"] = scored.name
        metadata["source_scored_sha256"] = hashlib.sha256(scored.read_bytes()).hexdigest()
        metadata["source_build"] = selected[0]["build"]
        metadata["source_run_id"] = selected[0]["run_id"]
        expected_summary = next(r for r in scores if r["row_type"] == "summary" and r["Pipeline"] == PIPELINE)
        expected = dict(complete=expected_summary["Complete"], one_error=expected_summary["One error"], multi_error=expected_summary["2+ errors"],
                        producer_changed=0, wine_changed=0, producer_invoked=0, wine_invoked=0)
        fields_by_image = defaultdict(dict)
        for row in selected:
            fields_by_image[(row["image"], row["attempt"])][row["field"]] = row["recognized"]
        predictions = []
        for (image, attempt), fields in sorted(fields_by_image.items()):
            rows = groups[(image, attempt)]
            if {r["run_id"] for r in rows} != {selected[0]["run_id"]}:
                raise ValueError("Raw/scored run mismatch or missing raw photo")
            summaries = [r for r in rows if r["row_type"] == "image_summary"]
            pipelines = [r for r in rows if r["row_type"] == "pipelines" and r["pipeline"] == PIPELINE]
            if len(summaries) != 1 or len(pipelines) != 1:
                raise ValueError("Missing/duplicate raw image or pipeline summary")
            if json.loads(pipelines[0]["fields"]) != fields:
                raise ValueError("Raw pipeline fields differ from displayed/exported scored fields")
            # Blank before-values are real evidence of fills, not absent diagnostic keys.
            keep_blank = {"producer_before_gazetteer", "wine_before_gazetteer", "producer", "wine_name"}
            trace = [{k: v for k, v in r.items() if (v or (r["stage"] == "spatial_evidence_summary" and k in keep_blank))
                      and k not in {"build", "mode", "run_id", "image", "attempt", "row_type"}}
                     for r in rows if r["row_type"] == "trace"]
            final = next(r for r in trace if r.get("stage") == "spatial_evidence_summary")
            for kind, before, after in [("producer", "producer_before_gazetteer", "producer"), ("wine", "wine_before_gazetteer", "wine_name")]:
                expected[kind + "_invoked"] += final.get(kind + "_gazetteer_status", "unavailable") != "unavailable"
                expected[kind + "_changed"] += final.get(before, "") != final.get(after, "")
            predictions.append(dict(image=image, attempt=int(attempt), status=summaries[0]["status"],
                                    engine_incomplete=summaries[0]["engine_incomplete"] == "true", trace=trace,
                                    pipelines=[dict(pipeline=PIPELINE, status=pipelines[0]["status"], fields=fields)]))
    return dict(metadata=metadata, expected=expected, predictions=predictions)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--scored", type=Path)
    parser.add_argument("--raw", type=Path, action="append", default=[])
    args = parser.parse_args()
    if bool(args.scored) != bool(args.raw):
        parser.error("Supply both --scored and every --raw part, or neither")
    args.destination.write_text(json.dumps(prepare(args.scored, args.raw), ensure_ascii=False))
