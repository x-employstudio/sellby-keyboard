// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.util

/** The customer details found in a filled-in order form. Each field is null when the buyer left it
 *  blank (or, for [phone], wrote something that isn't a plausible phone number). [phone] is digits
 *  only, exactly as written (no 0/62 prefix handling - that depends on the invoice's channel). */
data class ParsedOrderForm(val name: String?, val phone: String?, val address: String?)

/**
 * Pulls Nama / No Hp / Alamat out of a buyer-filled "Format Order" message (the default Auto-Text
 * template: "Form Order / Nama : / No Hp : / Alamat : / Order :"), copied as plain text.
 *
 * Line-based: a line shaped like "<short label> : <value>" starts a field, and lines without such a
 * label continue the Alamat value (addresses are often written over several lines) until the next
 * labelled line. Any other label ("Order :", "Catatan :", ...) just ends the current field.
 * Deliberately tolerant of label spelling ("No Hp", "No. HP", "Nomor WhatsApp", ...) and spacing
 * around the colon, since buyers retype the template by hand.
 */
object OrderFormParser {
    private val LABEL_LINE = Regex("""^\s*([^:：\n]{1,30}?)\s*[:：]\s*(.*)$""")
    private val WHITESPACE = Regex("\\s+")

    private val NAME_LABELS = setOf("nama", "nama lengkap", "nama penerima", "nama pembeli")
    private val PHONE_LABELS = setOf(
        "no hp", "nohp", "no hp/wa", "no hp wa", "no wa", "no whatsapp", "no handphone", "no telp",
        "no telepon", "nomor hp", "nomor wa", "nomor whatsapp", "nomor telepon", "nomor telp",
        "hp", "wa", "whatsapp", "telp", "telepon",
    )
    private val ADDRESS_LABELS = setOf("alamat", "alamat lengkap", "alamat pengiriman", "alamat penerima")

    private enum class Field { NAME, PHONE, ADDRESS, OTHER }

    private fun classify(rawLabel: String): Field {
        val label = rawLabel.lowercase().replace(".", "").replace("*", "").replace("_", "")
            .replace(WHITESPACE, " ").trim()
        return when (label) {
            in NAME_LABELS -> Field.NAME
            in PHONE_LABELS -> Field.PHONE
            in ADDRESS_LABELS -> Field.ADDRESS
            else -> Field.OTHER
        }
    }

    /** Null unless [text] really looks like a filled order form: at least 2 of the 3 labels are
     *  present AND at least one of them has a value. That keeps ordinary copied text (and the empty
     *  unfilled template) from ever triggering the paste offer. */
    fun parse(text: String): ParsedOrderForm? {
        val values = LinkedHashMap<Field, StringBuilder>()
        var current = Field.OTHER
        for (line in text.lines()) {
            val match = LABEL_LINE.matchEntire(line)
            if (match != null) {
                val field = classify(match.groupValues[1])
                if (field == Field.OTHER || field in values) {
                    // Unknown label, or a repeated one (first occurrence wins) - ends the current field.
                    current = Field.OTHER
                } else {
                    current = field
                    values[field] = StringBuilder(match.groupValues[2].trim())
                }
            } else if (current == Field.ADDRESS && line.isNotBlank()) {
                val address = values.getValue(Field.ADDRESS)
                if (address.isNotEmpty()) address.append(", ")
                address.append(line.trim())
            }
        }
        if (values.size < 2) return null

        val name = values[Field.NAME]?.toString()?.takeIf { it.isNotBlank() }
        val phoneDigits = values[Field.PHONE]?.toString()?.filter { it.isDigit() }
        val phone = phoneDigits?.takeIf { it.length in 8..15 }
        val address = values[Field.ADDRESS]?.toString()?.takeIf { it.isNotBlank() }
        if (name == null && phone == null && address == null) return null
        return ParsedOrderForm(name, phone, address)
    }
}
