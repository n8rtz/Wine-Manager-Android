package com.yourapp.wine.textlab.evidence

import android.graphics.RectF
import com.yourapp.wine.merge.BoxMath
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.extract.MetadataRules
import com.yourapp.wine.extract.ProducerLineSelector
import com.yourapp.wine.extract.WineDesignations

internal data class Observation(val id:Int,val record:TokenRecord)
internal data class Phrase(val id:Int,val text:String,val observations:Set<Int>,val box:RectF,val construction:String)
internal data class EvidenceGraph(val observations:List<Observation>,val phrases:List<Phrase>,val truncated:Boolean)

/** Alternative segmentations refer to unchanged observations. No canonical winner or score fusion. */
internal object EvidenceGraphBuilder {
    const val MAX_OBSERVATIONS=128
    const val MAX_PHRASES=384
    private val yearOrVintageBoundary=Regex(
        """(?<![\p{L}\p{N}])(?:18|19|20|[il1]\s*9|2\s*[o0])\s*\d\s*\d(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
    private val vintageFramingBoundary=Regex("(?iu)\\b(?:vintage|millesime|annata|cosecha|vendimia|harvest)\\b")
    fun build(
        records:List<TokenRecord>,
        checkBudget:()->Unit = {},
        typedIdentity:Boolean=false,
        isBroadGeographyLine:(String)->Boolean={ false },
        isKnownProducerLine:(String)->Boolean={ false },
        allowWineTitlePairs:Boolean=false,
        wineTitlePairMaxHeightRatio:Float=1.45f,
        allowStackedAppellations:Boolean=false,
        allowSplitCrestVintage:Boolean=true,
        enforceSpatialSemanticGuards:Boolean=true,
        requireRoleAwareJoins:Boolean=false,
        filterAdministrative:Boolean=false,
    ):EvidenceGraph {
        val observations=records.take(MAX_OBSERVATIONS).mapIndexedNotNull { i,r ->
            val b=r.bboxRect
            val scrubbed=TextNorm.scrub(r.textRaw)
            if(scrubbed.isBlank() || scrubbed.length>256 || !r.score.isFinite() || r.score !in 0f..1f ||
                b.width()<=0 || b.height()<=0 || listOf(b.left,b.top,b.right,b.bottom).any { !it.isFinite() || it !in 0f..1f }) null
            else Observation(i,r.copy(textRaw=scrubbed,textNorm=TextNorm.normalize(scrubbed),bboxRect=RectF(b),bboxOrig=RectF(r.bboxOrig)))
        }
        val phrases=mutableListOf<Phrase>()
        var truncated=records.size>MAX_OBSERVATIONS
        fun add(text:String,ids:Set<Int>,box:RectF,why:String) {
            if(phrases.size>=MAX_PHRASES) { truncated=true;return }
            if(text.length<=256 && phrases.none { it.observations==ids && TextNorm.normalize(it.text)==TextNorm.normalize(text) })
                phrases+=Phrase(phrases.size,text,ids,RectF(box),why)
        }
        observations.forEach { add(it.record.textRaw,setOf(it.id),it.record.bboxRect,"observed") }
        // Camel-case boundary is a proposed segmentation, never a rewrite of the original observation.
        observations.forEach { o ->
            val separated=o.record.textRaw.replace(Regex("(?<=[A-ZÀ-Ý])(?=(?:de|du|di)\\b)")," ")
            if(separated!=o.record.textRaw) add(separated,setOf(o.id),o.record.bboxRect,"case-boundary segmentation")
        }
        val singles=phrases.toList()
        val observationsById=observations.associateBy { it.id }
        fun passFor(ids:Set<Int>):Pair<String,String>? {
            val source=ids.mapNotNull(observationsById::get)
            if(source.size!=ids.size) return null
            return source.map { it.record.engineId to it.record.variantId }.distinct().singleOrNull()
        }
        fun sameEnginePass(a:Phrase,b:Phrase):Boolean {
            val aPass=passFor(a.observations) ?: return false
            return aPass==passFor(b.observations)
        }
        val geographyCache=mutableMapOf<String,Boolean>()
        val producerCache=mutableMapOf<String,Boolean>()
        fun isGeographyLine(text:String):Boolean {
            val key=TextNorm.normalize(text)
            return geographyCache.getOrPut(key) { isBroadGeographyLine(text) }
        }
        fun isKnownProducer(text:String):Boolean {
            val key=TextNorm.normalize(text)
            return producerCache.getOrPut(key) { isKnownProducerLine(text) }
        }
        fun isHardBoundary(text:String):Boolean = yearOrVintageBoundary.containsMatchIn(text) ||
            vintageFramingBoundary.containsMatchIn(TextNorm.normalize(text)) ||
            MetadataRules.isBoilerplate(text) || MetadataRules.isPackaging(text) || WineDesignations.framedAppellation(text)!=null
        val lineRoles=ProducerLineSelector()
        fun compatibleRoles(a:String,b:String):Boolean {
            val combined="$a $b"
            if(filterAdministrative && (com.yourapp.wine.extract.LabelTextRoles.isAdministrativeIdentity(a) ||
                com.yourapp.wine.extract.LabelTextRoles.isAdministrativeIdentity(b))) return false
            val producerA=isKnownProducer(a) || lineRoles.hasProducerCue(a)
            val producerB=isKnownProducer(b) || lineRoles.hasProducerCue(b)
            val descriptionA=isGeographyLine(a) || lineRoles.isWineDescription(a) || WineDesignations.isDesignation(a)
            val descriptionB=isGeographyLine(b) || lineRoles.isWineDescription(b) || WineDesignations.isDesignation(b)
            if((producerA && descriptionB) || (producerB && descriptionA)) return false
            // Positive support: a catalog compound, producer heading continuation, or explicit cuvee.
            return isKnownProducer(combined) || isGeographyLine(combined) ||
                WineDesignations.isAppellationName(combined) || WineDesignations.classificationBase(combined)!=null ||
                (producerA && !descriptionB) || (producerB && !descriptionA) ||
                Regex("""(?iu)\b(?:cuvee|cuvée)\b""").containsMatchIn(combined)
        }
        fun canJoinHorizontally(a:Phrase,b:Phrase):Boolean {
            val combined=a.text+" "+b.text
            if(isHardBoundary(a.text) || isHardBoundary(b.text) || isHardBoundary(combined)) return false
            if(requireRoleAwareJoins && !compatibleRoles(a.text,b.text)) return false
            if(WineDesignations.isAppellationName(combined) || WineDesignations.classificationBase(combined)!=null) return true
            val aGeo=isGeographyLine(a.text);val bGeo=isGeographyLine(b.text);val combinedGeo=isGeographyLine(combined)
            if(aGeo || bGeo || combinedGeo) {
                val aHasProducerCue=lineRoles.hasProducerCue(a.text);val bHasProducerCue=lineRoles.hasProducerCue(b.text)
                val aWineDescription=lineRoles.isWineDescription(a.text);val bWineDescription=lineRoles.isWineDescription(b.text)
                return (aGeo && bGeo || combinedGeo) && !aHasProducerCue && !bHasProducerCue &&
                    !aWineDescription && !bWineDescription
            }
            return true
        }
        fun isBoundedWineTitlePair(upper:String,lower:String):Boolean {
            if(!allowWineTitlePairs || isHardBoundary(upper) || isHardBoundary(lower) ||
                isGeographyLine(upper) || isGeographyLine(lower) || isGeographyLine("$upper $lower")) return false
            if(lower.count(Char::isLetter)<3 ||
                (!lineRoles.isWineDescription(lower) && !WineDesignations.isDesignation(lower))) return false
            if(upper.count(Char::isLetter)<3 || lineRoles.isWineDescription(upper) || lineRoles.hasProducerCue(upper) ||
                (typedIdentity && (EvidenceIdentityRoles.headingOnly(upper) || EvidenceIdentityRoles.namedEstate(upper))) ||
                isKnownProducer(upper)) return false
            return !WineDesignations.isDesignation(lower) && !WineDesignations.isBareClassification(lower) &&
                (lineRoles.isWineDescription(lower) || WineDesignations.isAppellationName(lower))
        }
        // Some labels print the two halves of a year on opposite sides of a central crest.
        // Rejoin only same-engine, same-variant two-digit fragments that are level, similarly
        // sized, and symmetric around the label text's horizontal center. This is a proposal over
        // the original observations; neither fragment is rewritten or counted as extra OCR support.
        fun yearHalf(raw:String):String? {
            val compact=TextNorm.normalize(raw).filter(Char::isLetterOrDigit)
            if(compact.length!=2) return null
            val repaired=compact.map { ch -> when(ch) {
                'i','l' -> '1'
                'o' -> '0'
                else -> ch
            } }
            return repaired.takeIf { it.all(Char::isDigit) }?.joinToString("")
        }
        data class YearFragment(val observation:Observation,val digits:String)
        val fragments=if(allowSplitCrestVintage) observations.mapNotNull { observation ->
            yearHalf(observation.record.textRaw)?.let { YearFragment(observation,it) }
        } else emptyList()
        for((_,pass) in fragments.groupBy { it.observation.record.engineId to it.observation.record.variantId }) {
            val leftHalves=pass.filter { it.digits in setOf("19","20") }
            val rightHalves=pass.filter { it.digits.all(Char::isDigit) }
            if(leftHalves.isEmpty() || rightHalves.isEmpty()) continue
            val passKey=pass.first().observation.record.let { it.engineId to it.variantId }
            val contextCenters=observations.filter { observation ->
                observation.record.engineId==passKey.first && observation.record.variantId==passKey.second &&
                    yearHalf(observation.record.textRaw)==null
            }.map { it.record.bboxRect.centerX() }.sorted()
            val observedTextCenter=when {
                contextCenters.isEmpty() -> null
                contextCenters.size%2==1 -> contextCenters[contextCenters.size/2]
                else -> (contextCenters[contextCenters.size/2-1]+contextCenters[contextCenters.size/2])*.5f
            }
            for(left in leftHalves) for(right in rightHalves) {
                checkBudget()
                if(left.observation.id==right.observation.id) continue
                val a=left.observation.record.bboxRect
                val b=right.observation.record.bboxRect
                if(a.centerX()>=b.centerX()) continue
                val year=(left.digits+right.digits).toIntOrNull() ?: continue
                if(year !in 1900..2099) continue
                val maxHeight=maxOf(a.height(),b.height())
                val heightRatio=minOf(a.height(),b.height())/maxHeight
                if(heightRatio<.60f || kotlin.math.abs(a.centerY()-b.centerY())>maxHeight*.45f) continue
                val gap=b.left-a.right
                if(gap<-.02f || gap>.32f) continue
                val pairCenter=(a.centerX()+b.centerX())*.5f
                val labelCenter=observedTextCenter ?: pairCenter
                if(kotlin.math.abs(pairCenter-labelCenter)>.10f) continue
                val leftDistance=labelCenter-a.centerX()
                val rightDistance=b.centerX()-labelCenter
                if(leftDistance<=0f || rightDistance<=0f || kotlin.math.abs(leftDistance-rightDistance)>.04f) continue
                add(year.toString(),setOf(left.observation.id,right.observation.id),
                    BoxMath.union(listOf(a,b)),"split crest vintage")
            }
        }
        for(a in singles) for(b in singles) {
            checkBudget()
            if(a.observations.intersect(b.observations).isNotEmpty()) continue
            if(enforceSpatialSemanticGuards && !sameEnginePass(a,b)) continue
            val ak=TextNorm.normalize(a.text).filter(Char::isLetterOrDigit);val bk=TextNorm.normalize(b.text).filter(Char::isLetterOrDigit)
            if(ak==bk || (BoxMath.iou(a.box,b.box)>.25f && TextNorm.editDistance(ak,bk,2)!=null)) continue
            val horizontal=a.box.centerX()<b.box.centerX() && BoxMath.verticalOverlap(a.box,b.box) >= .55f &&
                b.box.left >= a.box.left && b.box.left-a.box.right<=.06f &&
                (minOf(a.box.right,b.box.right)-maxOf(a.box.left,b.box.left))<.6f*minOf(a.box.width(),b.box.width()) &&
                kotlin.math.abs(a.box.centerY()-b.box.centerY())<=minOf(a.box.height(),b.box.height())*.5f
            val vertical=a.box.centerY()<b.box.centerY() &&
                kotlin.math.abs(a.box.centerX()-b.box.centerX())<=maxOf(a.box.width(),b.box.width())*.25f &&
                b.box.top-a.box.bottom<=maxOf(a.box.height(),b.box.height())*.65f &&
                b.box.centerY()-a.box.centerY()<=maxOf(a.box.height(),b.box.height())*1.8f
            if(!horizontal && !vertical) continue
            val combinedText=a.text+" "+b.text
            // A bounded wine-title pair (Item 1a) is its own role support: a title
            // line over a varietal/appellation line needs no catalog or producer
            // evidence, so the role-aware veto must not kill it before evaluation.
            val pairCandidate=vertical && sameEnginePass(a,b) &&
                maxOf(a.box.height(),b.box.height())/minOf(a.box.height(),b.box.height())<=wineTitlePairMaxHeightRatio &&
                isBoundedWineTitlePair(a.text,b.text)
            if(requireRoleAwareJoins && !pairCandidate && !compatibleRoles(a.text,b.text)) continue
            if(filterAdministrative && (com.yourapp.wine.extract.LabelTextRoles.isAdministrativeIdentity(a.text) ||
                com.yourapp.wine.extract.LabelTextRoles.isAdministrativeIdentity(b.text))) continue
            val heightRatio=maxOf(a.box.height(),b.box.height())/minOf(a.box.height(),b.box.height())
            val stackedAppellation=allowStackedAppellations && vertical && sameEnginePass(a,b) &&
                heightRatio<=1.85f && !isHardBoundary(a.text) && !isHardBoundary(b.text) &&
                (WineDesignations.isAppellationName(combinedText) || WineDesignations.classificationBase(combinedText)!=null)
            // Item 1a: a bounded wine-title pair is evaluated before the generic
            // vertical caps, because a script cuvee line is legitimately several
            // times taller than the small-caps varietal below it. The pair has its
            // own hard boundaries (no framing/vintage/geography lines, same engine,
            // lower line must be a varietal or appellation) in isBoundedWineTitlePair.
            val boundedPairCandidate=pairCandidate
            if(enforceSpatialSemanticGuards) {
                if(horizontal && !canJoinHorizontally(a,b)) continue
                if(vertical) {
                    val maxRatio=when {
                        stackedAppellation -> 1.85f
                        boundedPairCandidate -> wineTitlePairMaxHeightRatio
                        else -> 1.45f
                    }
                    if(heightRatio>maxRatio || isHardBoundary(a.text) || isHardBoundary(b.text)) continue
                    if(!stackedAppellation && !boundedPairCandidate && (isGeographyLine(a.text) || isGeographyLine(b.text) || isGeographyLine(combinedText))) continue
                }
            }
            val an=TextNorm.normalize(a.text);val bn=TextNorm.normalize(b.text)
            // Contained overlapping readings remain alternatives, not concatenations (LA / LA RESERVE).
            if(BoxMath.iou(a.box,b.box)>.10f && (" $an ".contains(" $bn ") || " $bn ".contains(" $an "))) continue
            val designation=WineDesignations.isDesignation(b.text) && !WineDesignations.isDesignation(a.text)
            val leadingConnector=Regex("^(de|du|di|des)\\b").containsMatchIn(bn)
            val estateName=if(enforceSpatialSemanticGuards) {
                val lowerNonProducerRole=lineRoles.isWineDescription(b.text) || isGeographyLine(b.text)
                val knownProducerContinuation=lowerNonProducerRole && typedIdentity && EvidenceIdentityRoles.headingOnly(a.text) &&
                    lineRoles.hasProducerCue(a.text) && !isGeographyLine(b.text) && isKnownProducer(a.text+" "+b.text)
                typedIdentity && EvidenceIdentityRoles.headingOnly(a.text) &&
                    !EvidenceIdentityRoles.headingOnly(b.text) && b.text.count(Char::isLetter) >= 3 &&
                    !b.text.any(Char::isDigit) && !isHardBoundary(b.text) &&
                    (!lowerNonProducerRole || knownProducerContinuation)
            } else {
                typedIdentity && EvidenceIdentityRoles.headingOnly(a.text) &&
                    !EvidenceIdentityRoles.headingOnly(b.text) && b.text.count(Char::isLetter) >= 3 &&
                    !b.text.any(Char::isDigit) && !com.yourapp.wine.extract.MetadataRules.isBoilerplate(b.text) &&
                    !com.yourapp.wine.extract.MetadataRules.isPackaging(b.text) && !WineDesignations.isDesignation(b.text)
            }
            val wineTitlePair=boundedPairCandidate
            val verticalAllowed=enforceSpatialSemanticGuards || (!stackedAppellation && !wineTitlePair) || stackedAppellation || wineTitlePair
            if(verticalAllowed && (estateName || horizontal || designation || stackedAppellation || leadingConnector ||
                Regex("\\b(de|du|di|des)$").containsMatchIn(an) || bn in setOf("de","du","di","des") || wineTitlePair)) {
                val construction=when {
                    wineTitlePair -> "bounded vertical wine-title pair"
                    estateName -> "estate-heading composition"
                    horizontal -> "aligned horizontal phrase"
                    designation -> "designation-linked phrase"
                    stackedAppellation -> "aligned appellation phrase"
                    else -> "connector-linked phrase"
                }
                add(combinedText,a.observations+b.observations,BoxMath.union(listOf(a.box,b.box)),construction)
            }
        }
        // Horizontal heading fragments (e.g. article + title) can precede an isolated connector.
        for(a in phrases.toList().filter { it.construction=="aligned horizontal phrase" }) for(b in singles) {
            checkBudget()
            if(TextNorm.normalize(b.text) !in setOf("de","du","di","des") || a.observations.intersect(b.observations).isNotEmpty()) continue
            if(requireRoleAwareJoins && !compatibleRoles(a.text,b.text)) continue
            if(enforceSpatialSemanticGuards && (!sameEnginePass(a,b) || isHardBoundary(a.text) || isHardBoundary(b.text) ||
                isGeographyLine(a.text) || isGeographyLine(b.text) || isGeographyLine(a.text+" "+b.text))) continue
            if(b.box.centerY()>a.box.centerY() && b.box.top-a.box.bottom<=.08f &&
                kotlin.math.abs(a.box.centerX()-b.box.centerX())<=a.box.width()*.25f)
                add(a.text+" "+b.text,a.observations+b.observations,BoxMath.union(listOf(a.box,b.box)),"connector-linked phrase")
        }
        // Third title line, still bounded; do not recursively expand arbitrary paragraphs.
        for(a in phrases.toList().filter { it.construction=="connector-linked phrase" }) for(b in singles) {
            checkBudget()
            if(a.observations.intersect(b.observations).isNotEmpty() ||
                !Regex("\\b(de|du|di|des)$").containsMatchIn(TextNorm.normalize(a.text))) continue
            if(requireRoleAwareJoins && !compatibleRoles(a.text,b.text)) continue
            if(enforceSpatialSemanticGuards && (!sameEnginePass(a,b) ||
                isHardBoundary(a.text) || isHardBoundary(b.text) || isGeographyLine(a.text) || isGeographyLine(b.text))) continue
            if(TextNorm.normalize(b.text) in setOf("de","du","di","des") || b.text.count(Char::isLetter)<4) continue
            if(a.observations.any { id -> singles.any { s -> id in s.observations && TextNorm.normalize(s.text).filter(Char::isLetterOrDigit)==TextNorm.normalize(b.text).filter(Char::isLetterOrDigit) } }) continue
            val connectorY=observations.filter { it.id in a.observations && TextNorm.normalize(it.record.textRaw) in setOf("de","du","di","des") }.maxOfOrNull { it.record.bboxRect.centerY() } ?: a.box.centerY()
            if(b.box.centerY()>connectorY && b.box.top-a.box.bottom<=.08f &&
                kotlin.math.abs(a.box.centerX()-b.box.centerX())<=maxOf(a.box.width(),b.box.width())*.25f)
                add(a.text+" "+b.text,a.observations+b.observations,BoxMath.union(listOf(a.box,b.box)),"three-line connector phrase")
        }
        return EvidenceGraph(observations,phrases,truncated)
    }
}
