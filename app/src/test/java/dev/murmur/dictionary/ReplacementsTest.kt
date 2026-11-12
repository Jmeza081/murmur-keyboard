package dev.murmur.dictionary

import dev.murmur.dictionary.MurmurDictionary.Replacement
import kotlin.test.assertEquals
import kotlin.test.Test

class ReplacementsTest {
    private val list = listOf(Replacement("sherpa onyx", "sherpa-onnx"), Replacement("fluid voice", "FluidVoice"), Replacement("ok", "OK"))

    @Test fun replacesPhrasesAnyCaseAndSpacing() =
        assertEquals("I built it with sherpa-onnx today.", MurmurDictionary.apply("I built it with Sherpa  Onyx today.", list))

    @Test fun keepsPunctuationAround() =
        assertEquals("Try FluidVoice, then OK.", MurmurDictionary.apply("Try fluid voice, then ok.", list))

    @Test fun wholeWordsOnly() =
        assertEquals("look okay", MurmurDictionary.apply("look okay", list))

    @Test fun replacementTextIsLiteral() =
        assertEquals("cost \$5", MurmurDictionary.apply("cost five", listOf(Replacement("five", "\$5"))))
}
