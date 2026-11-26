package app.pairline.android.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-085: block-list entries ending with '*' block every number that starts with them. */
class BlockPrefixTest {
    @Test
    fun parsesPrefixes() {
        assertEquals(PhoneNumbers.BlockPrefix(true, "234"), PhoneNumbers.blockPrefix("+234*"))
        assertEquals(PhoneNumbers.BlockPrefix(true, "234"), PhoneNumbers.blockPrefix("00234*"))
        assertEquals(PhoneNumbers.BlockPrefix(false, "0900"), PhoneNumbers.blockPrefix("0900*"))
        assertNull(PhoneNumbers.blockPrefix("+905551234567"), "an ordinary number")
        assertNull(PhoneNumbers.blockPrefix("*100#"), "a USSD code")
        assertNull(PhoneNumbers.blockPrefix("+2*"), "one digit is too broad")
        assertNull(PhoneNumbers.blockPrefix("BANK*"))
    }

    @Test
    fun matchesNumbers() {
        val intl = PhoneNumbers.blockPrefix("+234*")!!
        assertTrue(PhoneNumbers.prefixMatches(intl, "+2348012345678"))
        assertTrue(PhoneNumbers.prefixMatches(intl, "002348012345678"))
        assertFalse("a local number isn't international", PhoneNumbers.prefixMatches(intl, "08012345678"))
        assertFalse(PhoneNumbers.prefixMatches(intl, "+905551234567"))
        val local = PhoneNumbers.blockPrefix("0900*")!!
        assertTrue(PhoneNumbers.prefixMatches(local, "0900 123 45 67"))
        assertFalse(PhoneNumbers.prefixMatches(local, "09121234567"))
    }

    @Test
    fun theListUsesThem() {
        val matcher = BlockRules.Matcher(listOf(BlockedNumber("+234*", 0), BlockedNumber("+989121234567", 0)))
        assertTrue(matcher.matches("+2347011112222"))
        assertTrue("ordinary entries still match by their ending", matcher.matches("09121234567"))
        assertFalse(matcher.matches("+905551234567"))
        assertEquals("+234*", PhoneNumbers.normalizeBlockable(" +234 * "))
        assertTrue("removing the entry by its own text", PhoneNumbers.blockMatches("+234*", "+234*"))
    }

    private fun assertNull(actual: Any?, message: String) = org.junit.Assert.assertNull(message, actual)
}
