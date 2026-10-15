package com.n8rtz.winetracker.scanner

/**
 * GS1 check-digit validation for the barcodes a wine bottle can carry.
 *
 * A decoded barcode is only worth a network lookup when the check digit is consistent, which also
 * filters out the confident-looking misreads that a linear barcode scanner produces on a curved,
 * reflective bottle. Pure logic so the arithmetic is unit tested without a device.
 */
internal object Gtin {
    private val GTIN_LENGTHS = setOf(8, 12, 13, 14)

    /** Strips separators and rejects anything that is not a plausible GTIN length. */
    fun normalize(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit)?.takeIf(String::isNotEmpty) ?: return null
        return digits.takeIf { it.length in GTIN_LENGTHS }
    }

    fun isValid(raw: String?): Boolean {
        val code = normalize(raw) ?: return false
        val digits = code.map { it - '0' }
        val checkDigit = digits.last()
        var sum = 0
        for (index in 0 until digits.size - 1) {
            // Weights alternate 3/1 starting with 3 for the digit left of the check digit.
            val positionFromCheckDigit = digits.size - 1 - index
            sum += digits[index] * if (positionFromCheckDigit % 2 == 1) 3 else 1
        }
        return (10 - sum % 10) % 10 == checkDigit
    }
}
