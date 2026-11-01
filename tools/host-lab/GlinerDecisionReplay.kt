package com.yourapp.wine.gliner

import java.util.Base64
import com.yourapp.wine.kb.BundledGrapes
import com.yourapp.wine.kb.BundledOrigins
import com.yourapp.wine.kb.BundledWineStyles

// Line protocol for the host replay: scoring targets are never passed into the decision process.
private fun decode(s:String)=String(Base64.getUrlDecoder().decode(s),Charsets.UTF_8)
private fun encode(s:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
private val fieldNames=listOf("producer","wine_name","country","region","subregion","vintage")

fun main() {
    val grapes=BundledGrapes.terms.flatMap { listOf(it.name)+it.aliases }.toSet()
    val styles=BundledWineStyles.terms.flatMap { listOf(it.name)+it.aliases }.toSet()
    val places=BundledOrigins.terms.flatMap { listOf(it.name,it.country,it.region)+it.aliases }
        .filter(String::isNotBlank).map(GlinerDocument::normalize).toSet()
    generateSequence(::readLine).forEach { line ->
        val parts=line.split('\t')
        require(parts.size==4)
        val fields=parts[0].split(',').map(::decode).let { values -> fieldNames.zip(values).toMap() }
        val hints=parts[1].split(';').filter { it.isNotEmpty() }.map { raw ->
            val p=raw.split(',')
            GlinerFieldHint(GlinerLane.valueOf(p[0]),decode(p[2]),p[1].toFloat())
        }
        val readings=parts[2].split(';').filter { it.isNotEmpty() }.map { raw ->
            val p=raw.split(',')
            GlinerEvidenceLine(decode(p[0]),decode(p[1]),p[2].toFloat(),p[3].toFloat(),p[4].toFloat(),p[5].toFloat())
        }
        val result=GlinerFieldDecisionPolicy.applyWithEvidence(fields,hints,readings,grapes,styles,places,GlinerGeography.origins)
        println(parts[3]+"\t"+fieldNames.joinToString(",") { encode(result.fields[it].orEmpty()) }+
            "\t"+result.changes.joinToString(",") { encode(it) })
    }
}
