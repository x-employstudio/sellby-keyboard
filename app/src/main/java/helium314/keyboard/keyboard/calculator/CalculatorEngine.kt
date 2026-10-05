// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.calculator

import java.util.Locale
import kotlin.math.abs

/**
 * The pure part of the Calculator: key normalisation, the live expression formatting and the expression
 * evaluator/formatter, ported from sellby_keyboard.dart's _calculateExpression/_evalMath/_evalSubMath/
 * _parseTokenValue/_formatResult (line ~840-1057) and _formatExpressionString. No Android, so it is unit
 * tested ([CalculatorEngineTest]); [CalculatorView] only owns the views and the key-press state machine.
 *
 * The expression uses "." as thousand separator and "," as decimal separator (Indonesian), and the
 * operators + - × ÷ (plain ASCII hyphen-minus for subtraction, see [normalizeKey]).
 */
internal object CalculatorEngine {
    /** U+2212, built from its code point so it can never be confused with the hyphen-minus in a diff. */
    val TYPOGRAPHIC_MINUS: String = Char(0x2212).toString()

    /** The labels of the plain keys, row by row (the keys with their own look - AC, backspace, ABC+comma
     *  and "=" - are built separately). The view builds its keys from these, and [CalculatorEngineTest]
     *  checks that every one of them is a key the state machine understands, so a label can no longer
     *  drift from what the logic accepts. */
    val ROW1_KEYS = listOf("1", "2", "3")
    val ROW2_KEYS = listOf("4", "5", "6", "×", "÷")
    val ROW3_KEYS = listOf("7", "8", "9", "+", TYPOGRAPHIC_MINUS)
    val ROW4_KEYS = listOf("0", "000", "%")

    /** The minus key is labelled with the typographic minus "−" (U+2212, it looks better than a hyphen on
     *  a key) but everything here - the key-press state machine, the formatter and the evaluator -
     *  speaks the ASCII hyphen-minus "-". Without this mapping the "−" key fell through to the digit
     *  branch, was appended as an unknown character and silently skipped by the tokenizer, so
     *  "680 − 670" evaluated to 680 instead of 10. Every key goes through here first. */
    fun normalizeKey(key: String): String = if (key == TYPOGRAPHIC_MINUS) "-" else key

    /** The result text for [expression]: "0" for an empty one, "Error" for division by zero / overflow,
     *  "..." when the expression cannot be evaluated yet. */
    fun evaluate(expression: String): String {
        if (expression.isEmpty()) return "0"
        return try {
            var cleanExpr = expression
                .replace(TYPOGRAPHIC_MINUS, "-") // an expression never holds it, but never let one through unseen
                .replace("×", "*")
                .replace("÷", "/")
                .replace(".", "")
                .replace(",", ".")
            val openCount = cleanExpr.count { it == '(' }
            val closeCount = cleanExpr.count { it == ')' }
            if (openCount > closeCount) cleanExpr += ")".repeat(openCount - closeCount)
            formatResult(evalMath(cleanExpr))
        } catch (e: Exception) {
            "..."
        }
    }

    fun getCurrentNumberSegment(expr: String): String {
        if (expr.isEmpty()) return ""
        var lastOp = -1
        for (i in expr.length - 1 downTo 0) {
            if (expr[i] in "+-×÷()") { lastOp = i; break }
        }
        return if (lastOp == -1) expr else expr.substring(lastOp + 1)
    }

    /** Adds "." thousand separators / "," decimal separator to the live expression display, ported from _formatExpressionString */
    fun formatExpressionString(expr: String): String {
        if (expr.isEmpty()) return ""
        val buffer = StringBuilder()
        var currentNum = StringBuilder()
        fun flushNumber() {
            if (currentNum.isNotEmpty()) {
                val clean = currentNum.toString().replace(".", "")
                if (clean.contains(",")) {
                    val parts = clean.split(",", limit = 2)
                    buffer.append(addThousandSeparators(parts[0])).append(",").append(parts.getOrElse(1) { "" })
                } else {
                    buffer.append(addThousandSeparators(clean))
                }
                currentNum = StringBuilder()
            }
        }
        for (c in expr) {
            when {
                c.isDigit() || c == ',' -> currentNum.append(c)
                c == '.' -> continue
                else -> { flushNumber(); buffer.append(c) }
            }
        }
        flushNumber()
        return buffer.toString()
    }

    fun addThousandSeparators(intStr: String): String {
        val sb = StringBuilder()
        var count = 0
        for (i in intStr.length - 1 downTo 0) {
            sb.append(intStr[i])
            count++
            if (count == 3 && i != 0) { sb.append('.'); count = 0 }
        }
        return sb.reverse().toString()
    }

