package dev.murmur.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import dev.murmur.speech.MicRecorder
import dev.murmur.speech.ModelManager
import dev.murmur.speech.SharedEngine
import kotlin.math.log10

/**
 * Android's speech recognizer API backed by Murmur's on-device model. Android requires an
 * assistant to provide one, and other apps that ask the system for speech recognition get Murmur
 * once it's the assistant.
 */
class MurmurRecognitionService : RecognitionService() {
    private var recorder: MicRecorder? = null
    private var callback: Callback? = null

    override fun onStartListening(intent: Intent?, listener: Callback) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return safely { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
        }
        if (!ModelManager.isInstalled(this)) return safely { listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
        recorder?.cancel()
        callback = listener
        SharedEngine.preload(this)
        var spoke = false
        val rec = MicRecorder(object : MicRecorder.Listener {
            override fun onLevel(rms: Float) {
                if (!spoke && rms > MicRecorder.SPEECH_RMS) { spoke = true; safely { listener.beginningOfSpeech() } }
                safely { listener.rmsChanged((20 * log10(rms.coerceAtLeast(1e-4f))).toFloat() + 60f) }
            }
            override fun onAutoStop() = finish()
            override fun onNoSpeech() { safely { listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }; recorder = null }
        })
        if (!rec.start()) return safely { listener.error(SpeechRecognizer.ERROR_AUDIO) }
        recorder = rec
        safely { listener.readyForSpeech(Bundle()) }
    }

    override fun onStopListening(listener: Callback) = finish()

    override fun onCancel(listener: Callback) {
        recorder?.cancel()
        recorder = null
    }

    private fun finish() {
        val rec = recorder ?: return
        val listener = callback ?: return
        recorder = null
        safely { listener.endOfSpeech() }
        rec.stop { samples ->
            SharedEngine.transcribe(this, samples) { result ->
                val text = result?.text.orEmpty()
                if (text.isEmpty()) safely { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
                else safely {
                    listener.results(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                    })
                }
            }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w("MurmurRecognizer", "Client went away", e) }
    }
}
