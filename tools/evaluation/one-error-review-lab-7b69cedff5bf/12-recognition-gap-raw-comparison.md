# Raw OCR comparison — 12 recognition-gap cases

**Run:** Lab build `7b69cedff5bf`, device `CPH2583`. Raw OCR readings below are every `recognition` event from the ML Kit reference and selected PP-OCR candidate for each image, in trace order. These are before field extraction and downstream cleanup. The final output is the `lab-linked-wine-v1` six-field prediction from the same run. “Reference CSV” is the imported value; for #63 the separately adjudicated scored value is shown as well. OCR traces and field output are run artifacts, not human-verified transcriptions. `␠` marks a literal trailing space in an OCR line so it remains visible in Markdown.

## At-a-glance comparison

| # | Errant field | Imported target | Current scored target | Final Lab value |
|---:|---|---|---|---|
| 12 | producer | La Francette | La Francette | — |
| 18 | producer | Château d'Agassac | Château d'Agassac | CHÂTEAU D'AGASSAO |
| 31 | producer | Château La Tour de By | Château La Tour de By | — |
| 45 | producer | La Chapelle de Calon | La Chapelle de Calon | La Chapelle de Caler |
| 55 | wine_name | Léo by Léo | Léo by Léo | SAINT-EMILION Grand Cru |
| 63 | wine_name | Volcanie Rosé | Volcanic Rosé | LANGUEDOC |
| 75 | producer | Château La Tour de By | Château La Tour de By | CHÂTEAU La Cour de By |
| 114 | producer | Le Pas du Templier | Le Pas du Templier | — |
| 117 | producer | La Francette | La Francette | — |
| 133 | wine_name | Terre de Provence | Terre de Provence | cÔTES DE PROVENCE |
| 159 | producer | Domaine Georges Bertrand | Domaine Georges Bertrand | GEORGES BERTRAN bEORGES BERTRAM DOMAINE |
| 168 | wine_name | Léo by Léo | Léo by Léo | SAINT-EMILION Grand Cru |

The sections below provide the complete six-field reference/final output and every raw recognition line from each OCR engine for that case.


## Review #12 — `0ff9ea33887ddd65e6c601286c9df6b6581ecf35.jpg`

**Error field:** `producer`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | La Francette | — |
| wine_name | Cabernet Sauvignon | Cabernet Sauvignon |
| country | France | France |
| region | — | — |
| subregion | — | — |
| vintage | — | — |

**Raw ML Kit (`mlkit_reference`)**
```text
PRODUIT DE FRANCE
CABERNET SAUVIGNON
VIN DE FRANCE
Mis en Bouteille par
s Celliers des Terres de France AF 30942-Nimes
```

**Raw PP-OCR (`candidate_3`)**
```text
PRODUIT DE FRANCE
Trancette
CABERNET SAUVIGNON
VIN DE FRANCE
PH0
L
76
```

## Review #18 — `1b01bca28dee816f1ef0fe7b91cf0257a43a0ec6.jpg`

**Error field:** `producer`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Château d'Agassac | CHÂTEAU D'AGASSAO |
| wine_name | Haut-Médoc | HAUT-MÉDOC |
| country | France | France |
| region | Bordeaux | Bordeaux |
| subregion | Haut-Médoc | Haut-Médoc |
| vintage | 2014 | 2014 |

**Raw ML Kit (`mlkit_reference`)**
```text
CH ATEA U
DAGASA
201 4
HAUT- MÉD0C
```

**Raw PP-OCR (`candidate_3`)**
```text
4
CHÂTEAU
D'AGASSAO
2014
HAUT-MÉDOC
```

## Review #31 — `29ea6458e0ce20b341287e5e59589a94d6c0ae94.jpg`

**Error field:** `producer`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Château La Tour de By | — |
| wine_name | Médoc | MÉDOC |
| country | France | France |
| region | Bordeaux | Bordeaux |
| subregion | Médoc | Médoc |
| vintage | 2015 | 2015 |

**Raw ML Kit (`mlkit_reference`)**
```text
CHẤTEAU
MÉDOC
HATEAU
2015
GRAND VIN DE BODEA
```

