// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.tutorial

import helium314.keyboard.sellby.companion.tutorial.InvoiceTextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the key lines of the dummy invoice text to the real default template in
 * InvoicePanelView.generateAndSendInvoice(), so a change there doesn't silently drift from the tutorial.
 */
class InvoiceTextBuilderTest {
    private val text = InvoiceTextBuilder.build(stateAfter("invl_12", storeName = "Toko Kamu"))

    @Test
    fun greetingAndHeader() {
        assertTrue(text.startsWith("Terima kasih sudah berbelanja di *Toko Kamu* yaa! 👋\n\nHalo Kak *Rina*, berikut rincian pesanannya\n📅 "))
    }

    @Test
    fun itemLineShowsPromoPriceAndTheStruckNormalPrice() {
        assertTrue(text, "1. *Kaos Polos (M)* × 2 = *Rp120.000* (Normal: ~Rp150.000~, Disc 20%)" in text)
        assertTrue("Total Harga Barang\n*Rp120.000*" in text)
    }

    @Test
    fun shippingBlockIsColonAligned() {
        assertTrue("Alamat        : Jl. Melati No. 5, Bandung" in text)
        assertTrue("No. Telepon   : +6281234567890" in text)
        assertTrue("Ekspedisi     : JNE - Reguler" in text)
        assertTrue("Ongkos Kirim  : Rp15.000" in text)
        assertTrue("Catatan       : -" in text)
    }

    @Test
    fun totalPaymentAndFooter() {
        assertTrue("💰 *TOTAL PEMBAYARAN*\n*Rp135.000*" in text)
        assertTrue("💳 *Metode Pembayaran*\n• BCA a/n Toko Kamu 1234567890" in text)
        assertTrue(text.endsWith("Silakan lakukan pembayaran dan\nkonfirmasi dengan mengirimkan\nbukti pembayaran yaa. 🙏"))
    }

    @Test
    fun threeDividersLikeTheRealTemplate() {
        assertEquals(3, text.lines().count { it == "-------------------------------------" })
    }

    @Test
    fun usesTheStoreNameGivenByTheHost() {
        val other = InvoiceTextBuilder.build(stateAfter("invl_12", storeName = "Warung Bu Sari"))
        assertTrue("*Warung Bu Sari*" in other)
        assertTrue("• BCA a/n Warung Bu Sari 1234567890" in other)
    }
}
