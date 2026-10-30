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

    /**
     * Minor words kept lowercase by [displayCase] unless they open the value:
     * French/Italian/Spanish/Portuguese/German articles, prepositions and
     * conjunctions common in wine names, plus English the/of/and/a/an.
     */
    private val displayCaseMinorWords = setOf(
        "de", "du", "des", "di", "da", "del", "dello", "della", "delle", "dei", "degli",
        "le", "la", "les", "il", "el", "los", "las", "y", "e", "et", "au", "aux", "en",
        "sur", "von", "zu", "zum", "zur", "dos", "das", "do", "the", "of", "and", "a", "an")

    /**
     * Display standardization for final extracted fields: the first letter of
     * each word is capitalized and every other letter is lowercased, except
     * minor words (see [displayCaseMinorWords]) which stay lowercase unless
     * they open the value. Hyphenated parts follow the same rule, a French
     * elision prefix (d'Agassac, l'Ermitage) keeps its lowercase letter
     * mid-value, and a possessive tail (Hawk's) is not treated as a new word.
     * Whitespace runs collapse to single spaces. Digits and punctuation are
     * preserved.
     */
    fun displayCase(text: String): String {
        if (text.isBlank()) return text
        return text.trim().split(multiSpace)
            .mapIndexed { index, word -> displayCaseWord(word, first = index == 0) }
            .joinToString(" ")
    }

    private fun displayCaseWord(word: String, first: Boolean): String {
        val lowered = word.lowercase(Locale.ROOT)
        if (!first && lowered.filter(Char::isLetter) in displayCaseMinorWords) return lowered
        return lowered.split('-').mapIndexed { partIndex, part ->
            if (partIndex > 0 && part.filter(Char::isLetter) in displayCaseMinorWords) part
            else displayCaseSegment(part, elidedPrefix = !first && partIndex == 0 &&
                part.length > 2 && part[1] == '\'' && (part[0] == 'd' || part[0] == 'l'))
        }.joinToString("-")
    }

    private fun displayCaseSegment(segment: String, elidedPrefix: Boolean): String {
        val out = StringBuilder(segment.length)
        var segmentStart = true
        for ((index, ch) in segment.withIndex()) {
            when {
                ch.isLetter() -> {
                    out.append(if (segmentStart && !(elidedPrefix && index == 0)) ch.uppercaseChar() else ch)
                    segmentStart = false
                }
                ch == '.' -> { out.append(ch); segmentStart = true }
                ch == '\'' -> {
                    out.append(ch)
                    // A possessive/contraction tail ('s, 't) stays lowercase; a
                    // real following segment (d'Agassac, O'Brien) is capitalized.
                    segmentStart = segment.substring(index + 1).takeWhile(Char::isLetter).length > 1
                }
                ch.isDigit() -> { out.append(ch); segmentStart = false }
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

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
