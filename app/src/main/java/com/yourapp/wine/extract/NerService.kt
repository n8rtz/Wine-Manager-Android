package com.yourapp.wine.extract

import com.yourapp.wine.fill.FieldCandidate
import com.yourapp.wine.merge.LabelLine

/** Optional extra signal only. No TFLite dependency or model/tokenizer is assumed or required. */
interface NerService {
    val available: Boolean
    /** Input line evidence is normalized rectified-label space; never replaces gazetteer/pattern validation. */
    fun extraSignals(lines: List<LabelLine>): List<FieldCandidate>
}

/** Hook remains disabled until real assets/ner/model.tflite AND a specified tokenizer.json are supplied. */
object NoNerService : NerService {
    override val available = false
    /** Normalized rectified line evidence is intentionally unused without a supplied model and tokenizer contract. */
    override fun extraSignals(lines: List<LabelLine>): List<FieldCandidate> = emptyList()
}
