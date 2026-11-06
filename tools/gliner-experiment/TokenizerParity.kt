// Host-only parity probe: the actual Android tokenizer, with org.json supplied by the runner.
package com.yourapp.wine.gliner

import java.io.File
import java.util.Base64

fun main(args:Array<String>) {
    val tokenizer=GlinerTokenizer.parse(File(args.single()).readText())
    generateSequence(::readLine).forEach { line ->
        val value=String(Base64.getUrlDecoder().decode(line),Charsets.UTF_8)
        println(tokenizer.encodeWord(value).joinToString(","))
    }
}
