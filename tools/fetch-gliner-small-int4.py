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

Requirements (desktop, not CI). The 4-bit quantizer API is the one that ships in onnxruntime
1.20.x; newer runtimes moved it to matmul_nbits_quantizer (needs onnx_ir) and it will not import,
so pin the versions:
    python3 -m venv .venv-gliner && source .venv-gliner/bin/activate
    pip install "gliner[onnx]" "onnxruntime==1.20.1" "onnx==1.17.0" transformers torch

Examples:
    python3 tools/fetch-gliner-small-int4.py --out app/src/main/assets/gliner-lab-v1 --pin
    python3 tools/fetch-gliner-small-int4.py --device-dir ~/Downloads/gliner-bundle
    python3 tools/fetch-gliner-small-int4.py --out bundle --no-quantize-embeddings

Size: the DeBERTa-v3-small embedding table is a plain Gather of 128100x768 floats, roughly 394 MB
in float32. Quantizing the MatMuls alone leaves that table untouched, so the bundle stays around
400 MB. --quantize-embeddings (on by default) also block-quantizes that Gather to
GatherBlockQuantized, which is what brings the bundle into the ~60-110 MB range. It needs opset 21
(the export bumps it automatically) and ONNX Runtime >= 1.16, both satisfied by the app's pinned
com.microsoft.onnxruntime:onnxruntime-android:1.21.1.
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


def quantize_int4(fp32: pathlib.Path, int4: pathlib.Path, block_size: int,
                  quantize_embeddings: bool) -> pathlib.Path:
    import onnx

    try:
        from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer
    except ModuleNotFoundError as error:  # newer runtimes renamed the module
        raise SystemExit(
            "the 4-bit quantizer moved out of onnxruntime; install the pinned versions:\n"
            '    pip install "onnxruntime==1.20.1" "onnx==1.17.0"\n'
            f"(import failed: {error})"
        ) from error

    op_types = ("MatMul", "Gather") if quantize_embeddings else ("MatMul",)
    print(f"[2/5] INT4 weight-only quantization ({', '.join(op_types)}, block {block_size})")
    # Pass the ModelProto, not an ONNXModel: the quantizer wraps it again and 1.20.1 then trips
    # over its own wrapper ("'method' object is not iterable" in set_opset_import).
    quantizer = MatMul4BitsQuantizer(onnx.load(str(fp32)), block_size=block_size,
                                     is_symmetric=True, accuracy_level=4,
                                     op_types_to_quantize=op_types)
    quantizer.process()
    quantizer.model.save_model_to_file(str(int4), use_external_data_format=False)
    # onnx.checker rejects this graph after quantization: it reports "nodes must be topologically
    # sorted" for values used inside an If branch, which is a strictness bug in the checker, not a
    # broken graph. ONNX Runtime is the real gate, so the smoke run below decides.
    try:
        onnx.checker.check_model(quantizer.model.model)
    except Exception as error:  # noqa: BLE001 - any checker complaint is downgraded to a warning
        print(f"[2/5] onnx.checker warning (not fatal): {str(error).splitlines()[0]}")
    megabytes = int4.stat().st_size / 1e6
    print(f"[2/5] wrote {int4} ({megabytes:.1f} MB)")
    if megabytes > 90:
        print(f"       over 90 MB: split it before uploading, e.g. split -b 90m {int4.name} "
              f"{int4.name}.part-")
    return int4


def verify_int4(int4: pathlib.Path) -> int:
    import onnx

    graph = onnx.load(str(int4))
    count = sum(1 for node in graph.graph.node if node.op_type == "MatMulNBits")
    gathers = sum(1 for node in graph.graph.node if node.op_type == "GatherBlockQuantized")
    print(f"[3/5] MatMulNBits nodes: {count}, GatherBlockQuantized (embeddings): {gathers}")
    if count == 0:
        raise SystemExit("INT4 export contains no MatMulNBits node; refusing to pin a non-INT4 bundle")
    return count


def smoke_inference(int4: pathlib.Path, tokenizer_file: pathlib.Path, config_file: pathlib.Path,
                    reference: pathlib.Path | None = None) -> None:
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from gliner_feeds import build_feed, decode_prompt, prompt_words  # noqa: PLC0415

    config = json.loads(config_file.read_text())
    max_width = int(config.get("max_width", 12))
    tokenizer = Tokenizer.from_file(str(tokenizer_file))
    words = SMOKE_TEXT.split()
    feed = build_feed(tokenizer, config, PROMPTS, SMOKE_TEXT)
    print(f"[4/5] prompt words: {' '.join(prompt_words(PROMPTS, config))}")
    print(f"[4/5] decoded prompt+text: {decode_prompt(tokenizer, feed)[:160]}")

    def run(path: pathlib.Path):
        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        available = {item.name for item in session.get_inputs()}
        feeds = {key: value for key, value in feed.items() if key in available}
        return session.run(None, feeds)[0]

    logits = run(int4)
    print(f"[4/5] smoke run ok: output={logits.shape} words={len(words)} max_width={max_width}")
    if reference is not None and reference.is_file():
        reference_logits = run(reference)
        print(f"[4/5] un-quantized reference: output={reference_logits.shape} "
              f"max |int4 - fp32| = {float(np.abs(reference_logits - logits).max()):.4f}")
    scores = 1.0 / (1.0 + np.exp(-logits.astype("float64")))
    per_span = scores.reshape(-1, scores.shape[-1])
    for label_index, label in enumerate(PROMPTS):
        best = int(per_span[:, label_index].argmax())
        start, end = best // max_width, min(best // max_width + best % max_width, len(words) - 1)
        print(f"       {label}: {' '.join(words[start:end+1])} "
              f"({per_span[best, label_index]:.3f})")


def pin_manifest(out: pathlib.Path, files: dict[str, pathlib.Path], urls: dict[str, str]) -> None:
    manifest_path = out / MANIFEST_NAME
    if not manifest_path.is_file():
        # --out usually points at a scratch folder for the upload; borrow the repo template so the
        # pinned manifest travels with the bundle.
        template = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/gliner-lab-v1" / MANIFEST_NAME
        if not template.is_file():
            raise SystemExit(f"{manifest_path} is missing and no template at {template}")
        shutil.copyfile(template, manifest_path)
        print(f"[5/5] seeded {manifest_path.name} from the asset template")
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
    parser.add_argument("--quantize-embeddings", dest="quantize_embeddings", action="store_true",
                        default=True,
                        help="also block-quantize the embedding Gather (default; ~400 MB -> ~90 MB)")
    parser.add_argument("--no-quantize-embeddings", dest="quantize_embeddings", action="store_false",
                        help="leave the float32 embedding table alone (bigger, slightly safer)")
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
    quantize_int4(fp32, int4, args.block_size, args.quantize_embeddings)
    verify_int4(int4)
    for name in (TOKENIZER_FILE, CONFIG_FILE):
        candidate = work / name
        if not candidate.is_file():
            candidate = fp32.parent / name
        if not candidate.is_file():
            raise SystemExit(f"export did not write {name}; cannot build a bundle without it")
        shutil.copyfile(candidate, out / name)
    smoke_inference(int4, out / TOKENIZER_FILE, out / CONFIG_FILE, reference=fp32)
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
