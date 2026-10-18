package com.yourapp.wine.benchmark

/** Explicit bounded recipes, not an exhaustive Cartesian product. Order is recorded for thermal/order bias. */
data class Treatment(val id: String, val contrast: String = "raw", val sharpen: Boolean = false,
    val window: Int = 0, val k: Double = .2, val thicken: Boolean = false,
    val shear: Double = 0.0, val thin: Boolean = false, val aspect: Boolean = false) {
    val description: String get() = if (id == "baseline") "Current V0/V1/conditional V2 pipeline" else
        "Order: $contrast -> sharpen=$sharpen -> Sauvola window=$window k=$k (0=off) -> Zhang-Suen thinning=$thin -> dark-stroke dilation=$thicken -> " +
            "fixed shear=$shear (padded/compressed, not estimated deskew) -> half-height=$aspect"
}

object BenchmarkPlan {
    val cases: List<Treatment> = buildList {
        add(Treatment("baseline"))
        add(Treatment("raw"))
        add(Treatment("clahe", "clahe"))
        add(Treatment("unsharp", sharpen=true))
        add(Treatment("aspect", sharpen=true, aspect=true))
        add(Treatment("illumination", "illumination"))
        add(Treatment("illumination-sharp", "illumination", sharpen=true))
        for (contrast in listOf("raw", "illumination")) for (window in listOf(25,51)) for (k in listOf(.2,.35))
            add(Treatment("$contrast-sauvola-$window-$k",contrast,window=window,k=k))
        for (contrast in listOf("raw","clahe","illumination"))
            add(Treatment("$contrast-thicken",contrast,thicken=true))
        for (k in listOf(.2,.35)) add(Treatment("sauvola-thicken-$k",window=25,k=k,thicken=true))
        for (s in listOf(-.24,-.12,.12,.24)) add(Treatment("shear-$s",shear=s))
        for (s in listOf(-.12,.12)) add(Treatment("clahe-shear-$s","clahe",shear=s))
        for (s in listOf(-.12,.12)) add(Treatment("sauvola-shear-$s",window=25,shear=s))
        add(Treatment("skeleton",window=25,thin=true))
        add(Treatment("skeleton-thicken",window=25,thin=true,thicken=true))
    }.also { require(it.size==30 && it.map { t -> t.id }.distinct().size==30) }
}
