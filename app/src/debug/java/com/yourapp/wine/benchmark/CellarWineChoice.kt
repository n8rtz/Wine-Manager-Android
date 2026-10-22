package com.yourapp.wine.benchmark

import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.merge.TextNorm

/** A read-only cellar snapshot used exclusively as editable benchmark ground truth. */
data class CellarWineChoice(val bottleId:Long,val maker:String,val name:String,val country:String,
    val region:String,val subregion:String,val vintage:String) {
    val title:String get() = listOf(maker,name,vintage).filter(String::isNotBlank).joinToString(" · ")
    fun expected():Map<FieldId,String> = mapOf(
        FieldId.PRODUCER to maker, FieldId.WINE_NAME to name, FieldId.COUNTRY to country,
        FieldId.REGION to region, FieldId.SUBREGION to subregion,
        // NV/unknown is not a printed year. Blank means unscored in the lab.
        FieldId.VINTAGE to vintage.trim().takeIf { it.matches(Regex("(?:19|20)[0-9]{2}")) }.orEmpty()
    ).mapValues { it.value.trim() }
    fun matches(query:String):Boolean {
        val haystack=TextNorm.normalize(listOf(maker,name,country,region,subregion,vintage).joinToString(" "))
        return TextNorm.normalize(query).split(' ').filter(String::isNotBlank).all { it in haystack }
    }
}
