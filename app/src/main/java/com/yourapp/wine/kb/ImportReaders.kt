package com.yourapp.wine.kb

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.io.Reader
import java.nio.charset.CodingErrorAction

/** Byte and per-value limits apply before parsers allocate strings; the caller owns the input stream. */
internal object ImportReaders {
    const val MAX_ROW_CHARS = 8192
    const val MAX_JSON_DEPTH = 4

    fun utf8(input: InputStream, maxBytes: Long): PushbackReader {
        require(maxBytes in 1 until Long.MAX_VALUE)
        val limited = object : InputStream() {
            private var count = 0L
            override fun read(): Int = input.read().also { if (it >= 0) consumed(1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val wanted = minOf(length.toLong(), maxBytes - count + 1).toInt()
                return input.read(bytes, offset, wanted).also { if (it > 0) consumed(it) }
            }
            private fun consumed(size: Int) {
                count += size
                require(count <= maxBytes) { "KB import exceeds the input byte budget" }
            }
            override fun close() = Unit // Do not close a caller-owned stream when the parser closes.
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return PushbackReader(BufferedReader(InputStreamReader(limited, decoder), 4096), 1)
    }

    fun line(reader: Reader): String? {
        val text = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return text.takeIf { it.isNotEmpty() }?.toString()
            if (value == '\n'.code) return text.toString().removeSuffix("\r")
            require(text.length < MAX_ROW_CHARS) { "CSV row exceeds $MAX_ROW_CHARS characters; adapt the dump first" }
            text.append(value.toChar())
        }
    }

    // Android JsonReader buffers entire string values. Bound lexical strings/depth before it sees them.
    fun jsonEnvelope(reader: Reader): Reader = object : Reader() {
        private var quoted = false
        private var escaped = false
        private var stringChars = 0
        private var literalChars = 0
        private var depth = 0

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val count = reader.read(buffer, offset, length)
            for (i in offset until offset + count.coerceAtLeast(0)) {
                val char = buffer[i]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quoted = false
                    stringChars++
                    require(stringChars <= MAX_ROW_CHARS) { "JSON string exceeds $MAX_ROW_CHARS encoded characters" }
                } else when (char) {
                    '"' -> { quoted = true; stringChars = 0; literalChars = 0 }
                    '{', '[' -> { literalChars = 0; depth++; require(depth <= MAX_JSON_DEPTH) { "Nested data is not wine-kb/v1" } }
                    '}', ']' -> { literalChars = 0; depth--; require(depth >= 0) { "Unbalanced JSON" } }
                    ',', ':' -> literalChars = 0
                    else -> if (char.isWhitespace()) literalChars = 0 else {
                        // IDs have at most 19 digits; a huge unquoted number must not become a giant String either.
                        require(++literalChars <= 32) { "Oversized JSON scalar" }
                    }
                }
            }
            return count
        }

        override fun close() = reader.close()
    }
}
