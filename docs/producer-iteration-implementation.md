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

## First measurements and second implemented batch

Android 39854457095 executed all 672 cases and synthetic checks successfully. Its
publishing step expected decision.json, which the first collector omitted. The fix
and recovery job 39856497283 retrieved/scored the existing artifact without any rerun.
Actual first-batch producer C/W/missing and complete forms:
- guarded control: 71/34/60, 32/160 complete, 82 multi-error forms;
- direct two-engine: 71/35/59, 32 complete;
- direct one-engine: 73/36/56, 32 complete;
- composed one-engine: 76/39/50, 31 complete (one previous complete lost);
- composed consensus: 76/35/54, 33 complete (one gained, none lost), 80 multi-error forms.
None meets the user's acceptance target; no producer variant is promoted.

The saved traces identify specific GENERAL design failures: generic multiword estate
headings could be accepted, adding adjacent fragments could corrupt a reliable full
name, and uncatalogued brand names had no admissible producer role. The second batch
therefore tests stricter generic-heading handling; weaker vineyard markers no longer
suffice as a producer cue without exact KB evidence; reliable existing name extents
are protected; compositions appending known origin/grape terms to an already named
estate are rejected. Explicit produced-and-bottled attribution is distinguished from
bottling alone.

Two additional exploratory variants admit uncatalogued title-like names based on
relative text height (not fixed image zones), existing layout and engine support,
excluding grape/style terms, exact places, cuvee markers, vineyard markers and URLs.
They are ablated with one vs two engines. These are hypotheses about brand roles,
not a claim that typography or engine agreement proves identity. The two strictly
cue-based refined variants run alongside them. Old policies and controls are reused,
not rerun. The second device test adds contrasting generic headings, new invented
brand names, varietals and full-name protection before evaluating the corpus.
