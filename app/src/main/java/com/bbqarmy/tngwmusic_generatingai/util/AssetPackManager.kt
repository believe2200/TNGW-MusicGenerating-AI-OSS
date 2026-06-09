package com.bbqarmy.tngwmusic_generatingai.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class ModelProvider(private val context: Context) {
    private val tag = "ModelProvider"
    val modelDir = File(context.filesDir, "models")

    fun isReady(): Boolean {
        // Simple check: check if the main ONNX file exists in internal storage
        return File(modelDir, "onnx/dit_q4.onnx").exists()
    }

    fun prepareModels(onProgress: (String) -> Unit) {
        if (!modelDir.exists()) modelDir.mkdirs()
        
        val assets = context.assets
        val list = mutableListOf<String>()
        
        // config.json
        list.add("config.json")
        
        // onnx files
        assets.list("onnx")?.forEach { list.add("onnx/$it") }
        
        // tokenizer files
        assets.list("tokenizer")?.forEach { list.add("tokenizer/$it") }

        list.forEachIndexed { index, path ->
            val destFile = File(modelDir, path)
            destFile.parentFile?.let {
                if (!it.exists()) it.mkdirs()
            }
            
            if (!destFile.exists()) {
                onProgress("Extracting $path (${index + 1}/${list.size})...")
                Log.d(tag, "Copying $path to ${destFile.absolutePath}")
                assets.open(path).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
