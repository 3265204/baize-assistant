package com.baize.assistant

import android.content.res.AssetManager
import java.io.File

class LocalSherpaAsr(private val settings: DeepSeekSettings) {
    fun transcribe(samples: FloatArray, sampleRate: Int): Result<String> {
        return runCatching {
            val model = File(settings.localAsrModelPath)
            val tokens = File(model.parentFile, "tokens.txt")
            if (!model.exists()) {
                throw IllegalStateException(
                    "没有找到内置 ASR 模型：${settings.localAsrModelPath}。请重新构建，让 Gradle 自动下载并打包 Paraformer small。"
                )
            }
            if (!tokens.exists()) {
                throw IllegalStateException("没有找到 Paraformer tokens.txt：${tokens.absolutePath}")
            }

            val api = SherpaReflectionApi()
            val recognizer = api.createParaformerRecognizer(
                modelPath = model.absolutePath,
                tokensPath = tokens.absolutePath,
                sampleRate = sampleRate,
                numThreads = settings.localAsrNumThreads.coerceIn(1, 4)
            )
            val stream = api.createStream(recognizer)
            try {
                api.acceptWaveform(stream, samples, sampleRate)
                api.decode(recognizer, stream)
                api.getText(api.getResult(recognizer, stream)).trim()
            } finally {
                api.release(stream)
                api.release(recognizer)
            }
        }
    }
}

private class SherpaReflectionApi {
    private val featureConfigClass = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
    private val paraformerConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig")
    private val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
    private val recognizerConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
    private val recognizerClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
    private val streamClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineStream")

    fun createParaformerRecognizer(
        modelPath: String,
        tokensPath: String,
        sampleRate: Int,
        numThreads: Int
    ): Any {
        val featureConfig = newDefault(featureConfigClass).also {
            set(it, "sampleRate", sampleRate)
            set(it, "featureDim", 80)
        }
        val paraformer = newDefault(paraformerConfigClass).also {
            set(it, "model", modelPath)
        }
        val modelConfig = newDefault(modelConfigClass).also {
            set(it, "paraformer", paraformer)
            set(it, "tokens", tokensPath)
            set(it, "numThreads", numThreads)
            set(it, "provider", "cpu")
            setIfExists(it, "modelType", "paraformer")
            setIfExists(it, "modelingUnit", "cjkchar")
        }
        val recognizerConfig = newDefault(recognizerConfigClass).also {
            set(it, "featConfig", featureConfig)
            set(it, "modelConfig", modelConfig)
        }

        recognizerClass.constructors
            .firstOrNull { it.parameterTypes.size == 2 && it.parameterTypes[0] == AssetManager::class.java }
            ?.let { return it.newInstance(null, recognizerConfig) }

        recognizerClass.constructors
            .firstOrNull { it.parameterTypes.size == 1 }
            ?.let { return it.newInstance(recognizerConfig) }

        throw NoSuchMethodException("OfflineRecognizer constructor")
    }

    fun createStream(recognizer: Any): Any {
        return recognizerClass.getMethod("createStream").invoke(recognizer)
    }

    fun acceptWaveform(stream: Any, samples: FloatArray, sampleRate: Int) {
        streamClass.methods
            .firstOrNull {
                it.name == "acceptWaveform" &&
                    it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java, Integer.TYPE))
            }
            ?.let {
                it.invoke(stream, samples, sampleRate)
                return
            }

        streamClass.methods
            .firstOrNull {
                it.name == "acceptWaveform" &&
                    it.parameterTypes.contentEquals(arrayOf(Integer.TYPE, FloatArray::class.java))
            }
            ?.let {
                it.invoke(stream, sampleRate, samples)
                return
            }

        throw NoSuchMethodException("OfflineStream.acceptWaveform")
    }

    fun decode(recognizer: Any, stream: Any) {
        recognizerClass.getMethod("decode", streamClass).invoke(recognizer, stream)
    }

    fun getResult(recognizer: Any, stream: Any): Any {
        return recognizerClass.getMethod("getResult", streamClass).invoke(recognizer, stream)
    }

    fun getText(result: Any): String {
        return result.javaClass.getMethod("getText").invoke(result) as String
    }

    fun release(instance: Any) {
        runCatching { instance.javaClass.getMethod("release").invoke(instance) }
    }

    private fun newDefault(clazz: Class<*>): Any {
        clazz.constructors.firstOrNull { it.parameterCount == 0 }?.let { return it.newInstance() }
        val constructor = clazz.constructors.maxBy { it.parameterCount }
        val args = constructor.parameterTypes.map { defaultValue(it) }.toTypedArray()
        return constructor.newInstance(*args)
    }

    private fun set(instance: Any, property: String, value: Any) {
        val setter = "set" + property.replaceFirstChar { it.uppercaseChar() }
        val method = instance.javaClass.methods.firstOrNull {
            it.name == setter && it.parameterTypes.size == 1
        } ?: throw NoSuchMethodException("${instance.javaClass.name}.$setter")
        method.invoke(instance, value)
    }

    private fun setIfExists(instance: Any, property: String, value: Any) {
        runCatching { set(instance, property, value) }
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Long.TYPE -> 0L
            String::class.java -> ""
            else -> newDefault(type)
        }
    }
}
