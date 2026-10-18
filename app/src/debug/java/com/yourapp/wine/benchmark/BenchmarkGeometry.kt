package com.yourapp.wine.benchmark

import com.yourapp.wine.geom.Matrix3

object BenchmarkGeometry {
    /** BASE label pixels -> same-sized sheared variant, compressed in X to retain both slanted edges. */
    fun shear(width:Int,height:Int,slope:Double):Matrix3 {
        require(width>0 && height>0 && slope.isFinite())
        val scale=width/(width+kotlin.math.abs(slope)*height)
        val tx=scale*(kotlin.math.abs(slope)*height/2-slope*height/2)
        return Matrix3(listOf(scale,slope*scale,tx,0.0,1.0,0.0,0.0,0.0,1.0))
    }
}
