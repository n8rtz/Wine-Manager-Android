package com.yourapp.wine.phonecorpus

import java.text.Normalizer
import java.util.Locale

/** Same blank-aware frozen-reference scoring rules as the host. Never used during OCR/extraction. */
internal object PhoneCorpusMath {
    val fields=listOf("producer","wine_name","country","region","subregion","vintage")
    data class Target(val status:String,val accepted:Set<String>,val display:String="")
    fun normalize(value:String)=Normalizer.normalize(
        com.yourapp.wine.merge.TextNorm.scrub(value).lowercase(Locale.ROOT).replace("ς","σ").replace("&"," et "),
        Normalizer.Form.NFKD
    ).filter { it.isLetterOrDigit() }
    fun outcome(target:Target,value:String,failed:Boolean=false):String=when {
        failed -> "execution_failed"
        target.status=="unresolved" -> if(value.isBlank()) "unscored_blank" else "unscored_filled"
        target.status=="expected_blank" -> if(value.isBlank()) "correct_blank" else "unsupported_fill"
        value.isBlank() -> "missing_value"
        normalize(value) in target.accepted -> "correct_value"
        else -> "wrong_value"
    }
    fun correct(outcome:String)=outcome in setOf("correct_value","correct_blank")
    fun eligible(targets:Map<String,Target>)=fields.all { targets.getValue(it).status!="unresolved" }
}
