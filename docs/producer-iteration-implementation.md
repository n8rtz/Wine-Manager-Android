# Producer-only intervention, first implemented batch

Four new Kotlin configurations operate on frozen guarded-hybrid output and saved OCR.
They change producer only, leaving wine name, origin bundle and vintage untouched.
The host collector enforces this invariant and reports the user's >=80% complete /
strictly <5% multi-error target under the new blank-aware scoring. Old baseline,
evidence extractor and OCR are not executed. Production and lab defaults are unchanged.

Implemented changes:
- Candidates from full observed names and (opt-in) spatially adjacent same-engine
  compositions. Existing producer alternatives are audited but cannot become a fill
  without matching reliable OCR/phrase support.
- Producer-role checks: named-estate cues or exact existing local producer aliases;
  no new catalog entry, fuzzy spelling invention, producer-to-origin inference or
  bottle-specific rule. Explicit cuvees, appellations, packaging, boilerplate and
  heading-only strings are not producer candidates.
- Bottler-context exclusion and full-extent confidence checks. Cross-engine fragments
  cannot manufacture a name. Repeated same-engine observations never add engine votes.
- Only a unique eligible repair may replace the original. Conflicting names preserve
  it. Safe modes repair blank/administrative values or complete a matching prefix.
  One experimental consensus mode can replace an unsupported original with a name
  corroborated by two co-located independent engine families.
- Every considered candidate records text, source observation IDs, construction,
  engine support and rejection reason. Truncation preserves the original rather
  than accepting an incomplete candidate search. Each call has a 60-second budget.

Four ablations: observed-only/two engines, observed-only/one engine,
composition/one engine, and composition/two-engine unsupported-original replacement.
No variant has been promoted merely because it was implemented. Synthetic Android
checks run before corpus execution: repeated engine votes, separated boxes, legal
bottlers, cuvees, conflicting names, unsupported alternatives, valid-original
preservation and split-name assembly. Complete gains and losses must both be reviewed.

Scope: this is the selection/construction portion of the producer plan. The residual
reading-quality cases still need image-level diagnosis; this does not claim to solve
OCR errors or to make all candidate-pool opportunities recoverable.
