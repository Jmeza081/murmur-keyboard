package dev.murmur.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals

class TextCleanupTest {
    private fun clean(s: String) = TextCleanup.clean(s)

    @Test fun leavesCleanTextAlone() {
        assertEquals("Meet me at the farmers market at 9.", clean("Meet me at the farmers market at 9."))
    }

    @Test fun removesFillerWords() {
        assertEquals("So the place is on Valencia.", clean("So um the place is on Valencia."))
        assertEquals("I think we should go.", clean("Uh, I think we should, um, go."))
        assertEquals("That works for me.", clean("Erm that works for me."))
    }

    @Test fun keepsWordsThatOnlyLookLikeFillers() {
        assertEquals("Ah, I see what you mean.", clean("Ah, I see what you mean."))
        assertEquals("I like the blue one.", clean("I like the blue one."))
        assertEquals("Do you know the way?", clean("Do you know the way?"))
        assertEquals("Umbrella and umber.", clean("Umbrella and umber."))
    }

    @Test fun removesCommaDelimitedLikeAndYouKnow() {
        assertEquals("It was really good.", clean("It was, like, really good."))
        assertEquals("It's right next to the bookstore.", clean("It's, you know, right next to the bookstore."))
        assertEquals("We should leave early.", clean("You know, we should leave early."))
        assertEquals("The line was so long.", clean("Like, the line was so long."))
    }

    @Test fun collapsesStutters() {
        assertEquals("The place is open.", clean("The the place is open."))
        assertEquals("I think so.", clean("I I think so."))
        assertEquals("I know that that is true.", clean("I know that that is true."))
    }

    @Test fun scratchThatDropsThePreviousSentence() {
        assertEquals("I'll be there at 6.", clean("I'll be there at 5. Scratch that. I'll be there at 6."))
        assertEquals("Buy oat milk.", clean("Buy milk, scratch that, buy oat milk."))
        assertEquals("", clean("Never mind this. Scratch that."))
    }

    @Test fun shortSelfCorrectionsReplaceTheLastWords() {
        assertEquals("Meet at 6 tomorrow.", clean("Meet at 5, I mean 6 tomorrow."))
        assertEquals("Call Jim later.", clean("Call John, no wait, Jim later."))
        assertEquals("Send it to Jim.", clean("Send it to John, I mean Jim."))
    }

    @Test fun moreCorrectionCues() {
        assertEquals("I want to meet on Thursday.", clean("I want to meet on Wednesday, wait no, on Thursday."))
        assertEquals("I'll be there at 5.30.", clean("I'll be there at 5, no actually 5.30."))
        assertEquals("Book it for Friday.", clean("Book it for Thursday, or rather Friday."))
        assertEquals("We went to Paris, actually Paris was amazing.", clean("We went to Paris, actually Paris was amazing."))
    }

    @Test fun multiWordNamesAndNumbers() {
        assertEquals("Fly to San Francisco on Friday.", clean("Fly to New York, I mean San Francisco on Friday."))
        assertEquals("It costs 40 dollars.", clean("It costs 30, no wait, 40 dollars."))
    }

    @Test fun collapsesRestartedPhrases() {
        assertEquals("Okay so first we need to book the flights and then the hotel.",
            clean("Okay so first we need to um, well, first we need to book the flights and then the hotel."))
        assertEquals("I want to go to the park.", clean("I want to, I want to go to the park."))
        assertEquals("We should leave at noon.", clean("We should, uh, we should leave at noon."))
    }

    @Test fun keepsDeliberateRepetition() {
        assertEquals("Bye bye, see you soon.", clean("Bye bye, see you soon."))
        assertEquals("It was a long, long day.", clean("It was a long, long day."))
    }

    @Test fun leavesLongIMeanAlone() {
        val s = "It was fine, I mean the food was great but the service was slow."
        assertEquals(s, clean(s))
    }

    @Test fun fixesSpacingAndCapitals() {
        assertEquals("Hello there. How are you?", clean("hello there.  how are you ?"))
        assertEquals("Okay, sounds good.", clean("Okay , , sounds good ."))
    }
}
