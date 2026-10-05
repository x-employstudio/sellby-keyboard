// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorEngineTest {
    private val minus = CalculatorEngine.TYPOGRAPHIC_MINUS

    /** The reported bug: 680 - 670 showed =680. The minus KEY is labelled "−" (U+2212), not the ASCII "-". */
    @Test
    fun theMinusKeyIsNotTheAsciiHyphen() {
        assertNotEquals("-", minus)
        assertEquals("−", minus)
    }

    @Test
    fun theMinusKeyBecomesTheAsciiMinusTheLogicUnderstands() {
        assertEquals("-", CalculatorEngine.normalizeKey(minus))
        assertTrue(CalculatorEngine.normalizeKey(minus).single() in "+-×÷")
    }

    @Test
    fun otherKeysPassThroughUnchanged() {
        listOf("0", "5", "000", "%", "+", "-", "×", "÷", "C", "DEL", "=", ",").forEach {
            assertEquals(it, CalculatorEngine.normalizeKey(it))
        }
    }

    /** What the user typed: the keys 6 8 0 − 6 7 0, the minus key pressed as labelled. */
    @Test
    fun subtractingWithTheMinusKeyGivesTheDifference() {
        assertEquals("10", CalculatorEngine.evaluate("680" + CalculatorEngine.normalizeKey(minus) + "670"))
    }

    /** Even if a typographic minus ever reached an expression, it must not be silently skipped (the old result: 680). */
    @Test
    fun aTypographicMinusInAnExpressionStillSubtracts() {
        assertEquals("10", CalculatorEngine.evaluate("680" + minus + "670"))
    }

    @Test
    fun theFourBasicOperations() {
        assertEquals("1.350", CalculatorEngine.evaluate("680+670"))
        assertEquals("10", CalculatorEngine.evaluate("680-670"))
        assertEquals("6", CalculatorEngine.evaluate("2×3"))
        assertEquals("5", CalculatorEngine.evaluate("10÷2"))
    }

    @Test
    fun multiplicationAndDivisionBindTighterThanAdditionAndSubtraction() {
        assertEquals("14", CalculatorEngine.evaluate("2+3×4"))
        assertEquals("-2", CalculatorEngine.evaluate("10-3×4"))
    }

    @Test
    fun aResultBelowZeroKeepsItsSign() {
        assertEquals("-10", CalculatorEngine.evaluate("670-680"))
    }

    @Test
    fun thousandSeparatorsAreDotsAndTheDecimalSeparatorIsAComma() {
        // The expression itself is written the Indonesian way: "1.000" is one thousand, "1,5" one and a half.
        assertEquals("1.500", CalculatorEngine.evaluate("1.000+500"))
        assertEquals("3", CalculatorEngine.evaluate("1,5+1,5"))
        assertEquals("1,5", CalculatorEngine.evaluate("3÷2"))
    }

    @Test
    fun percentOfAnAmount() {
        assertEquals("1.100", CalculatorEngine.evaluate("1.000+10%"))
        assertEquals("900", CalculatorEngine.evaluate("1.000-10%"))
    }

    @Test
    fun anEmptyOrUnfinishedExpressionDoesNotBreak() {
        assertEquals("0", CalculatorEngine.evaluate(""))
        assertEquals("680", CalculatorEngine.evaluate("680-")) // a dangling operator is ignored while typing
        assertEquals("Error", CalculatorEngine.evaluate("5÷0"))
    }

    @Test
    fun theLiveExpressionGetsItsThousandSeparators() {
        assertEquals("1.234.567", CalculatorEngine.formatExpressionString("1234567"))
        assertEquals("1.000-500", CalculatorEngine.formatExpressionString("1000-500"))
        assertEquals("1.234,5", CalculatorEngine.formatExpressionString("1234,5"))
    }

    /** The guard against label drift: every plain key on the keyboard must be one the key-press logic handles. */
    @Test
    fun everyPlainKeyLabelIsUnderstoodByTheKeyPressLogic() {
        val handled = setOf("+", "-", "×", "÷", "%", "000")
        val allKeys = CalculatorEngine.ROW1_KEYS + CalculatorEngine.ROW2_KEYS + CalculatorEngine.ROW3_KEYS + CalculatorEngine.ROW4_KEYS
        allKeys.forEach { label ->
            val key = CalculatorEngine.normalizeKey(label)
            val isDigit = key.length == 1 && key[0].isDigit()
            assertTrue("key \"$label\" would fall into the digit branch without being a digit", isDigit || key in handled)
        }
    }

    @Test
    fun theKeyboardHasAllFourOperatorsAndTenDigitsWorthOfKeys() {
        val allKeys = (CalculatorEngine.ROW1_KEYS + CalculatorEngine.ROW2_KEYS + CalculatorEngine.ROW3_KEYS + CalculatorEngine.ROW4_KEYS)
            .map { CalculatorEngine.normalizeKey(it) }
        listOf("+", "-", "×", "÷", "%").forEach { assertTrue("missing $it", it in allKeys) }
        ('0'..'9').forEach { assertTrue("missing digit $it", allKeys.any { k -> k == it.toString() }) }
    }
}
