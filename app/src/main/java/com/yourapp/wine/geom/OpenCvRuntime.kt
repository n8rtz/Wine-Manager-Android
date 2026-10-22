package com.yourapp.wine.geom

/** Loads only the genuine source-built OpenCV core/imgproc Java library. */
object OpenCvRuntime {
    @Volatile private var loaded = false
    /** No coordinates: initialize native core/imgproc once, on a worker thread, before creating Mats. */
    @Synchronized fun ensureLoaded() {
        if (!loaded) { System.loadLibrary("opencv_java4"); loaded = true }
    }
}
