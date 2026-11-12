package dev.murmur.speech

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * One [SpeechEngine] for the whole app process, shared by the keyboard, the power-button
 * assistant and the recognition service, so the ~1.3 GB model is only loaded once.
 * All loading and decoding happens on one background thread.
 */
object SharedEngine {
    private const val TAG = "MurmurEngine"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "murmur-speech") }
    @Volatile private var engine: SpeechEngine? = null
    @Volatile private var loadListener: (() -> Unit)? = null

    val isLoaded get() = engine != null

    /** Loads the model in the background if it's installed and not loaded yet. */
    fun preload(context: Context, onLoaded: (() -> Unit)? = null) {
        if (engine != null) return onLoaded?.invoke() ?: Unit
        if (!ModelManager.isInstalled(context)) return
        loadListener = onLoaded
        val app = context.applicationContext
        worker.execute { load(app) }
    }

    private val network = Executors.newSingleThreadExecutor { Thread(it, "murmur-cloud") }

    /**
     * Transcribes with ElevenLabs when it's on and a key is set, else (or when that fails) with Parakeet on the
     * worker thread. [onDone] gets null only if neither worked. Runs on a background thread.
     */
    fun transcribe(context: Context, samples: FloatArray, onDone: (SpeechEngine.Result?) -> Unit) {
        val app = context.applicationContext
        if (!CloudEngine.active(app)) return transcribeOnDevice(app, samples, onDone)
        network.execute {
            val keyterms = runCatching {
                dev.murmur.dictionary.MurmurDictionary.words(app) + dev.murmur.dictionary.MurmurDictionary.replacements(app).map { it.to }
            }.getOrDefault(emptyList())
            try {
                val r = CloudEngine.transcribe(app, samples, keyterms).copy(engine = "ElevenLabs")
                dev.murmur.settings.DictationPrefs.setLastRun(app, r.decodeMs, r.engine, null)
                onDone(r)
            } catch (e: CloudEngine.Failure) {
                Log.w(TAG, "ElevenLabs failed (${e.reason}); falling back to Parakeet", e.cause)
                transcribeOnDevice(app, samples) { r ->
                    r?.let { dev.murmur.settings.DictationPrefs.setLastRun(app, it.decodeMs, it.engine, e.reason) }
                    onDone(r)
                }
            }
        }
    }

    /**
     * Finishes a live ElevenLabs [stream] (started with the recording), or falls back to Parakeet on [samples]
     * when there's no stream or it failed. [onDone] runs on a background thread.
     */
    fun finish(context: Context, stream: CloudStream?, samples: FloatArray, onDone: (SpeechEngine.Result?) -> Unit) {
        val app = context.applicationContext
        if (stream == null) return transcribe(app, samples, onDone)
        val start = System.currentTimeMillis()
        stream.finish { text ->
            if (text != null) {
                val r = SpeechEngine.Result(text, samples.size.toDouble() / SpeechEngine.SAMPLE_RATE, System.currentTimeMillis() - start, "ElevenLabs")
                dev.murmur.settings.DictationPrefs.setLastRun(app, r.decodeMs, r.engine, null)
                onDone(r)
            } else {
                val reason = stream.failure ?: "No reply"
                Log.w(TAG, "ElevenLabs stream failed ($reason); falling back to Parakeet")
                transcribeOnDevice(app, samples) { r ->
                    r?.let { dev.murmur.settings.DictationPrefs.setLastRun(app, it.decodeMs, it.engine, reason) }
                    onDone(r)
                }
            }
        }
    }

    private fun transcribeOnDevice(app: Context, samples: FloatArray, onDone: (SpeechEngine.Result?) -> Unit) {
        worker.execute {
            val result = load(app)?.transcribe(samples)
            result?.let {
                Log.i(TAG, "Transcribed %.1f s in %d ms (RTF %.3f)".format(it.audioSeconds, it.decodeMs, it.rtf))
                dev.murmur.settings.DictationPrefs.setLastDecodeMs(app, it.decodeMs) // for the settings model card
                dev.murmur.settings.DictationPrefs.setLastRun(app, it.decodeMs, it.engine, null)
            }
            onDone(result)
        }
    }

    @Synchronized
    private fun load(context: Context): SpeechEngine? {
        engine?.let { return it }
        return try {
            SpeechEngine.load(ModelManager.dir(context)).also {
                engine = it
                Log.i(TAG, "Model loaded in ${it.loadMs} ms")
                loadListener?.invoke()
                loadListener = null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Model failed to load", e)
            null
        }
    }
}
