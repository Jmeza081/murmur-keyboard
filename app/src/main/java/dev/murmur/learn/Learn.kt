package dev.murmur.learn

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.DictionaryFacilitator
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.settings.Settings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Teaches the keyboard's personal history (words and which word follows which) from text that
 * was never typed key by key: dictation, and writing you import. Stays on the phone like typed history.
 */
object Learn {
    private const val PENDING = "murmur_learn_pending.txt"
    private const val MAX_IMPORT_CHARS = 2_000_000
    private const val BATCH = 150 // sentences per main-thread slice, so a big import never freezes the keyboard
    private val main = Handler(Looper.getMainLooper())
    private val sentenceEnd = Regex("[.!?…\\n]+")
    private val wordRe = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")

    /** True when the current field and settings allow learning (no incognito, no passwords, history on). */
    fun allowed(): Boolean {
        val sv = Settings.getValues() ?: return false
        return sv.mUsePersonalizedDicts && !sv.mIncognitoModeEnabled && sv.needsToLookupSuggestions()
    }

    /** Learns [text] now. Call on the main thread with the keyboard's facilitator. */
    fun fromText(f: DictionaryFacilitator, text: String) = learnSentences(f, sentences(text))

    /** Saves imported writing; the keyboard learns it the next time it opens. Returns the word count. */
    fun queue(ctx: Context, text: String): Int {
        val t = text.take(MAX_IMPORT_CHARS)
        File(ctx.filesDir, PENDING).appendText(t + "\n")
        return wordRe.findAll(t).count()
    }

    /** Learns any queued import, in small slices. Personal-history setting must be on; field rules don't apply. */
    fun drainPending(ctx: Context, f: DictionaryFacilitator) {
        val file = File(ctx.filesDir, PENDING)
        if (!file.exists() || !f.hasAtLeastOneInitializedMainDictionary()) return
        val sv = Settings.getValues() ?: return
        if (!sv.mUsePersonalizedDicts) return
        val all = sentences(file.readText())
        file.delete()
        fun slice(from: Int) {
            learnSentences(f, all.subList(from, minOf(from + BATCH, all.size)))
            if (from + BATCH < all.size) main.post { slice(from + BATCH) }
        }
        if (all.isNotEmpty()) slice(0)
    }

    private fun sentences(text: String): List<List<String>> =
        text.split(sentenceEnd).map { s -> wordRe.findAll(s).map { it.value.trimEnd('\'', '’', '-') }.toList() }.filter { it.isNotEmpty() }

    private fun learnSentences(f: DictionaryFacilitator, list: List<List<String>>) {
        val sv = Settings.getValues() ?: return
        val now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        for (words in list) {
            val first = words[0]
            // A capital at the start of a sentence says nothing about the word itself
            val autoCap = first.length > 1 && first[0].isUpperCase() && first.substring(1).all { !it.isUpperCase() }
            f.addToUserHistory(words.joinToString(" "), autoCap, NgramContext.BEGINNING_OF_SENTENCE, now, sv.mBlockPotentiallyOffensive)
        }
    }
}
