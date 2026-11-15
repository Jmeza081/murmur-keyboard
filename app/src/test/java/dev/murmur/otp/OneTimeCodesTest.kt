package dev.murmur.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneTimeCodesTest {
    private fun x(s: String) = OneTimeCodes.extract(s)

    @Test fun plainCode() = assertEquals("482913", x("Your Chase verification code is 482913. Don't share it."))
    @Test fun splitCode() = assertEquals("482913", x("G-482 913 is your Google verification code"))
    @Test fun fourDigits() = assertEquals("7731", x("Use 7731 to sign in to Uber"))
    @Test fun spanish() = assertEquals("55120", x("Tu código es 55120"))
    @Test fun noCue() = assertNull(x("Dinner at 7, bring 2 bottles. Table 1234"))
    @Test fun skipsYear() = assertEquals("903311", x("Code for your 2026 account: 903311"))
    @Test fun skipsPrice() = assertNull(x("Your login total was $1250"))
    @Test fun skipsTime() = assertNull(x("Sign in reminder for 1030am"))
}
