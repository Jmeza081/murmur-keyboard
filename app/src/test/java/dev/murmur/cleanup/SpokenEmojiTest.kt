package dev.murmur.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenEmojiTest {
    private fun e(s: String) = SpokenEmoji.apply(s)

    @Test fun turnsNamedEmojiIntoEmoji() {
        assertEquals("I love you ❤️", e("I love you heart emoji"))
        assertEquals("That's so sad 🫠", e("That's so sad melting face emoji."))
        assertEquals("Ok 😃 see you", e("Ok smiley emoji see you"))
        assertEquals("Miss you. 🫠", e("Miss you. Melting emoji."))
    }

    @Test fun longestNameWins() {
        assertEquals("💙", e("blue heart emoji"))
        assertEquals("😂", e("crying laughing emoji"))
    }

    @Test fun leavesUnknownOrUnmarkedWordsAlone() {
        assertEquals("I need a burrito emoji", e("I need a burrito emoji"))
        assertEquals("My heart is full", e("My heart is full"))
        assertEquals("I like emoji", e("I like emoji"))
    }
}
