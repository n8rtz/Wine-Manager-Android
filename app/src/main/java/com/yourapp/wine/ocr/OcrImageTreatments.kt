package com.yourapp.wine.ocr

/** Human-readable descriptions of the implemented pixel operations, not a list of proposed filters. */
object OcrImageTreatments {
    const val PROBE = "working-image localization probe; original color; no contrast filter"
    const val RAW = "base label image; original color; no contrast filter"
    const val FOIL = "RGB -> Lab; highlight compression above L=200 (slope 0.55); CLAHE on luminance (clip=2.0, tiles=8x8); Lab -> color RGB"
    const val UNSHARP = "color RGB; median denoise (3x3); unsharp mask (Gaussian sigma=0.7, original=1.35, blur=-0.35)"
    const val THRESHOLD = "grayscale; Gaussian adaptive binary threshold (block=31, C=7); explicit last-resort mode, not enabled by default"
    const val REUSED_PROBE = "reused mapped working-image localization probe; original color; no new V0 image processing or OCR"

    fun aspect(w: Int, h: Int, baseW: Int, baseH: Int): String =
        "$UNSHARP; half-height aspect rescue ${w}x${h} -> base ${baseW}x${baseH}; INTER_AREA resize; polygons inverse-scaled"
}
