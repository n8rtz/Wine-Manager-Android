package com.yourapp.wine.merge

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object TextNorm {
    private val apostropheAcute = Regex("(?i)\\b([dlcnst]|qu)\u00B4(?=[aeiouyh])")
    private val spacingMarks = Regex("[\u00A8\u00AF\u00B4\u00B8\u02C6\u02D8-\u02DD]+")
    private val combiningMarks = Regex("\\p{M}+")
    private val multiSpace = Regex("\\s+")

    /**
     * Replaces Latin accent marks, spacing diacritic artifacts and non-decomposing Latin letters /
     * ligatures with their plain ASCII letter equivalents while preserving case, digits and spacing.
     */
    fun scrub(text: String): String {
        if (text.all { it.code < 128 }) return text
        val precleaned = apostropheAcute.replace(text, "$1'")
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('ʼ', '\'')
            .let { spacingMarks.replace(it, "") }
        val folded = buildString(precleaned.length + 4) {
            for (ch in precleaned) {
                when (ch) {
                    'Œ' -> append("OE")
                    'œ' -> append("oe")
                    'Æ' -> append("AE")
                    'æ' -> append("ae")
                    'ẞ' -> append("SS")
                    'ß' -> append("ss")
                    'Ø' -> append('O')
                    'ø' -> append('o')
                    'Ł', 'Ŀ' -> append('L')
                    'ł', 'ŀ' -> append('l')
                    'Đ', 'Ð' -> append('D')
                    'đ', 'ð' -> append('d')
                    'Þ' -> append("TH")
                    'þ' -> append("th")
                    'İ' -> append('I')
                    'ı' -> append('i')
                    'Ĳ' -> append("IJ")
                    'ĳ' -> append("ij")
                    'Ħ' -> append('H')
                    'ħ' -> append('h')
                    'Ŧ' -> append('T')
                    'ŧ' -> append('t')
                    else -> append(ch)
                }
            }
        }
        return Normalizer.normalize(folded, Normalizer.Form.NFD).replace(combiningMarks, "")
    }

    /** No coordinates: ASCII-folded NFKD matching form; callers retain scrubbed ASCII text. */
    fun normalize(text: String): String = Normalizer.normalize(scrub(text), Normalizer.Form.NFKD)
        .replace(combiningMarks, "")
        .replace(multiSpace, " ").trim().lowercase(Locale.ROOT)

    /** No coordinates: compare normalized strings using Jaro-Winkler, without external models. */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val radius = max(0, max(a.length, b.length) / 2 - 1)
        val am = BooleanArray(a.length)
        val bm = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            for (j in max(0, i - radius)..min(b.lastIndex, i + radius)) {
                if (!bm[j] && a[i] == b[j]) {
                    am[i] = true; bm[j] = true; matches++; break
                }
            }
        }
        if (matches == 0) return 0.0
        val ac = a.indices.filter { am[it] }.map { a[it] }
        val bc = b.indices.filter { bm[it] }.map { b[it] }
        val transpositions = ac.indices.count { ac[it] != bc[it] } / 2.0
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions) / m) / 3.0
        val prefix = a.zip(b).take(4).takeWhile { it.first == it.second }.size
        return if (jaro > 0.7) jaro + prefix * 0.1 * (1.0 - jaro) else jaro
    }

    /** No coordinates: bounded Levenshtein; null means the distance exceeds the given bound. */
    fun editDistance(a: String, b: String, bound: Int = 2): Int? {
        require(bound >= 0)
        if (abs(a.length - b.length) > bound) return null
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1) { bound + 1 }
            row[0] = i + 1
            val start = max(1, i + 1 - bound)
            val end = min(b.length, i + 1 + bound)
            for (j in start..end) {
                row[j] = minOf(row[j - 1] + 1, previous[j] + 1,
                    previous[j - 1] + if (a[i] == b[j - 1]) 0 else 1)
            }
            if (row.minOrNull()!! > bound) return null
            previous = row
        }
        return previous[b.length].takeIf { it <= bound }
    }
}
