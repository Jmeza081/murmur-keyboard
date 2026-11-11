package dev.murmur.cleanup

/**
 * Rule-based cleanup for dictated text. Fast and predictable: it only removes or swaps words in
 * patterns that are clearly speech artifacts, and leaves anything ambiguous alone.
 *
 * Order matters: self-corrections run first (they rely on commas), then fillers, then
 * stutters, then spacing and capitals.
 */
object TextCleanup {

    fun clean(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return s
        s = scratchThat(s)
        s = selfCorrections(s)
        s = fillers(s)
        s = stutters(s)
        return tidy(s)
    }

    // "... Scratch that ..." drops the sentence (or clause) it follows.
    private val SCRATCH = Regex("""\bscratch that\b[.,!?]*""", RegexOption.IGNORE_CASE)

    private fun scratchThat(text: String): String {
        var s = text
        while (true) {
            val m = SCRATCH.find(s) ?: return s
            val before = s.substring(0, m.range.first).trimEnd()
            val after = s.substring(m.range.last + 1).trimStart(' ', ',')
            val cut = if (before.endsWith('.') || before.endsWith('!') || before.endsWith('?')) {
                // "A. B. Scratch that." drops B: cut back to the end of the sentence before it.
                sentenceStart(before.dropLast(1))
            } else {
                // "A, scratch that, B" drops A: cut back to the start of this sentence.
                sentenceStart(before)
            }
            s = (s.substring(0, cut) + after).trim()
        }
    }

    /** Index where the sentence containing the end of [text] starts. */
    private fun sentenceStart(text: String): Int {
        val i = text.indexOfLast { it == '.' || it == '!' || it == '?' }
        return if (i < 0) 0 else i + 1
    }

    // "5, I mean 6" and "John, no wait, Jim": swap a number for a number, or a name for a name.
    private val NAME_OR_NUMBER = """(?:(?:[A-Z][\w'’-]*\s+){0,2}[A-Z][\w'’-]*|\d[\d.,:]*)"""
    private val CUE = """(?:I mean|no,? wait|wait,? no|no,? actually|actually,? no|sorry|or rather)"""
    private val PREP = """(?:on|at|in|to|for|by|with|from)"""
    private val CORRECTION = Regex(
        """($NAME_OR_NUMBER)\s*,\s*$CUE\s*,?\s+(?:$PREP\s+)?($NAME_OR_NUMBER)""",
    )

    private fun selfCorrections(text: String): String = CORRECTION.replace(text) { m ->
        val wrong = m.groupValues[1]
        val right = m.groupValues[2].trimEnd()
        val bothNumbers = wrong[0].isDigit() && right[0].isDigit()
        val bothNames = wrong[0].isUpperCase() && right[0].isUpperCase()
        // "I" is capitalised but isn't a name ("It was fine, I mean ..."), so it never counts.
        if (!(bothNumbers || bothNames) || wrong == "I" || right == "I" || right.startsWith("I ")) return@replace m.value
        // A sentence's first word is capitalised without being part of a name ("Call John"): keep it.
        var keep = ""
        val words = wrong.split(Regex("""\s+"""))
        val atSentenceStart = text.substring(0, m.range.first).let { it.isBlank() || it.trimEnd().last() in ".!?" }
        if (bothNames && atSentenceStart && words.size > 1) keep = words.first() + " "
        keep + right
    }

    // Fillers: um, uh, erm, er, plus comma-wrapped "like" and "you know".
    private val FILLER = Regex("""(?:,\s*)?\b(?:u+m+|u+h+m*|e+r+m+|er)\b(?:\s*,)?""", RegexOption.IGNORE_CASE)
    private val WRAPPED = Regex("""\s*,\s*(?:like|you know)\s*,""", RegexOption.IGNORE_CASE)
    private val LEADING = Regex("""(^|[.!?]\s+)(?:like|you know|so like)\s*,\s*""", RegexOption.IGNORE_CASE)

    private fun fillers(text: String): String {
        var s = WRAPPED.replace(text, "")
        s = LEADING.replace(s) { it.groupValues[1] }
        s = FILLER.replace(s) { m ->
            // Keep one comma if the filler sat between two clauses that both had commas? No: drop it.
            if (m.value.startsWith(",") && m.value.endsWith(",")) "" else " "
        }
        return s
    }

    // "the the" -> "the". A few doubles are grammatical ("that that", "had had").
    private val STUTTER = Regex("""\b([A-Za-z']+)(?:\s+\1\b)+""", RegexOption.IGNORE_CASE)
    private val REAL_DOUBLES = setOf("that", "had")

    private fun stutters(text: String) = STUTTER.replace(text) { m ->
        if (m.groupValues[1].lowercase() in REAL_DOUBLES) m.value else m.groupValues[1]
    }

    private fun tidy(text: String): String {
        var s = text
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s+([,.!?;:])"""), "$1")
            .replace(Regex(""",(?:\s*,)+"""), ",")
            .replace(Regex(""",\s*([.!?])"""), "$1")
            .replace(Regex("""^[\s,;:]+"""), "")
            .replace(Regex("""([.!?])\s*[,;:]+\s*"""), "$1 ")
            .replace(Regex("""([,.!?;:])(?=[A-Za-z])"""), "$1 ")
            .trim()
        // Capitalise the first letter of the text and of each sentence.
        s = Regex("""(^|[.!?]\s+)([a-z])""").replace(s) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return s
    }
}
