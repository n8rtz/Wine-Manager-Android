package com.yourapp.wine.capture

import java.io.File

/** Upgrade-only deletion of exact private model-cache names; never touches photos, CSVs or the KB. */
internal object RetiredModelCleanup {
    fun remove(root:File) {
        val retired=mapOf(
            "indra-v1" to listOf("det.onnx","rec.onnx","orient.onnx","lm.npz","meta.json"),
            "text-lab-paddle-v1" to listOf("inference.onnx","detection.onnx","v6-small-det.onnx","ppocrv5_dict.utf8")
        )
        retired.forEach { (folder,names) ->
            names.forEach { name ->
                val file=File(File(root,folder),name)
                if(file.isFile && !file.delete()) android.util.Log.w("OfflineWineScanner","Cannot remove retired OCR cache: $folder/$name")
            }
        }
    }
}
