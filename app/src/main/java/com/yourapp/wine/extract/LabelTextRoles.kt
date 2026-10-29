package com.yourapp.wine.extract

import com.yourapp.wine.merge.TextNorm

/** Conservative role recognition, never spelling completion or a producer inferred from an appellation. */
object LabelTextRoles {
    private fun compact(text: String) = TextNorm.normalize(text).filter(Char::isLetterOrDigit)

    /** Role syntax, not producer-specific vocabulary. Legal origin frames remain usable as geography. */
    fun isAdministrativeIdentity(text:String):Boolean {
        val n=TextNorm.normalize(text)
        return MetadataRules.isPackaging(text) || MetadataRules.isBoilerplate(text) ||
            Regex("""\b(?:indication geographique|indicazione geografica|earl|sarl|s\.n\.c|exploitant|proprietaire|imported by|distributed by|bodegas fundadas)\b""").containsMatchIn(n)
    }

    /** A closed classification frame may contain a non-year cuvee number; packaging/years may not. */
    fun isNumericTitle(text:String):Boolean {
        val n=TextNorm.normalize(text)
        if(isAdministrativeIdentity(text)) return false
        return Regex("""^(?:(?:gran|grand)\s+)?(?:reserva|reserve|riserva)\s+[0-9]{1,3}$""").matches(n)
    }

    /** Exact appellation core plus a bounded Villages suffix/fragment; a prefixed domaine is NOT matched. */
    fun isRhoneAppellation(text: String): Boolean {
        val value = compact(text)
        val core = "cotesdurhone"
        if (!value.startsWith(core)) return false
        val tail = value.removePrefix(core)
        return tail.isEmpty() || tail in setOf("vil", "vill", "villa", "villac", "villag", "village", "villages") ||
            (tail.length in 5..8 && tail.startsWith("vill") && TextNorm.editDistance(tail, "villages", 2) != null)
    }

    fun isDamagedRhoneAppellation(text:String):Boolean {
        val value=TextNorm.normalize(text)
        if(!value.startsWith("cotes du ") || isRhoneAppellation(text)) return false
        val tail=value.removePrefix("cotes du ").replace(" ","")
        return tail.length in 5..14 && tail.startsWith("rh") &&
            (4..6).any { n -> tail.length>=n && TextNorm.editDistance(tail.take(n),"rhone",1)!=null &&
                (tail.drop(n).isEmpty() || tail.drop(n).matches(Regex("v[ilav]{1,7}"))) }
    }

    /** Founding statements, including observed split/doubled letters; do not reject a brand containing a similar word. */
    fun isFoundingStatement(text: String): Boolean {
        val value = compact(text)
        if (value in setOf("fondee", "fonddee", "fondeee")) return true
        return (5..7).any { n ->
            value.length > n && TextNorm.editDistance(value.take(n), "fondee", 1) != null &&
                value.drop(n).matches(Regex("(?:e?en){1,2}(?:[0-9il]+)?|[0-9]{3,4}"))
        }
    }

    /** Only a whole legal line or legal prefix + legal remainder qualifies for fuzzy suppression. */
    fun isLegalStatement(text: String): Boolean {
        val value = compact(text)
        fun legalTail(tail: String): Boolean = tail.isEmpty() ||
            tail.startsWith("dorigine") || tail.startsWith("origine") ||
            tail.startsWith("doricine") || tail.startsWith("dorigin") || tail.startsWith("prote")
        val appellation = (9..12).any { n -> value.length >= n &&
            TextNorm.editDistance(value.take(n), "appellation", 2) != null && legalTail(value.drop(n)) }
        val denomination=value.length in 11..15 && TextNorm.editDistance(value,"denominazione",2)!=null
        val legalItalian=(listOf("doriginecontrollata","dioriginecontrollata").any { TextNorm.editDistance(value,it,2)!=null })
        return value.startsWith("denominaciondeor") || denomination || legalItalian || value=="egarantita" || appellation || value.matches(Regex("n?d?origineprote[\\p{L}]{2,5}"))
    }
}
