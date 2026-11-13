package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StanceJsonBoundaryTest {
    @Test
    fun validEscapesAndScientificNotationRemainSupported() {
        assertEquals(mapOf("value" to "a\nb\r\t\b\u000C\\\"/中", "n" to -125.0),
            StanceJson.parse("""{"value":"a\nb\r\t\b\f\\\"\/\u4e2d","n":-1.25e2}"""))
    }

    @Test
    fun trailingContentIsNotSilentlyIgnored() {
        assertThrows(IllegalArgumentException::class.java) { StanceJson.parse("{} false") }
    }

    @Test
    fun duplicateKeysCannotOverrideValidatedValues() {
        assertThrows(IllegalArgumentException::class.java) { StanceJson.parse("""{"schema":2,"schema":1}""") }
    }

    @Test
    fun deepNestingIsRejectedWithoutStackOverflow() {
        val text = "[".repeat(80) + "0" + "]".repeat(80)
        assertThrows(IllegalArgumentException::class.java) { StanceJson.parse(text) }
    }

    @Test
    fun nonFiniteAndNonJsonNumbersAreRejected() {
        for (text in listOf("1e999", "+1", "01", ".5", "1.", "-01", "NaN")) {
            assertThrows(text, IllegalArgumentException::class.java) { StanceJson.parse(text) }
        }
    }

    @Test
    fun truncatedOrUnescapedStringsAreRejectedAsBadInput() {
        for (text in listOf("\"unclosed", "\"bad\\q\"", "\"line\nbreak\"", "\"\\u12\"")) {
            assertThrows(text, IllegalArgumentException::class.java) { StanceJson.parse(text) }
        }
    }
}
