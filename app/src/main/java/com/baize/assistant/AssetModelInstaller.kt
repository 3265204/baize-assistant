package com.baize.assistant

import android.content.Context
import java.io.File

class AssetModelInstaller(private val context: Context) {
    private val modelDir = File(context.filesDir, ASSET_MODEL_DIR)

    fun modelPath(): String {
        return File(modelDir, MODEL_FILE).absolutePath
    }

    fun tokensPath(): String {
        return File(modelDir, TOKENS_FILE).absolutePath
    }

    fun ensureInstalled(): Result<Unit> {
        return runCatching {
            modelDir.mkdirs()
            copyAssetIfNeeded("$ASSET_MODEL_DIR/$MODEL_FILE", File(modelDir, MODEL_FILE))
            copyAssetIfNeeded("$ASSET_MODEL_DIR/$TOKENS_FILE", File(modelDir, TOKENS_FILE))
        }
    }

    private fun copyAssetIfNeeded(assetPath: String, target: File) {
        if (target.exists() && target.length() > 0L) return
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    companion object {
        const val ASSET_MODEL_DIR = "sherpa-onnx-paraformer-zh-small"
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
    }
}
