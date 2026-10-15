package com.n8rtz.winetracker.scanner

import java.io.File

/**
 * Remembers barcode lookups so the second scan of a bottle is instant and works offline.
 *
 * Line-based text next to the app's other file stores: one line per barcode, `|` separated. Malformed
 * lines are skipped rather than failing the cache, and the file is capped so it cannot grow forever.
 */
internal class BarcodeIdentityCache(
    private val file: File,
    private val maxEntries: Int = MAX_ENTRIES
) {
    private val entries = mutableMapOf<String, WineBarcodeIdentity>()

    @Volatile
    private var loaded = false

    @Synchronized
    fun get(barcode: String): WineBarcodeIdentity? {
        loadIfNeeded()
        return entries[barcode]
    }

    @Synchronized
    fun put(identity: WineBarcodeIdentity) {
        loadIfNeeded()
        entries[identity.barcode] = identity
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
        write()
    }

    @Synchronized
    fun size(): Int {
        loadIfNeeded()
        return entries.size
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            file.forEachLine { line ->
                parse(line)?.let { identity -> entries[identity.barcode] = identity }
            }
        }
    }

    private fun write() {
        runCatching {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { writer ->
                entries.values.forEach { identity -> writer.appendLine(format(identity)) }
            }
        }
    }

    private fun parse(line: String): WineBarcodeIdentity? {
        if (line.isBlank()) return null
        val fields = line.split(FIELD_SEPARATOR)
        if (fields.size < HEADER_FIELDS) return null
        val barcode = fields[0]
        if (Gtin.normalize(barcode) == null) return null
        fun field(index: Int): String? = fields.getOrNull(index)?.takeIf(String::isNotBlank)
        return WineBarcodeIdentity(
            barcode = barcode,
            maker = field(1),
            name = field(2),
            vintage = field(3),
            country = field(4),
            region = field(5),
            type = field(6),
            imageUrl = field(7)
        )
    }

    private fun format(identity: WineBarcodeIdentity): String = listOf(
        identity.barcode,
        identity.maker,
        identity.name,
        identity.vintage,
        identity.country,
        identity.region,
        identity.type,
        identity.imageUrl
    ).joinToString(FIELD_SEPARATOR.toString()) { value ->
        value.orEmpty().replace(FIELD_SEPARATOR, ' ').replace('\n', ' ').trim()
    }

    private companion object {
        const val FIELD_SEPARATOR = '|'
        const val HEADER_FIELDS = 8
        const val MAX_ENTRIES = 500
    }
}
