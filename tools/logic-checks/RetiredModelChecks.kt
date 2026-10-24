package com.yourapp.wine.capture

import java.io.File

object RetiredModelChecks {
    fun run() {
        val root=java.nio.file.Files.createTempDirectory("retired-model-check").toFile()
        try {
            fun put(path:String)=File(root,path).apply { parentFile.mkdirs();writeText("test cache fixture") }
            val retired=listOf("indra-v1/det.onnx","indra-v1/rec.onnx","indra-v1/orient.onnx","indra-v1/lm.npz","indra-v1/meta.json",
                "text-lab-paddle-v1/inference.onnx","text-lab-paddle-v1/detection.onnx","text-lab-paddle-v1/v6-small-det.onnx","text-lab-paddle-v1/ppocrv5_dict.utf8").map(::put)
            val retained=listOf("text-lab-paddle-v1/v6-medium-det.onnx","text-lab-paddle-v1/v6-medium-rec.onnx","text-lab-paddle-v1/ppocrv6_dict.utf8",
                "text-lab-tests/old.csv","photos/label.jpg","cellar.db","indra-v1/user-notes.txt").map(::put)
            RetiredModelCleanup.remove(root);RetiredModelCleanup.remove(root)
            check(retired.none { it.exists() })
            check(retained.all { it.readText()=="test cache fixture" })
            println("PASS: retired model cache cleanup is idempotent; production models, CSVs, photos and KB preserved")
        } finally { root.deleteRecursively() }
    }
}
