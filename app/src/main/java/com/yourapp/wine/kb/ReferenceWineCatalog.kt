package com.yourapp.wine.kb

import com.yourapp.wine.merge.TextNorm

/** Auditable bundled reference, not a complete legal register. New catalog-based suggestions stay YELLOW. */
object ReferenceWineCatalog {
    /** [path] is the containing chain below the country ('Bordeaux/Médoc'); empty means country+broad region only. */
    data class Origin(val name:String,val code:String,val country:String,val regions:List<String>,val aliases:List<String>,
        val nameRole:String,val contextRequired:Boolean,val source:String,val path:List<String>)
    data class LabelTerm(val role:String,val name:String,val countries:List<String>,val action:String)
    val origins:List<Origin> by lazy { GeneratedWineKnowledge.origins.map { row ->
        val p=row.split('|');Origin(p[0],p[1],p[2],p[3].split(';').filter(String::isNotBlank),
            p[4].split(';').filter(String::isNotBlank),p[5],p[6]=="context_required",p[7],
            p.getOrElse(8){""}.split('/').filter(String::isNotBlank))
    } }
    val labelTerms:List<LabelTerm> by lazy { GeneratedWineKnowledge.labelTerms.map { row ->
        val p=row.split('|');LabelTerm(p[0],p[1],p[2].split(';').filter(String::isNotBlank),p[3])
    } }
    val countryCodes by lazy { origins.associate { it.country to it.code } }
    private val indexed by lazy { origins.associateBy { it.code to TextNorm.normalize(it.name) } }
    val terms by lazy { origins.map { WineOriginTerm(it.name,it.country,it.regions.singleOrNull().orEmpty(),it.aliases) } }
    fun origin(place:Place):Origin? = indexed[place.countryCode to place.nameNorm]
    fun requiresConfirmation(place:Place):Boolean {
        val row=origin(place) ?: return false
        if(place.id >= 0) return false // Preserve imported/seeded records and their independent validation.
        val previous=BundledOrigins.legacy(row.name,row.country)
        return previous == null || previous.region != row.regions.singleOrNull().orEmpty()
    }
    fun named(name:String,code:String):Origin? = indexed[code to TextNorm.normalize(name)]
    fun names(role:String):List<String> = labelTerms.filter { it.role==role }.map { it.name }
    private val admin by lazy { names("administrative_prefix").map(TextNorm::normalize) }
    private val descriptions by lazy { labelTerms.filter { it.role in setOf("classification","sweetness","style") }.map { TextNorm.normalize(it.name) }.toSet() }
    fun isAdministrative(text:String):Boolean {
        val n=TextNorm.normalize(text)
        return admin.any { n==it || n.startsWith("$it ") }
    }
    fun isDescription(text:String):Boolean = TextNorm.normalize(text) in descriptions

    private val regionWideIndicationKeys = setOf(
        "FR" to "paysdoc",
        "FR" to "coteauxdulanguedoc",
        "FR" to "comtetolosan",
        "IT" to "sicilia",
        "IT" to "terresiciliane",
        "IT" to "toscana",
        "IT" to "piemonte",
    )

    /** Regional-scale PGIs/IGPs/IGTs/DOCs coextensive with their parent region (not a sub-locality). */
    fun isRegionWideIndication(place: Place): Boolean =
        (place.countryCode to place.nameNorm.filter(Char::isLetterOrDigit)) in regionWideIndicationKeys

    private val regionRequiredContextNames = setOf(
        "bianco", "denmark", "orange", "augusta", "hermann", "warren hills", "douglas"
    )

    /** Ambiguous short/common names need a separately spelled country/region in the same searched text. */
    fun mayMatch(place:Place,text:String):Boolean = mayMatch(place,text,emptySet(),emptySet(),false)

    /**
     * Cross-line overload used by Lab `LabelContext`: when [standaloneOrFramed] is true for the
     * evaluated phrase, separate country/region/legal context elsewhere on the label may unlock a
     * `context_required` wine origin without turning bare style words (`Vino`, `Bianco`, `Orange Wine`)
     * into geographic claims.
     */
    fun mayMatch(
        place:Place,
        text:String,
        contextCountryCodes:Set<String>,
        contextRegionsNormalized:Set<String>,
        standaloneOrFramed:Boolean,
    ):Boolean {
        val row=origin(place) ?: return true
        if(!row.contextRequired) return true
        if(place.nameNorm=="orange" && Regex("\\borange\\s+wine\\b").containsMatchIn(text)) return false
        val evidence=listOf(row.country)+row.regions
        val sameLine=evidence.any { name ->
            val n=TextNorm.normalize(name)
            n!=place.nameNorm && (listOf(row.name)+row.aliases).any { form ->
                val term=Regex.escape(TextNorm.normalize(form))
                val anchor=Regex.escape(n)
                Regex("(?<![\\p{L}\\p{N}])(?:$term[ ,;/:-]+$anchor|$anchor[ ,;/:-]+$term)(?![\\p{L}\\p{N}])").containsMatchIn(text)
            }
        }
        if(sameLine) return true
        if(!standaloneOrFramed || place.nameNorm=="vino") return false
        if(place.nameNorm in regionRequiredContextNames) {
            return row.regions.isNotEmpty() && row.regions.any { TextNorm.normalize(it) in contextRegionsNormalized }
        }
        if(row.code !in contextCountryCodes) return false
        return row.regions.isEmpty() || contextRegionsNormalized.isEmpty() ||
            row.regions.any { TextNorm.normalize(it) in contextRegionsNormalized }
    }
}
