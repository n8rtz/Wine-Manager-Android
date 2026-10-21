package com.yourapp.wine.textlab

internal data class TextLabChoice(val id:String,val title:String)
internal object TextLabChoices {
    val all=PaddleCandidates.all.map { TextLabChoice(it.id,it.title) }
    fun selected(ids:Set<String>)=all.filter { it.id in ids }
}
