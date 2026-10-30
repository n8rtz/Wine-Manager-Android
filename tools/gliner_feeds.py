#!/usr/bin/env python3
"""Build the exact tensors the GLiNER ONNX graph expects.

This mirrors what the gliner library itself feeds its ONNX session (confirmed by capturing the
library's `InferenceSession.run` input on this repository's export):

  * the prompt is a list of **words**: ``<<ENT>> label words ... <<ENT>> ... <<SEP>>``, tokenized
    together with the text words as pretokenized words, so ``<<ENT>>`` becomes its SentencePiece
    pieces (2569, 4052, 20773, 1504, 1504 for deberta-v3-small) and *not* the added-token id,
  * ``[CLS]``/``[SEP]`` come from the tokenizer's own special tokens, once, around the whole
    sequence — never around each label,
  * ``words_mask`` is 1-based over the text words only, set on the first subtoken of each word and
    0 on the prompt, the special tokens and the remaining subtokens,
  * ``text_lengths`` is the number of text words,
  * ``span_idx`` is ``(words * max_width, 2)``: every start with offsets ``0 .. max_width-1``,
    ``end = start + offset``, and spans that run past the last word are kept but masked out.
"""
from __future__ import annotations

import json
import pathlib

MODEL_FILE = "gliner_small_int4.onnx"
TOKENIZER_FILE = "tokenizer.json"
CONFIG_FILE = "gliner_config.json"


def prompt_words(labels: list[str], config: dict) -> list[str]:
    ent = config.get("ent_token", "<<ENT>>")
    sep = config.get("sep_token", "<<SEP>>")
    words: list[str] = []
    for label in labels:
        words.append(ent)
        words.extend(label.split())
    words.append(sep)
    return words


def build_feed(tokenizer, config: dict, labels: list[str], text: str) -> dict:
    import numpy as np

    max_width = int(config.get("max_width", 12))
    prompt = prompt_words(labels, config)
    text_words = text.split()
    encoding = tokenizer.encode(prompt + text_words, is_pretokenized=True)
    ids = list(encoding.ids)
    words_mask = [0] * len(ids)
    seen: set[int] = set()
    for position, word in enumerate(encoding.word_ids):
        if word is None or word < len(prompt):
            continue
        if word in seen:
            continue
        seen.add(word)
        words_mask[position] = word - len(prompt) + 1
    spans = [(start, start + offset)
             for start in range(len(text_words))
             for offset in range(max_width)]
    span_mask = [end < len(text_words) for _, end in spans]
    return {
        "input_ids": np.array([ids], dtype=np.int64),
        "attention_mask": np.ones((1, len(ids)), dtype=np.int64),
        "words_mask": np.array([words_mask], dtype=np.int64),
        "text_lengths": np.array([[len(text_words)]], dtype=np.int64),
        "span_idx": np.array(spans, dtype=np.int64).reshape(1, -1, 2),
        "span_mask": np.array([span_mask], dtype=bool),
    }


def load_bundle(bundle: pathlib.Path):
    from tokenizers import Tokenizer

    config = json.loads((bundle / CONFIG_FILE).read_text())
    tokenizer = Tokenizer.from_file(str(bundle / TOKENIZER_FILE))
    return tokenizer, config


def decode_prompt(tokenizer, feed: dict) -> str:
    return tokenizer.decode(feed["input_ids"][0].tolist())
