package dev.murmur.cleanup

/**
 * Decides whether a language model's rewrite still says what you said. Small models sometimes
 * drop half a sentence or answer a question instead of cleaning it; when that happens Murmur
 * keeps the rule-based text instead.
 */
object MeaningGuard {
    /** Words that cleanup is allowed to remove without it counting as a meaning change. */
    private val REMOVABLE = setOf(
        "um", "umm", "uh", "uhm", "erm", "er", "ah", "like", "you", "know", "well", "so", "just",
        "actually", "basically", "literally", "okay", "ok", "mean", "wait", "no", "sorry", "scratch",
        "that", "the", "a", "an", "and", "or", "i", "it", "is", "to", "of", "kind", "sort", "really",
    )

    /** Small words that flip or soften meaning: dropping any of them is never "cleanup". */
    private val MUST_KEEP = setOf(
        "not", "never", "don't", "doesn't", "didn't", "can't", "cannot", "won't", "isn't", "aren't",
        "wasn't", "weren't", "shouldn't", "wouldn't", "couldn't", "without", "maybe", "probably",
        "might", "perhaps", "possibly",
    )

    private val WORD = Regex("""[\p{L}\p{N}']+""")

    fun keeps(original: String, rewrite: String): Boolean {
        if (rewrite.isBlank()) return false
        val before = words(original)
        val after = words(rewrite)
        val afterSet = after.toSet()
        val beforeSet = before.toSet()

        // 1. It must not drop the words that carry meaning.
        val content = before.filter { it !in REMOVABLE }
        val missing = content.count { it !in afterSet }
        val allowedMissing = if (content.size <= 6) 1 else content.size / 6 // about 15%
        if (missing > allowedMissing) return false

        // ...and never the ones that flip or soften it.
        if (before.any { it in MUST_KEEP && it !in afterSet }) return false

        // 2. It must not add its own words (a couple are fine, e.g. "5:30" for "5.30").
        val added = after.count { it !in beforeSet }
        if (added > 2) return false

        // 3. It must not turn a statement into a question, or the other way round.
        if (original.trimEnd().endsWith('?') != rewrite.trimEnd().endsWith('?')) return false

        // 4. It must not be much longer (answering or explaining instead of cleaning).
        if (after.size > before.size + 3) return false
        return true
    }

    private fun words(s: String) = WORD.findAll(s.lowercase().replace('’', '\'')).map { it.value }.toList()
}