**Raw PP-OCR (`candidate_3`)**
```text
CHÂTEAU
La Cour de By
AU CHATEAU
自自
mml
1
MIS␠
MÉDOC
2015
GRAND VIN DE BORDEAUX
```

## Review #45 — `3e2e56b18069c4227cf9d95a286011c3ad00aa12.jpg`

**Error field:** `producer`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | La Chapelle de Calon | La Chapelle de Caler |
| wine_name | Saint-Estèphe | SAINT-ESTÈPHE |
| country | France | France |
| region | Bordeaux | Bordeaux |
| subregion | Saint-Estèphe | Saint-Estèphe |
| vintage | 2011 | 2011 |

**Raw ML Kit (`mlkit_reference`)**
```text
Ohapelle d. Ca
SAINT-E EsTÈPHE
2011
```

**Raw PP-OCR (`candidate_3`)**
```text
La Chapelle de Caler
SAINT-ESTÈPHE
2011
```

## Review #55 — `5369c23329d9c59e5b42c5de952328191b4ff8ec.jpg`

**Error field:** `wine_name`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Bertrand Ravache | Bertrand Ravache |
| wine_name | Léo by Léo | SAINT-EMILION Grand Cru |
| country | France | France |
| region | Bordeaux | Bordeaux |
| subregion | Saint-Émilion | Saint-Émilion Grand Cru |
| vintage | 2018 | 2018 |

**Raw ML Kit (`mlkit_reference`)**
```text
RAVACHE
ND
LEO
by
Ghavea i: do Botsaue
2018
SAINT-EMILION GRAND CRU
MIS EN BOUTEILLE À LA PROPRIETE
AUD A C PA R
BERTRAND RAVACHE
L21034
I35
```

**Raw PP-OCR (`candidate_3`)**
```text
RAND RAVACHE
LÉO
by
Grand Vin de Bordeaux
SAINT-EMILION GRAND CRU
MIS EN BOUTEILLE À LA PROPRIÉTÉ
2018
BERTRAND RAVACHE
AUDACE PAR
```

## Review #63 — `60e353e003e2211494936b66d477731691c90b18.jpg`

**Error field:** `wine_name`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Château La Sauvageonne | Gerard Bertrand |
| wine_name | Volcanie Rosé | LANGUEDOC |
| country | France | France |
| region | Languedoc | Languedoc |
| subregion | — | — |
| vintage | 2018 | 2018 |

**Adjudication:** imported wine-name value `Volcanie Rosé`; user-confirmed scored value `Volcanic Rosé`. The run predates this correction; its prediction remains `LANGUEDOC`.


**Raw ML Kit (`mlkit_reference`)**
```text
SUD DE FRANCE
CHẬTEAU LA
AUVAGE EONN
2018
LANG EDOC
GÉRARD BERTRAND
```

**Raw PP-OCR (`candidate_3`)**
```text
JAL
SUD DE FRANCE
CHÂTEAU LA
AUVAGEONNE
Volcanic Lasi
2018
LANGUEDOC
GÉRARD BERTRAND
```

## Review #75 — `70dd815ec4023a81863d68d051410930e144686b.jpg`

**Error field:** `producer`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Château La Tour de By | CHÂTEAU La Cour de By |
| wine_name | Médoc | MÉDOC |
| country | France | France |
| region | Bordeaux | Bordeaux |
| subregion | Médoc | Médoc |
| vintage | 2015 | 2015 |

**Raw ML Kit (`mlkit_reference`)**
```text
CHATEAU
MÉDOC
2015
HATEAI
GRAND VIN N DE BORDEA UX
```

**Raw PP-OCR (`candidate_3`)**
```text
CHÂTEAU
La Cour de By
MIS EN BOUTEILLE AU CHATEAU
MÉDOC
2015
GRAND VIN DE BORDEAUX
```

## Review #114 — `acce906b357f0554795a353ae0226adad4ddefbc.jpg`

**Error field:** `producer`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Le Pas du Templier | — |
| wine_name | Minervois | MINERVOIS |
| country | France | France |
| region | Languedoc | Languedoc |
| subregion | Minervois | Minervois |
| vintage | — | — |

