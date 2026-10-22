# Residual producer-reading audit (three examples, not a corpus-wide conclusion)

During the producer selector experiments, three pinned original source photos were
retrieved and visually inspected. Their Git blob SHA-1 matches the source image IDs
in the frozen corpus. The evaluated corpus JPEGs were separately re-encoded at
acquisition (EXIF transpose, RGB, maximum edge 1600, JPEG quality 88); original-source
and evaluated-image SHA-256 values are intentionally different. Provenance is recorded
in `tools/evaluation/producer-iteration-39854457095/reading-audit-provenance.json`.
No OCR engine or baseline was rerun, and no reference was changed.

| Review | Visual reading | Saved Medium reading | Saved CTC posterior | Visible challenge |
|---|---|---|---:|---|
| 7 | Château Amélisse | Château Améliss | 0.94519216 | Cursive, large flourished capitals and small final letters |
| 18 | Château d'Agassac | D'AGASSAO | 0.91892445 | Reflective gold lettering and bottle curvature |
| 45 | La Chapelle de Calon | La Chapelle de Caler | 0.8408295 | Name extends toward curved right edge |

These are visibly available names with incorrect saved readings. High recognizer
posterior is not calibrated evidence that the name is correct. This inspection does
not establish whether detection, crop normalization, resolution or decoding is the
root cause, or whether a particular preprocessing change will fix it. It does show
why simply choosing more existing producer candidates cannot solve every remaining
case. Future targeted experiments should compare source and normalized text crops,
then test revised reading/crop logic with the existing approved OCR stack. They must
not infer the missing letters from these reference names or add bottle-specific rules.
