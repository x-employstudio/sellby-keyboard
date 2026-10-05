// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChapterId
import helium314.keyboard.sellby.companion.tutorial.DummyProduct
import helium314.keyboard.sellby.companion.tutorial.FieldId
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.Guide
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.ProdukForm
import helium314.keyboard.sellby.companion.tutorial.ProdukView
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialStep
import helium314.keyboard.sellby.companion.tutorial.Typing
import helium314.keyboard.sellby.util.CurrencyFormat

/** The text the real "Kirim Deskripsi" button drops into the chat (ProdukPanelView.sendProductDescriptionToChat). */
private fun descriptionMessage(p: DummyProduct): String = buildString {
    appendLine("*Deskripsi Produk: ${p.name}*")
    appendLine(p.description.trim().ifEmpty { "Belum ada deskripsi untuk produk ini." })
    appendLine("")
    if (p.discount > 0) {
        appendLine("Harga Normal: ~${CurrencyFormat.rupiah(p.price.toDouble())}~")
        appendLine("Harga Promo (${p.discountPercent}%): *${CurrencyFormat.rupiah(p.finalPrice.toDouble())}*")
    } else {
        appendLine("Harga: *${CurrencyFormat.rupiah(p.price.toDouble())}*")
    }
}.trimEnd()

/**
 * The detour: the invoice found no product, so the seller adds one in the Produk tab. It starts with
 * the (empty) Produk list already open - [InvoiceAwalChapter]'s last step opened it - and ends back in
 * the chat; [InvoiceLanjutChapter] then returns to the Invoice tab.
 */
internal val ProdukChapter = ChapterDef(
    id = ChapterId.Produk,
    title = "Produk",
    steps = listOf(
        TutorialStep(
            id = "produk_1",
            guide = Guide("Tab Produk menyimpan semua barang jualanmu. Masih kosong, ketuk '+ Tambah Produk'.", Mood.Flat),
            spotlight = listOf(TargetId.ProdukAdd),
            gate = Gate.Tap(TargetId.ProdukAdd),
            effect = { it.copy(produk = it.produk.copy(view = ProdukView.Form, form = ProdukForm())) },
        ),
        TutorialStep(
            id = "produk_2",
            guide = Guide("Nama dan deskripsi produk kuketikkan lewat keyboard.", Mood.Flat),
            spotlight = listOf(TargetId.ProdukNama, TargetId.ProdukDeskripsi),
            gate = Gate.Auto(),
            typing = listOf(
                Typing(FieldId.ProdukNama, SampleData.KAOS_NAMA),
                Typing(FieldId.ProdukDeskripsi, SampleData.KAOS_DESKRIPSI, charsPerSecond = 28),
            ),
            effect = { s -> s.withForm { it.copy(nama = SampleData.KAOS_NAMA, deskripsi = SampleData.KAOS_DESKRIPSI) } },
        ),
        TutorialStep(
            id = "produk_3",
            guide = Guide("Sekarang harga normalnya.", Mood.Flat),
            spotlight = listOf(TargetId.ProdukHarga),
            gate = Gate.Auto(),
            typing = listOf(Typing(FieldId.ProdukHarga, SampleData.KAOS_HARGA)),
            effect = { s -> s.withForm { it.copy(harga = SampleData.KAOS_HARGA) } },
        ),
        TutorialStep(
            id = "produk_4",
            guide = Guide(
                "Diskon diisi dalam Rupiah, bukan persen. Sellby hitung persennya: 15.000 dari 75.000 = 20%.",
                Mood.Happy,
            ),
            spotlight = listOf(TargetId.ProdukDiscount),
            typing = listOf(Typing(FieldId.ProdukDiscount, SampleData.KAOS_DISCOUNT)),
            effect = { s -> s.withForm { it.copy(discount = SampleData.KAOS_DISCOUNT) } },
        ),
        TutorialStep(
            id = "produk_5",
            guide = Guide(
                "Beda ukuran atau warna? Pilih 'Dengan Variasi'. Tiap variasi jadi produk terpisah.",
                Mood.Flat,
            ),
            spotlight = listOf(TargetId.ProdukVariasiToggle),
            gate = Gate.Tap(TargetId.ProdukVariasiToggle),
            // One empty variation row opens up; the next steps fill it in.
            effect = { s -> s.withForm { it.copy(variasi = true, variasiRows = listOf("" to "")) } },
        ),
        TutorialStep(
            id = "produk_6",
            guide = Guide("Isi variasi pertama: ukuran M dengan stok 10.", Mood.Flat),
            spotlight = listOf(TargetId.VariasiRow0),
            gate = Gate.Auto(),
            typing = listOf(Typing(FieldId.VariasiNama0, "M"), Typing(FieldId.VariasiStok0, "10")),
            effect = { s -> s.withForm { it.copy(variasiRows = listOf("M" to "10")) } },
        ),
        TutorialStep(
            id = "produk_7",
            guide = Guide("Ada ukuran lain? Ketuk '+ Tambah Variasi'.", Mood.Flat),
            spotlight = listOf(TargetId.VariasiAdd),
            gate = Gate.Tap(TargetId.VariasiAdd),
            effect = { s -> s.withForm { it.copy(variasiRows = it.variasiRows + ("" to "")) } },
        ),
        TutorialStep(
            id = "produk_8",
            guide = Guide("Variasi kedua: ukuran L dengan stok 14.", Mood.Flat),
            spotlight = listOf(TargetId.VariasiRow1),
            gate = Gate.Auto(),
            typing = listOf(Typing(FieldId.VariasiNama1, "L"), Typing(FieldId.VariasiStok1, "14")),
            effect = { s -> s.withForm { it.copy(variasiRows = listOf("M" to "10", "L" to "14")) } },
        ),
        TutorialStep(
            id = "produk_9",
            guide = Guide("Semua variasi sudah terisi. Ketuk Simpan!", Mood.Happy),
            spotlight = listOf(TargetId.ProdukSimpan),
            gate = Gate.Tap(TargetId.ProdukSimpan),
            effect = {
                it.copy(
                    produk = it.produk.copy(
                        view = ProdukView.List,
                        products = (it.produk.products + SampleData.kaosProducts()).sortedBy { p -> p.name },
                        form = ProdukForm(),
                    ),
                )
            },
        ),
        TutorialStep(
            id = "produk_10",
            guide = Guide(
                "Rina tanya bahan kaosnya. Ketuk 'Kirim Deskripsi' untuk mengirim deskripsi dan harga.",
                Mood.Flat,
            ),
            spotlight = listOf(TargetId.ProdukKirimDeskripsi),
            gate = Gate.Tap(TargetId.ProdukKirimDeskripsi),
            entry = { it.buyerSays("Kak, kaosnya bahannya apa ya?", delayMs = 500) },
            effect = {
                val product = it.produk.products.first { p -> p.name.startsWith(SampleData.KAOS_NAMA) }
                it.copy(panel = null, chatInput = descriptionMessage(product))
            },
        ),
        TutorialStep(
            id = "produk_11",
            guide = Guide(
                "Teks sudah di kolom chat tapi belum terkirim. Kamu yang menekan Kirim.",
                Mood.Flat,
            ),
            spotlight = listOf(TargetId.ChatSend),
            gate = Gate.Tap(TargetId.ChatSend),
            effect = { it.sendChatInput(buyerReply = "Oke bagus! Jadi pesan yang M 2 pcs ya Kak 😍") },
        ),
    ),
)
