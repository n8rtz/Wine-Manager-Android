package com.yourapp.wine.textlab

/** Process-wide samples, NOT peak RSS and NOT memory attributable exclusively to a model. */
internal object TextLabMemory {
    fun sample():Map<String,String> {
        val runtime=Runtime.getRuntime()
        return mapOf("process_native_heap_sample_bytes" to android.os.Debug.getNativeHeapAllocatedSize().toString(),
            "process_java_heap_used_sample_bytes" to (runtime.totalMemory()-runtime.freeMemory()).toString(),
            "process_java_heap_limit_bytes" to runtime.maxMemory().toString())
    }
}
