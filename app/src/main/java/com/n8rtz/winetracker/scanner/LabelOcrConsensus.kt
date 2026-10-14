package com.n8rtz.winetracker.scanner

import java.text.Normalizer
import java.util.Locale

/**
 * Fuses the OCR passes of one scan instead of trusting a whole pass at once.
 *
 * A single pass either reads a word or it does not: the full-frame pass often keeps the printed
 * lines but drops a calligraphic cuvée, while the enhanced crop reads that cuvée and clips the
 * small print. Merging line by line keeps the words both passes agree on, adds the lines only one
 * pass saw, and reports how much of the chosen reading the other pass corroborated.
 *
 * Only pure text handling lives here so the merge can be unit tested without ML Kit or a device.
 */
internal object LabelOcrConsensus {
    /** Similarity above which two lines are treated as the same physical label line. */
    private const val MIN_LINE_SIMILARITY = 0.8
    private val whitespaceRegex = Regex("\\s+")

    data class Result(
        val text: String,
        /** Share of the winning pass's lines that at least one other pass also read. */
        val agreement: Double,
        val supportedLines: Int,
        val totalLines: Int
    )

    private data class LineSource(val hypothesisIndex: Int, val line: String)

    /** Mutable on purpose: never rely on equality or hashing of a Cluster, use its index. */
    private class Cluster(val key: String) {
        val members = mutableListOf<LineSource>()
        var representative: String = ""
    }

    /**
     * Returns null when no pass produced text. When the passes share no line at all, the highest
     * scoring pass is used as-is: mixing two unrelated readings would invent a label that is on no
     * bottle.
     */
    fun merge(
        hypotheses: List<LabelOcrHypothesis>,
        parser: WineLabelParser,
        dictionary: WineReferenceDictionary
    ): Result? {
        val passes = hypotheses.map { hypothesis ->
            hypothesis.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        }
        val readablePasses = hypotheses.indices.filter { passes[it].isNotEmpty() }
        if (readablePasses.isEmpty()) return null
        if (readablePasses.size == 1) {
            // One usable pass cannot be corroborated by anything, so every line is uncontradicted.
            val lines = passes[readablePasses.single()]
            return Result(
                text = lines.joinToString("\n"),
                agreement = 1.0,
                supportedLines = lines.size,
                totalLines = lines.size
            )
        }

        val rankedIndexes = hypotheses.indices.sortedByDescending { index ->
            LabelOcrHypothesisSelector.evidenceScore(hypotheses[index], parser, dictionary)
        }
        val clusters = clusterLines(passes)
        val supported = clusters.filter { distinctSources(it) > 1 }

        val primaryIndex = rankedIndexes.first { passes[it].isNotEmpty() }
        if (supported.isEmpty()) {
            // No line was read twice: keep today's single-pass decision rather than inventing a
            // label by mixing two unrelated readings.
            val fallback = if (hypotheses.size == 2) {
                LabelOcrHypothesisSelector.choose(hypotheses[0], hypotheses[1], parser, dictionary)
            } else {
                hypotheses[primaryIndex]
            }
            val fallbackLines = fallback.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            return Result(
                text = fallbackLines.joinToString("\n"),
                agreement = 0.0,
                supportedLines = 0,
                totalLines = fallbackLines.size
            )
        }

        val mergedLines = mergeLines(passes, clusters, primaryIndex, rankedIndexes, dictionary)
        val primaryLines = passes[primaryIndex]
        val supportedPrimaryLines = primaryLines.count { line ->
            supported.any { cluster -> cluster.members.any { it.line == line } }
        }
        return Result(
            text = mergedLines.joinToString("\n"),
            agreement = supportedPrimaryLines.toDouble() / primaryLines.size,
            supportedLines = supportedPrimaryLines,
            totalLines = primaryLines.size
        )
    }

    private fun clusterLines(passes: List<List<String>>): List<Cluster> {
        val clusters = mutableListOf<Cluster>()
        passes.forEachIndexed { hypothesisIndex, lines ->
            lines.forEach { line ->
                val key = comparableKey(line)
                val cluster = clusters.firstOrNull { similarity(it.key, key) >= MIN_LINE_SIMILARITY }
                    ?: Cluster(key = key).also(clusters::add)
                cluster.members += LineSource(hypothesisIndex, line)
            }
        }
        return clusters
    }

