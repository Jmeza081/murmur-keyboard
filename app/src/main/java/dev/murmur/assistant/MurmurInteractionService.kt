package dev.murmur.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.murmur.speech.SharedEngine

/**
 * Makes Murmur selectable as the phone's digital assistant, so holding the power (side) button
 * opens [MurmurSession] over any app. Android binds this service while Murmur is the assistant.
 */
class MurmurInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        SharedEngine.preload(this) // warm the model so the first hold is fast
    }
}

/** Creates a [MurmurSession] each time the assistant is invoked. */
class MurmurSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MurmurSession(this)
}
