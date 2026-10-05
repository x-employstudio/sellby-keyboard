// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial

import helium314.keyboard.sellby.util.CurrencyFormat

/**
 * The invoice text the dummy "Buat Invoice" button drops into the chat. It copies the DEFAULT template
 * of InvoicePanelView.generateAndSendInvoice() (WhatsApp channel: *bold* and ~strike~), including the
 * emoji sections, the 37-dash dividers and the colon-aligned shipping lines. InvoiceTextBuilderTest
 * pins the key lines so a change of the real template doesn't go unnoticed.
 */
internal object InvoiceTextBuilder {
    private const val DIVIDER = "-------------------------------------"

    fun build(state: TutorialState): String {
        val inv = state.invoice
        val contact = "+62" + inv.wa.filter { it.isDigit() }.removePrefix("62").removePrefix("0")
        val address = inv.alamat.trim().ifEmpty { "-" }
        val expedition = when {
            inv.expedition != null && inv.service != null -> "${inv.expedition} - ${inv.service}"
            inv.expedition != null -> inv.expedition
            inv.service != null -> inv.service
            else -> "-"
        }
        val payments = if (inv.payment == null) "-" else "• ${inv.payment} a/n ${state.storeName} ${SampleData.BANK_ACCOUNT}"

        val rincian = inv.items.mapIndexed { i, item ->
            val subtotal = money(item.subtotal)
            if (item.hasDiscount) {
                val original = money(item.originalUnitPrice * item.qty)
                "${i + 1}. ${bold(item.name)} × ${item.qty} = ${bold(subtotal)} (Normal: ${strike(original)}, Disc ${item.discountPercent}%)"
            } else {
                "${i + 1}. ${bold(item.name)} × ${item.qty} = $subtotal"
            }
        }.joinToString("\n")

        return buildString {
            appendLine("Terima kasih sudah berbelanja di ${bold(state.storeName)} yaa! 👋")
            appendLine()
            appendLine("Halo Kak ${bold(inv.nama.trim())}, berikut rincian pesanannya")
            appendLine("📅 ${SampleData.INVOICE_DATE}")
            appendLine()
            appendLine(DIVIDER)
            appendLine("🧾 ${bold("RINCIAN PESANAN")}")
            appendLine()
            appendLine(rincian)
            appendLine()
            appendLine("Total Harga Barang")
            appendLine(bold(money(inv.itemsTotal)))
            appendLine(DIVIDER)
            appendLine()
            appendLine("🚚 ${bold("PENGIRIMAN")}")
            appendLine()
            appendLine("${"Alamat".padEnd(14)}: $address")
            appendLine("${"No. Telepon".padEnd(14)}: $contact")
            appendLine("${"Ekspedisi".padEnd(14)}: $expedition")
            appendLine("${"Ongkos Kirim".padEnd(14)}: ${money(inv.shipping)}")
            appendLine()
            appendLine("${"Catatan".padEnd(14)}: -")
            appendLine()
            appendLine(DIVIDER)
            appendLine("💰 ${bold("TOTAL PEMBAYARAN")}")
            appendLine(bold(money(inv.grandTotal)))
            appendLine()
            appendLine("💳 ${bold("Metode Pembayaran")}")
            appendLine(payments)
            appendLine()
            appendLine("Silakan lakukan pembayaran dan")
            appendLine("konfirmasi dengan mengirimkan")
            append("bukti pembayaran yaa. 🙏")
        }
    }

    private fun money(value: Long) = CurrencyFormat.rupiahCompact(value.toDouble())
    private fun bold(text: String) = "*$text*"
    private fun strike(text: String) = "~$text~"
}
