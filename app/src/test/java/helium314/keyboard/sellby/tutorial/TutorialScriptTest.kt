// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.tutorial

import helium314.keyboard.sellby.companion.tutorial.ChatTag
import helium314.keyboard.sellby.companion.tutorial.DummyOrderStatus
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.InvoiceView
import helium314.keyboard.sellby.companion.tutorial.PanelTab
import helium314.keyboard.sellby.companion.tutorial.Sender
import helium314.keyboard.sellby.companion.tutorial.StatusAction
import helium314.keyboard.sellby.companion.tutorial.StatusOverlay
import helium314.keyboard.sellby.companion.tutorial.TutorialScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-data checks of the tutorial script: no Compose, no Android. */
class TutorialScriptTest {
    private val steps = TutorialScript.chapters.flatMap { it.steps }

    @Test
    fun stepIdsAreUnique() {
        val ids = steps.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyTapGateIsSpotlighted() {
        steps.forEach { step ->
            val gate = step.gate
            if (gate is Gate.Tap) {
                assertTrue("${step.id}: gate target must be spotlighted", gate.target in step.spotlight)
            }
        }
    }

    @Test
    fun typingStepsNeverHaveATapGate() {
        steps.forEach { assertTrue(it.id, it.typing.isEmpty() || it.gate !is Gate.Tap) }
    }

    @Test
    fun autoStepsAlwaysHaveSomethingToType() {
        steps.forEach { assertTrue(it.id, it.gate !is Gate.Auto || it.typing.isNotEmpty()) }
    }

    /** The guide bubble has a FIXED height (the card above must never jump), so every text stays short. */
    @Test
    fun guideTextsStayShortSoTheBubbleNeverOverflows() {
        steps.forEach { assertTrue("${it.id}: ${it.guide.text.length} chars", it.guide.text.length <= 100) }
    }

    @Test
    fun wholeScriptFoldsWithoutErrors() {
        stateAfter(steps.last().id)
    }

    @Test
    fun theChatStartsEmptyAndTheBuyerAppearsAfterTheToolbarIntro() {
        assertTrue(stateAfter("intro_2").chat.isEmpty())
        assertEquals(1, stateAfter("intro_3").chat.size)
    }

    @Test
    fun sellerBubbleComesFirstThenTheBuyerRepliesAfterADelay() {
        val s = stateAfter("st_4")
        val (seller, reply) = s.chat.takeLast(2)
        assertEquals(Sender.Seller, seller.sender)
        assertEquals(0L, seller.appearDelayMs)
        assertEquals(Sender.Buyer, reply.sender)
        assertTrue(reply.appearDelayMs > 0)
    }

    @Test
    fun theOrderTravelsThroughStatusToSelesai() {
        val reminder = stateAfter("st_3")
        assertEquals(null, reminder.panel)
        assertTrue("mengingatkan" in reminder.chatInput)
        assertEquals(DummyOrderStatus.Pending, reminder.orders.single().status)

        assertEquals(StatusOverlay.Confirm, stateAfter("st_6").status.overlay)

        val lunas = stateAfter("st_7")
        assertEquals(DummyOrderStatus.Lunas, lunas.orders.single().status)
        assertEquals(null, lunas.panel)
        assertTrue("LUNAS" in lunas.chatInput)
        assertTrue(lunas.chat.none { it.sender == Sender.Seller && "LUNAS" in it.text })
        assertTrue(stateAfter("st_8").chat.any { it.sender == Sender.Seller && "LUNAS" in it.text })

        val confirmProses = stateAfter("st_10")
        assertEquals(StatusOverlay.Confirm, confirmProses.status.overlay)
        assertEquals(StatusAction.Proses, confirmProses.status.confirm)
        val proses = stateAfter("st_11")
        assertEquals(DummyOrderStatus.Proses, proses.orders.single().status)
        assertTrue("DIPROSES" in proses.chatInput)
        assertTrue(stateAfter("st_12").chat.any { it.sender == Sender.Seller && "DIPROSES" in it.text })

        assertEquals(StatusAction.Selesai, stateAfter("st_14").status.confirm)
        val selesai = stateAfter("st_15")
        assertEquals(DummyOrderStatus.Selesai, selesai.orders.single().status)
        assertTrue("SELESAI" in selesai.chatInput && "Toko Kamu" in selesai.chatInput)
        assertTrue(stateAfter("st_16").chat.any { it.sender == Sender.Seller && "SELESAI" in it.text })

        assertEquals(StatusOverlay.Detail, stateAfter("st_18").status.overlay)
        assertEquals(StatusOverlay.None, stateAfter("st_19").status.overlay)
    }

    @Test
    fun formOrderLoopsBackIntoTheInvoice() {
        assertTrue(stateAfter("at_3").chatInput.startsWith("Form Order"))
        // The buyer's reply is the copyable form bubble.
        assertTrue(stateAfter("at_4").chat.last().tag == ChatTag.FormOrder)
        assertTrue(stateAfter("at_5").copyMenuOpen)
        // Copying makes Sellby offer the form once the Invoice tab opens.
        val copied = stateAfter("at_6")
        assertEquals(false, copied.copyMenuOpen)
        assertEquals("Dina", copied.formOrderOffer?.nama)
        val pasted = stateAfter("at_8")
        assertEquals(null, pasted.formOrderOffer)
        assertEquals("Dina", pasted.invoice.nama)
        assertEquals("85711112222", pasted.invoice.wa)
        assertEquals("Jl. Kenanga 12, Surabaya", pasted.invoice.alamat)
    }

    @Test
    fun theSuggestionStripReplacesTheShortcutWithTheFullMessage() {
        val typed = stateAfter("at_11")
        assertTrue(typed.suggestionsActive)
        assertEquals("ter", typed.chatInput)
        val picked = stateAfter("at_12")
        assertEquals(false, picked.suggestionsActive)
        assertTrue("Terima Kasih telah berbelanja di Toko Kamu!" in picked.chatInput)
    }
@Test
    fun theInvoiceStartsByHeadingToAnEmptyProductCatalog() {
        val s = stateAfter("inv_6")
        assertEquals(PanelTab.Produk, s.panel)
        assertTrue(s.produk.products.isEmpty())
        // The half-filled invoice must survive the detour.
        assertEquals("Rina", s.invoice.nama)
        assertEquals("081234567890", s.invoice.wa)
    }

    @Test
    fun produkDetourCreatesTwoProductsAndSendsTheDescription() {
        val saved = stateAfter("produk_9")
        assertEquals(listOf("Kaos Polos (L)", "Kaos Polos (M)"), saved.produk.products.map { it.name })

        val sent = stateAfter("produk_11")
        assertTrue(sent.chat.any { it.sender == Sender.Seller && "Deskripsi Produk: Kaos Polos" in it.text })
        assertEquals("", sent.chatInput)
        assertEquals(null, sent.panel)
        // ... and the draft customer data is still there when the seller goes back to the invoice.
        assertEquals("Rina", sent.invoice.nama)
    }

    @Test
    fun expeditionSubScreenOnlyCommitsOnSimpan() {
        val picking = stateAfter("invl_9")
        assertEquals(InvoiceView.Expedition, picking.invoice.view)
        assertEquals(null, picking.invoice.expedition)
        assertEquals("JNE", picking.invoice.pendingExpedition)

        val saved = stateAfter("invl_10")
        assertEquals(InvoiceView.Form, saved.invoice.view)
        assertEquals("JNE", saved.invoice.expedition)
        assertEquals("Reguler", saved.invoice.service)
    }

    @Test
    fun makingTheInvoiceFillsTheChatInputResetsTheFormAndCreatesAPendingOrder() {
        val before = stateAfter("invl_12")
        assertEquals(135_000L, before.invoice.grandTotal)

        val s = stateAfter("invl_13")
        assertTrue("RINCIAN PESANAN" in s.chatInput)
        assertEquals(null, s.panel)
        assertTrue(s.invoice.items.isEmpty())
        assertEquals(1, s.orders.size)
        assertEquals(135_000L, s.orders.single().total)
        assertEquals(DummyOrderStatus.Pending, s.orders.single().status)
        // 10 in stock, 2 sold.
        assertEquals(8, s.produk.products.first { it.name == "Kaos Polos (M)" }.stock)

        // Sending is a separate step: the text must NOT be in the chat before that.
        assertTrue(s.chat.none { it.sender == Sender.Seller && "RINCIAN PESANAN" in it.text })
        val sent = stateAfter("invl_14")
        assertTrue(sent.chat.any { it.sender == Sender.Seller && "RINCIAN PESANAN" in it.text })
        assertEquals("", sent.chatInput)
    }
}
