package com.n8rtz.winetracker.drinkingwindow

/**
 * One ageing rule. Years are counted from the vintage year: the window runs from
 * `vintage + startYears` to `vintage + endYears`.
 *
 * Every entry carries the web sources it was drafted from. Entries start with `reviewed = false`:
 * the owner reviews the table after implementation (W-050). Where a source gave only a maximum, the
 * start year is an assumption and the note says so.
 *
 * @param type normalised wine type this rule applies to ("red", "white", ...), or null for any.
 * @param groups every group must have at least one phrase present in the wine text.
 * @param reservaBonusYears added to the end year when the text contains "Reserva" or "Riserva".
 *   ("Reserve" is not used: it is a brand word on many labels and unregulated in the US.)
 *
 * Price paid is deliberately NOT an input: what someone paid says nothing reliable about how long a
 * wine ages (owner decision, 2026-10-16).
 */
internal class AgeingProfile(
    val id: String,
    val label: String,
    val level: MatchLevel,
    val type: String?,
    val groups: List<List<String>>,
    val startYears: Int,
    val endYears: Int,
    val source: String,
    val reservaBonusYears: Int = 0,
    val reviewed: Boolean = false
) {
    val normalizedGroups: List<List<String>> = groups.map { group -> group.map { normalizeText(it) } }
}

/**
 * The draft ageing table. See each entry's `source`. All entries are unreviewed.
 * The most specific level wins (appellation, then region + grape, then grape, then type). Within a
 * level the rule whose keywords matched the most text wins, and an exact tie goes to the rule listed
 * first. Region-wide fallbacks (generic Bordeaux, generic Rhône) are therefore kept at region level so
 * that a named commune such as Margaux always beats them.
 */
internal object AgeingProfiles {

    private const val WF_AGE = "winefolly.com/tips/collecting-age-worthy-wine"
    private const val WCR = "wineclubreviews.net/guides/aged-wine/fundamentals"
    private const val CAVERNS = "thewinecaverns.co.uk (grape ageing guide)"
    private const val BINWISE = "home.binwise.com (aging window by grape)"
    private const val RHONE_NOTE = "winewithseth.com/winewiki/compare/chateauneuf-du-pape-vs-cotes-du-rhone"
    private const val GENERAL = "General knowledge, unsourced"

    private fun rule(
        id: String, label: String, level: MatchLevel, type: String?, groups: List<List<String>>,
        start: Int, end: Int, source: String,
        reserva: Int = 0
    ) = AgeingProfile(id, label, level, type, groups, start, end, source, reserva)

    private val A = MatchLevel.APPELLATION
    private val RG = MatchLevel.REGION_AND_GRAPE
    private val G = MatchLevel.GRAPE
    private val T = MatchLevel.TYPE

