// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.util

/**
 * Rupiah formatting ported from invoice_panel.dart's `_formatCurrency`/`ThousandsSeparatorInputFormatter`
 * - acuan tunggal. Used by every panel that shows money (Invoice first, Produk/Status later).
 */
object CurrencyFormat {

    /** "Rp 1.000.000" - grouped with dots, no decimals, with the "Rp " prefix. */
    fun rupiah(value: Double): String {
        if (value <= 0.0) return "Rp 0"
        return "Rp " + groupThousands(value.toLong().toString())
    }

    /** "Rp1.000.000" - same as [rupiah] but no space after "Rp", for the redesigned default
     *  invoice message (invoice_panel.dart's own template always uses "Rp " with a space; this
     *  compact form is a deliberate Sellby-specific styling choice, not a Flutter port). */
    fun rupiahCompact(value: Double): String {
        if (value <= 0.0) return "Rp0"
        return "Rp" + groupThousands(value.toLong().toString())
    }

    /** Live-typing formatter for a price/discount/shipping-cost field: digits only, dot-grouped,
     *  no "Rp " prefix (that's only added when the value is shown inside generated invoice text). */
    fun liveDigitsToGrouped(rawInput: String): String {
        val digits = rawInput.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        val stripped = digits.trimStart('0').ifEmpty { "0" }
        return groupThousands(stripped)
    }

    fun parseDigits(text: String): Double = text.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0

    private fun groupThousands(digits: String): String {
        val builder = StringBuilder()
        var count = 0
        for (i in digits.length - 1 downTo 0) {
            builder.append(digits[i])
            count++
            if (count == 3 && i != 0) {
                builder.append('.')
                count = 0
            }
        }
        return builder.reverse().toString()
    }
}
