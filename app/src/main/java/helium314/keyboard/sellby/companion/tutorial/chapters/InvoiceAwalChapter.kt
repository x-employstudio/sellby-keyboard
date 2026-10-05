// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChapterId
import helium314.keyboard.sellby.companion.tutorial.FieldId
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.Guide
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.PanelTab
import helium314.keyboard.sellby.companion.tutorial.ProdukView
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialStep
import helium314.keyboard.sellby.companion.tutorial.Typing

/**
 * First half of the Invoice lesson. It stops at the "Input otomatis" search finding nothing (the
 * catalog is empty on purpose) and sends the user to the Produk tab - see [ProdukChapter].
 */
internal val InvoiceAwalChapter = ChapterDef(
    id = ChapterId.InvoiceAwal,
    title = "Invoice",
    steps = listOf(
        TutorialStep(
            id = "inv_1",
            guide = Guide("Rina minta dibuatkan invoice. Ketuk tab Invoice.", Mood.Flat),
            spotlight = listOf(TargetId.TabInvoice),
            gate = Gate.Tap(TargetId.TabInvoice),
            effect = { it.copy(panel = PanelTab.Invoice) },
        ),
        TutorialStep(
            id = "inv_2",
            guide = Guide(
                "Panel Invoice: channel, pelanggan, produk, pengiriman, lalu pembayaran. Total ada di bar bawah.",
                Mood.Flat,
            ),
            spotlight = listOf(TargetId.InvoiceChannel),
        ),
        TutorialStep(
            id = "inv_3",
            guide = Guide("Isi data pelanggan. Aku ketikkan contohnya: nama dan nomor WA Rina.", Mood.Flat),
            spotlight = listOf(TargetId.InvoiceNama, TargetId.InvoiceWa),
            gate = Gate.Auto(),
            typing = listOf(
                Typing(FieldId.InvoiceNama, SampleData.BUYER_NAME),
                Typing(FieldId.InvoiceWa, SampleData.BUYER_WA_DIGITS),
            ),
            effect = { s -> s.withInvoice { it.copy(nama = SampleData.BUYER_NAME, wa = SampleData.BUYER_WA_DIGITS) } },
        ),
        TutorialStep(
            id = "inv_4",
            guide = Guide(
                "Produk diambil dari katalog lewat 'Input otomatis'. Ketuk kotak pencariannya.",
                Mood.Flat,
            ),
            spotlight = listOf(TargetId.InvoiceSearch),
            gate = Gate.Tap(TargetId.InvoiceSearch),
            effect = { s -> s.withInvoice { it.copy(searchOpen = true) } },
        ),
        TutorialStep(
            id = "inv_5",
            guide = Guide("Rina pesan kaos, jadi kita cari 'kaos'.", Mood.Flat),
            spotlight = listOf(TargetId.InvoiceSearch),
            gate = Gate.Auto(1500),
            typing = listOf(Typing(FieldId.InvoiceSearch, "kaos")),
            effect = { s -> s.withInvoice { it.copy(search = "kaos") } },
        ),
        TutorialStep(
            id = "inv_6",
            guide = Guide(
                "Produk tidak ditemukan: katalog masih kosong. Kita buat dulu di tab Produk, datamu aman.",
                Mood.Sad,
            ),
            spotlight = listOf(TargetId.TabProduk),
            gate = Gate.Tap(TargetId.TabProduk),
            effect = { it.copy(panel = PanelTab.Produk, produk = it.produk.copy(view = ProdukView.List)) },
        ),
    ),
)
