package com.yourapp.wine.merge

/** Geometry-only phrase proposals, not identity assignments or independent OCR votes.
 * All boxes in one call must describe the SAME image, coordinate frame, engine and pass.
 * Coordinates may be pixels or normalized coordinates; never mix the two.
 */
object SpatialPhraseMerger {
    const val MAX_DETECTIONS = 512
    data class Box(val x1:Double,val y1:Double,val x2:Double,val y2:Double) {
        init {
            require(listOf(x1,y1,x2,y2).all { it.isFinite() } && x2>x1 && y2>y1 && (x2-x1).isFinite() && (y2-y1).isFinite()) { "Box must have finite coordinates and positive area" }
        }
        val width get()=x2-x1
        val height get()=y2-y1
        val midY get()=y1+height/2
        fun union(other:Box)=Box(minOf(x1,other.x1),minOf(y1,other.y1),maxOf(x2,other.x2),maxOf(y2,other.y2))
    }
    data class Detection(val text:String,val bbox:Box)
    data class Phrase(val mergedText:String,val boundingBoxes:List<Box>,val sourceIndices:List<Int>)
    private data class Fragment(val index:Int,val source:Detection,val text:String)
    private data class Line(val members:List<Fragment>,val box:Box,val text:String)
    private data class Link(val upper:Int,val lower:Int,val gap:Double,val centerDistance:Double)

    /** Does not change source text/boxes. Blank strings are omitted; invalid boxes fail at construction.
     * Character count is Unicode code points in the original text, including spaces.
     * Both distance thresholds are strict (<); horizontal vertical-span membership is inclusive.
     * Vertical components default to two lines and a 1.45 height ratio; predicates can enforce roles.
     */
    fun merge(
        detections:List<Detection>,
        maxVerticalLines:Int=2,
        maxVerticalHeightRatio:Double=1.45,
        allowHorizontalJoin:(leftText:String,rightText:String)->Boolean={ _,_ -> true },
        allowVerticalJoin:(upperText:String,lowerText:String)->Boolean={ _,_ -> true },
    ):List<Phrase> {
        require(detections.size<=MAX_DETECTIONS) { "At most $MAX_DETECTIONS detections per engine/pass" }
        require(maxVerticalLines>=1) { "At least one vertical line must be allowed" }
        require(maxVerticalHeightRatio>=1.0 && !maxVerticalHeightRatio.isNaN()) { "Vertical height ratio must be at least 1" }
        val fragments=detections.mapIndexedNotNull { index,d ->
            val text=d.text.trim().replace(Regex("\\s+")," ")
            if(text.isEmpty()) null else Fragment(index,d,text)
        }.sortedWith(compareBy({it.source.bbox.x1},{it.source.bbox.y1},{it.source.bbox.x2},{it.text},{it.index}))
        if(fragments.isEmpty()) return emptyList()
        val parent=IntArray(fragments.size) { it }
        val componentFirst=IntArray(fragments.size) { it }
        val componentText=Array(fragments.size) { fragments[it].text }
        fun root(start:Int):Int {
            var i=start
            while(parent[i]!=i) { parent[i]=parent[parent[i]];i=parent[i] }
            return i
        }
        // Use ORIGINAL adjacent-box character widths, not a growing union's inflated width.
        // The semantic predicate receives the current left/right component readings, so a marked
        // producer heading can safely span several adjacent OCR tokens without losing its cue.
        for(a in fragments.indices) for(b in a+1 until fragments.size) {
            val first=fragments[a];val second=fragments[b]
            val x=first.source.bbox;val y=second.source.bbox
            val characterWidth=x.width/first.source.text.codePointCount(0,first.source.text.length)
            if(y.midY in x.y1..x.y2 && y.x1-x.x2<2.5*characterWidth) {
                val aRoot=root(a);val bRoot=root(b)
                if(aRoot==bRoot) continue
                val (leftRoot,rightRoot)=if(componentFirst[aRoot]<=componentFirst[bRoot]) aRoot to bRoot else bRoot to aRoot
                if(allowHorizontalJoin(componentText[leftRoot],componentText[rightRoot])) {
                    parent[rightRoot]=leftRoot
                    componentFirst[leftRoot]=minOf(componentFirst[leftRoot],componentFirst[rightRoot])
                    componentText[leftRoot]+=" "+componentText[rightRoot]
                }
            }
        }
        val lines=fragments.indices.groupBy { root(it) }.values.map { indices ->
            val members=indices.map { fragments[it] } // already left-to-right
            Line(members,members.map { it.source.bbox }.reduce { a,b -> a.union(b) },members.joinToString(" ") { it.text })
        }.sortedWith(compareBy({it.box.y1},{it.box.x1},{it.box.y2},{it.members.first().text}))

        val links=mutableListOf<Link>()
        for(a in lines.indices) for(b in a+1 until lines.size) {
            val upper=lines[a].box;val lower=lines[b].box
            // Distinct stacked lines; slightly overlapping vertical spans are allowed.
            if(lower.y1<=upper.y1 || lower.midY<=upper.midY) continue
            val overlap=minOf(upper.x2,lower.x2)-maxOf(upper.x1,lower.x1)
            val gap=lower.y1-upper.y2
            val heightRatio=maxOf(upper.height,lower.height)/minOf(upper.height,lower.height)
            if(overlap>0.5*minOf(upper.width,lower.width) && gap<0.8*upper.height &&
                heightRatio<=maxVerticalHeightRatio && allowVerticalJoin(lines[a].text,lines[b].text)) {
                links+=Link(a,b,gap/upper.height,kotlin.math.abs((upper.x1+upper.width/2)-(lower.x1+lower.width/2)))
            }
        }
        // Resolve competing continuations nearest-first, with only one predecessor/successor.
        // Never compare against an ever-growing block box (which could swallow nearby columns).
        // The caller may cap a proposal to a safe number of adjacent lines; joining remains a
        // candidate path, so each original line is still present independently in the output.
        val next=IntArray(lines.size) { -1 };val previous=IntArray(lines.size) { -1 }
        val component=IntArray(lines.size) { it };val componentSize=IntArray(lines.size) { 1 }
        fun componentRoot(start:Int):Int {
            var i=start
            while(component[i]!=i) { component[i]=component[component[i]];i=component[i] }
            return i
        }
        for(link in links.sortedWith(compareBy({it.gap},{it.centerDistance},{it.upper},{it.lower}))) {
            if(next[link.upper]>=0 || previous[link.lower]>=0) continue
            val upperRoot=componentRoot(link.upper);val lowerRoot=componentRoot(link.lower)
            if(upperRoot==lowerRoot || componentSize[upperRoot]+componentSize[lowerRoot]>maxVerticalLines) continue
            next[link.upper]=link.lower;previous[link.lower]=link.upper
            component[upperRoot]=lowerRoot
            componentSize[lowerRoot]+=componentSize[upperRoot]
        }
        return lines.indices.filter { previous[it]<0 }.map { start ->
            val members=mutableListOf<Fragment>();var line=start
            while(line>=0) { members+=lines[line].members;line=next[line] }
            Phrase(members.joinToString(" ") { it.text },members.map { it.source.bbox },members.map { it.index })
        }
    }
}