**Raw ML Kit (`mlkit_reference`)**
```text
PRODT DE FRANCE
Le pas du
EMPLIER
MINERVOIS
APPELLATION MINERVOIS CONTROLEE
MIS EN BOUTEILLE PAR
LES CELLIERS DES TERRES DE FRANCE
Å F.30942
```

**Raw PP-OCR (`candidate_3`)**
```text
PRODUIT DE FRANCE
Le Pas du
EMPLIER
MINERVOIS
APPELLATION MINERVOIS CONTRÔLÉE
PUL
MIS EN BOUTEILLE PAR
LES CELLIERS DES TERRES DE FRANCE
À F.30942
```

## Review #117 — `aed0bfd1dda2777a63691703ec0fe3909a0c92da.jpg`

**Error field:** `producer`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | La Francette | — |
| wine_name | Merlot | Merlot |
| country | France | France |
| region | — | — |
| subregion | — | — |
| vintage | — | — |

**Raw ML Kit (`mlkit_reference`)**
```text
PRODUIT DE FRANCE
MERLOT
VIN DE FRANCE
Mis en bouteille par
Les Celliers des Terres de France À F30942- Nimes
```

**Raw PP-OCR (`candidate_3`)**
```text
PRODUIT DE FRANCE
Trancette
MERLOT
VIN DE FRANCE
125%vol.
Mis en bouteille par
LEdETedFaAA2
```

## Review #133 — `c8791cbdef50a92ea13b208eeb2f33538a19e6db.jpg`

**Error field:** `wine_name`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Cavalier | CAVALIER |
| wine_name | Terre de Provence | cÔTES DE PROVENCE |
| country | France | France |
| region | Provence | Provence |
| subregion | Côtes de Provence | Côtes de Provence |
| vintage | 2020 | 2020 |

**Raw ML Kit (`mlkit_reference`)**
```text
CAVALIFR
cÔTES DE PROVENCE
```

**Raw PP-OCR (`candidate_3`)**
```text
2020
CAVALIER
Terre de Prorence
CÔTES DE PROVENCE
```

## Review #159 — `f26cdfb733a40ad85d1ffd61a60565396adef8d4.jpg`

**Error field:** `producer`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Domaine Georges Bertrand | GEORGES BERTRAN bEORGES BERTRAM DOMAINE |
| wine_name | Corbières | CORBIÈRES |
| country | France | France |
| region | Languedoc | Languedoc |
| subregion | Corbières | Corbières |
| vintage | 2019 | 2019 |

**Raw ML Kit (`mlkit_reference`)**
```text
SUD DE FRANCE
DOMAINE
bEORGES BERTRAM
2019
CORBIÈRES
APPELCLATION D'ORIGINE PROTÉGÉE
GÉRARD BERTRAND
```

**Raw PP-OCR (`candidate_3`)**
```text
SUD DE FRANCE
GEORGES BERTRAN
2019
APPELLIORIGNEPOTEEM
GÉRARD BERTRAND
```

## Review #168 — `ff16be743b4329579aa82341aa5ef68884a8a9b1.jpg`

**Error field:** `wine_name`

| Field | Imported reference CSV | Final Lab output |
|---|---|---|
| producer | Bertrand Ravache | Bertrand Ravache |
| wine_name | Léo by Léo | SAINT-EMILION Grand Cru |
| country | France | France |
| region | Bordeaux | Bordeaux |
| subregion | Saint-Émilion | Saint-Émilion Grand Cru |
| vintage | 2018 | 2018 |

**Raw ML Kit (`mlkit_reference`)**
```text
AND AVACHE
LÉO
by
SAINT-EMILION GRAND CRU
2018
MIS EN BOUTEILLE À LA PROPRIÉTÉ
AU D ACE PA R
BERTRAND RAVACHE
L210120
11:35
```

**Raw PP-OCR (`candidate_3`)**
```text
RAND RAVACHE
福
LÉO
by
eo
Grand Din de Bordeaux
SAINT-EMILION GRAND CRU
2018
MIS EN BOUTEILLE À LA PROPRIÉTÉ
AUDACE PAR
BERTRAND RAVACHE
L210120
11:35
```
