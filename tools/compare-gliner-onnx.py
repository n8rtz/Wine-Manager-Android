#!/usr/bin/env python3
"""Compare our GLiNER ONNX feeds against the ones the gliner library builds itself.

Desktop/CI only (needs the gliner package and network access to fetch the checkpoint). The point is
to stop guessing the input convention: this prints, for the same label text,

  * the predictions of the PyTorch model,
  * the predictions of the exported ONNX graph,
  * the exact feed tensors the library hands to the ONNX session,
  * the feed tensors this repository's builder produces for the same text.

If the two feeds differ, the repository is wrong and the diff shows where.

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


def show(title: str, feed: dict) -> None:
    import numpy as np

    print(f"\n== {title} ==")
    for key in sorted(feed):
        value = np.asarray(feed[key])
        flat = value.reshape(-1)
        head = ", ".join(str(int(item)) if np.issubdtype(flat.dtype, np.integer) else f"{float(item):.4g}"
                         for item in flat[:24])
        print(f"  {key}: shape={value.shape} dtype={flat.dtype} first={head}")


def library_feed(model) -> dict:
    import numpy as np
    import onnxruntime as ort

    captured: dict = {}
    original = ort.InferenceSession.run

    def spy(self, output_names, input_feed, *args, **kwargs):  # noqa: ANN001
        captured.setdefault("feed", {key: np.array(value) for key, value in input_feed.items()})
        return original(self, output_names, input_feed, *args, **kwargs)

    ort.InferenceSession.run = spy
    try:
        entities = model.predict_entities(TEXT, LABELS, threshold=0.2)
    finally:
        ort.InferenceSession.run = original
    print("\n== library ONNX predictions ==")
    for entity in entities:
        print(f"  {entity['label']!r}: {entity['text']!r} ({entity['score']:.3f})")
    return captured.get("feed", {})


def our_feed(bundle: pathlib.Path) -> dict:
    """The feed this repository builds, via the shared builder."""
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from gliner_feeds import build_feed, decode_prompt, load_bundle  # noqa: PLC0415

    tokenizer, config = load_bundle(bundle)
    feed = build_feed(tokenizer, config, LABELS, TEXT)
    print(f"\n== repository prompt ==\n  {decode_prompt(tokenizer, feed)[:200]}")
    return feed


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--onnx", required=True, help="exported ONNX graph to load")
    parser.add_argument("--bundle", default=None, help="folder with tokenizer.json + gliner_config.json")
    parser.add_argument("--model", default=MODEL)
    args = parser.parse_args(argv)

    from gliner import GLiNER

    torch_model = GLiNER.from_pretrained(args.model)
    print("== PyTorch predictions ==")
    for entity in torch_model.predict_entities(TEXT, LABELS, threshold=0.2):
        print(f"  {entity['label']!r}: {entity['text']!r} ({entity['score']:.3f})")

    onnx_model = GLiNER.from_pretrained(args.model, load_onnx_model=True, onnx_model_file=args.onnx)
    feed = library_feed(onnx_model)
    show("library ONNX feed", feed)
    from transformers import AutoTokenizer

    library_tokenizer = AutoTokenizer.from_pretrained(args.model, use_fast=True)
    print(f"\n== library prompt ==\n  {library_tokenizer.decode(feed['input_ids'][0].tolist())[:200]}")
    print(f"  <<ENT>> pieces: {library_tokenizer.tokenize('<<ENT>>')} "
          f"ids={library_tokenizer.encode('<<ENT>>', add_special_tokens=False)}")

    if args.bundle:
        show("repository feed", our_feed(pathlib.Path(args.bundle)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
