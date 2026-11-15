package dev.murmur.cleanup

/**
 * Spoken edits applied after cleanup, at every level: "new line", "new paragraph", "make that a list",
 * "capitalize that", "all caps that". ("Scratch that" and "start over" are handled by [TextCleanup].)
 */
object VoiceCommands {
    private val BREAK = Regex("""[\s,;:]*\b(new line|newline|next line|new paragraph|next paragraph)\b[\s,.;:!]*""", RegexOption.IGNORE_CASE)
    private val LIST = Regex("""[\s,.;:]*\b(?:make (?:that|this|it) (?:a|into a) (?:bulleted |bullet |numbered )?list|bullet (?:that|this)|(?:make (?:that|this|it) )?numbered list)\b[\s,.;:!]*$""", RegexOption.IGNORE_CASE)
    private val CAPS = Regex("""([\p{L}\p{N}'’-]+)[\s,.;:]*\b(capitalize that|cap that|all caps that|caps that|uppercase that|lowercase that|no caps that)\b,?([.!?]?)""", RegexOption.IGNORE_CASE)

    fun apply(text: String): String {
        var s = text
        s = CAPS.replace(s) { m ->
            val word = m.groupValues[1]
            when (m.groupValues[2].lowercase()) {
                "all caps that", "caps that", "uppercase that" -> word.uppercase()
                "lowercase that", "no caps that" -> word.lowercase()
                else -> word.replaceFirstChar { it.uppercase() }
            } + m.groupValues[3]
        }
        s = BREAK.replace(s) { m -> if (m.groupValues[1].lowercase().endsWith("paragraph")) "\n\n" else "\n" }
        s = LIST.find(s)?.let { m -> toList(s.substring(0, m.range.first), numbered = m.value.contains("number", true)) } ?: s
        // a new line starts a new sentence: "...friday.\nthanks" -> "\nThanks"
        s = Regex("""(\n)([a-z])""").replace(s) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return s.trim()
    }

    /** The last paragraph of [before] as a list: a "Header:" stays on top; items split on sentences, else commas/"and". */
    private fun toList(before: String, numbered: Boolean): String {
        val cut = before.lastIndexOf("\n\n").let { if (it < 0) 0 else it + 2 }
        val head = before.substring(0, cut)
        var para = before.substring(cut).trim().trimEnd('.', ',', ';', '!')
        var header = ""
        val colon = para.indexOf(':')
        if (colon in 1 until para.length - 1) { header = para.substring(0, colon + 1).trim(); para = para.substring(colon + 1).trim() }
        val sentences = para.split(Regex("""(?<=[.!?])\s+""")).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        val items = when {
            sentences.size > 1 -> sentences
            para.contains(',') -> para.split(Regex(""",\s*(?:and\s+|or\s+)?|\s+and\s+(?=[^,]*$)"""))
            else -> para.split(Regex("""\s+and\s+"""))
        }.map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        if (items.size < 2) return before.trim()
        val lines = items.mapIndexed { i, it -> (if (numbered) "${i + 1}. " else "- ") + it.replaceFirstChar { c -> c.uppercase() } }
        return (head + (if (header.isNotEmpty()) header + "\n" else "") + lines.joinToString("\n")).trim()
    }
}
