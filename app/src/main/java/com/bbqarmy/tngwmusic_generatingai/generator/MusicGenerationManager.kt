package com.bbqarmy.tngwmusic_generatingai.generator

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.time.Duration.Companion.milliseconds

class MusicGenerationManager private constructor(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        @Volatile
        private var instance: MusicGenerationManager? = null

        fun getInstance(context: Context): MusicGenerationManager {
            return instance ?: synchronized(this) {
                instance ?: MusicGenerationManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val tag = "MusicGenerationManager"
    private var env: OrtEnvironment = OrtEnvironment.getEnvironment()
    
    private val tokenizer = T5GemmaTokenizer()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private var stopRequested = false

    private val _generationProgress = MutableStateFlow(0f)
    val generationProgress: StateFlow<Float> = _generationProgress

    private val _generationStatus = MutableStateFlow("")
    val generationStatus: StateFlow<String> = _generationStatus


    private fun createSession(onnxFile: File): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(true)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        return env.createSession(onnxFile.absolutePath, options)
    }

    fun pauseGeneration() {
        _isPaused.value = true
    }

    fun resumeGeneration() {
        _isPaused.value = false
    }

    fun stopGeneration() {
        stopRequested = true
        _isPaused.value = false // Resume if paused to allow exit
    }

    suspend fun generateMusic(
        prompt: String,
        modelDir: File,
        durationSeconds: Float = 10f,
        numSteps: Int = 25,
        cfgScale: Float = 4.5f,
        sampler: String = "pingpong",
        seed: Long = 0L
    ): File? = withContext(Dispatchers.Default) {
        _isGenerating.value = true
        _generationProgress.value = 0f
        
        val onnxDir = File(modelDir, "onnx")
        val duration = durationSeconds.coerceIn(5f, 60f)
        
        try {
            // 0. Prompt Enhancement (Improve quality and adherence)
            val enhancedPrompt = if (!prompt.contains(",") && prompt.length < 15) {
                "$prompt, high quality music, clear studio recording"
            } else {
                prompt
            }
            android.util.Log.d(tag, "Original prompt: '$prompt', Enhanced prompt: '$enhancedPrompt'")

            // 1. Tokenize
            _generationStatus.value = "Tokenizing prompt..."
            _generationProgress.value = 0.05f
            val tokenizerFile = File(modelDir, "tokenizer/tokenizer.json")
            if (tokenizerFile.exists() && !tokenizer.isLoaded()) {
                tokenizer.load(tokenizerFile)
            }
            val inputIds = tokenizer.encode(enhancedPrompt)
            val padId = tokenizer.padTokenId.toLong()
            val attentionMask = LongArray(256) { if (it < inputIds.indexOfFirst { id -> id == padId } || inputIds.indexOfFirst { id -> id == padId } == -1) 1L else 0L }
            
            // 2. Text Encoding (T5Gemma) - Load, Run, Release
            _generationStatus.value = "Encoding text prompt..."
            _generationProgress.value = 0.1f
            val textEncoderSessionLocal = createSession(File(onnxDir, "text_encoder_q4.onnx"))
            
            // Conditional Pass
            val textEncoderInputs = mapOf(
                "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, 256)),
                "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(1, 256))
            )
            val textEncoderOutput = textEncoderSessionLocal.run(textEncoderInputs)
            val textEncoderTensor = textEncoderOutput[0] as OnnxTensor
            val lastHiddenStateFlattened = FloatArray(1 * 256 * 768)
            textEncoderTensor.floatBuffer.get(lastHiddenStateFlattened)
            textEncoderInputs.values.forEach { it.close() }
            textEncoderOutput.close()

            // Unconditional Pass (only if cfgScale > 1.0)
            val uncondLastHiddenStateFlattened = if (cfgScale > 1.0f) {
                val nullInputIds = tokenizer.encode("")
                val nullPadId = tokenizer.padTokenId.toLong()
                val nullAttentionMask = LongArray(256) { if (it < nullInputIds.indexOfFirst { id -> id == nullPadId } || nullInputIds.indexOfFirst { id -> id == nullPadId } == -1) 1L else 0L }
                val nullInputs = mapOf(
                    "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(nullInputIds), longArrayOf(1, 256)),
                    "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(nullAttentionMask), longArrayOf(1, 256))
                )
                val nullOutput = textEncoderSessionLocal.run(nullInputs)
                val nullTensor = nullOutput[0] as OnnxTensor
                val nullState = FloatArray(1 * 256 * 768)
                nullTensor.floatBuffer.get(nullState)
                nullInputs.values.forEach { it.close() }
                nullOutput.close()
                nullState
            } else null

            textEncoderSessionLocal.close()
            System.gc() // Encourage immediate reclamation

            // 3. Duration Conditioning - Load, Run, Release
            _generationStatus.value = "Calculating duration embeddings..."
            _generationProgress.value = 0.15f
            val conditionerSessionLocal = createSession(File(onnxDir, "number_conditioner.onnx"))
            val seconds = floatArrayOf(duration)
            val conditionerInputs = mapOf("seconds" to OnnxTensor.createTensor(env, FloatBuffer.wrap(seconds), longArrayOf(1)))
            val conditionerOutput = conditionerSessionLocal.run(conditionerInputs)
            val conditionerTensor = conditionerOutput[0] as OnnxTensor
            val globalEmbed = FloatArray(768)
            conditionerTensor.floatBuffer.get(globalEmbed)
            
            conditionerInputs.values.forEach { it.close() }
            conditionerOutput.close()
            conditionerSessionLocal.close()
            System.gc()

            // 4. Setup Sampling Parameters
            val tLat = (kotlin.math.ceil((duration.toDouble() + 6.0) * 44100.0 / 8192.0) * 2).toInt()
            val latentChannels = 256
            
            // LogSNR schedule
            val logsnrStart = -6.5f
            val logsnrEnd = 12.0f // Increased to reach near-zero noise
            val tSteps = FloatArray(numSteps + 1)
            for (i in 0..numSteps) {
                val progress = i.toFloat() / numSteps
                val logsnr = logsnrStart + progress * (logsnrEnd - logsnrStart)
                tSteps[i] = (1.0 / (1.0 + kotlin.math.exp(logsnr.toDouble()))).toFloat()
            }
            // Ensure the very last step reaches 0 for pure data
            tSteps[numSteps] = 0.0f
            
            val crossAttnCond = FloatArray(257 * 768)
            System.arraycopy(lastHiddenStateFlattened, 0, crossAttnCond, 0, 256 * 768)
            System.arraycopy(globalEmbed, 0, crossAttnCond, 256 * 768, 768)
            val crossAttnCondTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(crossAttnCond), longArrayOf(1, 257, 768))

            val uncondCrossAttnCondTensor = if (uncondLastHiddenStateFlattened != null) {
                val uncondCrossAttnCond = FloatArray(257 * 768)
                System.arraycopy(uncondLastHiddenStateFlattened, 0, uncondCrossAttnCond, 0, 256 * 768)
                System.arraycopy(globalEmbed, 0, uncondCrossAttnCond, 256 * 768, 768)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(uncondCrossAttnCond), longArrayOf(1, 257, 768))
            } else null

            val random = if (seed == 0L) java.util.Random() else java.util.Random(seed)
            val latents = FloatArray(latentChannels * tLat) { random.nextGaussian().toFloat() }
            
            val localAddCond = FloatArray(257 * tLat)
            val paddingMaskBuffer = ByteBuffer.allocateDirect(tLat)
            repeat(tLat) { paddingMaskBuffer.put(1.toByte()) }
            paddingMaskBuffer.rewind()

            // 5. Sampling Loop - Load DiT, Run Loop, Release
            _generationStatus.value = "Diffusion Sampling..."
            val ditSessionLocal = createSession(File(onnxDir, "dit_q4.onnx"))
            
            val paddingMaskTensor = OnnxTensor.createTensor(env, paddingMaskBuffer, longArrayOf(1, tLat.toLong()), OnnxJavaType.BOOL)
            val localAddCondTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(localAddCond), longArrayOf(1, 257, tLat.toLong()))
            val globalEmbedTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(globalEmbed), longArrayOf(1, 768))

            fun runDit(x: FloatArray, t: Float): FloatArray {
                val tTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(t)), longArrayOf(1))
                val xTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, latentChannels.toLong(), tLat.toLong()))

                val condDitInputs = mapOf(
                    "x" to xTensor,
                    "t" to tTensor,
                    "cross_attn_cond" to crossAttnCondTensor,
                    "global_embed" to globalEmbedTensor,
                    "padding_mask" to paddingMaskTensor,
                    "local_add_cond" to localAddCondTensor
                )

                val condDitOutput = ditSessionLocal.run(condDitInputs)
                val condVelocityTensor = condDitOutput[0] as OnnxTensor
                val condVelocity = FloatArray(x.size)
                condVelocityTensor.floatBuffer.get(condVelocity)
                condDitOutput.close()

                val velocity = if (uncondCrossAttnCondTensor != null && cfgScale > 1.0f) {
                    val uncondDitInputs = mapOf(
                        "x" to xTensor,
                        "t" to tTensor,
                        "cross_attn_cond" to uncondCrossAttnCondTensor,
                        "global_embed" to globalEmbedTensor,
                        "padding_mask" to paddingMaskTensor,
                        "local_add_cond" to localAddCondTensor
                    )
                    val uncondDitOutput = ditSessionLocal.run(uncondDitInputs)
                    val uncondVelocityTensor = uncondDitOutput[0] as OnnxTensor
                    val uncondVelocity = FloatArray(x.size)
                    uncondVelocityTensor.floatBuffer.get(uncondVelocity)
                    uncondDitOutput.close()

                    FloatArray(x.size) { j ->
                        val vUncond = if (uncondVelocity[j].isNaN()) 0f else uncondVelocity[j]
                        val vCond = if (condVelocity[j].isNaN()) 0f else condVelocity[j]
                        vUncond + cfgScale * (vCond - vUncond)
                    }
                } else {
                    condVelocity
                }
                
                tTensor.close()
                xTensor.close()
                return velocity
            }

            for (i in 0 until numSteps) {
                while (_isPaused.value) {
                    delay(500.milliseconds)
                    if (stopRequested) break
                }
                if (stopRequested) {
                    _generationStatus.value = "Generation stopped by user"
                    return@withContext null
                }

                val tCurr = tSteps[i]
                val tNext = tSteps[i+1]
                val h = tNext - tCurr
                _generationStatus.value = "Diffusion Sampling (Step ${i + 1}/$numSteps)..."
                
                val velocity = when (sampler) {
                    "rk4" -> {
                        val k1 = runDit(latents, tCurr)
                        val k2Latents = FloatArray(latents.size) { j -> latents[j] + h * 0.5f * k1[j] }
                        val k2 = runDit(k2Latents, tCurr + h * 0.5f)
                        val k3Latents = FloatArray(latents.size) { j -> latents[j] + h * 0.5f * k2[j] }
                        val k3 = runDit(k3Latents, tCurr + h * 0.5f)
                        val k4Latents = FloatArray(latents.size) { j -> latents[j] + h * k3[j] }
                        val k4 = runDit(k4Latents, tNext)
                        
                        FloatArray(latents.size) { j -> (k1[j] + 2f * k2[j] + 2f * k3[j] + k4[j]) / 6f }
                    }
                    else -> runDit(latents, tCurr)
                }

                if (i < numSteps - 1) {
                    when (sampler) {
                        "euler", "rk4", "dpmpp" -> {
                            // dpmpp fallback to euler for now
                            for (j in latents.indices) {
                                latents[j] = latents[j] + h * velocity[j]
                            }
                        }
                        else -> { // pingpong
                            for (j in latents.indices) {
                                val denoisedJ = latents[j] - tCurr * velocity[j]
                                latents[j] = (1f - tNext) * denoisedJ + tNext * random.nextGaussian().toFloat()
                            }
                        }
                    }
                } else {
                    // Last step
                    for (j in latents.indices) {
                        latents[j] = latents[j] - tCurr * velocity[j]
                    }
                }

                _generationProgress.value = 0.2f + (i.toFloat() / numSteps.toFloat()) * 0.6f
            }
            
            paddingMaskTensor.close()
            localAddCondTensor.close()
            globalEmbedTensor.close()
            crossAttnCondTensor.close()
            uncondCrossAttnCondTensor?.close()
            ditSessionLocal.close()
            System.gc() // Large model released, GC now

            // 6. Decode (Latents to Audio) - Load, Run, Release
            _generationStatus.value = "Decoding to audio..."
            _generationProgress.value = 0.85f
            val decoderSessionLocal = createSession(File(onnxDir, "decoder_q4.onnx"))
            val decoderInputs = mapOf(
                "latents" to OnnxTensor.createTensor(env, FloatBuffer.wrap(latents), longArrayOf(1, latentChannels.toLong(), tLat.toLong()))
            )
            val decoderOutput = decoderSessionLocal.run(decoderInputs)
            val audioTensor = decoderOutput[0] as OnnxTensor
            // Expected shape: [1, 2, samples]
            val audioBuffer = audioTensor.floatBuffer
            val numSamples = (audioTensor.info.shape[2]).toInt()
            val pcmData = FloatArray(2 * numSamples)
            
            // audioBuffer is [LeftChanSamples... RightChanSamples...] in some layouts, 
            // but ORT usually returns it flattened if we ask for the buffer.
            // However, the model output is [1, 2, numSamples].
            // The buffer will contain 2 * numSamples floats.
            // We need to interleave them: L0, R0, L1, R1...
            // Let's check the layout. Usually it's C-style (row-major).
            // So: [L0, L1, ..., LN, R0, R1, ..., RN]
            val allSamples = FloatArray(2 * numSamples)
            audioBuffer.get(allSamples)
            
            for (s in 0 until numSamples) {
                pcmData[s * 2] = allSamples[s]
                pcmData[s * 2 + 1] = allSamples[s + numSamples]
            }
            
            decoderInputs.values.forEach { it.close() }
            decoderOutput.close()
            decoderSessionLocal.close()
            System.gc()

            // 7. Save to WAV
            _generationStatus.value = "Saving to WAV file..."
            val musicDir = File(appContext.filesDir, "generated_music")
            if (!musicDir.exists()) musicDir.mkdirs()
            val outputFile = File(musicDir, "music_${System.currentTimeMillis()}.wav")
            saveAsWav(outputFile, pcmData)
            
            _generationProgress.value = 1.0f
            _generationStatus.value = "Music generated successfully!"
            return@withContext outputFile
            
        } catch (e: Throwable) {
            Log.e(tag, "Generation error: ${e.message}", e)
            _generationStatus.value = "Error: ${e.message}"
            return@withContext null
        } finally {
            _isGenerating.value = false
            _isPaused.value = false
            stopRequested = false
        }
    }

    private fun saveAsWav(file: File, pcmData: FloatArray) {
        val sampleRate = 44100
        val channels = 2
        val bitsPerSample = 16
        val dataSize = pcmData.size * 2
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()

        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort()) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign)
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)
            
            out.write(header.array())
            
            // Write in smaller chunks to avoid large ByteBuffer allocation
            val chunkSize = 1024 * 32 // 32KB
            val chunkBuffer = ByteBuffer.allocate(chunkSize * 2).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < pcmData.size) {
                chunkBuffer.clear()
                val remaining = pcmData.size - i
                val toWrite = if (remaining < chunkSize) remaining else chunkSize
                for (j in 0 until toWrite) {
                    val sample = pcmData[i + j]
                    val s = (sample.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    chunkBuffer.putShort(s)
                }
                out.write(chunkBuffer.array(), 0, toWrite * 2)
                i += toWrite
            }
        }
    }

    fun release() {
        // Since models are now loaded and closed locally in generateMusic,
        // there's nothing global to release here except the OrtEnvironment
        // but OrtEnvironment.getEnvironment() returns a singleton that should persist.
    }
}
