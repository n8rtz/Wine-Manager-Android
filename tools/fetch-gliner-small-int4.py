#!/usr/bin/env python3
"""Build the GLiNER-Small INT4 bundle used by Settings > Scanner testing > GLiNER lane lab.

This is a developer/desktop tool. The phone never downloads a model: you either
  * run this with --out and build with -PglinerLabAssets=true (bundle baked into assets), or
  * run this with --device-dir and copy the three files to the phone, then use the screen's
    "Import model files" picker.

What it does
  1. loads urchade/gliner_small-v2.1 (or --model) through the upstream gliner package,
  2. exports the ONNX graph (opset 19) with GLiNER's own export_to_onnx(),
  3. quantizes the matmul weights to INT4, weight-only (MatMulNBits, block 32, symmetric),
  4. checks the INT4 graph still contains MatMulNBits nodes and runs one smoke inference,
  5. copies tokenizer.json + gliner_config.json and (with --pin) writes bytes/sha256 into
     app/src/main/assets/gliner-lab-v1/model-manifest.json.

Requirements (desktop, not CI):
    pip install "gliner[onnx]" onnx onnxruntime transformers torch

Example:
    python3 tools/fetch-gliner-small-int4.py --out app/src/main/assets/gliner-lab-v1 --pin
    python3 tools/fetch-gliner-small-int4.py --device-dir ~/Downloads/gliner-bundle
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import sys

MANIFEST_NAME = "model-manifest.json"
MODEL_FILE = "gliner_small_int4.onnx"
TOKENIZER_FILE = "tokenizer.json"
CONFIG_FILE = "gliner_config.json"
PROMPTS = ["wine producer or winery name", "wine name or cuvee", "wine appellation, region or country"]
SMOKE_TEXT = ("Chateau Margaux 2015 grand vin premier grand cru classe margaux "
              "mis en bouteille au chateau")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def export_fp32(model_name: str, work: pathlib.Path, opset: int) -> pathlib.Path:
    from gliner import GLiNER  # noqa: PLC0415 - desktop-only dependency

    print(f"[1/5] loading {model_name}")
    model = GLiNER.from_pretrained(model_name)
    target = work / "gliner_small_fp32.onnx"
    paths = model.export_to_onnx(save_dir=str(work), onnx_filename=target.name, opset=opset, quantize=False)
    exported = pathlib.Path(paths["onnx_path"])
    print(f"[1/5] exported {exported} ({exported.stat().st_size/1e6:.1f} MB)")
    return exported


def quantize_int4(fp32: pathlib.Path, int4: pathlib.Path, block_size: int) -> pathlib.Path:
    import onnx
    from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer
    from onnxruntime.quantization.onnx_quantizer import ONNXModel

    print(f"[2/5] INT4 weight-only quantization (MatMulNBits, block {block_size})")
    model = ONNXModel(onnx.load(str(fp32)))
    quantizer = MatMul4BitsQuantizer(model, block_size=block_size, is_symmetric=True, accuracy_level=4)
    quantizer.process()
    quantizer.model.save_model_to_file(str(int4), use_external_data_format=False)
    onnx.checker.check_model(onnx.load(str(int4)))
    print(f"[2/5] wrote {int4} ({int4.stat().st_size/1e6:.1f} MB)")
    return int4


def verify_int4(int4: pathlib.Path) -> int:
    import onnx

    graph = onnx.load(str(int4))
    count = sum(1 for node in graph.graph.node if node.op_type == "MatMulNBits")
    print(f"[3/5] MatMulNBits nodes: {count}")
    if count == 0:
        raise SystemExit("INT4 export contains no MatMulNBits node; refusing to pin a non-INT4 bundle")
    return count


def smoke_inference(int4: pathlib.Path, tokenizer_file: pathlib.Path, config_file: pathlib.Path) -> None:
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer

    config = json.loads(config_file.read_text())
    ent_token = config.get("ent_token", "<<")
    sep_token = config.get("sep_token", ">>")
    max_width = int(config.get("max_width", 12))
    tokenizer = Tokenizer.from_file(str(tokenizer_file))
    ent_id = tokenizer.token_to_id(ent_token)
    sep_id = tokenizer.token_to_id(sep_token)
    if ent_id is None or sep_id is None:
        raise SystemExit(f"tokenizer.json has no {ent_token!r}/{sep_token!r} token for this config")

    words = SMOKE_TEXT.split()
    ids, words_mask = [], []
    for label in PROMPTS:
        ids.append(ent_id)
        words_mask.append(0)
        for piece in tokenizer.encode(label, is_pretokenized=False).ids:
            ids.append(piece)
            words_mask.append(0)
    ids.append(sep_id)
    words_mask.append(0)
    for index, word in enumerate(words):
        for sub, piece in enumerate(tokenizer.encode(word, is_pretokenized=False).ids):
            ids.append(piece)
            words_mask.append(index + 1 if sub == 0 else 0)
    spans = [(start, start + width) for start in range(len(words))
             for width in range(max_width) if start + width < len(words)]
    feeds = {
        "input_ids": np.array([ids], dtype=np.int64),
        "attention_mask": np.ones((1, len(ids)), dtype=np.int64),
        "words_mask": np.array([words_mask], dtype=np.int64),
        "text_lengths": np.array([[len(words)]], dtype=np.int64),
        "span_idx": np.array([spans], dtype=np.int64),
        "span_mask": np.ones((1, len(spans)), dtype=bool),
    }
    session = ort.InferenceSession(str(int4), providers=["CPUExecutionProvider"])
    available = {item.name for item in session.get_inputs()}
    feeds = {key: value for key, value in feeds.items() if key in available}
    logits = session.run(None, feeds)[0]
    print(f"[4/5] smoke run ok: inputs={sorted(available)} output={logits.shape}")
    if logits.ndim == 3 and logits.shape[1] == len(spans):
        scores = 1.0 / (1.0 + np.exp(-logits[0]))
        for label_index, label in enumerate(PROMPTS):
            best = scores[:, label_index].argmax()
            if scores[best, label_index] >= 0.5:
                start, end = spans[best]
                print(f"       {label}: {' '.join(words[start:end+1])} ({scores[best, label_index]:.2f})")


def pin_manifest(out: pathlib.Path, files: dict[str, pathlib.Path], urls: dict[str, str]) -> None:
    manifest_path = out / MANIFEST_NAME
    manifest = json.loads(manifest_path.read_text())
    for entry in manifest["files"]:
        path = files.get(entry["name"])
        if path is None:
            continue
        entry["bytes"] = path.stat().st_size
        entry["sha256"] = sha256(path)
        if entry["name"] in urls and urls[entry["name"]]:
            entry["url"] = urls[entry["name"]]
    manifest["files"] = manifest["files"]
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print(f"[5/5] pinned {manifest_path}")
    for entry in manifest["files"]:
        print(f"       {entry['name']}: {entry['bytes']} bytes sha256={entry['sha256']}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default="urchade/gliner_small-v2.1")
    parser.add_argument("--out", default="app/src/main/assets/gliner-lab-v1",
                        help="bundle folder (defaults to the asset folder the app reads)")
    parser.add_argument("--device-dir", default=None, help="also copy the three files here for the import picker")
    parser.add_argument("--fp32-onnx", default=None, help="reuse an existing fp32 ONNX export instead of exporting")
    parser.add_argument("--opset", type=int, default=19)
    parser.add_argument("--block-size", type=int, default=32)
    parser.add_argument("--pin", action="store_true", help="write bytes/sha256 into model-manifest.json")
    parser.add_argument("--url-model", default="", help="optional download URL recorded in the pinned manifest")
    parser.add_argument("--url-tokenizer", default="")
    parser.add_argument("--url-config", default="")
    parser.add_argument("--work", default=None, help="scratch folder for the fp32 export")
    args = parser.parse_args(argv)

    out = pathlib.Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    work = pathlib.Path(args.work).resolve() if args.work else out / "_export"
    work.mkdir(parents=True, exist_ok=True)

    fp32 = pathlib.Path(args.fp32_onnx).resolve() if args.fp32_onnx else export_fp32(args.model, work, args.opset)
    int4 = out / MODEL_FILE
    quantize_int4(fp32, int4, args.block_size)
    verify_int4(int4)
    for name in (TOKENIZER_FILE, CONFIG_FILE):
        candidate = work / name
        if not candidate.is_file():
            candidate = fp32.parent / name
        if not candidate.is_file():
            raise SystemExit(f"export did not write {name}; cannot build a bundle without it")
        shutil.copyfile(candidate, out / name)
    smoke_inference(int4, out / TOKENIZER_FILE, out / CONFIG_FILE)
    files = {MODEL_FILE: int4, TOKENIZER_FILE: out / TOKENIZER_FILE, CONFIG_FILE: out / CONFIG_FILE}
    if args.pin:
        pin_manifest(out, files, {MODEL_FILE: args.url_model, TOKENIZER_FILE: args.url_tokenizer, CONFIG_FILE: args.url_config})
    else:
        print("[5/5] not pinned: add bytes/sha256 (or rerun with --pin) before -PglinerLabAssets=true")
    if args.device_dir:
        device = pathlib.Path(args.device_dir).resolve()
        device.mkdir(parents=True, exist_ok=True)
        for name in files:
            shutil.copyfile(out / name, device / name)
        print(f"copied the bundle to {device} for the on-phone import picker")
    print("\nNext: build with -PglinerLabAssets=true, or import the files on the device.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
