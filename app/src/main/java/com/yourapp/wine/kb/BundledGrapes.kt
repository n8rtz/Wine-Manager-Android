package com.yourapp.wine.kb

/** Existing source-authored wine vocabulary shared with the legacy parser; not a world-catalog download. */
object BundledGrapes {
    val terms: List<WineTerm> by lazy { parseWineTerms(redSource) + parseWineTerms(whiteSource) + parseWineTerms(otherSource) }

    val redSource = """
        Aglianico
        Alicante Bouschet|Garnacha Tintorera;Alicante
        Alvarelhão
        Tempranillo|Tinta Roriz;Aragonez;Tinta de Toro
        Aramon
        Baga
        Barbera
        Blauburger
        Blaufränkisch|Lemberger;Kékfrankos;Frankovka
        Bobal
        Bonarda|Charbono
        Brachetto
        Brancellao
        Cabernet Franc
        Cabernet Sauvignon
        Caladoc
        Canaiolo Nero
        Carignan|Cariñena;Mazuelo;Carignane
        Carménère|Carmenere
        Cinsault|Cinsaut
        Colorino
        Corvina
        Corvinone
        Counoise
        Dolcetto
        Dornfelder
        Duras
        Durif|Petite Sirah;Petite Syrah
        Feteasca Neagra|Fetească Neagră
        Fer Servadou|Braucol
        Frappato
        Freisa
        Fumin
        Gamay
        Garnacha|Grenache;Grenache Noir;Garnacha Tinta
        Graciano|Tinta Miuda
        Grand Noir de la Calmette
        Grignolino
        Kadarka
        Lagrein
        Lambrusco
        Listán Negro|Listan Negro
        Malbec|Cot
        Mavrud
        Mencía|Mencia
        Merlot
        Molinara
        Mondeuse Noire
        Montepulciano
        Mourvèdre|Mourvedre;Monastrell;Mataro
        Nebbiolo
        Nerello Cappuccio
        Nerello Mascalese
        Nero d'Avola|Nero d Avola;Calabrese
        Nero di Troia|Uva di Troia
        Nielluccio|Niellucciu
        País|Pais;Listan Prieto
        Petit Verdot
        Pinot Meunier
        Pinot Noir|Pinot Nero;Spätburgunder;Spatburgunder
        Plavac Mali
        Poulsard|Ploussard
        Zinfandel|Primitivo
        Refosco dal Peduncolo Rosso|Refosco
        Rondinella
        Sagrantino
        Sangiovese
        Schiava|Vernatsch;Trollinger
        Sciacarello|Sciaccarellu
        St. Laurent|Sankt Laurent;Saint Laurent
        Susumaniello
        Syrah|Shiraz;Sirah
        Tannat
        Teroldego
        Tinta Barroca
        Tinta Cão|Tinta Cao
        Tinta Francisca
        Touriga Franca
        Touriga Nacional
        Trincadeira|Tinta Amarela
        Trousseau|Bastardo
        Vranac
        Xinomavro
        Zweigelt|Blauer Zweigelt;Rotburger
        Abouriou
        Acolon
        Agiorgitiko
        Aleatico
        Alibernet
        Ancellotta
        Arinarnoa
        Baga
        Black Muscat|Moscato Nero
        Boğazkere|Bogazkere
        Bonda
        Cabernet Dorsa
        Cabernet Mitos
        Castelao|Castelão;Periquita
        Catawba
        Cesanese
        Chambourcin
        Charbono
        Corinto Nero
        Croatina|Bonarda Piemontese
        Crljenak Kaštelanski|Tribidrag
        Dobričić|Dobricic
        Espadeiro
        Früburgunder|Pinot Noir Précoce
        Gaglioppo
        Gamza
        Grignolino
        Heroldrebe
        Kotsifali
        Limnio
        Mandilaria
        Maratheftiko
        Marzemino
        Maturana Tinta
        Mavrotragano
        Mazuelo
        Melnik
        Misket Varnenski
        Négrette|Negrette
        Ner d'Avola
        Negroamaro
        Norton
        Oseleta
        Pelaverga
        Petit Bouschet
        Pineau d'Aunis|Chenin Noir
        Pinotage
        Plavac Mali
        Rara Neagra|Rară Neagră
        Regent
        Rondo
        Rufete
        Saperavi
        Sercial|Esgana Cão
        Sercialinho
        Souvignier Gris
        Tannat
        Tinta Negra|Negramoll
        Tinta Roriz
        Vaccarèse|Vaccarese
        Vespolina
        Vranac
        Xinomavro
    """

