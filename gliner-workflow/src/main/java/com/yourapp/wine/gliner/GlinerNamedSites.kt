package com.yourapp.wine.gliner

/**
 * Published geographical mentions, independent of photo references and producer identities.
 * These names are used only as contextual support for text actually read on a label.
 * Barolo DOCG: Italian official gazette, 8 February 2010, Article 8 and annex:
 * https://www.gazzettaufficiale.it/do/atto/serie_generale/caricaPdf?cdimg=10A0135000100010110006&dgu=2010-02-08&art.dataPubblicazioneGazzetta=2010-02-08&art.codiceRedazionale=10A01350&art.num=1&art.tiposerie=SG
 * See also https://italianwinecentral.com/denomination/barolo-docg/ (2015 specifications).
 * Fleurie: https://www.beaujolais.com/en/appellation/fleurie/ (commonly claimed lieux-dits).
 * Catalog membership is not proof of a wine's provenance, ownership, or wine name.
 */
object GlinerNamedSites {
    private val barolo="""
        Albarella;Altenasso;Annunziata;Arborina;Arione;Ascheri;Bablino;Badarina;Barolo;Baudana;Bergeisa;Bergera-Pezzole;Berri;Bettolotti;Boiolo;Borzone;Boscareto;Boscatto;Boschetti;Brandini;Brea;Breri;Bricco Ambrogio;Bricco Boschis;Bricco Chiesa;Bricco Cogni;Bricco delle Viole;Bricco Luciani;Bricco Manescotto;Bricco Manzoni;Bricco Rocca;Bricco Rocche;Bricco San Biagio;Bricco San Giovanni;Bricco San Pietro;Bricco Voghera;Briccolina;Broglio;Brunate;Brunella;Bussia;Campasso;Cannubi;Cannubi Boschis;Cannubi Muscatel;Cannubi San Lorenzo;Cannubi Valletta;Canova;Capalot;Cappallotto;Carpegna;Case Nere;Castagni;Castellero;Castelletto;Castello;Castiglione Falletto;Cerequio;Cerrati;Cerretta;Cerviano-Merli;Cherasco;Ciocchini;Ciocchini-Loschetto;Codana;Collaretto;Colombaro;Conca;Corini-Pallaretta;Costabella;Coste di Rose;Coste di Vergne;Crosia;Damiano;Diano d’Alba;Drucà;Falletto;Fiasco;Fontanafredda;Fossati;Francia;Gabutti;Galina;Gallaretto;Garretti;Gattera;Giachini;Gianetto;Ginestra;Gramolere;Grinzane Cavour;Gustava;La Corte;La Morra;La Serra;La Vigna;La Volta;Lazzarito;Le Coste;Le Coste di Monforte;Le Turne;Lirano;Liste;Manocino;Mantoetto;Marenca;Margheria;Mariondino;Massara;Meriame;Monforte d’Alba;Monprivato;Monrobiolo di Bussia;Montanello;Monvigliero;Mosconi;Neirane;Novello;Ornato;Paiagallo;Panerole;Parafada;Parussi;Pernanno;Perno;Piantà;Pira;Pisapola;Prabon;Prapò;Preda;Pugnane;Ravera;Ravera di Monforte;Raviole;Riva Rocca;Rivassi;Rive;Rivette;Rocche dell’Annunziata;Rocche dell’Olmo;Rocche di Castiglione;Rocchettevino;Rodasca;Roddi;Roere di Santa Maria;Roggeri;Roncaglie;Ruè;San Bernardo;San Giacomo;San Giovanni;San Lorenzo;San Lorenzo di Verduno;San Pietro;San Ponzio;San Rocco;Sant’Anna;Santa Maria;Sarmassa;Scarrone;Serra;Serra dei Turchi;Serradenari;Serralunga d’Alba;Silio;Solanotto;Sorano;Sottocastello di Novello;Teodoro;Terlo;Torriglione;Valentino;Verduno;Vignane;Vignarionda;Vignolo;Villero;Zoccolaio;Zonchetta;Zuncai
    """.trim().split(';').map(String::trim).filter(String::isNotBlank)
    private val fleurie=listOf("La Madone","Les Labourons","Grille-Midi","Les Moriers","Les Garants",
        "La Presle","La Chapelle des Bois","Grand Pré","La Roilette","Champagne")
    val byAppellation:Map<String,Set<String>> by lazy {
        mapOf("barolo" to barolo.map(GlinerDocument::normalize).toSet(),
            "fleurie" to fleurie.map(GlinerDocument::normalize).toSet())
    }
}
