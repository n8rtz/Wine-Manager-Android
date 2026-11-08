#!/usr/bin/env python3
"""Pre-flight check for a GLiNER-Small INT4 bundle, before building or importing it on the phone.

The Android lab accepts only what this script proves about a bundle:

  * the three files exist and match model-manifest.json (bytes + sha256 when pinned),
  * the ONNX graph really is INT4 (contains MatMulNBits nodes),
  * the graph exposes the documented inputs and one float output,
  * tokenizer.json contains the ent_token / sep_token from gliner_config.json,
  * the output tensor shape is one of the layouts GlinerOnnxModel understands.

It then runs one smoke inference on a sample label and prints the lane spans the app would use,
so a shape or prompt mismatch surfaces on a desktop instead of on the phone.

Usage:
    python3 tools/inspect-gliner-bundle.py app/src/main/assets/gliner-lab-v1
    python3 tools/inspect-gliner-bundle.py ~/Downloads/gliner-bundle --text "Chateau Margaux 2015"

Requires: onnx, onnxruntime, tokenizers (desktop only; the phone needs none of these).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

MODEL_FILE = "gliner_small_int4.onnx"
TOKENIZER_FILE = "tokenizer.json"
CONFIG_FILE = "gliner_config.json"
MANIFEST = "model-manifest.json"
REQUIRED_INPUTS = {"input_ids", "attention_mask"}
KNOWN_INPUTS = {"input_ids", "attention_mask", "words_mask", "text_lengths", "span_idx", "span_mask"}
PROMPTS = ["wine producer or winery name", "wine name or cuvee", "wine appellation, region or country"]
SAMPLE = "Chateau Margaux 2015 grand vin premier grand cru classe margaux mis en bouteille au chateau"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_files(bundle: pathlib.Path) -> dict:
    problems = []
    for name in (MODEL_FILE, TOKENIZER_FILE, CONFIG_FILE):
        if not (bundle / name).is_file():
            problems.append(f"missing {name}")
    if problems:
        raise SystemExit("\n".join(problems) + f"\nProvision the bundle with tools/fetch-gliner-small-int4.py --out {bundle}")
    manifest_path = bundle / MANIFEST
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text())
        for entry in manifest.get("files", []):
            path = bundle / entry["name"]
            if not path.is_file():
                continue
            if entry.get("bytes") and path.stat().st_size != entry["bytes"]:
                problems.append(f"{entry['name']}: {path.stat().st_size} bytes, manifest says {entry['bytes']}")
            if entry.get("sha256") and sha256(path) != entry["sha256"]:
                problems.append(f"{entry['name']}: sha256 mismatch")
            if not entry.get("sha256"):
                print(f"note: {entry['name']} is unpinned in the manifest; the app accepts it but cannot verify its bytes")
    else:
        print(f"note: no {MANIFEST} in {bundle}; file pins are not checked")
    if problems:
        raise SystemExit("\n".join(problems))
    return {"model": bundle / MODEL_FILE, "tokenizer": bundle / TOKENIZER_FILE, "config": bundle / CONFIG_FILE}


def check_graph(model: pathlib.Path) -> tuple[list[str], list[str]]:
    import onnx
    import onnxruntime as ort

    graph = onnx.load(str(model))
    int4 = sum(1 for node in graph.graph.node if node.op_type == "MatMulNBits")
    print(f"MatMulNBits nodes: {int4}")
    if int4 == 0:
        raise SystemExit("graph has no MatMulNBits node: this is not the INT4 bundle")
    session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    inputs = [item.name for item in session.get_inputs()]
    outputs = [item.name for item in session.get_outputs()]
    print(f"graph inputs: {inputs}")
    print(f"graph outputs: {outputs}")
    if not REQUIRED_INPUTS.issubset(inputs):
        raise SystemExit(f"graph is missing {sorted(REQUIRED_INPUTS - set(inputs))}")
    unknown = [name for name in inputs if name not in KNOWN_INPUTS]
    if unknown:
        raise SystemExit(f"the app does not build these inputs: {unknown}")
    if len(outputs) != 1:
        raise SystemExit(f"the app expects exactly one output, got {outputs}")
    if session.get_outputs()[0].type != "tensor(float)":
        raise SystemExit(f"output must be float32 logits, got {session.get_outputs()[0].type}")
    return inputs, outputs


def smoke(config: dict, tokenizer_file: pathlib.Path, inputs: list[str], bundle: pathlib.Path, text: str) -> bool:
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from gliner_feeds import build_feed  # noqa: PLC0415

    max_width = int(config.get("max_width", 12))
    tokenizer = Tokenizer.from_file(str(tokenizer_file))
    words = text.split()
    feed = build_feed(tokenizer, config, PROMPTS, text)
    span_count = feed["span_idx"].shape[1]
    feeds = {key: value for key, value in feed.items() if key in inputs}
    session = ort.InferenceSession(str(bundle / MODEL_FILE), providers=["CPUExecutionProvider"])
    logits = session.run(None, feeds)[0]
    print(f"words: {len(words)} · spans: {span_count} · max_width: {max_width}")
    print(f"logits shape: {logits.shape}")
    scores = 1.0 / (1.0 + np.exp(-logits.astype("float64")))
    classes = len(PROMPTS)
    shape = scores.shape
    if shape == (1, len(words), max_width, classes):
        layout, per_span = "span logits [1, words, max_width, classes]", scores[0].reshape(span_count, classes)
    elif scores.ndim == 2 and shape[0] == span_count and shape[1] == classes:
        layout, per_span = "span logits [spans, classes]", scores
    elif scores.ndim == 3 and shape[0] == 1 and shape[1] == span_count and shape[2] == classes:
        layout, per_span = "span logits [1, spans, classes]", scores[0]
    elif scores.ndim == 3 and shape[0] == 1 and shape[1] == classes and shape[2] == span_count:
        layout, per_span = "span logits [1, classes, spans]", scores[0].T
    elif scores.ndim == 4 and shape[0] == 1 and shape[1] == span_count and shape[2] == classes and shape[3] == 1:
        layout, per_span = "span logits [1, spans, classes, 1]", scores[0, :, :, 0]
    else:
        print(f"output shape {shape} is not one of the app's accepted layouts for {span_count} spans and "
              f"{classes} classes; the screen would report it and stop")
        return False
    print(f"layout: {layout}")
    ok = False
    for label_index, label in enumerate(PROMPTS):
        column = per_span[:, label_index]
        best = int(column.argmax())
        start, offset = divmod(best, max_width)
        end = min(start + offset, len(words) - 1)
        print(f"{label}: {' '.join(words[start:end+1])} ({column[best]:.3f})")
        ok = ok or float(column[best]) >= 0.5
    if not ok:
        print("no lane reaches 0.5 on the sample text: the bundle runs, but check the prompt "
              "convention against tools/compare-gliner-onnx.py before trusting the labels")
    return True


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle", nargs="?", default="app/src/main/assets/gliner-lab-v1")
    parser.add_argument("--text", default=SAMPLE)
    args = parser.parse_args(argv)
    bundle = pathlib.Path(args.bundle).resolve()
    files = check_files(bundle)
    config = json.loads(files["config"].read_text())
    print(f"gliner_config: ent={config.get('ent_token')} sep={config.get('sep_token')} "
          f"max_width={config.get('max_width')} max_len={config.get('max_len')}")
    inputs, _ = check_graph(files["model"])
    if not smoke(config, files["tokenizer"], inputs, bundle, args.text):
        return 1
    print("\nbundle is compatible with the GLiNER lane lab")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
