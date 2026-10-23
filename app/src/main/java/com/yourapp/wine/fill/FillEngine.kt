package com.yourapp.wine.fill

import com.yourapp.wine.extract.FieldExtractor
import com.yourapp.wine.extract.WineTextNormalizer
import com.yourapp.wine.kb.SupplementedWineKnowledgeBase
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.kb.PlaceHierarchy
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.LineBuilder
import com.yourapp.wine.merge.TokenMerger
import com.yourapp.wine.ocr.TokenRecord

class FillEngine(baseKb: WineKnowledgeBase) {
    private val kb = SupplementedWineKnowledgeBase(baseKb)
    private val normalizer = WineTextNormalizer(kb)
    private val merger = TokenMerger()
    private val lines = LineBuilder()
    private val hierarchy = PlaceHierarchy(kb).apply { snapshot() }
    private val extractor = FieldExtractor(kb, hierarchy = hierarchy, normalizer = normalizer)
    private val validator = Validator(kb, hierarchy)

    /** Consumes original/rectified normalized token boxes; always returns explicit states for all six fields. */
    fun fill(records: List<TokenRecord>, currentYear: Int, context: ConfidenceContext = ConfidenceContext()): FillResult {
        val tokens = merger.merge(records)
        val built=lines.build(tokens)
        val titleConnectors=com.yourapp.wine.extract.LabelAlternativeEvidence.connectors(built,records)
        val extracted=extractor.extract(built, currentYear,titleConnectors)
        val validated = validator.validate(extracted, currentYear)
        val fields = validated.mapValues { (_, candidate) ->
            if (candidate.state == UiState.RED) candidate.copy(value = "") else {
                val reasons = mutableListOf<String>()
                if (!context.labelFound) reasons += "No label quad; full-frame fallback is YELLOW maximum"
                if (context.deadlineReached) reasons += "Scan budget reached; partial evidence requires confirmation"
                if (context.weakQuality) reasons += "Weak quality / incomplete reading"
                if (candidate.sources.any { !it.hasReliableSupport }) reasons += "Insufficient measured confidence; estimated scores require confirmation"
                if (candidate.sources.any { token -> token.members.map { normalizer.comparison(it.textRaw) }.distinct().size > 1 }) reasons += "OCR readings disagree"
                if (candidate.sources.any { source -> tokens.any { other -> other !== source &&
                        BoxMath.iou(source.bboxRect, other.bboxRect) >= 0.25f &&
                        normalizer.comparison(source.canonical) != normalizer.comparison(other.canonical) } }) reasons += "Overlapping alternative OCR reading"
                if (reasons.isEmpty()) candidate else candidate.copy(state = UiState.YELLOW,
                    reason = candidate.reason + "; " + reasons.joinToString("; "))
            }
        }
        val hint = context.hint ?: if (fields.values.all { it.state == UiState.RED }) "Retake the label or enter details manually" else null
        return FillResult(fields, hint,extracted.alternatives,extracted.roles.mapIndexed { i,role ->
            "line=$i; ${role.reason}; bbox=${role.line.bboxRect.left},${role.line.bboxRect.top},${role.line.bboxRect.right},${role.line.bboxRect.bottom}; text=${role.line.text}; sources=${role.line.tokens.flatMap { it.members }.joinToString { it.engineId+"/"+it.variantId+":"+it.textRaw }}"
        },extracted.spellingSuggestions,extracted.geographyPath,fallbackCandidates=extracted.fallbackCandidates)
    }

    /** No coordinates: a failed quality gate fills nothing and carries a retake/manual-entry hint. */
    fun red(hint: String): FillResult = FillResult(FieldId.entries.associateWith {
        FieldCandidate(it, "", UiState.RED, hint, emptyList())
    }, hint)
}
