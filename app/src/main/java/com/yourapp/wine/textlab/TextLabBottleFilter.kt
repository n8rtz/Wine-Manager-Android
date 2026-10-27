package com.yourapp.wine.textlab

import android.graphics.RectF
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lab v4 spatial bottle-column & medal-sticker filter plus conditional tiny-footprint zoom-crop.
 *
 * Operates purely on normalized `[0, 1]` coordinates and general wine-industry competition/critic
 * sticker vocabulary (never corpus-specific strings), so:
 * 1) Post-OCR filtering works identically on both fresh OCR and Cached OCR runs without touching
 *    Prod v1.0-v3.0 baselines.
 * 2) Conditional zoom-crop triggers only when the primary bottle text cluster occupies `<= 10%`
 *    of the full frame, padding generously above (neck/collar), below (foot strip), and laterally
 *    so distant tabletop bottles gain up to 2x resolution without clipping bottle extremities.
 */
internal object TextLabBottleFilter {
    const val MAX_TINY_FOOTPRINT_AREA = 0.10f
    const val MAX_ZOOM_CROP_AREA = 0.40f
    const val MAX_ZOOM_CROP_SPAN = 0.85f

    private val whitespaceRegex = Regex("\\s+")
    private val fourDigitYearRegex = Regex("\\b(?:19\\d{2}|20\\d{2})\\b")
    private val medalSealRegex = Regex(
        "\\b(?:" +
            "concours|concor[s]?|concol|" +
            "medaille[s]?|medailli|medalla|medaglia|" +
            "gold\\s+medal|silver\\s+medal|bronze\\s+medal|double\\s+gold|" +
            "challenge\\s+international|gilbert\\s*&?\\s*gaillard|feminalise|" +
            "mondial\\s+du|berliner\\s+wein\\s+trophy|" +
            "decanter|wine\\s+spectator|wine\\s+enthusiast|james\\s+suckling|robert\\s+parker" +
            ")\\b",
        RegexOption.IGNORE_CASE,
    )

    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val centerX: Float get() = 0.5f * (left + right)
        val centerY: Float get() = 0.5f * (top + bottom)
        val area: Float get() = width * height
    }

    data class Entry<T>(
        val payload: T,
        val text: String,
        val rectBox: Box,
        val origBox: Box = rectBox,
    )

    data class Dropped<T>(
        val entry: Entry<T>,
        val reason: String,
    )

    data class FilterResult<T>(
        val kept: List<T>,
        val dropped: List<Dropped<T>>,
    )

    fun filterTokens(records: List<TokenRecord>): FilterResult<TokenRecord> {
        val entries = records.map { token ->
            Entry(
                payload = token,
                text = token.textRaw,
                rectBox = token.bboxRect.toBox(),
                origBox = token.bboxOrig.toBox(),
            )
        }
        return filterEntries(entries)
    }

    fun <T> filterEntries(entries: List<Entry<T>>): FilterResult<T> {
        if (entries.size < 4) return FilterResult(entries.map { it.payload }, emptyList())
        val primary = primaryBottleCluster(entries) ?: return FilterResult(entries.map { it.payload }, emptyList())
        val core = primary.filter { alnumCount(it.text) >= 3 }.ifEmpty { primary }

        val rawLeft = core.minOf { it.rectBox.left }
        val rawRight = core.maxOf { it.rectBox.right }
        val colW = (rawRight - rawLeft).coerceAtLeast(0.14f)

        val wideLines = core.filter { it.rectBox.width >= 0.38f * colW }
        val axisSource = wideLines.ifEmpty { core }
        val sortedCenters = axisSource.map { it.rectBox.centerX }.sorted()
        val axisCx = sortedCenters[sortedCenters.size / 2]
        val centeredLayout = wideLines.size >= 3 &&
            wideLines.maxOf { abs(it.rectBox.centerX - axisCx) } <= 0.12f

        val colLeft = (rawLeft - 0.08f).coerceAtLeast(0f)
        val colRight = (rawRight + 0.08f).coerceAtMost(1f)
        val mainBottom = axisSource.maxOf { it.rectBox.bottom }

        val stageOneKept = ArrayList<Entry<T>>(entries.size)
        val dropped = ArrayList<Dropped<T>>()

        for (entry in entries) {
            val b = entry.rectBox
            val ob = entry.origBox
            val bw = b.width
            val bcx = b.centerX
            val clean = TextNorm.scrub(entry.text).replace(whitespaceRegex, " ").trim()
            val alnum = alnumCount(clean)
            val letters = clean.filter { it.isLetter() }
            val hasDigits = clean.any { it.isDigit() }
            val hasYear = fourDigitYearRegex.containsMatchIn(clean)

            // 1. Outside padded primary bottle column (neighboring bottles, shelf tags, side watermarks).
            if (b.right < colLeft + 0.02f || b.left > colRight - 0.02f || bcx < colLeft || bcx > colRight) {
                dropped.add(Dropped(entry, "outside_bottle_column"))
                continue
            }

            // 2. Extreme photo-edge camera/website watermark or statutory border banner.
            if ((ob.top >= 0.965f || ob.bottom <= 0.035f) && abs(bcx - axisCx) > 0.20f * colW && !hasYear) {
                dropped.add(Dropped(entry, "border_watermark"))
                continue
            }

            // 3. Explicit competition medal or critic score sticker text (badge-sized).
            if (bw <= 0.45f * colW && medalSealRegex.containsMatchIn(clean)) {
                dropped.add(Dropped(entry, "medal_seal_text"))
                continue
            }

            // 4. Detached lower-bottle round competition sticker below main wide label lines.
            if (centeredLayout &&
                b.top >= mainBottom + 0.025f &&
                bw <= minOf(0.18f, 0.25f * colW) &&
                abs(bcx - axisCx) >= 0.12f * colW &&
                alnum <= 8 &&
                !hasYear
            ) {
                dropped.add(Dropped(entry, "detached_lower_sticker"))
                continue
            }

            // 5. Tiny off-axis lowercase cursive margin signature scribble inside a centered label.
            // Never drops uppercase label words (e.g. CHATEAU/HERITAGE) or split 2-digit vintage halves.
            if (centeredLayout &&
                !hasDigits &&
                letters.isNotEmpty() &&
                letters.length <= 4 &&
                letters.all { it.isLowerCase() } &&
                bw <= minOf(0.16f, 0.22f * colW) &&
                abs(bcx - axisCx) >= 0.28f * colW
            ) {
                dropped.add(Dropped(entry, "margin_signature_scribble"))
                continue
            }

            stageOneKept.add(entry)
        }

        // 6. Small rim/score fragments immediately surrounding an explicit medal/critic sticker box.
        val medalBoxes = dropped.filter { it.reason == "medal_seal_text" }.map { it.entry.rectBox }
        val finalKept = if (medalBoxes.isEmpty()) {
            stageOneKept
        } else {
            val survivors = ArrayList<Entry<T>>(stageOneKept.size)
            for (entry in stageOneKept) {
                val b = entry.rectBox
                val clean = TextNorm.scrub(entry.text)
                if (alnumCount(clean) <= 8 &&
                    b.width <= minOf(0.20f, 0.35f * colW) &&
                    !fourDigitYearRegex.containsMatchIn(clean) &&
                    medalBoxes.any { mb ->
                        abs(b.centerX - mb.centerX) <= 0.22f * colW && abs(b.centerY - mb.centerY) <= 0.10f
                    }
                ) {
                    dropped.add(Dropped(entry, "near_medal_seal_fragment"))
                } else {
                    survivors.add(entry)
                }
            }
            survivors
        }

        // Fail-safe: never filter away every reading on a photo.
        if (finalKept.isEmpty()) return FilterResult(entries.map { it.payload }, emptyList())
        return FilterResult(finalKept.map { it.payload }, dropped)
    }

    /**
     * Proposes a generously padded bottle zoom-crop in `[0, 1]` full-image coordinates when the
     * primary bottle's text footprint is tiny (`<= 10%` of the frame), preserving room above for
     * neck bands/collars and below for foot strips.
     */
    fun proposeZoomCrop(journal: TextLabJournal): TextCrop? {
        val entries = journalEntries(journal)
        if (entries.size < 2) return null
        val primary = primaryBottleCluster(entries) ?: entries
        val left = primary.minOf { it.rectBox.left }
        val top = primary.minOf { it.rectBox.top }
        val right = primary.maxOf { it.rectBox.right }
        val bottom = primary.maxOf { it.rectBox.bottom }
        val w = right - left
        val h = bottom - top
        if (w <= 0.02f || h <= 0.02f) return null
        val footprintArea = w * h
        if (footprintArea > MAX_TINY_FOOTPRINT_AREA) return null

        val padX = maxOf(0.08f, 0.35f * w)
        val padTop = maxOf(0.18f, 0.80f * h)
        val padBottom = maxOf(0.12f, 0.45f * h)
        val crop = TextCrop.between(
            left - padX,
            top - padTop,
            right + padX,
            bottom + padBottom,
        )
        val cropW = crop.right - crop.left
        val cropH = crop.bottom - crop.top
        if (cropW < 0.12f || cropH < 0.12f) return null
        if (cropW * cropH > MAX_ZOOM_CROP_AREA || maxOf(cropW, cropH) > MAX_ZOOM_CROP_SPAN) return null
        return crop
    }

    fun shouldAcceptZoomPass(fullJournal: TextLabJournal, zoomJournal: TextLabJournal): Boolean {
        val mlSummary = zoomJournal.rows.lastOrNull {
            it["candidate"] == "mlkit_reference" && it["stage"] == "summary"
        } ?: return false
        if (mlSummary["status"] !in setOf("complete", "no_text")) return false

        for (candidateId in zoomJournal.selected) {
            val candSummary = zoomJournal.rows.lastOrNull {
                it["candidate"] == candidateId && it["stage"] == "candidate_summary"
            } ?: return false
            if (candSummary["status"] !in setOf("complete", "no_text", "no_regions")) return false
        }

        val zoomEntries = journalEntries(zoomJournal)
        if (zoomEntries.isEmpty()) return false
        val fullEntries = journalEntries(fullJournal)
        val fullPrimary = primaryBottleCluster(fullEntries) ?: fullEntries
        val fullAlnum = fullPrimary.sumOf { alnumCount(it.text) }
        val zoomPrimary = primaryBottleCluster(zoomEntries) ?: zoomEntries
        val zoomAlnum = zoomPrimary.sumOf { alnumCount(it.text) }
        val minRequired = if (fullAlnum <= 0) 1 else maxOf(4, (fullAlnum * 0.80).toInt())
        return zoomAlnum >= minRequired
    }

    fun promoteZoomPass(target: TextLabJournal, zoomJournal: TextLabJournal, zoomCrop: TextCrop) {
        val prefix = target.rows.filter { row ->
            row["candidate"] == "shared" && row["stage"] in setOf("image_queued", "service_setup")
        }
        val merged = ArrayList<Map<String, String>>(prefix.size + zoomJournal.rows.size)
        for (row in prefix + zoomJournal.rows) {
            val copy = LinkedHashMap(row)
            copy["sequence"] = (merged.size + 1).toString()
            merged.add(copy)
        }
        target.rows.clear()
        target.rows.addAll(merged)
        for (key in ZOOM_METADATA_KEYS) {
            val value = zoomJournal.metadata[key]
            if (!value.isNullOrBlank()) target.metadata[key] = value
        }
        target.metadata["auto_zoom_crop"] = String.format(
            Locale.ROOT,
            "%.4f %.4f %.4f %.4f",
            zoomCrop.left,
            zoomCrop.top,
            zoomCrop.right,
            zoomCrop.bottom,
        )
    }

    private fun <T> primaryBottleCluster(entries: List<Entry<T>>): List<Entry<T>>? {
        val substantial = entries.filter { entry ->
            alnumCount(entry.text) >= 2 &&
                entry.rectBox.width >= 0.03f &&
                entry.rectBox.height >= 0.006f
        }
        if (substantial.size < 2) return null

        val n = substantial.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }
        fun union(a: Int, b: Int) {
            parent[find(a)] = find(b)
        }

        for (i in 0 until n) {
            val a = substantial[i].rectBox
            for (j in i + 1 until n) {
                val b = substantial[j].rectBox
                val hOver = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) /
                    maxOf(1e-4f, minOf(a.width, b.width))
                val vOver = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f) /
                    maxOf(1e-4f, minOf(a.height, b.height))
                val hGap = (maxOf(a.left, b.left) - minOf(a.right, b.right)).coerceAtLeast(0f)
                if (hOver >= 0.25f || abs(a.centerX - b.centerX) <= 0.18f || (vOver >= 0.50f && hGap <= 0.08f)) {
                    union(i, j)
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<Entry<T>>>()
        for (idx in 0 until n) {
            groups.getOrPut(find(idx)) { ArrayList() }.add(substantial[idx])
        }
        return groups.values.maxByOrNull { group ->
            val area = group.sumOf { it.rectBox.area.toDouble() }
            val chars = group.sumOf { alnumCount(it.text) }.coerceAtLeast(1)
            val cx = 0.5 * (group.minOf { it.rectBox.left } + group.maxOf { it.rectBox.right })
            area * sqrt(chars.toDouble()) * (1.0 - 0.5 * abs(cx - 0.5))
        }
    }

    private fun journalEntries(journal: TextLabJournal): List<Entry<Map<String, String>>> =
        listOf("mlkit_reference", "candidate_3").flatMap { candidate ->
            TextLabWineTable.readings(journal, candidate, "").mapNotNull { row ->
                val rect = parseBox(row["base_box_normalized"]) ?: return@mapNotNull null
                val orig = parseBox(row["original_box_normalized"]) ?: rect
                Entry(row, row["raw_text"].orEmpty(), rect, orig)
            }
        }

    internal fun parseBox(value: String?): Box? {
        val p = value?.trim()?.split(whitespaceRegex)?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if (p.size == 4 && p.all { it.isFinite() && it in 0f..1f } && p[2] > p[0] && p[3] > p[1]) {
            Box(p[0], p[1], p[2], p[3])
        } else {
            null
        }
    }

    private fun alnumCount(text: String): Int = text.count { it.isLetterOrDigit() }

    private fun RectF.toBox(): Box = Box(left, top, right, bottom)

    private val ZOOM_METADATA_KEYS = listOf(
        "crop_sha256",
        "original_width",
        "original_height",
        "working_width",
        "working_height",
        "crop_x",
        "crop_y",
        "crop_width",
        "crop_height",
        "input_width",
        "input_height",
        "enlarge",
        "base_to_original",
        "original_to_base",
        "preparation_ms",
    )
}
