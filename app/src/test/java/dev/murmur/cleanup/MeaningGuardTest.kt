package dev.murmur.cleanup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cases are the real outputs from the Phase 4 comparison with a 1B model. */
class MeaningGuardTest {
    @Test fun acceptsGoodCleanups() {
        assertTrue(MeaningGuard.keeps(
            "Okay so first we need to well, first we need to book the flights and then the hotel.",
            "Okay, so first we need to book the flights and then the hotel."))
        assertTrue(MeaningGuard.keeps(
            "What time does the pharmacy close tonight?",
            "What time does the pharmacy close tonight?"))
        assertTrue(MeaningGuard.keeps(
            "It was fine, I mean the food was great but the service was kind of slow honestly.",
            "It was fine, I mean the food was great, but the service was kind of slow honestly."))
    }

    @Test fun rejectsDroppedClause() {
        assertFalse(MeaningGuard.keeps(
            "I think the main issue is that we don't have enough time to test it properly before the release.",
            "We don't have enough time to test it properly before the release."))
    }

    @Test fun rejectsStatementTurnedIntoQuestion() {
        assertFalse(MeaningGuard.keeps(
            "Can you pick up eggs, oat milk and spinach, oh and also the good salsa, the green one not the red one.",
            "Can you pick up eggs, oat milk, and spinach, oh, and also the good salsa, the green one, not the red one?"))
    }

    @Test fun rejectsAnswersAndAddedWords() {
        assertFalse(MeaningGuard.keeps(
            "What time does the pharmacy close tonight?",
            "Most pharmacies close at 9 PM, but check the store's website to be sure."))
        assertFalse(MeaningGuard.keeps(
            "So I was thinking we could maybe go to the farmers market on Saturday, like around 9 or something.",
            "I was thinking we could go to the farmers market on Saturday, around 9 or so."))
    }

    @Test fun rejectsDroppedNegation() {
        assertFalse(MeaningGuard.keeps("I do not want the red one.", "I do want the red one."))
    }

    @Test fun rejectsBlank() {
        assertFalse(MeaningGuard.keeps("Hello there.", "  "))
    }
}