    val all: List<AgeingProfile> = listOf(
        // ---- Rhône ------------------------------------------------------------------------
        rule("rhone_cdp_red", "Châteauneuf-du-Pape red", A, "red",
            listOf(listOf("chateauneuf du pape", "chateauneuf", "chateau neuf du pape")), 5, 15,
            "thebestvintage.fr 5-15 (top estates 15-30); lesvinsdecarole.com 5-12 classic; winefolly.com 5-10; bighammerwines.com 5-15"),
        rule("rhone_cdp_white", "Châteauneuf-du-Pape white", A, "white",
            listOf(listOf("chateauneuf du pape", "chateauneuf", "chateau neuf du pape")), 3, 7,
            "thebestvintage.fr / lesvinsdecarole.com: fresh styles 3-7 (Roussanne-led can go 10-20)"),
        rule("rhone_south_cru", "Southern Rhône cru (Vacqueyras, Gigondas, Rasteau...)", A, "red",
            listOf(listOf("vacqueyras", "gigondas", "rasteau", "cairanne", "lirac", "vinsobres", "beaumes de venise", "plan de dieu")), 3, 10,
            "End: winefolly.com (southern Rhône reds within 5-10 years). Start 3 is an assumption"),
        rule("rhone_cdr_villages", "Côtes du Rhône Villages", A, "red",
            listOf(listOf("cotes du rhone villages")), 2, 6,
            "$RHONE_NOTE (Côtes du Rhône best within 3-5 years, Villages somewhat longer); bighammerwines.com CdR 3-6. Start 2 is an assumption"),
        rule("rhone_cdr", "Côtes du Rhône", A, "red",
            listOf(listOf("cotes du rhone")), 1, 5,
            "$RHONE_NOTE (best within the first 3-5 years). Start 1 is an assumption"),
        rule("rhone_generic_red", "Rhône red", RG, "red",
            listOf(listOf("rhone", "rhone valley", "northern rhone", "southern rhone")), 2, 7,
            "Between Côtes du Rhône 3-5 and southern crus 5-10 (see those entries); a Rhône red with no appellation word is ambiguous"),

        // ---- Bordeaux ---------------------------------------------------------------------
        rule("bdx_left_bank", "Left Bank Bordeaux (Pauillac, Margaux, Saint-Julien, Saint-Estèphe)", A, "red",
            listOf(listOf("pauillac", "margaux", "saint julien", "st julien", "saint estephe", "st estephe", "haut medoc", "medoc")), 6, 15,
            "glassandnote.com: entry-level Margaux 8-12, First Growths 15-25 (classified growths far longer); vinovest.co. Start 6 and end 15 are assumptions for a village-level wine"),
        rule("bdx_right_bank", "Right Bank Bordeaux (Saint-Émilion, Pomerol)", A, "red",
            listOf(listOf("saint emilion", "st emilion", "pomerol", "lalande de pomerol", "fronsac")), 4, 15,
            "theaustinwinemerchant.com (Grand Cru Classé: a few years then 10-15). Start 4 is an assumption"),
        rule("bdx_cotes", "Côtes de Bordeaux / Blaye / Bourg red", A, "red",
            listOf(listOf("cotes de bordeaux", "blaye", "cadillac", "cotes de bourg", "cotes de borg", "bourg", "francs", "castillon", "cotes de castillon")), 1, 5,
            "bordeaux.com (Blaye Côtes de Bordeaux red 1-5 years). Cadillac AOC white is sweet and not matched here"),
        rule("bdx_generic_red", "Bordeaux / Bordeaux Supérieur red", RG, "red",
            listOf(listOf("bordeaux", "bordeaux superieur")), 2, 7,
            "thewinesociety.com (Bordeaux and Bordeaux Supérieur 2-7 years); vinatis.co.uk Supérieur 3-4"),

        // ---- Spain ------------------------------------------------------------------------
        rule("rioja_gran_reserva", "Rioja Gran Reserva", A, "red",
            listOf(listOf("rioja", "rioja alta", "rioja alavesa", "rioja baja"), listOf("gran reserva")), 5, 20,
            "coolersomm.com (Gran Reserva 15-20+ years); start 5 is an assumption (released ready to drink)"),
        rule("rioja_reserva", "Rioja Reserva", A, "red",
            listOf(listOf("rioja", "rioja alta", "rioja alavesa", "rioja baja"), listOf("reserva")), 4, 12,
            "coolersomm.com (Reserva 8-10 years of cellaring after release); start 4 and end 12 are assumptions"),
        rule("rioja_crianza", "Rioja Crianza", A, "red",
            listOf(listOf("rioja", "rioja alta", "rioja alavesa", "rioja baja"), listOf("crianza")), 3, 8,
            "coolersomm.com (Crianza 3-5 years of cellaring after release); end 8 is an assumption"),
        rule("rioja_generic", "Rioja (no aged designation)", RG, "red",
            listOf(listOf("rioja", "rioja alta", "rioja alavesa", "rioja baja")), 1, 3,
            "coolersomm.com (joven/generic Rioja: a year or two). If the bottle is really a Crianza the label may not say so"),
        rule("ribera_del_duero_red", "Ribera del Duero red", RG, "red",
            listOf(listOf("ribera del duero")), 4, 12,
            "coolersomm.com (Crianza 5-7, Reserva 10-15); blended here because the label word is often missing"),
        rule("jumilla_red", "Jumilla / Monastrell red", RG, "red",
            listOf(listOf("jumilla", "monastrell", "mourvedre")), 3, 7,
            "grapeguru.de (standard Monastrell 3-7; top/old-vine 10-15)", reserva = 3),

        // ---- Portugal ---------------------------------------------------------------------
        rule("dao_red", "Dão red", RG, "red", listOf(listOf("dao")), 5, 8,
            "vinhoatlas.com (entry Dão red 5-8, Reserva 10-15)", reserva = 5),
        rule("douro_red", "Douro red", RG, "red", listOf(listOf("douro")), 3, 12,
            "wineenthusiast.com (Douro/Dão reds 10-15+ years). Start 3 is an assumption", reserva = 3),

        // ---- Italy ------------------------------------------------------------------------
        rule("barolo", "Barolo / Barbaresco", A, "red",
            listOf(listOf("barolo", "barbaresco")), 10, 25,
            "winefolly.com (Barolo/Brunello 10-25); collectorcellar.ai Barolo 10-15"),
        rule("brunello", "Brunello di Montalcino", A, "red",
            listOf(listOf("brunello di montalcino", "brunello")), 10, 25,
            "winefolly.com (Barolo/Brunello 10-25); collectorcellar.ai Brunello 12-18"),
        rule("chianti_classico_riserva", "Chianti Classico Riserva", A, "red",
            listOf(listOf("chianti classico"), listOf("riserva")), 7, 12,
            "collectorcellar.ai/italian-wine-aging-guide (Chianti Classico Riserva 7-12)"),
        rule("chianti", "Chianti", A, "red",
            listOf(listOf("chianti", "chianti classico", "chianti rufina")), 3, 10,
            "$WF_AGE (Sangiovese 7-17); no Chianti-specific source found, so start 3 and end 10 are assumptions", reserva = 3),

        // ---- Germany / Loire / Chablis ----------------------------------------------------
        rule("riesling_auslese", "Riesling Auslese", A, "white",
            listOf(listOf("riesling"), listOf("auslese")), 10, 25,
            "collectorcellar.ai/german-riesling-aging-guide and rieslingnotes.com (Auslese 10-25); sources disagree"),
        rule("riesling_spatlese", "Riesling Spätlese", A, "white",
            listOf(listOf("riesling"), listOf("spatlese")), 5, 15,
            "collectorcellar.ai and rieslingnotes.com (Spätlese 5-15); germanyrieslings.com says 3-10. A dry Spätlese may differ"),
        rule("riesling_kabinett", "Riesling Kabinett", A, "white",
            listOf(listOf("riesling"), listOf("kabinett")), 2, 8,
            "collectorcellar.ai (Kabinett 2-8); germanyrieslings.com about 5"),
        rule("riesling_mosel", "Mosel Riesling", RG, "white",
            listOf(listOf("riesling"), listOf("mosel", "moselle", "mittelmosel", "piesport", "bernkastel", "wehlen", "graach", "urzig")), 3, 10,
            "Between Kabinett 2-8 and Spätlese 5-15 (see those entries) when no Prädikat is named; assumption"),
        rule("vouvray_moelleux", "Vouvray moelleux", A, "white",
            listOf(listOf("vouvray"), listOf("moelleux")), 10, 40,
            "grapeguru.de (moelleux 20-50+); end capped at 40, start 10 is an assumption"),
        rule("vouvray_demi_sec", "Vouvray demi-sec", A, "white",
            listOf(listOf("vouvray"), listOf("demi sec")), 8, 20,
            "grapeguru.de and dvin.ai (demi-sec 10-20); start 8 is an assumption"),
        rule("vouvray", "Vouvray", A, "white",
            listOf(listOf("vouvray")), 3, 10,
            "dvin.ai (sec 5-10); binwise Chenin 1-7. Style (sec/demi-sec) not stated on the label, so start 3 is an assumption"),
        rule("chablis_grand_cru", "Chablis Grand Cru", A, "white",
            listOf(listOf("chablis"), listOf("grand cru")), 8, 20,
            "chablis-wines.com (Grand Cru 10-12+); kentstreet-style guides 10-20"),
        rule("chablis_premier_cru", "Chablis Premier Cru", A, "white",
            listOf(listOf("chablis"), listOf("premier cru", "1er cru", "1er")), 4, 10,
            "chablis-wines.com and chablisienne.com (Premier Cru 5-10)"),
        rule("chablis_petit", "Petit Chablis", A, "white",
            listOf(listOf("petit chablis")), 1, 3,
            "chablisienne.com (Petit Chablis 1-3)"),
        rule("chablis", "Chablis", A, "white",
            listOf(listOf("chablis")), 2, 7,
            "chablisienne.com (Chablis 2-5); chablis-wines.com (5+)"),

        // ---- New World: place + grape -----------------------------------------------------
        rule("napa_cabernet", "Napa / Sonoma Cabernet", RG, "red",
            listOf(listOf("napa", "napa valley", "oakville", "rutherford", "stags leap", "stag s leap", "howell mountain", "sonoma", "alexander valley"), listOf("cabernet", "cabernet sauvignon", "cab")), 5, 12,
            "invintory.com ($30-75 bottles 5-10 years, $75-200 longer); cavisteapp.com Napa Cabernet 5-25. The 5-12 range is an assumption for a standard bottling"),
        rule("willamette_pinot", "Willamette Valley Pinot Noir", RG, "red",
            listOf(listOf("willamette", "williamette", "willamette valley", "oregon"), listOf("pinot noir", "pinot")), 5, 15,
            "Paul Gregutt (paulgregutt.substack.com): very fine 5-15 years after the vintage; internationalwineauthority.com 7-12"),
        rule("california_pinot", "California Pinot Noir", RG, "red",
            listOf(listOf("california", "russian river", "sonoma", "santa barbara", "monterey", "carneros", "sta rita hills", "santa lucia highlands"), listOf("pinot noir", "pinot")), 3, 10,
            "$WF_AGE (Pinot Noir about 10); $CAVERNS (2-8). Start 3 is an assumption"),
        rule("grape_zinfandel", "Zinfandel", G, "red", listOf(listOf("zinfandel", "zin")), 2, 7,
            "$WCR (Zinfandel 2-7); $WF_AGE (about 5)"),
        rule("mendoza_malbec", "Mendoza / Argentine Malbec", RG, "red",
            listOf(listOf("mendoza", "uco valley", "valle de uco", "lujan de cuyo", "argentina", "cafayate", "salta")), 3, 10,
            "$WCR (Malbec 5-10); $BINWISE; $WF_AGE (about 10). Start 3 is an assumption for an everyday Mendoza Malbec", reserva = 3),
        rule("chile_red", "Chilean red", RG, "red",
            listOf(listOf("chile", "cachapoal", "colchagua", "maipo", "central valley", "maule", "rapel", "casablanca")), 3, 8,
            "sommo.app (Cachapoal 5-10, low reliability). Start 3 and end 8 are assumptions", reserva = 3),
        rule("marlborough_sauvignon_blanc", "Marlborough Sauvignon Blanc", RG, "white",
            listOf(listOf("marlborough", "new zealand"), listOf("sauvignon blanc", "sauvignon")), 1, 3,
            "villamariawines.com (ideally drunk within 1-2 years); $WF_AGE (about 4)"),
        rule("australian_shiraz", "Australian Shiraz", RG, "red",
            listOf(listOf("australia", "barossa", "mclaren vale", "south australia", "victoria", "coonawarra", "hunter valley", "southeast australia", "south eastern australia"), listOf("shiraz", "syrah")), 3, 8,
            "lebaroudeurduvin.com (entry Shiraz 3-5, old-vine Barossa 15-20); $WF_AGE (Syrah 5-15). The 3-8 is a compromise for an unspecified tier"),

        // ---- Fresh styles by name ---------------------------------------------------------
        rule("fresh_whites", "Fresh white (Vinho Verde, Albariño, Rueda, Muscadet, Pinot Grigio...)", G, "white",
            listOf(listOf("vinho verde", "albarino", "rias baixas", "rueda", "verdejo", "muscadet", "pinot grigio", "pinot gris", "gruner veltliner", "vermentino", "picpoul")), 1, 3,
            "By analogy with Muscadet/Pinot Gris about 3 and Sauvignon Blanc about 4 ($WF_AGE). No Rueda or Vinho Verde source found: assumption"),
        rule("prosecco_cava", "Prosecco / Cava / Brut", G, null, // any type: some are entered as White
            listOf(listOf("prosecco", "cava", "cremant", "lambrusco", "brut", "espumante")), 1, 3,
            "wineaffection.com (Prosecco and simple sparkling 1-3 years); angelapersonaltuscantour.com 1-2"),

        // ---- Grape-level (no place found) --------------------------------------------------
        rule("grape_nebbiolo", "Nebbiolo", G, "red", listOf(listOf("nebbiolo")), 5, 20, "$WF_AGE (Nebbiolo about 20); start 5 is an assumption"),
        rule("grape_cabernet", "Cabernet Sauvignon", G, "red", listOf(listOf("cabernet sauvignon", "cabernet")), 5, 20, "$CAVERNS (Cabernet 5-20)"),
        rule("grape_merlot", "Merlot", G, "red", listOf(listOf("merlot")), 4, 10, "$CAVERNS and $WF_AGE (Merlot 7-17); blended here, assumption"),
        rule("grape_sangiovese", "Sangiovese", G, "red", listOf(listOf("sangiovese")), 4, 12, "$WF_AGE (7-17); $WCR (10-25); start and end are assumptions"),
        rule("grape_tempranillo", "Tempranillo", G, "red", listOf(listOf("tempranillo")), 4, 12, "$WF_AGE (Tempranillo 10-20); start and end are assumptions"),
        rule("grape_syrah", "Syrah / Shiraz", G, "red", listOf(listOf("syrah", "shiraz")), 4, 12, "$WF_AGE (5-15); $CAVERNS (4-16)"),
        rule("grape_pinot_noir", "Pinot Noir", G, "red", listOf(listOf("pinot noir")), 3, 10, "$WF_AGE (about 10); $WCR (2-15); $CAVERNS (2-8)"),
        rule("grape_malbec", "Malbec", G, "red", listOf(listOf("malbec")), 3, 10, "$WCR (5-10); $BINWISE; start 3 is an assumption"),
        rule("grape_chardonnay", "Chardonnay", G, "white", listOf(listOf("chardonnay")), 2, 10, "$WCR and $BINWISE (Chardonnay 2-10)"),
        rule("grape_sauvignon_blanc", "Sauvignon Blanc", G, "white", listOf(listOf("sauvignon blanc")), 1, 4, "$WF_AGE (about 4); $WCR (up to 7)"),
        rule("grape_riesling", "Riesling", G, "white", listOf(listOf("riesling")), 3, 8, "wineinsiders/grapetea guides (everyday 3-5, quality dry 5-10): assumption"),
        rule("grape_chenin", "Chenin Blanc", G, "white", listOf(listOf("chenin blanc", "chenin")), 1, 7, "$BINWISE (Chenin 1-7)"),
        rule("grape_viognier", "Viognier", G, "white", listOf(listOf("viognier")), 1, 4, "$WF_AGE (Viognier about 4)"),

        // ---- Type fallback ("Estimate by type") -------------------------------------------
        rule("type_red", "red wine", T, "red", emptyList(), 3, 8,
            "Median of the grape-level entries above (Cabernet 5-20, Merlot 4-10, Pinot 2-8, Malbec 5-10); assumption"),
        rule("type_white", "white wine", T, "white", emptyList(), 1, 5,
            "Median of Chardonnay 2-10, Sauvignon Blanc about 4, Chenin 1-7; assumption"),
        rule("type_rose", "rosé", T, "rose", emptyList(), 1, 2,
            "wineaffection.com and homekitchenex.com (rosé 1-2 years); angelapersonaltuscantour.com 1-1.5"),
        rule("type_sparkling", "sparkling wine", T, "sparkling", emptyList(), 1, 3,
            "wineaffection.com (simple sparkling 1-3 years)"),
        rule("type_dessert", "dessert wine", T, "dessert", emptyList(), 5, 20,
            "flatiron-wines.com (sweet Cadillac 3-15), grapeguru.de (moelleux 20-50+); very wide, $GENERAL")
    )
}
