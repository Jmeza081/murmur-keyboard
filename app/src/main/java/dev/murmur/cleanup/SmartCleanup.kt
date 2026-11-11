package dev.murmur.cleanup

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional "Smart" cleanup with Gemini Nano, which runs on the phone through Android's AICore.
 * Text never leaves the device. Any failure, timeout or meaning change falls back to the
 * rule-based text, so Smart can only make things better or leave them as they were.
 */
object SmartCleanup {
    enum class Availability { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

    private const val TAG = "MurmurSmart"
    private const val TIMEOUT_MS = 1500L
    private val SYSTEM = """
        You clean up dictated text. Remove filler words, stutters, false starts and self-corrections
        (keep only the corrected version). Fix punctuation and capitalization. Keep every other word
        the speaker said, in the same order, with the same meaning and tone. Never drop hedges or
        negations. Never turn a statement into a question. Do not add anything, summarize, or answer
        questions. Output only the cleaned text.
    """.trimIndent().replace('\n', ' ')

    private val model by lazy { Generation.getClient() }

    /** Whether Gemini Nano can run on this phone. Blocking; call off the main thread. */
    fun availability(): Availability = runBlocking {
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Availability.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> Availability.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> Availability.DOWNLOADING
                else -> Availability.UNAVAILABLE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Status check failed", e)
            Availability.UNAVAILABLE
        }
    }

    /** Asks AICore to download Gemini Nano. Collect the flow to follow progress. */
    fun download(): Flow<DownloadStatus> = model.download()

    /**
     * Returns a cleaner version of [text], or null if Gemini Nano isn't available, took longer than
     * 1.5 s, failed, or changed the meaning (see [MeaningGuard]). Blocking; call off the main thread.
     */
    fun polish(text: String): String? {
        if (text.isBlank()) return null
        val start = System.currentTimeMillis()
        val out = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    val request = generateContentRequest(SystemInstruction(SYSTEM), TextPart(text)) {
                        temperature = 0f
                        maxOutputTokens = 256
                    }
                    model.generateContent(request).candidates.firstOrNull()?.text?.trim()
                } catch (e: Exception) {
                    Log.w(TAG, "Gemini Nano failed: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
        }
        val ms = System.currentTimeMillis() - start
        return when {
            out == null -> { Log.i(TAG, "No result in $ms ms; using rules"); null }
            !MeaningGuard.keeps(text, out) -> { Log.i(TAG, "Rewrite changed the meaning ($ms ms); using rules"); null }
            else -> { Log.i(TAG, "Polished in $ms ms"); out }
        }
    }
}
