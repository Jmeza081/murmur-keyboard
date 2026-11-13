package dev.murmur.snippets

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Murmur: text you insert all the time, like your email or address (design/styles-snippets.html, 02).
 * Plain text only, stored on the phone and left out of backups. Never offered in password fields.
 */
object Snippets {
    enum class Kind(val label: String, val spoken: List<String>) {
        NONE("Any field", emptyList()),
        EMAIL("Email fields", listOf("email", "email address", "e-mail")),
        PHONE("Phone fields", listOf("phone", "phone number", "number", "cell", "cell number")),
        ADDRESS("Address fields", listOf("address", "home address", "mailing address")),
        LINK("Link fields", listOf("link", "website", "url")),
    }

    data class Snippet(val id: Long, val label: String, val text: String, val kind: Kind)

    private const val PREFS = "murmur"
    const val KEY = "snippets"

    fun all(context: Context): List<Snippet> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Snippet(o.getLong("id"), o.getString("label"), o.getString("text"),
                    runCatching { Kind.valueOf(o.optString("kind", "NONE")) }.getOrDefault(Kind.NONE))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<Snippet>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label).put("text", it.text).put("kind", it.kind.name)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Adds [s], or replaces the snippet with the same id. */
    fun put(context: Context, s: Snippet) {
        val list = all(context).toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        save(context, list)
    }

    fun remove(context: Context, id: Long) = save(context, all(context).filterNot { it.id == id })

    /** What a field asks for, from its input type and hint; null for password fields and anything else. */
    fun kindFor(info: EditorInfo?): Kind? {
        info ?: return null
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (isPassword(cls, variation)) return null
        if (cls == InputType.TYPE_CLASS_PHONE) return Kind.PHONE
        if (cls != InputType.TYPE_CLASS_TEXT) return null
        when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return Kind.EMAIL
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> return Kind.ADDRESS
            InputType.TYPE_TEXT_VARIATION_URI -> return Kind.LINK
        }
        // Many web forms use plain text fields; their hint says what they want
        val hint = info.hintText?.toString()?.lowercase() ?: return null
        return when {
            "email" in hint || "e-mail" in hint -> Kind.EMAIL
            "phone" in hint || "mobile" in hint -> Kind.PHONE
            "address" in hint && "email" !in hint -> Kind.ADDRESS
            "website" in hint || "url" in hint -> Kind.LINK
            else -> null
        }
    }

    private fun isPassword(cls: Int, variation: Int) = when (cls) {
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }

    fun forKind(context: Context, kind: Kind) = all(context).filter { it.kind == kind }

    /** The phrases that insert [s] while dictating: "insert my <label>", and the field kind's names. */
    fun spokenNames(s: Snippet): List<String> = (listOf(s.label.lowercase().trim()) + s.kind.spoken).filter { it.isNotEmpty() }.distinct()

    private const val VERBS = "insert|paste|add|type|drop in|put in"

    /**
     * "Sure, ship it to paste my address" becomes "Sure, ship it to 1428 Elm St…". Only "<verb> my <name>",
     * so "check my email" stays as spoken. Runs on the device at every cleanup level.
     */
    fun applySpoken(context: Context, text: String): String {
        if (!Regex("""\b(?:$VERBS)\s+my\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return text
        val list = all(context)
        if (list.isEmpty()) return text
        // Longest names first, so "home address" wins over "address"
        val names = list.flatMap { s -> spokenNames(s).map { it to s } }.sortedByDescending { it.first.length }
        var out = text
        for ((name, s) in names) {
            val words = name.split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
            val re = Regex("""\b(?:$VERBS)\s+my\s+$words\b\.?""", RegexOption.IGNORE_CASE)
            out = re.replace(out) { s.text } // a lambda, so "$" in the snippet stays literal
        }
        return out
    }
}
