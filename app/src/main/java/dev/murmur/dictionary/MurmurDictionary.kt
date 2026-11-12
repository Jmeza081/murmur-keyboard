package dev.murmur.dictionary

import android.content.Context
import android.provider.UserDictionary

/**
 * Your words and replacements (mockup "Dictionary").
 * - Words live in Android's user dictionary, which the keyboard already reads for suggestions.
 *   Murmur adds them for all languages, so they work in English and Spanish.
 * - Replacements ("sherpa onyx" -> "sherpa-onnx") are Murmur's own. They run on every dictation. When the
 *   "from" side is one word, it is also saved as a user-dictionary shortcut, so typing it suggests the fix.
 */
object MurmurDictionary {
    private const val PREFS = "murmur"
    private const val REPLACEMENTS = "replacements"
    private const val FREQUENCY = 250 // HeliBoard's default for words you add

    data class Replacement(val from: String, val to: String) {
        val alsoTyping get() = from.none { it.isWhitespace() }
    }

    // ---- words

    fun words(context: Context): List<String> {
        val out = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        runCatching {
            context.contentResolver.query(UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT),
                null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1).isNullOrEmpty()) c.getString(0)?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    fun addWord(context: Context, word: String) {
        val w = word.trim()
        if (w.isEmpty() || words(context).any { it.equals(w, ignoreCase = false) }) return
        UserDictionary.Words.addWord(context, w, FREQUENCY, null, null)
    }

    fun removeWord(context: Context, word: String) {
        context.contentResolver.delete(UserDictionary.Words.CONTENT_URI,
            "${UserDictionary.Words.WORD}=? AND (${UserDictionary.Words.SHORTCUT} IS NULL OR ${UserDictionary.Words.SHORTCUT}='')", arrayOf(word))
    }

    // ---- replacements

    fun replacements(context: Context): List<Replacement> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(REPLACEMENTS, "")!!
            .split('\n').mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { Replacement(it[0], it[1]) } }

    private fun save(context: Context, list: List<Replacement>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(REPLACEMENTS, list.joinToString("\n") { "${it.from}\t${it.to}" }).apply()

    fun addReplacement(context: Context, from: String, to: String) {
        val r = Replacement(from.trim().replace(Regex("\\s+"), " "), to.trim())
        if (r.from.isEmpty() || r.to.isEmpty()) return
        val rest = replacements(context).filterNot { it.from.equals(r.from, ignoreCase = true) }
        save(context, rest + r)
        if (r.alsoTyping) UserDictionary.Words.addWord(context, r.to, FREQUENCY, r.from.lowercase(), null)
    }

    fun removeReplacement(context: Context, r: Replacement) {
        save(context, replacements(context).filterNot { it == r })
        if (r.alsoTyping) context.contentResolver.delete(UserDictionary.Words.CONTENT_URI,
            "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.SHORTCUT}=?", arrayOf(r.to, r.from.lowercase()))
    }

    /** Applies every replacement to dictated text: whole words, any case, any spacing between words. */
    fun applyReplacements(context: Context, text: String): String = apply(text, replacements(context))

    internal fun apply(text: String, list: List<Replacement>): String = list.fold(text) { s, r ->
        val pattern = r.from.split(' ').joinToString("\\s+") { Regex.escape(it) }
        s.replace(Regex("(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE), Regex.escapeReplacement(r.to))
    }
}
