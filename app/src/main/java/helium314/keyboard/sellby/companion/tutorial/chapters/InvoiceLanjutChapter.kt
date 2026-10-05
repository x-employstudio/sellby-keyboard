// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChapterId
import helium314.keyboard.sellby.companion.tutorial.DummyOrder
import helium314.keyboard.sellby.companion.tutorial.FieldId
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.Guide
import helium314.keyboard.sellby.companion.tutorial.InvoiceItem
import helium314.keyboard.sellby.companion.tutorial.InvoiceState
import helium314.keyboard.sellby.companion.tutorial.InvoiceView
import helium314.keyboard.sellby.companion.tutorial.InvoiceTextBuilder
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.PanelTab
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import helium314.keyboard.sellby.companion.tutorial.TutorialStep
import helium314.keyboard.sellby.companion.tutorial.Typing

/** "Buat Invoice": the text lands in the chat input (NOT sent), the form resets, stock drops, an order appears. */
private fun createInvoice(s: TutorialState): TutorialState {
    val inv = s.invoice
    val soldByName = inv.items.associate { it.name to it.qty }
    return s.copy(
        panel = null,
        chatInput = InvoiceTextBuilder.build(s),
        invoice = InvoiceState(),
        produk = s.produk.copy(
            products = s.produk.products.map { p ->
                soldByName[p.name]?.let { qty -> p.copy(stock = (p.stock - qty).coerceAtLeast(0)) } ?: p
            },
        ),
        orders = s.orders + DummyOrder(
            customerName = inv.nama,
            contact = "+62" + inv.wa.removePrefix("0"),
            items = inv.items.map { it.name to it.qty },
            expedition = listOfNotNull(inv.expedition, inv.service).joinToString(" - ").ifEmpty { "-" },
            address = inv.alamat.ifEmpty { "-" },
            payment = inv.payment ?: "-",
            total = inv.grandTotal,
        ),
    )
}

