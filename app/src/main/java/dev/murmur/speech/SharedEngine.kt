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

    /** Transcribes on the worker thread. [onDone] gets null if the model couldn't load. Runs on the worker thread. */
    fun transcribe(context: Context, samples: FloatArray, onDone: (SpeechEngine.Result?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = load(app)?.transcribe(samples)
            result?.let { Log.i(TAG, "Transcribed %.1f s in %d ms (RTF %.3f)".format(it.audioSeconds, it.decodeMs, it.rtf)) }
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
