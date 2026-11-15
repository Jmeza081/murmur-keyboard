package dev.murmur.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {
    private fun v(s: String) = VoiceCommands.apply(s)

    @Test fun newParagraph() = assertEquals("Hi Sam.\n\nThanks for the notes.", v("Hi Sam. New paragraph. Thanks for the notes."))
    @Test fun newLine() = assertEquals("Line one\nLine two", v("Line one new line line two"))
    @Test fun listFromCommas() = assertEquals("Groceries:\n- Eggs\n- Milk\n- Bread", v("Groceries: eggs, milk, and bread. Make that a list."))
    @Test fun listFromSentences() = assertEquals("- Call mom\n- Book flights", v("Call mom. Book flights. Make it a list."))
    @Test fun numberedList() = assertEquals("1. Eggs\n2. Milk", v("Eggs and milk, make that a numbered list"))
    @Test fun listKeepsEarlierParagraphs() = assertEquals("Plan.\n\n- Eggs\n- Milk", v("Plan. New paragraph. Eggs, milk. Make that a list."))
    @Test fun capitalize() = assertEquals("I love Murmur.", v("I love murmur, capitalize that."))
    @Test fun allCaps() = assertEquals("This is URGENT", v("This is urgent all caps that"))
    @Test fun plainTextUntouched() = assertEquals("We made a list of new lines to try.", v("We made a list of new lines to try."))
}
