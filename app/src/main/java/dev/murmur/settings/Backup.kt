package dev.murmur.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.UserDictionary
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup and restore to one JSON file the user picks: every keyboard setting, Murmur's own settings
 * (cleanup, fillers, replacements, silence, waveform), and the words in the user dictionary.
 * The speech model is not included; it downloads again in setup.
 */
object Backup {
    private const val FORMAT = 1
    private const val MURMUR_PREFS = "murmur"
    /** Per-phone state that shouldn't move between phones. */
    private val SKIP = setOf("setup_seen", "last_decode_ms", "elevenlabs_key", "last_engine", "last_engine_ms", "last_fallback", "anthropic_key", "last_polish",
        "elevenlabs_key_saved_at", "anthropic_key_saved_at")

    data class Result(val settings: Int, val words: Int)

    fun write(context: Context, uri: Uri): Result {
        val keyboard = dump(context.prefs())
        val murmur = dump(context.getSharedPreferences(MURMUR_PREFS, Context.MODE_PRIVATE))
        val words = JSONArray()
        context.contentResolver.query(UserDictionary.Words.CONTENT_URI,
            arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT, UserDictionary.Words.FREQUENCY, UserDictionary.Words.LOCALE),
            null, null, null)?.use { c ->
            while (c.moveToNext()) words.put(JSONObject().apply {
                put("word", c.getString(0)); put("shortcut", c.getString(1)); put("frequency", c.getInt(2)); put("locale", c.getString(3))
            })
        }
        val root = JSONObject().put("murmurBackup", FORMAT).put("keyboard", keyboard).put("murmur", murmur).put("words", words)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString(2).toByteArray()) }
        return Result(keyboard.length() + murmur.length(), words.length())
    }

    fun read(context: Context, uri: Uri): Result {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        val root = JSONObject(text)
        require(root.optInt("murmurBackup", 0) in 1..FORMAT) { "This isn't a Murmur backup." }
        val keyboard = root.getJSONObject("keyboard")
        val murmur = root.getJSONObject("murmur")
        load(context.prefs(), keyboard)
        load(context.getSharedPreferences(MURMUR_PREFS, Context.MODE_PRIVATE), murmur)
        val words = root.optJSONArray("words") ?: JSONArray()
        val existing = mutableSetOf<Pair<String, String?>>()
        context.contentResolver.query(UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT),
            null, null, null)?.use { c -> while (c.moveToNext()) existing.add(c.getString(0) to c.getString(1)) }
        var added = 0
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val word = w.getString("word")
            val shortcut = w.optString("shortcut").takeIf { it.isNotEmpty() && it != "null" }
            if ((word to shortcut) in existing) continue
            val locale = w.optString("locale").takeIf { it.isNotEmpty() && it != "null" }
                ?.let { java.util.Locale.forLanguageTag(it.replace('_', '-')) }
            UserDictionary.Words.addWord(context, word, w.optInt("frequency", 250), shortcut, locale)
            added++
        }
        helium314.keyboard.keyboard.KeyboardSwitcher.getInstance().setThemeNeedsReload()
        return Result(keyboard.length() + murmur.length(), added)
    }

    private fun dump(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        prefs.all.forEach { (k, v) ->
            if (k in SKIP || v == null) return@forEach
            val (type, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is String -> "s" to v
                is Set<*> -> "S" to JSONArray(v.map { it.toString() })
                else -> return@forEach
            }
            out.put(k, JSONObject().put("t", type).put("v", value))
        }
        return out
    }

    private fun load(prefs: SharedPreferences, from: JSONObject) = prefs.edit {
        from.keys().forEach { k ->
            if (k in SKIP) return@forEach
            val e = from.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> putBoolean(k, e.getBoolean("v"))
                "i" -> putInt(k, e.getInt("v"))
                "l" -> putLong(k, e.getLong("v"))
                "f" -> putFloat(k, e.getDouble("v").toFloat())
                "s" -> putString(k, e.getString("v"))
                "S" -> e.getJSONArray("v").let { a -> putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
    }
}
