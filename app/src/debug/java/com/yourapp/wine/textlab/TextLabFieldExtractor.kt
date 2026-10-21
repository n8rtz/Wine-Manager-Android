package com.yourapp.wine.textlab

import com.yourapp.wine.extract.FieldExtractor
import com.yourapp.wine.fill.CellarFieldPresentation
import com.yourapp.wine.fill.FillResult
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.fill.Validator
import com.yourapp.wine.kb.SupplementedWineKnowledgeBase
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.merge.LineBuilder
import com.yourapp.wine.ocr.MergedToken
import com.yourapp.wine.ocr.TokenRecord

/** Debug reporting, NOT production autofill. Retain accepted OCR without the production merge gate.
 * Never raise a score to pass a threshold, fuse engines/passes, or report GREEN lab suggestions.
 */
internal class TextLabFieldExtractor(baseKb:WineKnowledgeBase) {
    private val kb=SupplementedWineKnowledgeBase(baseKb)
    private val extractor=FieldExtractor(kb)
    private val validator=Validator(kb)
    fun extract(records:List<TokenRecord>,year:Int):FillResult {
        val keys=records.map { it.engineId to it.variantId }.distinct()
        require(keys.size<=1) { "Lab field extraction must be isolated to one engine/pass" }
        val tokens=records.map { record ->
            require(record.textNorm.isNotBlank() && record.score.isFinite() && record.score in 0f..1f &&
                record.bboxRect.width()>0 && record.bboxRect.height()>0)
            // Each accepted line is a single observation, not an independently corroborated merge.
            MergedToken(record.textRaw,listOf(record.copy(scoreIsEstimated=true)),record.score,record.bboxRect)
        }
        val extracted=extractor.extract(LineBuilder().build(tokens),year)
        val fields=validator.validate(extracted,year).mapValues { (_,field) ->
            if(field.state==UiState.RED || field.value.isBlank()) field.copy(value="",state=UiState.RED)
            else field.copy(state=UiState.YELLOW,reason=field.reason+"; lab-only uncalibrated suggestion; confirm")
        }
        return CellarFieldPresentation.apply(FillResult(fields,null,extracted.alternatives,extracted.roles.mapIndexed { i,r -> "line=$i; ${r.reason}; bbox=${r.line.bboxRect.left},${r.line.bboxRect.top},${r.line.bboxRect.right},${r.line.bboxRect.bottom}; text=${r.line.text}; sources=${r.line.tokens.joinToString { it.canonical }}" }))
    }
}
