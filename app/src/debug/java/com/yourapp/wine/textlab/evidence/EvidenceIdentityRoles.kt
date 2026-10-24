package com.yourapp.wine.textlab.evidence

import com.yourapp.wine.merge.TextNorm

/** Structural roles, never a list of evaluated estates or reference answers. */
internal object EvidenceIdentityRoles {
    private val headings=setOf("chateau","domaine","maison","bodega","bodegas","estate","winery","vignoble","vignobles")
    private val particles=setOf("de","du","des","la","le","les","l","d","di","da","del","family")
    private fun words(text:String)=TextNorm.normalize(text).split(Regex("[^a-z0-9]+" )).filter(String::isNotBlank)
    fun headingOnly(text:String):Boolean {
        val words=words(text)
        return words.any(headings::contains) && words.all { it in headings || it in particles }
    }
    fun namedEstate(text:String):Boolean {
        val words=words(text)
        return words.firstOrNull() in headings && !headingOnly(text) && words.none { w -> w.any(Char::isDigit) }
    }
}
