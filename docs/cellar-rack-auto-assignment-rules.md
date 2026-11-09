# Future feature: automatic cellar-rack rules

**Status: planned; not implemented.**

Add an optional rule builder to both rack setup and rack edit. A user can define reusable conditions that automatically assign matching wines to that rack, while keeping manual multi-rack assignments available. Rule evaluation must not remove a wine from any other rack.

## Initial rule templates

- Country of origin — for example, Spain.
- Vintage — before / after / equal to a year, such as earlier than 2020.
- Price — between two amounts, such as $10–$20, with currency handled explicitly.

The editor should present readable, IFTTT-style clauses (`IF country is Spain AND vintage is before 2020 THEN add to this rack`) and let users chain multiple conditions. Include controls to add, edit, reorder if meaningful, and remove a condition, plus a clear indication of whether the chain uses ALL (AND) or ANY (OR) conditions. Empty rules must not match every wine. Rule matches should be derived from wine metadata and refresh when relevant metadata changes; existing manual rack assignments remain independent.

## Scope and open design checks

- This is a future feature: do not silently make existing racks automatic or infer rules from current membership.
- Decide how missing country, vintage, or price values behave (initial recommendation: a missing value does not satisfy its condition).
- Define currency conversion/comparison behavior before implementing price ranges; do not compare unlike currencies as though they were equal.
- Persist rules per rack and include them in JSON backup/restore when implemented.
- Test rule matching, chained ALL/ANY semantics, editing/deleting rules, metadata updates, multi-rack coexistence, and backup/restore.
