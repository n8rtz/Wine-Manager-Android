# Field-aware spelling suggestions

Version: `identity-v3-spelling`, lexical rule version `field-spelling-v1`.
Production and Text Lab use the same bounded lexical candidate generation. No new
model, remote service, dictionary download or confidence-score adjustment is involved.

## Evidence and trust

A suggestion keeps the observed text, matched vocabulary form/alias, proposed display
name, edit distance/type, qualitative strength (`near_match` or `plausible_match`) and
support description separate from the original OCR token. All lexical-only fields are
YELLOW/confirmation-required. The validator enforces this even if another rule would
otherwise mark the field GREEN. The engine's raw text/score and original/base geometry
are not rewritten. Scores are not reduced by an invented percentage or raised to pass
a gate. Existing production singleton confidence gates still run before extraction.

The table marks selected spelling suggestions with **†**. CSV cells retain clean field
values, with separate `<field>_observed`, `_matched_form`, `_edit_distance`, `_edit_kind`,
`_match_strength` and `_spelling_support` columns. `spelling_suggestions` also preserves
competing/unapplied lexical suggestions (bounded to 64). Production diagnostics list
suggestions separately from raw OCR and identity alternatives. The existing narrower
contextual geography character-repair rule now supplies the same structured evidence.

## What is searched

- **Producer:** only likely producer spans, against local KB names/aliases and the
  authored producer vocabulary. Exact matches take precedence. The SQLite FTS shortlist
  expands a bounded set of prefixes to recover missing/extra/replaced first characters,
  including `LACKSTONE` -> `Blackstone`. It uses one parameterized query with at most
  120 prefix terms and 25 returned identities, not a whole-database scan. The full
  observed span must still pass lexical matching; a shared substring is not sufficient.
- **Varietal/style:** against the closed grape/style vocabulary, not arbitrary cuvée or
  producer names. Exact producer/style readings are protected. Fuzzy style ranks are
  below exact printed styles, preventing a speculative alternative from displacing a
  correct Cabernet Sauvignon reading. We do not autocorrect every token inside an
  unrestricted name; comparisons are whole eligible field spans.
- **Cuvée:** only names belonging to an unambiguous exact local producer. A fuzzy-only
  producer does not authorize a cuvée lookup. All close cuvée hits are considered (up to
  eight retained by the KB), rather than accepting the first closest result.
- **Geography:** requires separately printed, compatible country/region evidence.
  Producer headquarters or a guessed parent never serves as the anchor. Equally close
  places are recorded but not applied. Corrected place claims and any dependent parent
  fields stay YELLOW. No country is invented to support an otherwise ungrounded match.
- **Vintage:** excluded from word matching. Existing numeric/context rules remain.

## Bounds and ambiguity

New lexical matching accepts normalized full spans of 5–64 characters, at most six
words, with word-count difference at most one. Digit-bearing spans are excluded from
this general matcher. One edit is allowed for shorter names; two only when both spans
have at least ten characters. Distance must also be at most 20% of the longer span.
No completion of a short prefix, large missing phrase, barcode or packaging text.
The pre-existing independently anchored geography character-confusion rule remains
separate (e.g. a digit confused with a letter in a known appellation).

Producer lexical ranks are 72 for one edit and 68 for two; fuzzy style ranks are 55/51,
below exact printed styles; producer-scoped fuzzy cuvées use 88/84, below exact cuvées.
These are transparent ranking units, not probabilities. The existing 25-pair joint
assignment and ambiguity margin apply. Equally strong competing producer or cuvée
identities abstain; alternatives remain available in diagnostics. Unknown producers
and cuvées are still allowed when there is no supported correction.

An exact matching reading from a different measured OCR engine may be recorded as
support. Same-engine variants are never independent corroboration; a nearby maker
marker supports the producer role, not the identity. Geography such as California
never boosts a producer match. Even corroborated lexical suggestions remain YELLOW
under this initial policy.

## Executed validation and limits

Host shared-logic checks include Blackstone recovery from missing-leading-letter OCR,
Merlo/Merlot, producer-scoped Pavillon Rouge, wrong/absent geographic anchors, competing
producer/cuvée/place matches, unknown wineries, short strings, packaging, digits,
unchanged scores, independent versus repeated-engine evidence, table marking and CSV
separation. The saved six-image corpus is replayed through 12 per-engine diagnostic
paths and six combined production-rule paths. Its ML Kit Blackstone case now produces
Blackstone as a suggestion without supplying a missing Merlot reading.

Python checks execute the prefix query shape with real host SQLite FTS5. Android
SQLite and Robolectric tests are authored separately; they are not claimed as executed
by the host suite. Phone APK build/runtime validation and unseen-label precision/coverage
are still needed. The bounded FTS shortlist is intentionally not exhaustive over every
possible typo in an imported catalog; no claim of universal recovery or confidence
calibration is made. Poor OCR can still yield wrong YELLOW suggestions.
