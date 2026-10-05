// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChapterId
import helium314.keyboard.sellby.companion.tutorial.ChatTag
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.Guide
import helium314.keyboard.sellby.companion.tutorial.InvoiceState
import helium314.keyboard.sellby.companion.tutorial.LessonId
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.PanelTab
import helium314.keyboard.sellby.companion.tutorial.ProdukPanelState
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import helium314.keyboard.sellby.companion.tutorial.TutorialStep

/*
 * The four stand-alone lessons (Settings -> Tutorial -> Cara penggunaan). They are NOT new scripts:
 * each one re-assembles the existing steps of the full story (the onboarding tutorial is untouched)
 * behind a short opening step that sets the scene, and ends with a one-line closing step. What a
 * lesson needs to already exist (a product catalog, a pending order) comes from its own seed, so the
 * lessons never depend on each other - which is also why the Invoice lesson has no detour to Produk.
 */

/** A copy of [this] step that says [text] instead; the behaviour of the step is unchanged. */
private fun TutorialStep.withGuide(text: String): TutorialStep = copy(guide = guide.copy(text = text))

/** The steps from [fromId] to [toId], both included. */
private fun ChapterDef.range(fromId: String, toId: String): List<TutorialStep> {
    val from = steps.indexOfFirst { it.id == fromId }
    val to = steps.indexOfFirst { it.id == toId }
    require(from >= 0 && to >= from) { "no step range $fromId..$toId in chapter $title" }
    return steps.subList(from, to + 1)
}

private fun opening(id: String, title: String, text: String, seed: TutorialState, entry: (TutorialState) -> TutorialState = { it }) =
    ChapterDef(
        id = ChapterId.Intro,
        title = title,
        seed = seed,
        steps = listOf(TutorialStep(id = id, guide = Guide(text, Mood.Happy), entry = entry)),
    )

private fun closing(id: String, text: String) = ChapterDef(
    id = ChapterId.Recap,
    title = "Selesai",
    steps = listOf(TutorialStep(id = id, guide = Guide(text, Mood.Happy))),
)

private fun body(title: String, steps: List<TutorialStep>) = ChapterDef(id = ChapterId.Produk, title = title, steps = steps)

private val catalogWithKaos get() = ProdukPanelState(products = SampleData.kaosProducts().sortedBy { it.name })

/**
 * Copying the buyer's filled-in Form Order and pasting it into the Invoice tab ("Form Order Terdeteksi" ->
 * Tempel): the customer's name, number and address fill themselves. This used to be the middle of the Auto
 * Text lesson; it belongs here, because it is the Invoice tab that does the pasting. Rina's form is the one
 * already in the chat when the lesson opens.
 */
private val InvoiceFormPasteSteps = listOf(
    TutorialStep(
        id = "linv_copy_1",
        guide = Guide("Rina sudah mengisi Form Order. Tahan pesannya untuk menyalin.", Mood.Flat),
        spotlight = listOf(TargetId.FormBubble),
        gate = Gate.Tap(TargetId.FormBubble),
        effect = { it.copy(copyMenuOpen = true) },
    ),
    TutorialStep(
        id = "linv_copy_2",
        guide = Guide("Pilih 'Salin'.", Mood.Flat),
        spotlight = listOf(TargetId.CopyButton),
        gate = Gate.Tap(TargetId.CopyButton),
        effect = { it.copy(copyMenuOpen = false, formOrderOffer = SampleData.rinaFormOffer) },
    ),
    TutorialStep(
        id = "linv_copy_3",
        guide = Guide("Teks tersalin. Sellby mendeteksi form itu saat kamu membuka tab Invoice.", Mood.Flat),
        spotlight = listOf(TargetId.TabInvoice),
        gate = Gate.Tap(TargetId.TabInvoice),
        entry = { it.copy(toast = "Teks disalin") },
        effect = { it.copy(toast = null, panel = PanelTab.Invoice, invoice = InvoiceState()) },
    ),
    TutorialStep(
        id = "linv_copy_4",
        guide = Guide("Ketuk 'Tempel' untuk mengisi data pelanggan dari form tadi.", Mood.Happy),
        spotlight = listOf(TargetId.OfferTempel),
        gate = Gate.Tap(TargetId.OfferTempel),
        effect = { s ->
            val offer = SampleData.rinaFormOffer
            s.copy(formOrderOffer = null)
                .withInvoice { it.copy(nama = offer.nama, wa = offer.wa.filter { c -> c.isDigit() }.removePrefix("0"), alamat = offer.alamat) }
        },
    ),
    TutorialStep(
        id = "linv_copy_5",
        guide = Guide("Nama dan nomor Rina terisi sendiri. Tanpa form? Ketik manual di kolom ini.", Mood.Happy),
        spotlight = listOf(TargetId.InvoiceNama, TargetId.InvoiceWa),
    ),
    TutorialStep(
        id = "linv_copy_6",
        guide = Guide("Alamat pengirimannya juga. Sekarang tinggal pilih produknya!", Mood.Happy),
        spotlight = listOf(TargetId.InvoiceAlamat),
    ),
)

/**
 * Invoice, start to finish, with the catalog already filled (so "Input otomatis" finds the product at once)
 * and Rina's filled-in Form Order already in the chat: copy it, paste it into the Invoice tab (the customer
 * fields and the address then fill themselves, so the lesson no longer types them), find the product, the
 * shipping, the payment, "Buat Invoice".
 */
