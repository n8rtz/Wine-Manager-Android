# User-supplied producer gazetteer — quarantined review input

`source.zip` is the original, byte-exact supplied archive. See `manifest.json` for its URL and Git blob/SHA-256 identity. It is **not an Android asset or an approved producer dataset**. No web app/server is installed or started.

`audit.json` records the actual offline result: 128 curated entries, absent bulk registry/database, search probes and limited lexical benchmark coverage. Curated names, legal identities and aliases are not independently verified. No license file was supplied.

Reproduce the audit with Python 3 and SQLite supporting FTS5 trigram:

```sh
python tools/producer-gazetteer-review/audit.py
```

This executes the reviewed, pinned builder/search scripts offline in a temporary folder. It does not fetch sources or run OCR. Its temporary database is deleted afterward. See `docs/producer-gazetteer-lab-plan.md` for the integration plan and blocking data requirements.
