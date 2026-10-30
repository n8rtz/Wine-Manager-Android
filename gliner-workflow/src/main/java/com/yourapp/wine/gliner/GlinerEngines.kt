package com.yourapp.wine.gliner

/**
 * The two OCR engines of stage 1, named exactly as the production engines name themselves so the
 * existing per-engine rules keep working:
 *  - `mlkit` is `com.yourapp.wine.ocr.MlKitOcrEngine.id`
 *  - `paddle-v6-medium` is `com.yourapp.wine.textlab.PaddleMediumOcrEngine.id`
 *
 * Kept in their own file (no Android imports) so the host replay harness can compile the real lane
 * record bridge without dragging in the OCR clients.
 */
object GlinerEngines {
    const val MLKIT="mlkit"
    const val PADDLE="paddle-v6-medium"
}
