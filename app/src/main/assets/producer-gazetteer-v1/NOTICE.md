# Producer reference data — Settings lookup and debug Lab

## Primary producer/brand layer

LWIN by Liv-ex — https://www.liv-ex.com/lwin/
Creative Commons Attribution 4.0: https://creativecommons.org/licenses/by/4.0/

Derived from the user-supplied LWINdatabase.xlsx. Input SHA-256, supplied GitHub blob and acquisition review are recorded in manifest.json. This records user-supplied primary-export provenance, not an independent download or a digital signature from Liv-ex.

Changes: retain explicit producer title/name and country groups plus explicit WINE names and producer–wine relationships for eligible live wine records; normalize matching keys separately from display names; retain LWIN7 relationships; exclude spirits, beer, cider, other/unknown types, inactive records, explicit mixed/gift packs and missing producer identities. Wine names are stored in a separate index, never as producer aliases. Missing WINE values are not reconstructed. No DISPLAY_NAME fallback, inferred producer aliases or geography autofill. A producer/brand group is not necessarily one physical winery.

## Supporting layers (separate files; never replacement names)

OSM: © OpenStreetMap contributors — https://www.openstreetmap.org/copyright
ODbL 1.0: https://opendatacommons.org/licenses/odbl/1-0/
Snapshot via OpenHistoryMap/openwinemap, latest captured commit recorded in manifest.json.
Changes: explicit named craft/industrial wineries only; exclude non-winery, inactive, unnamed and flagged non-grape-name features. Store normalized name and source ID only. Available for attribution/diagnostic support, not independent OCR votes. The separately licensed derived layer remains redistributable under ODbL; review obligations before merging into any other database.

X-Wines supplemental name candidates (debug/Lab only): upstream X-Wines dataset under CC0 1.0, https://github.com/rogerioxavier/X-Wines; name-list snapshot extracted from the precomputed payload in JamesOH5/Xwines-Atlas. Upstream list contains names reported as wineries, not verified producers or label identities. These names are stored only in a separate supplemental candidate index; no country, alias or producer-role claim is inferred. The Atlas repository has no separate license declaration; source snapshots, hashes and the X-Wines dataset citation are recorded in manifest.json. Cite de Azambuja, Morais & Filipe (2023), “X-Wines: A Wine Dataset for Recommender Systems and Machine Learning,” https://doi.org/10.3390/bdcc7010020.

TTB: supplied cached FOIA List of Permittees, Git blob 8bf9737463092b0a2ef233139dee8e88a0f6fae8 in n8rtz/Wine-Reference-Drop. Only filtered Wine Producer operating-name records; no legal-owner alias substitution. The source archive claims public-domain/CC0; its freshness/authenticity has not been independently checked with TTB. Exact source record IDs retained.

Build tools, source revisions and reproduction instructions: tools/producer-sources and docs/lab-primary-producer-resolver.md. No label artwork included. All suggestions require confirmation; reference membership is not proof of identity or wine origin.
