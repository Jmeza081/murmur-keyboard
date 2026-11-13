package dev.murmur.dictation

import android.content.Context
import dev.murmur.cleanup.Cleanup
import dev.murmur.cleanup.SpokenEmoji
import dev.murmur.snippets.Snippets

/**
 * Murmur: what the on-device cleanup will do to the words streaming in, so the teleprompter can show it live:
 * fillers and "scratch that" get crossed out and fold away, "heart emoji" turns into ❤️, "paste my address"
 * turns into a snippet pill. Only a preview; the real cleanup still runs on the whole text when you stop.
 */
object LiveMarks {
    class Insert(val text: String, val pill: Boolean)
    class Result(val struck: Set<Int>, val inserts: Map<Int, Insert>) // an insert goes after that word index

    private val FILLED = Regex("""u+m+|u+h+m*|e+r+m+|er+|e+h+m+|m{2,}""")
    private val VERBS = setOf("insert", "paste", "add", "type")
    private val EMOJI = setOf("emoji", "emojis", "emoticon", "emoticons")

    private fun bare(w: String) = w.lowercase().replace('’', '\'').trim { !it.isLetterOrDigit() && it != '\'' }
    private fun endsSentence(w: String) = w.trimEnd('"', '\'', ')').let { it.endsWith('.') || it.endsWith('!') || it.endsWith('?') }

    fun mark(context: Context, words: List<String>): Result {
        val struck = HashSet<Int>()
        val inserts = HashMap<Int, Insert>()
        if (words.isEmpty()) return Result(struck, inserts)
        val b = words.map(::bare)
        val cleanup = Cleanup.level(context) != Cleanup.Level.OFF

        if (cleanup) {
            // "let's start over" / "scratch all that": everything before goes
            for (i in b.indices) {
                val n = startOverAt(b, i)
                if (n > 0) for (k in 0 until i + n) struck += k
            }
            // "scratch that": the sentence (or clause) before it goes, with the command
            for (i in 0 until b.size - 1) {
                if (b[i] !in setOf("scratch", "strike", "delete") || b[i + 1] != "that") continue
                var j = i - 1
                if (j >= 0 && endsSentence(words[j])) j-- // "A. B. Scratch that." drops B
                while (j >= 0 && !endsSentence(words[j])) j--
                for (k in j + 1..i + 1) struck += k
            }
            // Filled pauses: um, uh, erm (never "uh huh")
            for (i in b.indices) if (FILLED.matches(b[i]) && b.getOrNull(i + 1) != "huh") struck += i
        }

        // "<name> emoji" becomes the emoji, at every level
        for (i in b.indices) {
            if (b[i] !in EMOJI) continue
            for (n in 4 downTo 1) {
                if (i - n < 0) continue
                val emoji = SpokenEmoji.lookup(b.subList(i - n, i).joinToString(" ")) ?: continue
                for (k in i - n..i) struck += k
                inserts[i] = Insert(emoji, pill = false)
                break
            }
        }

        // "paste my address" becomes the snippet, at every level
        val snippets = Snippets.all(context)
        if (snippets.isNotEmpty()) for (i in 0 until b.size - 2) {
            if (b[i] !in VERBS || b[i + 1] != "my") continue
            val match = snippets.flatMap { s -> Snippets.spokenNames(s).map { it.split(Regex("""\s+""")) to s } }
                .sortedByDescending { it.first.size }
                .firstOrNull { (name, _) -> i + 2 + name.size <= b.size && b.subList(i + 2, i + 2 + name.size) == name } ?: continue
            val end = i + 1 + match.first.size
            for (k in i..end) struck += k
            inserts[end] = Insert(match.second.label, pill = true)
        }
        return Result(struck, inserts)
    }

    private val START_OVER = listOf(listOf("let", "me", "start", "over"), listOf("let's", "start", "over"),
        listOf("scratch", "all", "that"), listOf("never", "mind", "all", "that"))

    private fun startOverAt(b: List<String>, i: Int): Int =
        START_OVER.firstOrNull { p -> i + p.size <= b.size && b.subList(i, i + p.size) == p }?.size ?: 0
}
