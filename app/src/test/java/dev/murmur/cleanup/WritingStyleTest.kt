package dev.murmur.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals

class WritingStyleTest {
    @Test fun casualLowercasesStartsAndDropsLastPeriod() {
        assertEquals("yeah I'm down. see you at 7", WritingStyle.casual("Yeah I'm down. See you at 7.", true, true))
        assertEquals("I think so", WritingStyle.casual("I think so.", true, true))
        assertEquals("iPhone works?", WritingStyle.casual("iPhone works?", true, true))
        assertEquals("NASA called", WritingStyle.casual("NASA called.", true, true))
        assertEquals("wait...", WritingStyle.casual("Wait...", true, true))
    }

    @Test fun casualTogglesOff() {
        assertEquals("Yeah I'm down.", WritingStyle.casual("Yeah I'm down.", false, false))
    }

    @Test fun polishedWritesThingsOut() {
        assertEquals("Yes, I'm going to be there around 7. Can you save me a seat?",
            WritingStyle.polished("lol yeah, I'm gonna be there around 7. Can you save me a seat?"))
        assertEquals("I want to go.", WritingStyle.polished("I wanna go"))
        assertEquals("See you there. ❤️", WritingStyle.polished("see you there ❤️"))
    }
}
