package com.yourapp.wine

import com.yourapp.wine.textlab.TextCrop
import com.yourapp.wine.textlab.TextLabBottleFilter
import com.yourapp.wine.textlab.TextLabJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLabBottleFilterTest {
    private fun entry(text: String, l: Float, t: Float, r: Float, b: Float, origTop: Float = t, origBot: Float = b) =
        TextLabBottleFilter.Entry(
            payload = text,
            text = text,
            rectBox = TextLabBottleFilter.Box(l, t, r, b),
            origBox = TextLabBottleFilter.Box(l, origTop, r, origBot),
        )

    @Test
    fun filtersSideBottlesWatermarksMedalsAndDetachedBadgesWithoutDroppingNeckOrSplitVintages() {
        val entries = listOf(
            entry("2019", 0.45f, 0.06f, 0.55f, 0.11f), // neck vintage
            entry("CHATEAU EXEMPLE", 0.30f, 0.36f, 0.70f, 0.42f),
            entry("SAINT-EMILION GRAND CRU", 0.27f, 0.45f, 0.73f, 0.50f),
            entry("20", 0.29f, 0.52f, 0.35f, 0.55f), // split crest year left
            entry("16", 0.65f, 0.52f, 0.71f, 0.55f), // split crest year right
            entry("MIS EN BOUTEILLE AU CHATEAU", 0.29f, 0.59f, 0.71f, 0.63f),
            entry("zxq", 0.66f, 0.53f, 0.75f, 0.58f), // lowercase margin signature scribble
            entry("Decanter", 0.58f, 0.24f, 0.65f, 0.26f), // award seal
            entry("95", 0.59f, 0.26f, 0.64f, 0.30f), // score fragment beside seal
            entry("BADGE", 0.58f, 0.77f, 0.65f, 0.80f), // detached lower round sticker
            entry("SIDE BOTTLE", 0.02f, 0.42f, 0.15f, 0.48f), // side bottle
            entry("BLOG WATERMARK", 0.01f, 0.98f, 0.28f, 0.995f), // frame-edge watermark
        )
        val result = TextLabBottleFilter.filterEntries(entries)
        assertEquals(
            listOf("2019", "CHATEAU EXEMPLE", "SAINT-EMILION GRAND CRU", "20", "16", "MIS EN BOUTEILLE AU CHATEAU"),
            result.kept,
        )
        val reasons = result.dropped.associate { it.entry.payload to it.reason }
        assertEquals("outside_bottle_column", reasons["SIDE BOTTLE"])
        assertEquals("outside_bottle_column", reasons["BLOG WATERMARK"])
        assertEquals("medal_seal_text", reasons["Decanter"])
        assertEquals("near_medal_seal_fragment", reasons["95"])
        assertEquals("detached_lower_sticker", reasons["BADGE"])
        assertEquals("margin_signature_scribble", reasons["zxq"])
    }

    @Test
    fun proposesGenerouslyPaddedZoomCropOnlyWhenPrimaryBottleFootprintIsTiny() {
        val tiny = TextLabJournal(setOf("candidate_3"), "test", true)
        tiny.add("shared", "service_setup", "complete")
        for ((cand, txt, box) in listOf(
            Triple("mlkit_reference", "BAROLO", "0.42 0.42 0.58 0.46"),
            Triple("candidate_3", "MONTEFIORE", "0.40 0.48 0.60 0.52"),
            Triple("candidate_3", "2019", "0.47 0.55 0.53 0.58"),
            Triple("mlkit_reference", "DENOMINAZIONE", "0.39 0.61 0.61 0.65"),
            Triple("mlkit_reference", "CORNER TAG", "0.01 0.97 0.14 0.99"), // distant clutter ignored by primary cluster
        )) {
            tiny.add(
                cand,
                "recognition",
                values = mapOf(
                    "raw_text" to txt,
                    "base_box_normalized" to box,
                    "original_box_normalized" to box,
                    "mlkit_confidence" to "0.9",
                    "ctc_posterior" to "0.9",
                    "confidence_estimated" to "false",
                ),
            )
        }
        tiny.add("mlkit_reference", "summary", "complete")
        tiny.add("candidate_3", "candidate_summary", "complete")

        val crop = TextLabBottleFilter.proposeZoomCrop(tiny)
        assertNotNull(crop)
        crop!!
        // Leaves ample room above (<= 0.25) for neck bands and below (>= 0.77) for foot strips.
        assertTrue(crop.top <= 0.25f)
        assertTrue(crop.bottom >= 0.77f)
        assertTrue(crop.left >= 0.25f && crop.right <= 0.75f)

        // Zoom pass promotion keeps service_setup prefix and records auto_zoom_crop metadata.
        val zoom = TextLabJournal(setOf("candidate_3"), "test", true)
        zoom.metadata["enlarge"] = "true"
        zoom.add("shared", "preparation", "complete")
        for ((cand, txt, box) in listOf(
            Triple("mlkit_reference", "BAROLO", "0.25 0.35 0.75 0.42"),
            Triple("candidate_3", "MONTEFIORE RISERVA", "0.20 0.45 0.80 0.55"),
            Triple("candidate_3", "2019", "0.42 0.58 0.58 0.64"),
            Triple("mlkit_reference", "DENOMINAZIONE DI ORIGINE", "0.18 0.68 0.82 0.76"),
        )) {
            zoom.add(
                cand,
                "recognition",
                values = mapOf(
                    "raw_text" to txt,
                    "base_box_normalized" to box,
                    "original_box_normalized" to "0.40 0.42 0.60 0.65",
                    "mlkit_confidence" to "0.92",
                    "ctc_posterior" to "0.92",
                    "confidence_estimated" to "false",
                ),
            )
        }
        zoom.add("mlkit_reference", "summary", "complete")
        zoom.add("candidate_3", "candidate_summary", "complete")

        assertTrue(TextLabBottleFilter.shouldAcceptZoomPass(tiny, zoom))
        TextLabBottleFilter.promoteZoomPass(tiny, zoom, crop)
        assertEquals("true", tiny.metadata["enlarge"])
        assertNotNull(tiny.metadata["auto_zoom_crop"])
        assertTrue(tiny.rows.any { it["raw_text"] == "MONTEFIORE RISERVA" })

        // Close-up bottle (> 10% text footprint) must not trigger zoom-crop.
        val closeup = TextLabJournal(setOf("candidate_3"), "test", true)
        for ((cand, txt, box) in listOf(
            Triple("mlkit_reference", "CHATEAU EXEMPLE", "0.20 0.20 0.80 0.30"),
            Triple("candidate_3", "GRAND VIN DE BORDEAUX", "0.22 0.55 0.78 0.65"),
        )) {
            closeup.add(
                cand,
                "recognition",
                values = mapOf(
                    "raw_text" to txt,
                    "base_box_normalized" to box,
                    "original_box_normalized" to box,
                    "mlkit_confidence" to "0.9",
                    "ctc_posterior" to "0.9",
                ),
            )
        }
        closeup.add("mlkit_reference", "summary", "complete")
        closeup.add("candidate_3", "candidate_summary", "complete")
        assertNull(TextLabBottleFilter.proposeZoomCrop(closeup))

        // Timed-out zoom pass is rejected cleanly so full-frame OCR stays intact.
        val timedOut = TextLabJournal(setOf("candidate_3"), "test", true)
        timedOut.add("mlkit_reference", "summary", "complete")
        timedOut.add("candidate_3", "candidate_summary", "timed_out")
        assertFalse(TextLabBottleFilter.shouldAcceptZoomPass(tiny, timedOut))
    }
}
