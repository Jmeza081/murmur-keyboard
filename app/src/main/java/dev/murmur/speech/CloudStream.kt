package dev.murmur.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One live dictation streamed to ElevenLabs (Scribe v2 Realtime), for the live words in the listening sheet
 * and the keyboard panel (design/live-words.html, option B). The recording is still kept on the phone, so
 * if the stream fails at any point the caller transcribes it with Parakeet instead.
 *
 * Text arrives as drafts ("partial") that may change, and final pieces ("committed"); ElevenLabs commits on
 * its own after each pause (VAD), and [finish] commits whatever is left.
 */
class CloudStream private constructor(private val listener: Listener) {
    interface Listener {
        /** Final text so far, and the current draft after it. Called on the main thread. */
        fun onWords(final: String, draft: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private val committed = StringBuilder()
    @Volatile private var draft = ""
    @Volatile var failure: String? = null; private set
    @Volatile private var finishing: ((String?) -> Unit)? = null
    @Volatile private var closed = false

    private fun open(key: String) {
        val url = "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=$MODEL&audio_format=pcm_16000" +
            "&commit_strategy=vad&vad_silence_threshold_secs=0.8"
        val req = Request.Builder().url(url).header("xi-api-key", key).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                fail(if (response?.code == 401) "API key not accepted" else "Connection lost", t)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed = true; deliver() }
        })
    }

    private fun handle(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (val type = j.optString("message_type")) {
            "session_started" -> Log.i(TAG, "Session started")
            "partial_transcript" -> { draft = j.optString("text"); publish() }
            "committed_transcript" -> {
                val t = j.optString("text").trim()
                synchronized(committed) { if (t.isNotEmpty()) { if (committed.isNotEmpty()) committed.append(' '); committed.append(t) } }
                draft = ""
                publish()
                // after finish(): wait a beat for any later piece, then hand over everything
                if (finishing != null) { main.removeCallbacks(deliverSoon); main.postDelayed(deliverSoon, 250) }
            }
            "quota_exceeded" -> fail("Out of plan hours")
            "auth_error" -> fail("API key not accepted")
            "rate_limited", "resource_exhausted", "queue_overflow" -> fail("ElevenLabs is busy")
            "insufficient_audio_activity" -> Unit // silence; fine
            else -> if (j.has("error")) fail("ElevenLabs: ${j.optString("error").ifEmpty { type }}")
        }
    }

    private fun publish() {
        val f = synchronized(committed) { committed.toString() }
        val d = draft
        main.post { listener.onWords(f, d) }
    }

    /** Streams 100 ms of audio. Called on the recording thread. */
    fun send(chunk: ShortArray, commit: Boolean = false) {
        if (failure != null) return
        val bytes = ByteArray(chunk.size * 2)
        chunk.forEachIndexed { i, s -> bytes[2 * i] = (s.toInt() and 0xFF).toByte(); bytes[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte() }
        socket?.send(JSONObject()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("commit", commit)
            .put("sample_rate", SpeechEngine.SAMPLE_RATE).toString())
    }

    /**
     * Commits what's left and hands the full text to [onDone] (on a background thread), or null if the stream
     * failed or the last words didn't arrive within [FINISH_TIMEOUT_MS]; then the caller falls back.
     */
    fun finish(onDone: (String?) -> Unit) {
        if (failure != null) return onDone(null)
        finishing = onDone
        send(ShortArray(SpeechEngine.SAMPLE_RATE / 10), commit = true) // 100 ms of silence carries the commit
        main.postDelayed({ if (finishing != null) { if (draft.isBlank()) deliver() else fail("Too slow") } }, FINISH_TIMEOUT_MS)
    }

    private val deliverSoon = Runnable { deliver() }

    private fun deliver() {
        val done = finishing ?: return
        finishing = null
        val text = synchronized(committed) { committed.toString() }.trim()
        socket?.close(1000, null)
        done(if (failure == null) text else null)
    }

    private fun fail(reason: String, t: Throwable? = null) {
        if (failure != null || (closed && finishing == null)) return
        failure = reason
        Log.w(TAG, "Stream failed: $reason", t)
        socket?.cancel()
        finishing?.let { finishing = null; it(null) }
    }

    /** Throws the session away (Cancel). */
    fun cancel() { finishing = null; failure = "Cancelled"; socket?.cancel() }

    companion object {
        private const val TAG = "MurmurStream"
        const val MODEL = "scribe_v2_realtime"
        private const val FINISH_TIMEOUT_MS = 4000L
        private val client by lazy { OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build() }

        /**
         * Final words in [ink], the draft after them in [sub] (design/live-words.html). Long dictations keep
         * only the last [tail] characters, so the newest words stay in view.
         */
        fun styled(final: String, draft: String, ink: Int, sub: Int, tail: Int = 220): CharSequence {
            val sb = android.text.SpannableStringBuilder()
            var f = final; var d = draft.trim()
            val total = f.length + d.length
            if (total > tail) { val cut = total - tail; if (cut < f.length) f = "…" + f.substring(cut).substringAfter(' ') else { d = "…" + d.substring(cut - f.length); f = "" } }
            sb.append(f, android.text.style.ForegroundColorSpan(ink), 0)
            if (d.isNotEmpty()) { if (f.isNotEmpty()) sb.append(' '); sb.append(d, android.text.style.ForegroundColorSpan(sub), 0) }
            return sb
        }

        /** Starts a stream if ElevenLabs is on and a key is set; otherwise null (use Parakeet). */
        fun start(context: Context, listener: Listener): CloudStream? {
            if (!CloudEngine.active(context)) return null
            val key = CloudEngine.apiKey(context) ?: return null
            return CloudStream(listener).also { it.open(key) }
        }
    }
}