private val InvoiceLesson = listOf(
    opening(
        id = "linv_1",
        title = "Invoice",
        text = "Rina sudah mengisi Form Order untuk pesan kaosnya. Yuk jadikan invoice, ikuti langkahku! 👋",
        seed = TutorialState(produk = catalogWithKaos),
        entry = { it.buyerSays(SampleData.RINA_FILLED_FORM, delayMs = 400, tag = ChatTag.FormOrder) },
    ),
    body(
        "Invoice",
        InvoiceFormPasteSteps +
            InvoiceAwalChapter.range("inv_2", "inv_2") + // the panel's layout, now that the customer is filled in
            InvoiceAwalChapter.range("inv_4", "inv_5") +
            InvoiceLanjutChapter.range("invl_3", "invl_5") +
            InvoiceLanjutChapter.range("invl_7", "invl_14"), // invl_6 typed the address: the paste already did
    ),
    closing("linv_end", "Invoice selesai! Pesanan Rina otomatis masuk tab Status. Produk belum ada? Tambah di tab Produk."),
)

/** Status: one pending order (Rina's), followed through Ingatkan -> Lunas -> Proses -> Selesai -> Detail. */
private val StatusLesson = listOf(
    opening(
        id = "lst_1",
        title = "Status",
        text = "Invoice Rina sudah terkirim tapi belum dibayar. Yuk kelola pesanannya!",
        seed = TutorialState(orders = listOf(SampleData.rinaPendingOrder())),
        entry = { it.buyerSays("Kak, invoicenya sudah kuterima ya 🙏", delayMs = 400) },
    ),
    body("Status", StatusChapter.steps),
    closing("lst_end", "Pesanan selesai bisa diarsipkan lewat tombol Arsipkan. Tombol Hapus (merah) mengembalikan stok."),
)

/** Produk: from the empty catalog to a saved product and its description sent to a buyer. */
private val ProdukLesson = listOf(
    opening(
        id = "lprod_1",
        title = "Produk",
        text = "Kita belajar menyimpan produk dan mengirim deskripsinya ke pembeli. Semua cuma contoh!",
        seed = TutorialState(),
    ),
    body(
        "Produk",
        listOf(
            TutorialStep(
                id = "lprod_tab",
                guide = Guide("Ketuk tab Produk untuk mulai menambah barang jualanmu.", Mood.Flat),
                spotlight = listOf(TargetId.TabProduk),
                gate = Gate.Tap(TargetId.TabProduk),
                effect = { it.copy(panel = PanelTab.Produk) },
            ),
        ) + ProdukChapter.steps,
    ),
    closing("lprod_end", "Produk tersimpan! Tab Invoice bisa mengambilnya lewat 'Input otomatis', stok ikut berkurang."),
)

/**
 * The Auto Text lesson's steps: the list is introduced first, then making a NEW Auto-Text and editing an
 * existing one ([AutoTextManageSteps]), then the shortcuts in use (Format Order, the suggestion strip). A few
 * of the story's texts are reworded to fit that order. Copying the buyer's filled form and pasting it into
 * the Invoice tab (at_5..at_10 of the full story) is NOT part of this lesson: it lives in the Invoice lesson
 * ([InvoiceFormPasteSteps]), so here the lesson only points to it.
 */
private fun autoTextLessonSteps(): List<TutorialStep> {
    val rewording = mapOf(
        "at_1" to "Buka tab Auto-Text untuk melihat jalan pintasmu.",
        "at_2" to "Ini daftar Auto-Text. Ketuk satu baris untuk langsung mengirim pesannya.",
        "at_3" to "Sekarang coba yang bawaan: 'Format Order' membuat pembeli mengisi datanya sendiri.",
    )
    val formFilledNote = TutorialStep(
        id = "lat_form_note",
        guide = Guide("Pembeli mengisi form-nya sendiri. Cara menempelnya ke invoice ada di tutorial Invoice.", Mood.Happy),
        spotlight = listOf(TargetId.FormBubble),
    )
    val steps = AutoTextChapter.range("at_1", "at_2") +
        AutoTextManageSteps +
        AutoTextChapter.range("at_3", "at_4") +
        formFilledNote +
        AutoTextChapter.range("at_11", "at_13")
    return steps.map { step -> rewording[step.id]?.let { step.withGuide(it) } ?: step }
}

/** Auto-Text: the shortcut rows, making and editing one, Format Order sent to a buyer, and the suggestion strip. */
private val AutoTextLesson = listOf(
    opening(
        id = "lat_1",
        title = "Auto Text",
        text = "Pembeli baru menyapa. Balas cepat pakai Auto-Text, jalan pintas pesan yang sering dikirim.",
        seed = TutorialState(),
        entry = { it.buyerSays("Halo Kak, aku mau pesan dong 😊", delayMs = 400) },
    ),
    body("Auto-Text", autoTextLessonSteps()),
    closing("lat_end", "Mantap! Auto-Text menghemat waktu balas chat. Buat pesanmu sendiri lewat tombol '+' di tabnya."),
)

internal fun lessonChapters(lesson: LessonId): List<ChapterDef> = when (lesson) {
    LessonId.Invoice -> InvoiceLesson
    LessonId.Status -> StatusLesson
    LessonId.Produk -> ProdukLesson
    LessonId.AutoText -> AutoTextLesson
}