    val whiteSource = """
        Albariño|Alvarinho
        Albarín Blanco|Albarin Blanco
        Aligoté|Aligote
        Arinto
        Arneis
        Assyrtiko|Asyrtiko
        Auxerrois
        Bacchus
        Bical
        Carricante
        Catarratto
        Chardonnay
        Chasselas|Fendant;Gutedel
        Chenin Blanc|Pineau de la Loire
        Clairette
        Cortese
        Elbling
        Encruzado
        Fiano
        Fernão Pires|Maria Gomes
        Feteasca Alba|Fetească Albă
        Feteasca Regala|Fetească Regală
        Furmint
        Friulano|Tocai Friulano
        Garganega
        Gewürztraminer|Gewurztraminer;Traminer Aromatico
        Glera
        Godello
        Greco
        Grechetto
        Grüner Veltliner|Gruner Veltliner;Veltliner
        Hondarrabi Zuri|Hondarribi Zuri
        Hárslevelű|Harslevelu
        Huxelrebe
        Inzolia|Ansonica
        Jacquère|Jacquere
        Kerner
        Koshu
        Loureiro
        Macabeo|Viura
        Malagousia|Malagouzia
        Malvasia Bianca
        Marsanne
        Melon de Bourgogne|Melon
        Misket
        Moschofilero
        Müller-Thurgau|Mueller-Thurgau
        Muscadelle
        Muscat|Moscato;Moscatel;Muscat Blanc
        Muscat Blanc à Petits Grains|Muscat à petits grains
        Muscat Ottonel
        Orange Muscat
        Palomino
        Parellada
        Pedro Ximénez|Pedro Jimenez;PX
        Petit Manseng
        Picpoul Blanc|Piquepoul Blanc
        Pinot Blanc|Pinot Bianco;Weissburgunder
        Pinot Gris|Pinot Grigio;Grauburgunder
        Riesling|Rhine Riesling
        Rkatsiteli
        Roussanne
        Sauvignon Blanc|Fumé Blanc;Fume Blanc
        Savagnin|Naturé
        Scheurebe
        Sémillon|Semillon
        Silvaner|Sylvaner
        Solaris
        Souvignier Gris
        Sylvaner
        Tămâioasă Românească|Tamaioasa Romaneasca
        Torrontés|Torrontes
        Trebbiano|Ugni Blanc;Procanico
        Verdejo
        Verdelho
        Vermentino|Rolle;Favorita
        Vernaccia
        Viognier
        Welschriesling|Olaszrizling;Riesling Italico
        Xarel·lo|Xarel-lo;Pansa Blanca
        Zibibbo
        Albillo
        Arneis
        Bouvier
        Bual|Boal
        Cercial
        Colombard
        Duras Blanc
        Garganega
        Grillo
        Grüner Silvaner|Grüner Sylvaner
        Hárslevelű
        Hibernal
        Jacquez Blanc
        Kanzler
        Klevner
        Malvasia Fina
        Marsanne Blanche
        Mauzac
        Mtsvane|Kakhuri Mtsvane
        Ortrugo
        Pálava|Palava
        Pansa Rosada
        Picolit
        Pignoletto
        Pinot Auxerrois
        Pinot Meunier Blanc
        Ribolla Gialla|Rebula
        Roter Veltliner
        Sacy
        Saperavi Blanc
        Sercial
        Souvignier Gris
        Tressallier
        Verdejo Blanco
        Verdiso
        Viosinho
        Viura
        Welschriesling
        Xynisteri
        Zéta|Zeta
    """

    val otherSource = """
        Cabernet Blanc
        Chambourcin
        Frontenac
        Marquette
        Muscat Hamburg
        Niagara
        Seyval Blanc
        Vidal Blanc
        Vignoles
        Villard Blanc
        Villard Noir
    """

}
