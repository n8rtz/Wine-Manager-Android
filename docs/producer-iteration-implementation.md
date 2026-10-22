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

## Refinement measured and selected for optional lab testing

Android run 39857230621, source 17c6f06, completed all four revised variants successfully.
The conservative `producer-refined-2` is selected for optional lab testing: producer
76 correct / 33 wrong / 56 missing, complete forms 33/160, multiple-error forms 80/160.
Relative to the guarded hybrid, four missing producers and one wrong producer became
correct; all 71 formerly correct producers were preserved; no new producer error was
introduced. One complete form gained, none lost. The other five fields are unchanged.

The one-engine refined mode was 77/35/53, 32 complete. Title-based two-engine mode was
77/39/49, 32 complete; title-based one-engine was 77/46/42, 33 complete. Broad title
classification is rejected: its extra fills are not worth the added wrong identities.
No variant approaches the user's 80% complete / <5% multiple-error target.

The chosen producer stage is integrated behind a separate default-off lab checkbox
under the guarded hybrid option. It reuses existing saved readings and the hybrid
producer, changes only producer, exports decision traces, retains baseline rows and
uses YELLOW/RED states. Production and previous defaults are untouched.

Measured emulator selection-only time for the chosen uncached variant: median 585 ms,
maximum 6,940 ms; rejected title variants reached about 34 seconds. A per-call exact-text
role cache avoids repeated graph/KB checks inside pairwise assembly. Before accepting
this optimization, an Android validation job runs actual lab-routing assertions and
checks the cached, alternative-free producer result against all 168 frozen preceding
predictions. No previous extractor or OCR executes; the preceding predictions are
behavioral parity controls, not reference answers. Android run 39861083810 passed
compilation, synthetic lab routing, and all 168 parity checks (zero mismatches).
Cached selection-only timings were median 290 ms, maximum 1,932 ms, total 63,204 ms.
These are separate emulator runs, not a controlled phone performance benchmark.
The workflow is marked failed solely because the final archive upload hit GitHub
artifact storage quota; validated results were already committed as 8be476c.
No rerun was needed. The 127 host tests also pass.

A separate three-photo source-image audit documents genuine name-reading discrepancies
(cursive, reflective and curved-label examples). It does not claim a cause-specific
OCR fix or use those names as hardcoded corrections; see producer-reading-audit.md.
