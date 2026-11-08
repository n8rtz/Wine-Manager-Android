#!/usr/bin/env python3
"""Find the input convention the exported GLiNER graph actually needs.

Desktop/CI only (needs the gliner package and network access to fetch the checkpoint).

The PyTorch model is the authority: it is what the export was traced from. This script captures the
exact tensors its forward pass receives, runs the exported ONNX graph on those same tensors and
compares the answers. If the ONNX graph reproduces the PyTorch scores, the export is sound and only
the feed convention matters; the captured tensors then show what that convention is.

It prints, for one label text:

  * PyTorch predictions,
  * ONNX predictions from the captured PyTorch feed,
  * ONNX predictions from this repository's own builder (tools/gliner_feeds.py),
  * the captured feed tensors, so the Kotlin side can be written against them.

Usage:
    python3 tools/compare-gliner-onnx.py --onnx /tmp/export/gliner_small_fp32.onnx
    python3 tools/compare-gliner-onnx.py --onnx bundle/gliner_small_int4.onnx --bundle bundle
"""
from __future__ import annotations

import argparse
import pathlib
import sys

TEXT = ("Chateau Margaux 2015 grand vin premier grand cru classe margaux "
        "mis en bouteille au chateau")
LABELS = ["wine producer or winery name", "wine name or cuvee",
          "wine appellation, region or country"]
MODEL = "urchade/gliner_small-v2.1"


def describe(feed: dict) -> None:
    import numpy as np

    for key in sorted(feed):
        value = np.asarray(feed[key])
        flat = value.reshape(-1)
        head = ", ".join(str(int(item)) if np.issubdtype(flat.dtype, np.integer) else f"{float(item):.4g}"
                         for item in flat[:20])
        print(f"  {key}: shape={value.shape} dtype={flat.dtype} first={head}")


def capture_torch_feed(model, text: str, labels: list[str]) -> dict:
    """Record the kwargs the core GLiNER module receives during a normal PyTorch prediction."""
    import numpy as np
    import torch

    captured: dict = {}
    core = model.model
    original = core.forward

    def spy(*args, **kwargs):  # noqa: ANN002, ANN003
        for source in (kwargs, {"positional": args}):
            for key, value in source.items():
                if isinstance(value, torch.Tensor) and key not in captured:
                    captured[key] = value.detach().clone()
        return original(*args, **kwargs)

    core.forward = spy
    try:
        entities = model.predict_entities(text, labels, threshold=0.2)
    finally:
        core.forward = original
    print("== PyTorch predictions ==")
    for entity in entities:
        print(f"  {entity['label']!r}: {entity['text']!r} ({entity['score']:.3f})")
    wanted = {"input_ids", "attention_mask", "words_mask", "text_lengths", "span_idx", "span_mask"}
    return {key: np.array(value.cpu()) for key, value in captured.items() if key in wanted}


def run_onnx(path: pathlib.Path, feed: dict):
    import numpy as np
    import onnxruntime as ort

    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    available = {item.name for item in session.get_inputs()}
    missing = available - set(feed)
    feeds = {key: value for key, value in feed.items() if key in available}
    logits = session.run(None, feeds)[0]
    scores = 1.0 / (1.0 + np.exp(-logits.astype("float64")))
    per_span = scores.reshape(-1, scores.shape[-1])
    print(f"  output {logits.shape} · fed {sorted(feeds)}"
          + (f" · MISSING {sorted(missing)}" if missing else ""))
    for index, label in enumerate(LABELS):
        best = int(per_span[:, index].argmax())
        print(f"    {label}: span #{best} ({per_span[best, index]:.3f})")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--onnx", required=True)
    parser.add_argument("--bundle", default=None, help="folder with tokenizer.json + gliner_config.json")
    parser.add_argument("--model", default=MODEL)
    args = parser.parse_args(argv)

    from gliner import GLiNER

    model = GLiNER.from_pretrained(args.model)
    feed = capture_torch_feed(model, TEXT, LABELS)
    print("\n== captured PyTorch feed ==")
    describe(feed)

    print("\n== ONNX on the captured PyTorch feed ==")
    run_onnx(pathlib.Path(args.onnx), feed)

    if args.bundle:
        sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
        from gliner_feeds import build_feed, decode_prompt, load_bundle  # noqa: PLC0415

        tokenizer, config = load_bundle(pathlib.Path(args.bundle))
        ours = build_feed(tokenizer, config, LABELS, TEXT)
        print("\n== this repository's feed ==")
        describe(ours)
        print(f"  decoded: {decode_prompt(tokenizer, ours)[:180]}")
        print("\n== ONNX on this repository's feed ==")
        run_onnx(pathlib.Path(args.onnx), ours)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
