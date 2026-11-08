# Lab expanded producer index — sources and attribution

`producers-expanded.sqlite` is used by Lab benchmark and GLiNER post-processing,
and is searchable on Database Lookup. Production/Prod pipelines do not read it.
Every suggestion it supports is
YELLOW / confirm manually; membership is lexical support, not proof of identity.

## Primary LWIN subset (verbatim)

All rows of the verified primary producer export (see
`../producer-gazetteer-v1/NOTICE.md` and its manifest) are copied unchanged,
including IDs, so the primary linked-wine index keeps joining. Those rows keep
their attested provenance.

## Supplemental candidates (tagged in `producer_sources`)

- **X-Wines** winery names (`source = 'xwines'`). X-Wines dataset, CC0-1.0.
  Citation: de Azambuja, Morais & Filipe (2023), X-Wines,
  https://doi.org/10.3390/bdcc7010020. Names extracted from the JamesOH5/Xwines-Atlas
  pinned snapshot recorded in `manifest.json`; the Atlas repository itself carries
  no license, so only the CC0 dataset's winery name facts are used.
- **TTB wine permits** (`source = 'ttb'`). Operating names of "Wine Producer"
  permit holders from the TTB FOIA List of Permittees
  (https://www.ttb.gov/public-information/foia/list-of-permittees), via the
  user-supplied pinned cache recorded in `manifest.json`. Authenticity/freshness
  of the supplied cache were not independently validated against a fresh export.
- **Owner-approved wine brands** (`source = 'approved_brand'`). Individually
  researched marketed names approved by the owner; source URLs and product
  examples are recorded in `tools/producer-sources/approved-wine-brands.json`.
  Brand names do not necessarily identify a legal winery. Source text and
  images are not included, and their reuse rights have not been established.
- **OpenStreetMap wineries** (`source = 'osm'`). © OpenStreetMap contributors,
  ODbL-1.0; derivative snapshot by OpenHistoryMap/openwinemap at the pinned
  commit in `manifest.json`. Named explicit craft/industrial wineries only.

Supplemental identity status is `candidate_not_verified_label_producer`: filters
remove known non-producer roles but do not establish every surviving record as a
verified grape-wine label producer. No country, region or aliases are inferred
from supplemental records, and source jurisdiction must never fill label origin
fields.
