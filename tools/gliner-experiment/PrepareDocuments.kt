// Host-only adapter. Reuses the shipping fusion and document implementation; no targets enter it.
package com.yourapp.wine.gliner

import android.graphics.RectF
import java.util.Base64

private fun dec(s:String) = String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)
private fun enc(s:String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

fun main() = generateSequence(::readLine).forEach { line ->
    val (id, raw) = line.split('\t', limit=2)
    val readings=raw.split(';').filter { it.isNotEmpty() }.map { record ->
        val p=record.split(',')
        GlinerFusion.Reading(dec(p[0]),dec(p[1]),RectF(p[3].toFloat(),p[4].toFloat(),p[5].toFloat(),p[6].toFloat()),
            emptyList(),p[2].toFloat())
    }
    val words=GlinerDocument.build(GlinerFusion.fuse(readings)).words.take(320)
    println(id+"\t"+words.joinToString(";") { enc(it.text)+","+enc(it.normalized) })
}
