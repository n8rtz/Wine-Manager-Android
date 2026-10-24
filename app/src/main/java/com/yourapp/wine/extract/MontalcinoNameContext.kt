package com.yourapp.wine.extract

import com.yourapp.wine.fill.*
import com.yourapp.wine.merge.LabelLine
import com.yourapp.wine.merge.TextNorm

/** Montalcino alone is not a style identifier: both Brunello and Rosso are real appellations. */
object MontalcinoNameContext {
    fun supported(lines:List<LabelLine>):FieldCandidate? {
        val location=lines.filter { Regex("\\bmontalcino\\b").containsMatchIn(TextNorm.normalize(it.text)) && !MetadataRules.isBoilerplate(it.text) }
        if(location.isEmpty()) return null
        val cues=lines.filterNot { MetadataRules.isBoilerplate(it.text) || MetadataRules.isPackaging(it.text) }.flatMap { line ->
            Regex("[\\p{L}]+").findAll(TextNorm.normalize(line.text)).mapNotNull { word ->
                val style=when {
                    word.value.length>=7 && TextNorm.editDistance(word.value,"brunello",1)!=null -> "Brunello"
                    word.value=="rosso" -> "Rosso"
                    else -> null
                }
                style?.let { it to line }
            }.toList()
        }
        if(cues.map { it.first }.distinct().size!=1) return null
        val style=cues.first().first
        return FieldCandidate(FieldId.WINE_NAME,"$style di Montalcino",UiState.YELLOW,
            "Observed $style text plus separately identified Montalcino; contextual wine-name suggestion, confirm; not inferred from town alone",
            (cues.flatMap { it.second.tokens }+location.flatMap { it.tokens }).distinct())
    }
}
