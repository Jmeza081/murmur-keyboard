package dev.murmur.speech

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * ElevenLabs Scribe, the primary engine when it's on and a key is set (PLAN.md, "Spike: ElevenLabs").
 * Uploads the recording once you finish (raw 16 kHz PCM, no encoding step) and returns the text.
 * Any failure (offline, slow, out of plan hours, bad key) throws [Failure], and the caller falls back
 * to Parakeet on the phone.
 */
object CloudEngine {
    private const val TAG = "MurmurCloud"
    private const val PREFS = "murmur"
    private const val ENABLED = "cloud_enabled"
    private const val KEY_BLOB = "elevenlabs_key" // encrypted with the Android keystore; never in backups
    private const val URL_STT = "https://api.elevenlabs.io/v1/speech-to-text"
    private const val URL_SUBSCRIPTION = "https://api.elevenlabs.io/v1/user/subscription"
    const val MODEL = "scribe_v2"

    /** Why the cloud wasn't used, in words for the settings screen. */
    class Failure(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(ENABLED, on).apply()
    fun hasKey(ctx: Context) = dev.murmur.settings.Secrets.has(ctx, KEY_BLOB)
    /** The keystore API needs Android 6; older phones stay on device. */
    val supported get() = dev.murmur.settings.Secrets.supported
    fun active(ctx: Context) = supported && enabled(ctx) && hasKey(ctx)

    // ---------------------------------------------------------------- transcription

    /** Blocking; call off the main thread. */
    fun transcribe(ctx: Context, samples: FloatArray, keyterms: List<String>): SpeechEngine.Result {
        val key = apiKey(ctx) ?: throw Failure("No API key")
        val start = System.currentTimeMillis()
        val seconds = samples.size.toDouble() / SpeechEngine.SAMPLE_RATE
        val boundary = "murmur" + System.nanoTime()
        val body = ByteArrayOutputStream(samples.size * 2 + 4096)
        fun field(name: String, value: String) = body.write(
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        field("model_id", MODEL)
        field("file_format", "pcm_s16le_16")
        field("tag_audio_events", "false")
        field("timestamps_granularity", "none")
        // Your Dictionary words as hints, so names come out right (adds ~20% to the hour price)
        keyterms.filter { it.length <= 50 && it.split(' ').size <= 5 && it.none { c -> c in "<>{}[]\\" } }.take(1000)
            .forEach { field("keyterms", it) }
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.pcm\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        body.write(pcm16(samples))
        body.write("\r\n--$boundary--\r\n".toByteArray())

        // Generous enough for a slow upload, short enough that Parakeet still feels quick after it
        val timeout = (4000 + seconds * 150).toInt().coerceAtMost(20_000)
        val conn = (URL(URL_STT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 3000; readTimeout = timeout
            setRequestProperty("xi-api-key", key)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(body.size())
        }
        try {
            conn.outputStream.use { body.writeTo(it) }
            val code = conn.responseCode
            if (code !in 200..299) throw Failure(describe(code, conn.errorStream?.bufferedReader()?.readText().orEmpty()))
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val ms = System.currentTimeMillis() - start
            Log.i(TAG, "Transcribed %.1f s in %d ms".format(seconds, ms))
            return SpeechEngine.Result(json.optString("text").trim(), seconds, ms)
        } catch (e: Failure) {
            throw e
        } catch (e: UnknownHostException) {
            throw Failure("Offline", e)
        } catch (e: SocketTimeoutException) {
            throw Failure("Too slow (over ${timeout / 1000} s)", e)
        } catch (e: Exception) {
            throw Failure("Network error", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(code: Int, body: String): String {
        val status = runCatching { JSONObject(body).optJSONObject("detail")?.optString("status") }.getOrNull().orEmpty()
        return when {
            status.contains("quota", true) || code == 402 -> "Out of plan hours"
            code == 401 -> "API key not accepted"
            code == 429 -> "Too many requests"
            else -> "ElevenLabs error $code${if (status.isNotEmpty()) " ($status)" else ""}"
        }
    }

    /** -1..1 floats to 16-bit little-endian PCM. */
    private fun pcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, f ->
            val s = (f.coerceIn(-1f, 1f) * 32767).toInt()
            out[2 * i] = (s and 0xFF).toByte(); out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    // ---------------------------------------------------------------- key check

    data class Plan(val tier: String, val used: Long, val limit: Long)

    /** Checks the key and reads the plan. Blocking; call off the main thread. */
    fun checkKey(key: String): Plan {
        val conn = (URL(URL_SUBSCRIPTION).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 8000; setRequestProperty("xi-api-key", key)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                Log.w(TAG, "Key check $code: $body")
                // A key limited to Speech to Text can't read the plan, but it's a real key
                if (body.contains("missing_permissions")) return Plan("", 0, 0)
                throw Failure(describe(code, body))
            }
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            return Plan(j.optString("tier", "unknown"), j.optLong("character_count"), j.optLong("character_limit"))
        } catch (e: Failure) { throw e
        } catch (e: UnknownHostException) { throw Failure("Offline", e)
        } catch (e: Exception) { throw Failure("Couldn't reach ElevenLabs", e)
        } finally { conn.disconnect() }
    }

    // ---------------------------------------------------------------- key storage (see Secrets)

    fun keySavedAt(ctx: Context) = dev.murmur.settings.Secrets.savedAt(ctx, KEY_BLOB)
    fun setApiKey(ctx: Context, key: String?) = dev.murmur.settings.Secrets.put(ctx, KEY_BLOB, key)
    internal fun apiKey(ctx: Context): String? = dev.murmur.settings.Secrets.get(ctx, KEY_BLOB)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
