# Bundled OCR Lab originals — v1

These three original JPEGs were supplied by the user, who requested bundling them for repeated
OCR Lab tests. They are **debug assets only**, not a production recognition database or a
publicly licensed image dataset. Do not redistribute the photos as a general dataset.
Original bytes, EXIF and full frames are preserved (about 9.85 MiB combined). SHA-256 names
and registry hashes match the uploaded originals and earlier benchmark CSV input hashes.
No screenshot, crop, recompression or synthetic substitute is used.

`samples.json` contains six-field **scoring truth only**, copied from the first matching
reviewed batch in `wine-ocr-tests (1).csv`, batch `1792304836305-8fbf1e57-a441-4f4d-804b-7481ca4ce05d`:

- Blackstone / Merlot / California; no supplied vintage, region or subregion.
- Bodegas Olarra / Reciente Gran Reserva / Spain / Rioja / 2019.
- La Reserve De L'Enclave / Cotes du Rhone Villages / France / Rhone / 2022.

These are the user's previous field assignments, **not a certification that every answer is
correct**. In particular the Rhône photo also prints Vignerons de l'Enclave as producer;
review the desired maker/name assignments. A later CSV batch leaves its vintage unscored;
the preset retains the earlier explicit 2022, which is printed on the photo. Do not derive
truth from OCR output. Empty fields remain unscored, not expected-absent assertions.

The user reviews each preset once. Subsequent edits/review confirmations are stored in local
debug preferences keyed by image SHA; changing any field resets review. Reloading restores
those saved values rather than silently overwriting them. Uninstall/clear app data clears
these preferences; clearing CSV history does not. The compressed photos are copied to private
cache with checksum verification; eviction is harmless because loading recreates the copies.
The same picker/import path then supplies only image bytes to the scanner.

Kirkland and Penfolds appear in the CSV but their original image bytes were not available;
they are deliberately not bundled. To extend the set, add actual user-approved originals,
explicit reviewed fields and hashes here, then update the UI count and integrity tests.
