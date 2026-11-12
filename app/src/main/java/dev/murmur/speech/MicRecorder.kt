package dev.murmur.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Records 16 kHz mono audio in memory and reports levels and pauses.
 * Callbacks run on the recording thread; post to the main thread before touching views.
 * The caller must hold the RECORD_AUDIO permission.
 */
class MicRecorder(private val listener: Listener) {
    /** Quiet after speech that ends the recording. Set from settings before [start]. */
    var silenceStopMs = SILENCE_STOP_MS
    /** Each 100 ms of 16-bit audio as it's recorded (for streaming to ElevenLabs). Called on the recording thread. */
    @Volatile var onChunk: ((ShortArray) -> Unit)? = null

    interface Listener {
        /** Loudness of the last 100 ms, roughly 0..0.3 for speech. */
        fun onLevel(rms: Float)
        /** Speech was heard, then [SILENCE_STOP_MS] of quiet, or the length limit was reached. */
        fun onAutoStop()
        /** [NO_SPEECH_MS] passed without any speech. */
        fun onNoSpeech()
    }

    @Volatile private var running = false
    @Volatile private var onFinished: ((FloatArray) -> Unit)? = null
    private var thread: Thread? = null

    /** Starts recording. Returns false if the mic couldn't be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = SpeechEngine.SAMPLE_RATE
        val chunk = ShortArray(rate / 10) // 100 ms
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk.size * 4))
        } catch (e: Exception) {
            Log.e(TAG, "Mic unavailable", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }

        running = true
        onFinished = null
        thread = thread(name = "murmur-mic") {
            val audio = Samples()
            var heardSpeech = false
            var quietMs = 0
            var autoStopSent = false
            rec.startRecording()
            try {
                while (running) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    onChunk?.invoke(chunk.copyOf(n))
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = chunk[i] / 32768f
                        audio.add(s)
                        sum += s * s
                    }
                    val rms = sqrt(sum / n).toFloat()
                    listener.onLevel(rms)
                    if (rms > SPEECH_RMS) { heardSpeech = true; quietMs = 0 } else quietMs += n * 1000 / rate
                    val tooLong = audio.size >= rate * MAX_SECONDS
                    if (!autoStopSent && ((heardSpeech && quietMs >= silenceStopMs) || tooLong)) {
                        autoStopSent = true
                        listener.onAutoStop()
                    }
                    if (!heardSpeech && quietMs >= NO_SPEECH_MS) { running = false; listener.onNoSpeech() }
                }
            } finally {
                rec.stop()
                rec.release()
            }
            onFinished?.invoke(audio.toArray())
        }
        return true
    }

    /** Stops and hands the recording to [onDone] on the recording thread. */
    fun stop(onDone: (FloatArray) -> Unit) {
        onFinished = onDone
        running = false
    }

    /** Stops and throws the audio away. */
    fun cancel() {
        onFinished = null
        running = false
    }

    private class Samples {
        private var data = FloatArray(SpeechEngine.SAMPLE_RATE * 10)
        var size = 0; private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    companion object {
        private const val TAG = "MurmurMic"
        const val SPEECH_RMS = 0.02f
        const val SILENCE_STOP_MS = dev.murmur.settings.DictationPrefs.DEFAULT_SILENCE_MS
        const val NO_SPEECH_MS = 8000
        const val MAX_SECONDS = 300
    }
}
