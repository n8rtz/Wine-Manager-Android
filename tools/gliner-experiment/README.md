# Automated GLiNER comparison on frozen OCR

This is a **test-only** GitHub Actions job, not an APK/model build or release. It starts on an experiment-code push to the current Arena branch; no phone scan or desktop operation is required. It pulls the already-released INT4 bundle into ephemeral runner storage, checks all three model/tokenizer/config SHA-256 hashes, then discards it with the runner. Its GitHub Actions job summary and check named **GLiNER saved-OCR comparison results** present aggregate scores; the result artifact also contains paired field-level changes and failures.

## Evidence and controls

`evidence-v10.json.gz` is a minimal saved-data subset of the user's v10 export (SHA-256 in the file): public benchmark OCR readings, the v7 base outputs, v10 phone results and model hints. It contains no original images or personal-batch scans. `references-v10.json.gz` freezes the scoring targets separately. There are 193 saved scans, five excluded for quality/unresolved reference; scores use **188 eligible** and six fields per image. Expected blank is a correct answer; a fill there is counted as unsupported. Targets and phone final answers are never input to inference or decision policy. They are loaded/used only for evaluation.

Each run invokes the actual Kotlin OCR fusion/document and field-decision code with the saved readings, plus the actual Android tokenizer as a parity oracle for every distinct word and prompt. The host ONNX feed reproduces Android's added-token prompt markers, word masks, span grid and INT4 graph. The three-label 0.50/global-overlap control must reproduce **all** saved phone result fields, including excluded images; otherwise the job fails and calls the comparisons diagnostic only. Even with identical final fields, small CPU/Android numerical differences or reconstructed OCR are possible; this is a host experiment, not a measured phone rerun. Test machinery also verifies the phone hints replay to the exact 148/188 and 1,063/1,128 baseline and that frozen references match the saved scoring outcomes.

## Prespecified configurations

- **control**: exact existing three prompts, 0.50 threshold, globally exclusive spans.
- **low_threshold**: the same model outputs, decoded at 0.35, global exclusivity.
- **lane_overlap**: the same 0.50 outputs, suppress overlap only within each lane rather than globally.
- **six_labels**: three original prompts plus country, region and vintage prompts, 0.50, per-lane overlap. Only the original three lanes feed the unchanged hybrid policy. The extra three lanes are independently measured as exact-reference span retrieval and blank-case emissions; no untested code turns them into final field values. This is **not** a six-field end-to-end implementation.

All final outputs are scored with the existing normalized-value comparator: complete labels, each field, paired gains/losses, wrong/missing values and unsupported fills. Every variant with even one inference failure is explicitly marked incomplete, never silently omitted from its denominator. Inference time, model load and wall time are host-only, not phone single-scan latency. The JSON also lists every changed field. These are **development** images: selecting a winning configuration on them is exploratory and needs independent/held-out images before generalization. The model cannot extract a field that saved OCR did not read, and exact span retrieval is not hierarchical geography resolution.

Host execution for developers: prepare JDK 21, Kotlin 2.2.10, a compiled `org.json` jar and Python `numpy<2`, `onnxruntime==1.21.1`, then set `JAVA_HOME`, `KOTLIN_HOME`, `JSON_JAR`, run `build-host.sh <temporary-dir>` and `python run.py --bundle <pinned-bundle-dir> --host <temporary-dir> --output <result.json>`. Unit tests use `GLINER_HOST` and `JAVA_HOME` for full frozen-device parity. The workflow does this automatically.
