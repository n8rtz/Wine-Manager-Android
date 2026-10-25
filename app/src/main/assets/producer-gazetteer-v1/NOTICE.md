# Producer reference data included in the app

## Primary producer/brand and linked-wine data

LWIN by Liv-ex — https://www.liv-ex.com/lwin/
Creative Commons Attribution 4.0: https://creativecommons.org/licenses/by/4.0/

Derived from the user-supplied LWINdatabase.xlsx. Input SHA-256, supplied GitHub blob, and acquisition review are recorded in `manifest.json`. This records user-supplied primary-export provenance, not an independent download or a digital signature from Liv-ex.

The packaged producer database retains explicit producer title/name and country groups for eligible live wine records. A separate linked-wine database retains explicit LWIN WINE names and producer relationships; wine names are not producer aliases. The build excludes spirits, beer, cider, other/unknown types, inactive records, explicit mixed/gift packs, and missing producer identities. Missing WINE values are not reconstructed. No DISPLAY_NAME fallback, inferred producer aliases, or geography autofill is used. A producer/brand group is not necessarily one physical winery.

## Sources deliberately not included in the producer database

TTB permit-holder lists, OpenStreetMap winery listings, and X-Wines winery/producer/brand name lists were not independently verified as label producers. They are **not packaged in `app/src/main/assets` or loaded by producer matching**. Their historical source notes and offline review tooling remain under `tools/producer-sources`; the source-refresh script writes review outputs outside app assets and does not modify the packaged manifest.

The 125 workbook-linked label photographs are included in the normal app assets at the owner's direction for the on-device benchmark. The source repository declares no image license. Do not redistribute the photos or an APK containing them beyond authorized project use without confirming rights.
