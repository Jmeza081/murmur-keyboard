package dev.murmur.speech

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * On-device speech-to-text with Parakeet TDT v3 through sherpa-onnx.
 *
 * Loading is slow (seconds) and uses about 1 GB of memory, so call [load] off the main thread
 * and keep one instance alive while the keyboard is in use.
 */
class SpeechEngine private constructor(
    private val recognizer: OfflineRecognizer,
    /** How long [load] took, in milliseconds. */
    val loadMs: Long,
) {
    data class Result(val text: String, val audioSeconds: Double, val decodeMs: Long, val engine: String = "Parakeet") {
        /** Real-time factor: decode time divided by audio length. Below 1 is faster than real time. */
        val rtf: Double get() = if (audioSeconds > 0) decodeMs / 1000.0 / audioSeconds else 0.0
    }

    /** Transcribes mono samples in the range -1..1. sherpa-onnx resamples to 16 kHz if needed. */
    fun transcribe(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): Result {
        val start = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        val text = try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
        return Result(text, samples.size.toDouble() / sampleRate, SystemClock.elapsedRealtime() - start)
    }

    fun release() = recognizer.release()

    companion object {
        const val SAMPLE_RATE = 16_000
        val MODEL_FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

        /** Names of required files missing from [dir]; empty when the model is ready to load. */
        fun missingFiles(dir: File): List<String> = MODEL_FILES.filter { !File(dir, it).isFile }

        /** Loads the model from [dir]. Throws [IllegalStateException] if files are missing. */
        fun load(dir: File, threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)): SpeechEngine {
            val missing = missingFiles(dir)
            check(missing.isEmpty()) { "Speech model files missing in $dir: ${missing.joinToString()}" }
            val start = SystemClock.elapsedRealtime()
            val recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(dir, "encoder.int8.onnx").path,
                            decoder = File(dir, "decoder.int8.onnx").path,
                            joiner = File(dir, "joiner.int8.onnx").path,
                        ),
                        tokens = File(dir, "tokens.txt").path,
                        modelType = "nemo_transducer",
                        numThreads = threads,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
            return SpeechEngine(recognizer, SystemClock.elapsedRealtime() - start)
        }
    }
}
