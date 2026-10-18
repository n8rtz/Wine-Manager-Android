package com.yourapp.wine.kb

/** Existing authored wine-style terms shared with the legacy parser; not producer identities. */
object BundledWineStyles {
    val terms: List<WineTerm> by lazy { parseWineTerms(source) }
    val source = """
        Amarone della Valpolicella|Amarone
        Beaujolais Nouveau
        Bordeaux Blend|Meritage
        Cava
        Champagne
        Chianti Classico
        Crémant|Cremant
        Franciacorta
        Ice Wine|Eiswein;Vin de glace
        Lambrusco
        Madeira
        Marsala
        Moscato d'Asti|Moscato d Asti
        Passito
        Port|Porto;Port Wine
        Prosecco
        Sauternes
        Sekt
        Sherry|Jerez;Xérès;Xeres
        Tokaji Aszú|Tokaji;Tokay
        Vin Santo
        Vinho Verde
        Orange Wine
        Pét-Nat|Pet Nat;Pétillant Naturel
        Crémant de Bourgogne
        Crémant d'Alsace
        Crémant de Loire
        Crémant de Limoux
        Gran Reserva
        Crianza
        Shiraz Cabernet|Cabernet Shiraz;Shiraz Cabernet Sauvignon
        Brut Nature
        Blanc de Blancs
        Blanc de Noirs
        Rosé|Rose Wine
    """
}
