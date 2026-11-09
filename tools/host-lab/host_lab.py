#!/usr/bin/env python3
"""Prepare/score driver for the host Lab replay harness (tools/run-host-lab.sh).

prepare: packaged cached-OCR asset -> runner input TSV (base64 row maps,
         mirroring WineAppLabelsOcrCache journal rows; raw_text scrubbing is
         applied Kotlin-side exactly as the on-device cache load does).
score:   runner output TSV -> scores against the packaged frozen references
         (same outcome rules as PhoneCorpusMath), with an optional per-label
         diff against a previous runner output (gains/losses, not just totals).
"""
import argparse
import base64
import gzip
import json
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIELDS = ["producer", "wine_name", "country", "region", "subregion", "vintage"]
# Phone baseline from the first compact-JSON diagnostic (a709ad6, cached OCR):
# the parity gate a host run of the same module set must reproduce.
# Lab v6 floor: the Prod v5.0 checkpoint (integrated modules) score. The historical
# Lab v5 phone-baseline parity (53/111) was proven and phone-verified in Oct 2026;
# see docs/lab-v5-124-diagnostic-a709ad6.md Sections 7-10.
PARITY = {"complete": 81, "at_most_one": 115, "eligible": 121}


def b64(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def prepare(cache_path: Path, out_path: Path):
    count = 0
    with gzip.open(cache_path, "rt", encoding="utf-8") as src, out_path.open("w", encoding="utf-8") as out:
        for line in src:
            entry = json.loads(line)
            out.write(f"IMAGE\t{entry['image']}\n")
            for row in entry["rows"]:
                pairs = []
                for key, value in row.items():
                    pairs += [b64(str(key)), b64(str(value))]
                out.write("ROW\t" + str(len(row)) + "".join("\t" + p for p in pairs) + "\n")
            count += 1
    print(f"prepared {count} images -> {out_path}")


def refs(scoring_path: Path, out_path: Path):
    """Oracle inputs for the GLiNER-lane host replay: one line per accepted reference value.

    The lane replay matches these strings to word spans, so it is told only what a perfect span
    labeller would have been asked for: accepted producer, wine name and place values.
    """
    scoring = json.loads(scoring_path.read_text(encoding="utf-8"))
    count = 0
    with out_path.open("w", encoding="utf-8") as out:
        for image in scoring["images"]:
            for field, target in image["fields"].items():
                if target["status"] != "expected_value":
                    continue
                for value in target["accepted"]:
                    if not value.strip():
                        continue
                    out.write("\t".join(b64(part) for part in (image["image"], field, "expected", value)) + "\n")
                    count += 1
    print(f"prepared {count} oracle reference values -> {out_path}")


def normalize(value: str) -> str:
    text = unicodedata.normalize("NFKD", str(value).casefold().replace("&", " et "))
    return "".join(c for c in text if c.isalnum() and not unicodedata.combining(c))


def outcome(target, value):
    if target["status"] == "unresolved":
        return "unscored_blank" if not value.strip() else "unscored_filled"
    if target["status"] == "expected_blank":
        return "correct_blank" if not value.strip() else "unsupported_fill"
    if not value.strip():
        return "missing_value"
    return "correct_value" if normalize(value) in {normalize(a) for a in target["accepted"]} else "wrong_value"


def correct(o):
    return o in ("correct_value", "correct_blank")


def read_output(path: Path):
    results = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        values = [base64.b64decode(p).decode("utf-8") for p in parts[2:]]
        results[parts[0]] = dict(zip(FIELDS, values), _status=parts[1])
    return results


def score(out_path: Path, report_path: Path, baseline_path: Path | None):
    scoring = json.loads((ROOT / "app/src/main/assets/wine-app-labels-v1/scoring.json").read_text())
    refs = {im["image"]: im for im in scoring["images"]}
    results = read_output(out_path)
    if set(results) != set(refs):
        print(f"WARNING: output covers {len(results)} images, references {len(refs)}", file=sys.stderr)
    per_field = {f: Counter() for f in FIELDS}
    per_image = {}
    for image, ref in refs.items():
        got = results.get(image, {})
        outs = {}
        for f in FIELDS:
            o = outcome(ref["fields"][f], got.get(f, ""))
            outs[f] = o
            per_field[f][o] += 1
        per_image[image] = outs
    eligible = {i: o for i, o in per_image.items()
                if not any(v.startswith("unscored") for v in o.values())}
    dist = Counter(sum(1 for v in o.values() if not correct(v)) for o in eligible.values())
    complete = dist.get(0, 0)
    at_most_one = complete + dist.get(1, 0)
    lines = ["# Host Lab replay score", "",
             f"Complete (6/6): **{complete}/{len(eligible)}** · ≤1 error: **{at_most_one}/{len(eligible)}** · "
             f"2 errors: {dist.get(2, 0)} · 3+: {sum(v for k, v in dist.items() if k >= 3)}", "",
             "| Field | Correct | Wrong | Missing | Unsupported | Accuracy |",
             "|---|---:|---:|---:|---:|---:|"]
    for f in FIELDS:
        c = per_field[f]
        corr = c["correct_value"] + c["correct_blank"]
        scored = corr + c["wrong_value"] + c["missing_value"] + c["unsupported_fill"]
        lines.append(f"| {f} | {corr} | {c['wrong_value']} | {c['missing_value']} | "
                     f"{c['unsupported_fill']} | {corr}/{scored} = {100 * corr / scored:.1f}% |")
    parity_ok = complete == PARITY["complete"] and at_most_one == PARITY["at_most_one"]
    lines += ["", f"Parity gate vs Lab v7 integrated floor (complete {PARITY['complete']}, "
                  f"≤1-error {PARITY['at_most_one']}): **{'PASS' if parity_ok else 'FAIL'}**"]
    if baseline_path:
        base = read_output(baseline_path)
        bf = {}
        for image, ref in refs.items():
            got = base.get(image, {})
            bf[image] = {f: outcome(ref["fields"][f], got.get(f, "")) for f in FIELDS}
        gains, losses = [], []
        for image in refs:
            for f in FIELDS:
                now, before = per_image[image][f], bf[image][f]
                if correct(now) and not correct(before):
                    gains.append((refs[image]["review_number"], f))
                elif correct(before) and not correct(now):
                    losses.append((refs[image]["review_number"], f))
        lines += ["", f"Vs baseline output: **+{len(gains)} gains / -{len(losses)} losses (net {len(gains) - len(losses):+})**", "",
                  "Gains: " + (", ".join(f"#{n} {f}" for n, f in sorted(gains)) or "none"), "",
                  "Losses: " + (", ".join(f"#{n} {f}" for n, f in sorted(losses)) or "none")]
    report = "\n".join(lines) + "\n"
    report_path.write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--cache", type=Path,
                      default=ROOT / "app/src/main/assets/wine-app-labels-v1/ocr-raw-cache-v1.jsonl.gz")
    prep.add_argument("--output", type=Path, required=True)
    ref = sub.add_parser("refs")
    ref.add_argument("--scoring", type=Path,
                     default=ROOT / "app/src/main/assets/wine-app-labels-v1/scoring.json")
    ref.add_argument("--output", type=Path, required=True)
    sco = sub.add_parser("score")
    sco.add_argument("output", type=Path)
    sco.add_argument("--report", type=Path, required=True)
    sco.add_argument("--baseline-output", type=Path)
    args = parser.parse_args()
    if args.cmd == "prepare":
        prepare(args.cache, args.output)
    elif args.cmd == "refs":
        refs(args.scoring, args.output)
    else:
        score(args.output, args.report, args.baseline_output)
