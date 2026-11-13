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
        s = artifacts(s)
        if (s.isEmpty()) return s
        s = startOver(s)
        s = scratchThat(s)
        s = selfCorrections(s)
        s = fragments(s)
        s = fillers(s)
        s = restarts(s)
        s = stutters(s)
        s = loops(s)
        s = spokenEmail(s)
        return casualSlang(tidy(s))
    }

    // Texting abbreviations stay lowercase, even at the start of a sentence: "LOL" and "Lol" become "lol".
    private val SLANG = Regex("""\b(?:lol|lmao|lmfao|rofl|omg|omfg|idk|idc|tbh|tbf|btw|brb|imo|imho|smh|ngl|ttyl|jk|ikr|hmu|wyd|wbu|nvm|ily|irl|afaik)\b""", RegexOption.IGNORE_CASE)

    private fun casualSlang(text: String) = SLANG.replace(text) { it.value.lowercase() }

    // Speech-engine junk (design/research/filler-words.md §6): [inaudible], (laughs), leaked tokens, music notes.
    private val TAGS = Regex("""\[[^\]\n]{1,40}]|<\|?[a-z_]+\|?>|[♪♫]""", RegexOption.IGNORE_CASE)
    private val SOUNDS = Regex("""\((?:laugh\w*|cough\w*|sigh\w*|clears throat|[a-z ]*music|inaudible|static|silence|noise|applause|chuckles?|giggles?|sniff\w*|breath\w*|exhales?|inhales?)\)""", RegexOption.IGNORE_CASE)

    private fun artifacts(text: String) = SOUNDS.replace(TAGS.replace(text, " "), " ").trim()

    // "Let me start over" drops everything said before it.
    private val START_OVER = Regex("""\b(?:let me start over|let's start over|let’s start over|scratch all that|never mind all that|start over from the top)\b[.,!?]*""", RegexOption.IGNORE_CASE)

    private fun startOver(text: String): String {
        val m = START_OVER.findAll(text).lastOrNull() ?: return text
        return text.substring(m.range.last + 1).trimStart(' ', ',')
    }

    // "... Scratch that ..." drops the sentence (or clause) it follows.
    private val SCRATCH = Regex("""\b(?:scratch that|strike that|delete that)\b[.,!?]*""", RegexOption.IGNORE_CASE)

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
    private val CUE = """(?:I mean|I meant|no,? wait|wait,? no|no,? actually|actually,? no|sorry|or rather|make that)"""
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

    // Word fragments: "tr- try" and "b-b-but" keep only the finished word.
    private val FRAGMENT = Regex("""\b(\w{1,5})[-–]\s+(?=\1)""", RegexOption.IGNORE_CASE)
    private val STAMMER = Regex("""\b(\w)(?:-\1)+-(?=\1)""", RegexOption.IGNORE_CASE)

    private fun fragments(text: String) = STAMMER.replace(FRAGMENT.replace(text, ""), "")

    // Filled pauses (always safe): um, umm, uhm, uh, uhh, er, erm, ehm, mm, mmm. Never "uh-huh", "uh huh" or "mm-hmm" (they mean yes).
    private val FILLER = Regex("""(?:,\s*)?(?<![\w-])(?:u+m+|u+h+m*|e+r+m*|e+h+m+|m{2,})(?![\w-])(?!\s+huh\b)(?:\s*,)?""", RegexOption.IGNORE_CASE)
    // Interjections that only go when wrapped in commas mid-sentence: "it was, hmm, fine"
    private val WRAPPED_INTERJECTION = Regex(""",\s*(?:a+h+|h+m+|hm|e+h)\s*,""", RegexOption.IGNORE_CASE)
    // Spoken fillers, only when comma-wrapped (research §2): "It was, like, good"; "It's, you know, fine"
    private val WRAPPED = Regex("""\s*,\s*(?:like|you know|basically|you see|I mean|let me think|let me see|let's see|what's the word|you know what I mean)\s*,""", RegexOption.IGNORE_CASE)
    private val LEADING = Regex("""(^|[.!?]\s+)(?:like|you know|so like|basically|okay so|ok so|alright so|all right so)\s*,\s*""", RegexOption.IGNORE_CASE)

    private fun fillers(text: String): String {
        var s = WRAPPED_INTERJECTION.replace(text, "")
        s = WRAPPED.replace(s, "")
        s = LEADING.replace(s) { it.groupValues[1] }
        s = FILLER.replace(s) { m ->
            // Keep one comma if the filler sat between two clauses that both had commas? No: drop it.
            if (m.value.startsWith(",") && m.value.endsWith(",")) "" else " "
        }
        return s
    }

    // "first we need to, well, first we need to book" -> "first we need to book": a phrase of 2-6
    // words said twice, with at most a filler or two in between, keeps only the second try.
    private val RESTART = Regex(
        """\b((?:[\w'’]+\s+){1,5}[\w'’]+)[\s,]+(?:(?:well|so|um+|uh+|I mean|like)[\s,]+){0,2}\1\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun restarts(text: String): String {
        var s = text
        while (true) {
            val next = RESTART.replace(s) { it.groupValues[1] }
            if (next == s) return s
            s = next
        }
    }

    // "the the" -> "the". Some doubles are grammatical ("that that", "had had") or deliberate ("bye bye").
    private val STUTTER = Regex("""\b([A-Za-z']+)(?:\s+\1\b)+""", RegexOption.IGNORE_CASE)
    // Grammatical or deliberate doubles, never collapsed (research §4b), plus number words ("one one two")
    private val REAL_DOUBLES = setOf("that", "had", "bye", "no", "yes", "yeah", "very", "really", "ha", "hey", "knock", "blah", "now",
        "do", "will", "can", "there", "so", "night", "much", "many", "long", "in", "on", "well", "tut", "chop", "choo", "go", "oh",
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "hundred", "thousand", "double")

    private fun stutters(text: String) = STUTTER.replace(text) { m ->
        if (m.groupValues[1].lowercase() in REAL_DOUBLES) m.value else m.groupValues[1]
    }

    // The same sentence said (or hallucinated) again and again keeps one copy.
    private val LOOP = Regex("""(\b[^.!?]{8,}[.!?])(?:\s*\1)+""")

    private fun loops(text: String) = LOOP.replace(text) { it.groupValues[1] }

    // "alex at gmail dot com" becomes "alex@gmail.com" (needs a real ending like com, so "connect the dots" is safe).
    private val SPOKEN_EMAIL = Regex("""\b([A-Za-z0-9._]+)\s+at\s+([A-Za-z0-9-]+)\s+dot\s+(com|org|net|io|edu|gov|co|ai|app|dev|me|us)\b""", RegexOption.IGNORE_CASE)

    private fun spokenEmail(text: String) = SPOKEN_EMAIL.replace(text) { m ->
        "${m.groupValues[1]}@${m.groupValues[2]}.${m.groupValues[3]}".lowercase()
    }

    private fun tidy(text: String): String {
        var s = text
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s+([,.!?;:])"""), "$1")
            .replace(Regex(""",(?:\s*,)+"""), ",")
            .replace(Regex(""",\s*([.!?])"""), "$1")
            .replace(Regex("""^[\s,;:]+"""), "")
            .replace(Regex("""([.!?])\s*[,;:]+\s*"""), "$1 ")
            .replace(Regex("""([,!?;:])(?=[A-Za-z])"""), "$1 ")
            .replace(Regex("""\.(?=[A-Z][a-z])"""), ". ") // but not inside "example.com" or "alex.rivera"
            .trim()
        // Capitalise the first letter of the text and of each sentence.
        s = Regex("""(^|[.!?]\s+)([a-z])""").replace(s) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return s
    }
}
