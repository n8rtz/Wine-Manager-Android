package com.yourapp.wine.extract

import com.yourapp.wine.fill.*
import com.yourapp.wine.merge.TextNorm

/** Interpretable ranking units, NOT probabilities. At most (4+unknown)^2 assignments. */
data class IdentityOption(val candidate:FieldCandidate,val priority:Double)
data class IdentitySelection(val fields:Map<FieldId,List<FieldCandidate>>,val alternatives:Map<FieldId,List<FieldCandidate>>)
object IdentityAssignment {
    const val VERSION="identity-v3-spelling"
    fun select(producers:List<IdentityOption>,names:List<IdentityOption>):IdentitySelection {
        fun bounded(input:List<IdentityOption>)=input.sortedWith(compareByDescending<IdentityOption> { it.priority }.thenByDescending { it.candidate.sources.size })
            .distinctBy { TextNorm.normalize(it.candidate.value) }.take(4)
        val p=bounded(producers);val n=bounded(names)
        data class Pairing(val producer:IdentityOption?,val name:IdentityOption?,val score:Double)
        val pairs=(p+listOf(null)).flatMap { a -> (n+listOf(null)).map { b ->
            val sameEvidence=a!=null && b!=null && a.candidate.sources.isNotEmpty() &&
                a.candidate.sources.any { it in b.candidate.sources }
            val sameName=a!=null && b!=null && TextNorm.normalize(a.candidate.value)==TextNorm.normalize(b.candidate.value)
            val penalty=if(sameName || (sameEvidence && !(a!!.priority>=90 && b!!.priority>=90))) 200.0 else 0.0
            Pairing(a,b,(a?.priority ?: 0.0)+(b?.priority ?: 0.0)-penalty)
        } }.sortedByDescending { it.score }
        val best=pairs.first()
        fun chosen(field:FieldId):List<FieldCandidate> {
            fun option(pair:Pairing)=if(field==FieldId.PRODUCER) pair.producer else pair.name
            val winner=option(best)
            val rivals=pairs.drop(1).filter { best.score-it.score<=3.0 &&
                TextNorm.normalize(option(it)?.candidate?.value.orEmpty())!=TextNorm.normalize(winner?.candidate?.value.orEmpty()) }
            if(winner==null || rivals.isNotEmpty()) {
                val options=(listOfNotNull(winner)+rivals.mapNotNull(::option)).map { it.candidate }
                return listOf(FieldCandidate(field,"",UiState.RED,
                    "$VERSION: "+if(rivals.isEmpty()) "No supported assignment" else "Ambiguous assignment; confirm: "+options.joinToString(" / ") { it.value },
                    options.flatMap { it.sources }.distinct()))
            }
            return listOf(winner.candidate.copy(reason=winner.candidate.reason+"; $VERSION rank=${winner.priority}; bounded joint selection"))
        }
        return IdentitySelection(listOf(FieldId.PRODUCER,FieldId.WINE_NAME).associateWith(::chosen),
            mapOf(FieldId.PRODUCER to p.map { it.candidate },FieldId.WINE_NAME to n.map { it.candidate }))
    }
}