    private fun mergeLines(
        passes: List<List<String>>,
        clusters: List<Cluster>,
        primaryIndex: Int,
        rankedIndexes: List<Int>,
        dictionary: WineReferenceDictionary
    ): List<String> {
        val output = mutableListOf<String>()
        val clusterIndexOfLine = mutableMapOf<String, Int>()
        clusters.forEachIndexed { clusterIndex, cluster ->
            cluster.members.forEach { member ->
                clusterIndexOfLine[member.line + "@" + member.hypothesisIndex] = clusterIndex
            }
        }
        val emitted = mutableSetOf<Int>()
        val canonicalSpellings = mutableMapOf<String, Boolean>()

        fun representativeOf(clusterIndex: Int): String {
            val cluster = clusters[clusterIndex]
            if (cluster.representative.isNotEmpty()) return cluster.representative
            val best = cluster.members
                .sortedWith(
                    // Prefer a reading the vocabulary already knows verbatim, then the winning pass,
                    // then the fuller line: "MERLOT" wins over "MERL0T".
                    compareByDescending<LineSource> { source ->
                        canonicalSpellings.getOrPut(source.line) {
                            dictionary.correct(source.line).correctedText.equals(source.line, ignoreCase = true)
                        }
                    }
                        .thenBy { it.hypothesisIndex != rankedIndexes.first() }
                        .thenByDescending { it.line.length }
                )
                .first()
            cluster.representative = best.line
            return best.line
        }

        // The winning pass defines the reading order of the lines it managed to read.
        passes[primaryIndex].forEach { line ->
            val clusterIndex = clusterIndexOfLine[line + "@" + primaryIndex] ?: return@forEach
            if (emitted.add(clusterIndex)) output += representativeOf(clusterIndex)
        }

        // Lines only another pass read are inserted where that pass saw them, next to the anchor
        // line that both passes know.
        rankedIndexes.filter { it != primaryIndex }.forEach { hypothesisIndex ->
            val lines = passes[hypothesisIndex]
            lines.forEachIndexed { lineIndex, line ->
                val clusterIndex = clusterIndexOfLine[line + "@" + hypothesisIndex] ?: return@forEachIndexed
                if (clusterIndex in emitted) return@forEachIndexed
                val anchorIndex = (lineIndex + 1 until lines.size).firstNotNullOfOrNull { followingIndex ->
                    clusterIndexOfLine[lines[followingIndex] + "@" + hypothesisIndex]
                        ?.takeIf { it in emitted }
                }
                val position = anchorIndex
                    ?.let { index -> output.indexOf(representativeOf(index)) }
                    ?: output.size
                output.add(position.coerceIn(0, output.size), representativeOf(clusterIndex))
                emitted += clusterIndex
            }
        }
        return output
    }

    private fun distinctSources(cluster: Cluster): Int =
        cluster.members.map(LineSource::hypothesisIndex).distinct().size

    /** Diacritic- and punctuation-insensitive form used only to compare two OCR readings of one line. */
    private fun comparableKey(line: String): String = Normalizer
        .normalize(line.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .filter { it.isLetterOrDigit() }

    private fun similarity(left: String, right: String): Double {
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val distance = editDistance(left, right)
        return 1.0 - distance.toDouble() / maxOf(left.length, right.length)
    }

    private fun editDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }
}

/**
 * Decides whether a scan is safe to prefill without asking the user to double-check.
 *
 * This is deliberately about *contradictions*, not about gaps: one pass failing to read the small
 * print is normal, while two passes disagreeing about the producer or the vintage means the reading
 * cannot be trusted. Nothing here decides the wine; it only reports how much the passes agree.
 */
internal object LabelScanAssessment {
    private const val MIN_AGREEMENT = 0.5

    data class Report(
        val agreement: Double,
        val contradictions: List<String>,
        val needsReview: Boolean
    )

    fun assess(
        consensus: LabelOcrConsensus.Result,
        perPassResults: List<ScannedWine>,
        mergedResult: ScannedWine
    ): Report {
        val contradictions = buildList {
            // The wine name is often a fallback guess, so only identity-critical fields are compared.
            addAll(compareField("maker", perPassResults.map { it.maker }))
            addAll(compareField("vintage", perPassResults.map { it.vintage }))
            addAll(compareField("country", perPassResults.map { it.country }))
            addAll(compareField("region", perPassResults.map { it.region }))
        }
        // A maker or name always exists as a fallback echo of the OCR lines, so it proves nothing;
        // only fields that had to be recognised count as evidence that this is a wine label.
        val hasWineEvidence = listOf(
            mergedResult.vintage,
            mergedResult.country,
            mergedResult.region,
            mergedResult.subregion,
            mergedResult.type
        ).any { !it.isNullOrBlank() }
        return Report(
            agreement = consensus.agreement,
            contradictions = contradictions,
            needsReview = contradictions.isNotEmpty() ||
                !hasWineEvidence ||
                consensus.agreement < MIN_AGREEMENT
        )
    }

    private fun compareField(field: String, values: List<String?>): List<String> {
        val distinct = values
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase(Locale.ROOT) }
        return if (distinct.size > 1) listOf("$field=${distinct.joinToString(" / ")}") else emptyList()
    }
}