/** Second half of the Invoice lesson, after the Produk detour: the draft is still there. */
internal val InvoiceLanjutChapter = ChapterDef(
    id = ChapterId.InvoiceLanjut,
    title = "Invoice (lanjut)",
    steps = listOf(
        TutorialStep(
            id = "invl_1",
            guide = Guide("Produk sudah ada. Kembali ke Invoice: data Rina masih tersimpan.", Mood.Happy),
            spotlight = listOf(TargetId.TabInvoice),
            gate = Gate.Tap(TargetId.TabInvoice),
            effect = { s ->
                s.copy(panel = PanelTab.Invoice).withInvoice { it.copy(search = "", searchOpen = true, searchPcs = 1) }
            },
        ),
        TutorialStep(
            id = "invl_2",
            guide = Guide("Cari 'kaos' lagi. Sekarang produknya ketemu!", Mood.Flat),
            spotlight = listOf(TargetId.InvoiceSearch),
            gate = Gate.Auto(1200),
            typing = listOf(Typing(FieldId.InvoiceSearch, "kaos")),
            effect = { s -> s.withInvoice { it.copy(search = "kaos") } },
        ),
        TutorialStep(
            id = "invl_3",
            guide = Guide("Rina pesan 2 pcs. Ketuk + untuk menambah jumlah.", Mood.Flat),
            spotlight = listOf(TargetId.SearchStepperPlus),
            gate = Gate.Tap(TargetId.SearchStepperPlus),
            effect = { s -> s.withInvoice { it.copy(searchPcs = 2) } },
        ),
        TutorialStep(
            id = "invl_4",
            guide = Guide("Pilih ukuran M: ketuk '+ Input' di Kaos Polos (M).", Mood.Flat),
            spotlight = listOf(TargetId.SearchAddM),
            gate = Gate.Tap(TargetId.SearchAddM),
            effect = { s ->
                val product = s.produk.products.first { it.name == SampleData.KAOS_M }
                s.withInvoice {
                    it.copy(
                        items = it.items + InvoiceItem(product.name, it.searchPcs, product.finalPrice, product.price),
                        search = "",
                        searchPcs = 1,
                    )
                }
            },
        ),
        TutorialStep(
            id = "invl_5",
            guide = Guide(
                "Produk masuk daftar kuning dengan harga promo. Barang di luar katalog? Pakai 'Input Manual'.",
                Mood.Flat,
            ),
            spotlight = listOf(TargetId.InvoiceItems),
        ),
        TutorialStep(
            id = "invl_6",
            guide = Guide("Alamat pengiriman Rina juga kuketikkan sebagai contoh.", Mood.Flat),
            spotlight = listOf(TargetId.InvoiceAlamat),
            gate = Gate.Auto(),
            typing = listOf(Typing(FieldId.InvoiceAlamat, SampleData.BUYER_ADDRESS, charsPerSecond = 22)),
            effect = { s -> s.withInvoice { it.copy(alamat = SampleData.BUYER_ADDRESS) } },
        ),
        TutorialStep(
            id = "invl_7",
            guide = Guide("Sekarang ekspedisi dan layanannya. Ketuk barisnya.", Mood.Flat),
            spotlight = listOf(TargetId.ExpeditionRow),
            gate = Gate.Tap(TargetId.ExpeditionRow),
            effect = { s ->
                s.withInvoice {
                    it.copy(view = InvoiceView.Expedition, pendingExpedition = it.expedition, pendingService = it.service)
                }
            },
        ),
        TutorialStep(
            id = "invl_8",
            guide = Guide("Ekspedisi di kiri, layanan di kanan. Pilih JNE.", Mood.Flat),
            spotlight = listOf(TargetId.ExpJne),
            gate = Gate.Tap(TargetId.ExpJne),
            effect = { s -> s.withInvoice { it.copy(pendingExpedition = SampleData.EXPEDITION) } },
        ),
        TutorialStep(
            id = "invl_9",
            guide = Guide("Lalu layanan Reguler.", Mood.Flat),
            spotlight = listOf(TargetId.SvcReguler),
            gate = Gate.Tap(TargetId.SvcReguler),
            effect = { s -> s.withInvoice { it.copy(pendingService = SampleData.SERVICE) } },
        ),
        TutorialStep(
            id = "invl_10",
            guide = Guide("Ketuk Simpan. 'Batalkan' menutup layar ini tanpa mengubah pilihan.", Mood.Flat),
            spotlight = listOf(TargetId.ExpSimpan),
            gate = Gate.Tap(TargetId.ExpSimpan),
            effect = { s ->
                s.withInvoice {
                    it.copy(view = InvoiceView.Form, expedition = it.pendingExpedition, service = it.pendingService)
                }
            },
        ),
        TutorialStep(
            id = "invl_11",
            guide = Guide("Ongkirnya Rp 15.000. Lihat bar bawah: total pembayaran ikut berubah.", Mood.Flat),
            spotlight = listOf(TargetId.InvoiceOngkir),
            gate = Gate.Auto(1400),
            typing = listOf(Typing(FieldId.InvoiceOngkir, SampleData.ONGKIR)),
            effect = { s -> s.withInvoice { it.copy(ongkir = SampleData.ONGKIR) } },
        ),
        TutorialStep(
            id = "invl_12",
            guide = Guide("Terakhir, metode pembayaran. Pilih BCA (boleh lebih dari satu).", Mood.Flat),
            spotlight = listOf(TargetId.PayBca),
            gate = Gate.Tap(TargetId.PayBca),
            effect = { s -> s.withInvoice { it.copy(payment = SampleData.BANK) } },
        ),
        TutorialStep(
            id = "invl_13",
            guide = Guide("Semua siap. Ketuk 'Buat Invoice'!", Mood.Happy),
            spotlight = listOf(TargetId.BuatInvoice),
            gate = Gate.Tap(TargetId.BuatInvoice),
            effect = ::createInvoice,
        ),
        TutorialStep(
            id = "invl_14",
            guide = Guide(
                "Invoice masuk kolom chat tapi belum terkirim. Periksa, lalu tekan Kirim. Stok ikut berkurang.",
                Mood.Happy,
            ),
            spotlight = listOf(TargetId.ChatSend),
            gate = Gate.Tap(TargetId.ChatSend),
            effect = { it.sendChatInput(buyerReply = "Makasih Kak! Nanti aku transfer ya 🙏") },
        ),
    ),
)