    private fun evalMath(expr0: String): Double {
        var expr = expr0.replace(" ", "")
        if (expr.isEmpty()) return 0.0
        expr = Regex("(\\d)\\(").replace(expr) { "${it.groupValues[1]}*(" }
        expr = Regex("\\)(\\d)").replace(expr) { ")*${it.groupValues[1]}" }
        expr = expr.replace(")(", ")*(")

        var guard = 0
        while (expr.contains("(") && guard < 30) {
            guard++
            val match = Regex("\\(([^()]+)\\)").find(expr) ?: break
            val value = evalSubMath(match.groupValues[1])
            expr = expr.replaceRange(match.range, value.toString())
        }
        return evalSubMath(expr)
    }

    private fun evalSubMath(expr: String): Double {
        if (expr.isEmpty()) return 0.0
        val tokens = mutableListOf<String>()
        var idx = 0
        while (idx < expr.length) {
            val c = expr[idx]
            if (c == '-' && (tokens.isEmpty() || tokens.last() in listOf("+", "-", "*", "/"))) {
                val sb = StringBuilder("-")
                idx++
                while (idx < expr.length && (expr[idx].isDigit() || expr[idx] == '.')) { sb.append(expr[idx]); idx++ }
                if (idx < expr.length && expr[idx] == '%') { sb.append('%'); idx++ }
                tokens.add(sb.toString())
                continue
            }
            if (c.isDigit() || c == '.') {
                val sb = StringBuilder()
                while (idx < expr.length && (expr[idx].isDigit() || expr[idx] == '.')) { sb.append(expr[idx]); idx++ }
                if (idx < expr.length && expr[idx] == '%') { sb.append('%'); idx++ }
                tokens.add(sb.toString())
                continue
            }
            if (c == '%') {
                if (tokens.isNotEmpty() && !tokens.last().endsWith("%")) tokens[tokens.size - 1] = tokens.last() + "%"
                idx++
                continue
            }
            if (c == '+' || c == '-' || c == '*' || c == '/') {
                tokens.add(c.toString())
                idx++
                continue
            }
            idx++
        }
        if (tokens.isEmpty()) return 0.0
        if (tokens.last() in listOf("+", "-", "*", "/")) tokens.removeAt(tokens.size - 1)
        if (tokens.isEmpty()) return 0.0

        val pass1 = mutableListOf<String>()
        var p = 0
        while (p < tokens.size) {
            if (tokens[p] == "*" || tokens[p] == "/") {
                val op = tokens[p]
                val prevVal = parseTokenValue(pass1.removeAt(pass1.size - 1))
                val nextStr = if (p + 1 < tokens.size) tokens[p + 1] else "1"
                val nextVal = parseTokenValue(nextStr)
                val res = if (op == "*") prevVal * nextVal
                    else {
                        if (nextVal == 0.0) return Double.NaN
                        prevVal / nextVal
                    }
                pass1.add(res.toString())
                p += 2
            } else {
                pass1.add(tokens[p])
                p++
            }
        }
        if (pass1.isEmpty()) return 0.0

        var current = parseTokenValue(pass1[0])
        var q = 1
        while (q < pass1.size) {
            val op = pass1[q]
            val nextToken = if (q + 1 < pass1.size) pass1[q + 1] else "0"
            if (nextToken.endsWith("%")) {
                val pctFactor = (nextToken.removeSuffix("%").toDoubleOrNull() ?: 0.0) / 100.0
                val amount = current * pctFactor
                if (op == "+") current += amount
                if (op == "-") current -= amount
            } else {
                val v = nextToken.toDoubleOrNull() ?: 0.0
                if (op == "+") current += v
                if (op == "-") current -= v
            }
            q += 2
        }
        return current
    }

    private fun parseTokenValue(token: String): Double {
        if (token.endsWith("%")) return (token.removeSuffix("%").toDoubleOrNull() ?: 0.0) / 100.0
        return token.toDoubleOrNull() ?: 0.0
    }

    private fun formatResult(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        val isNegative = value < 0
        val absValue = abs(value)

        if (absValue >= 1e15) {
            val expStr = String.format(Locale.US, "%.6e", absValue)
            val parts = expStr.split("e")
            val mantissa = parts[0].trimEnd('0').trimEnd('.')
            val exponent = parts[1].toInt()
            return (if (isNegative) "-" else "") + mantissa + "e" + exponent
        }

        if (absValue % 1.0 == 0.0) {
            val formatted = addThousandSeparators(absValue.toLong().toString())
            return if (isNegative) "-$formatted" else formatted
        }

        var rawFixed = String.format(Locale.US, "%.8f", absValue)
        if (rawFixed.contains(".")) rawFixed = rawFixed.trimEnd('0').trimEnd('.')

        return if (rawFixed.contains(".")) {
            val parts = rawFixed.split(".")
            val formatted = "${addThousandSeparators(parts[0])},${parts[1]}"
            if (isNegative) "-$formatted" else formatted
        } else {
            val formatted = addThousandSeparators(rawFixed)
            if (isNegative) "-$formatted" else formatted
        }
    }
}
