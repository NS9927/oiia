package net.posdaca.oiia.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FocusPositionWritebackTest {
    @Test
    fun rewritingFiveDoesNotConcatenateToFiftyFive() {
        val line = "\tx = 5"
        val next = apply(line, keyStart = 1, key = "x", value = 5)
        assertEquals("\tx = 5", next)
    }

    @Test
    fun digitCountCanGrowWithoutLeavingTheOldDigit() {
        val line = "\tx = 5"
        assertEquals("\tx = 15", apply(line, keyStart = 1, key = "x", value = 15))
        assertEquals("\tx = 55", apply(line, keyStart = 1, key = "x", value = 55))
    }

    @Test
    fun sameLineAxesOnlyReplaceTheRequestedKey() {
        val line = "x = 5 y = 2"
        assertEquals("x = 7 y = 2", apply(line, keyStart = 0, key = "x", value = 7))
        assertEquals("x = 5 y = 9", apply(line, keyStart = line.indexOf('y'), key = "y", value = 9))
    }

    @Test
    fun negativeCoordinatesAreReplacedAsOneToken() {
        assertEquals("x = 3", apply("x = -2", keyStart = 0, key = "x", value = 3))
        assertEquals("x = -4", apply("x = 1", keyStart = 0, key = "x", value = -4))
    }

    @Test
    fun lineStartOffsetIsAddedToTheReplacement() {
        val replacement = FocusPositionWriteback.axisNumberReplacement("x = 5", 100, 0, "x", 8)
        assertNotNull(replacement)
        assertEquals(104, replacement!!.start)
        assertEquals(105, replacement.end)
        assertEquals("8", replacement.text)
    }

    private fun apply(line: String, keyStart: Int, key: String, value: Int): String {
        val replacement = FocusPositionWriteback.axisNumberReplacement(line, 0, keyStart, key, value)
        assertNotNull(replacement)
        return line.replaceRange(replacement!!.start, replacement.end, replacement.text)
    }
}
