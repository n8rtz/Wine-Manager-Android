# Synthetic LM contract fixtures — NOT OCR model weights

`tiny-lm.npz` is a tiny, deliberately synthetic two-token backoff table used only in unit tests.
It is never packaged in the APK. `logp.tsv` and `beam.tsv` are golden outputs executed against
upstream `datalab-to/indra` lm.py at commit 0e96b93c6810f17cf8352acbb4487cc9e45b66d5.
They cover seen/unseen histories, backoff, blank skip, CTC repeated characters and competing
prefixes. Beam settings: alpha=.5, beta=1.5, beam=8, cand_floor=.003; NumPy RNG seed=23,
40 float32 Dirichlet([.7,.9,.8]) arrays of 9 timesteps; every fourth array has timestep 4
replaced by [.9999,.00005,.00005]. These test decoder parity, not model quality or Android JNI.
