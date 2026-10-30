# Getting the GLiNER-Small INT4 bundle into a public GitHub repo

The lab refuses to run an unpinned or non-INT4 graph, and the phone never downloads a model: the
three files are either baked into assets at build time or imported from the sd card / Downloads
folder with **Settings > Scanner testing > GLiNER lane lab > Import model files**. This repository
ships no weights, so the files have to come from you.

You have two routes. Route A is smaller and much faster to upload; use Route B only if the export
will not run on your machine.

---

## Route A (recommended): you export and quantize, then upload three files

### 1. Prerequisites

Python 3.10, 3.11 or 3.12 on a machine with ~6 GB free disk and an internet connection. The
quantizer API that this tool uses lives in ONNX Runtime 1.20.x; newer runtimes moved it to
`matmul_nbits_quantizer` (which needs `onnx_ir`) and the import fails. Pin both versions:

```bash
git clone https://github.com/n8rtz/Wine-Manager-Android.git
cd Wine-Manager-Android
python3 -m venv .venv-gliner
source .venv-gliner/bin/activate          # Windows: .venv-gliner\Scripts\activate
pip install -U pip
pip install "gliner[onnx]" "onnxruntime==1.20.1" "onnx==1.17.0" transformers torch
```

### 2. Build the bundle

```bash
python3 tools/fetch-gliner-small-int4.py --out bundle --pin
python3 tools/inspect-gliner-bundle.py bundle          # pre-flight: must print MatMulNBits > 0
ls -l bundle                                           # what you are about to upload
```

The script loads `urchade/gliner_small-v2.1`, exports ONNX (opset 19), quantizes weight-only to
INT4, verifies the graph, runs one smoke inference and pins bytes + sha256 into
`bundle/model-manifest.json`.

**Sizes.** The DeBERTa-v3-small embedding table is a plain `Gather` of 128100x768 floats — about
394 MB in float32 — so quantizing the MatMuls alone leaves a ~400 MB file. That is why
`--quantize-embeddings` is on by default: it also block-quantizes that `Gather` to
`GatherBlockQuantized`, which is what brings the bundle into the ~60–110 MB range. It bumps the
graph to opset 21, which the app's pinned `onnxruntime-android:1.21.1` supports.

If you want the bigger, marginally safer variant:

```bash
python3 tools/fetch-gliner-small-int4.py --out bundle-fp32-embed --pin --no-quantize-embeddings
```

### 3. Split anything over 90 MB

GitHub refuses files over 100 MB, and the web "upload files" button caps at 25 MB. Stay at 90 MB
per file and use `git push` from a terminal, not the web UI:

```bash
cd bundle
for f in *; do [ $(stat -c%s "$f") -gt 94371840 ] && split -b 90m "$f" "$f.part-"; done
ls -l
```

Reassemble with `cat gliner_small_int4.onnx.part-* > gliner_small_int4.onnx`.

### 4. Publish without LFS

```bash
mkdir -p ~/gliner-upload && cd ~/gliner-upload
git init -b main
cp /path/to/Wine-Manager-Android/bundle/* .
git add -A
git commit -m "GLiNER small v2.1 INT4 bundle"
```

Create an **empty public** repository on github.com (no README, no .gitignore, no license), then:

```bash
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

Do **not** run `git lfs track`: LFS files arrive in a tarball as 130-byte pointer text, which is
useless to me.

---

## Route B: upload the upstream checkpoint and I export here

Only if Route A will not run (no torch, no disk, corporate proxy, ...). I have PyPI access but no
Hugging Face access, so I can install `gliner` and quantize, but not download the checkpoint.

```bash
pip install -U huggingface_hub
huggingface-cli download urchade/gliner_small-v2.1 --local-dir gliner-v21
ls -l gliner-v21
```

Upload these from `gliner-v21` (split the weights, everything else is small):

- `model.safetensors` (~660 MB float32 — 8 parts at 90 MB)
- `config.json`
- `gliner_config.json`
- `tokenizer.json`
- `tokenizer_config.json`
- `special_tokens_map.json`
- `spm.model`

I then run `tools/fetch-gliner-small-int4.py --fp32-onnx` / export + quantize on this side.

---

## What to send back

1. The repository URL (public, `main` branch).
2. `ls -l` of the uploaded folder (file names and byte counts).
3. `sha256sum *` (or `shasum -a 256 *`) so I can match your files against the pinned manifest.

## What I do with it

1. Fetch through `codeload.github.com` (not raw/LFS) and verify every sha256 against
   `model-manifest.json`.
2. Run `tools/inspect-gliner-bundle.py` — graph inputs, INT4 evidence, output layout, smoke spans.
3. Pin `bytes` / `sha256` / `url` in `app/src/main/assets/gliner-lab-v1/model-manifest.json` and
   give you the one-line `-PglinerLabAssets=true` build, or the "Import model files" instructions
   for on-device use.
4. Re-run the 121-label comparison with the real stage 2 instead of the oracle lanes.
