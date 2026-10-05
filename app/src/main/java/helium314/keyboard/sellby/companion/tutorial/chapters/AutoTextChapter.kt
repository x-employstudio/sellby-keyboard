// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.AutoTextForm
import helium314.keyboard.sellby.companion.tutorial.AutoTextPanelState
import helium314.keyboard.sellby.companion.tutorial.AutoTextRow
import helium314.keyboard.sellby.companion.tutorial.AutoTextRows
import helium314.keyboard.sellby.companion.tutorial.AutoTextView
import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChapterId
import helium314.keyboard.sellby.companion.tutorial.ChatTag
import helium314.keyboard.sellby.companion.tutorial.FieldId
import helium314.keyboard.sellby.companion.tutorial.FormOrderOffer
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.Guide
import helium314.keyboard.sellby.companion.tutorial.InvoiceState
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.PanelTab
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import helium314.keyboard.sellby.companion.tutorial.TutorialStep
import helium314.keyboard.sellby.companion.tutorial.Typing

private val FORM_ORDER = AutoTextRows[0]
private val TERIMA_KASIH = AutoTextRows[2]

private fun TutorialState.withAutoText(change: (AutoTextPanelState) -> AutoTextPanelState): TutorialState =
    copy(autoText = change(autoText))

/** The list is alphabetical by shortcut, like the real panel's (AutoTextDao.getAll orders by shortcut). */
private fun List<AutoTextRow>.sortedByShortcut() = sortedBy { it.shortcut.lowercase() }

private fun TutorialState.lessonRowIndex() = autoText.rows.indexOfFirst { it.shortcut == SampleData.NEW_AUTOTEXT_SHORTCUT }

/**
 * Making a new Auto-Text and editing an existing one (used by the Auto Text lesson only, right after the
 * list has been introduced - the panel is open on its list): "+ Tambah Auto Text" -> shortcut -> message ->
 * Simpan, then the three dots -> Edit -> change the message -> Simpan. The row it creates is the one it
 * edits, so the lesson never touches the factory rows the rest of the story uses.
 */
internal val AutoTextManageSteps: List<TutorialStep> = listOf(
    TutorialStep(
        id = "atm_1",
        guide = Guide("Mau jalan pintas buatan sendiri? Ketuk '+ Tambah Auto Text'.", Mood.Flat),
        spotlight = listOf(TargetId.AtAdd),
        gate = Gate.Tap(TargetId.AtAdd),
        effect = { s -> s.withAutoText { it.copy(view = AutoTextView.Form, menuRow = null, form = AutoTextForm()) } },
    ),
    TutorialStep(
        id = "atm_2",
        guide = Guide("Isi shortcut: kata singkat yang nanti kamu ketik untuk memanggil pesannya.", Mood.Flat),
        spotlight = listOf(TargetId.AtShortcut),
        gate = Gate.Auto(),
        typing = listOf(Typing(FieldId.AtShortcut, SampleData.NEW_AUTOTEXT_SHORTCUT)),
        effect = { s -> s.withAutoText { it.copy(form = it.form.copy(shortcut = SampleData.NEW_AUTOTEXT_SHORTCUT)) } },
    ),
    TutorialStep(
        id = "atm_3",
        guide = Guide("Lalu pesannya. Boleh panjang dan lebih dari satu baris.", Mood.Flat),
        spotlight = listOf(TargetId.AtMessage),
        gate = Gate.Auto(),
        typing = listOf(Typing(FieldId.AtMessage, SampleData.NEW_AUTOTEXT_MESSAGE, charsPerSecond = 22)),
        effect = { s -> s.withAutoText { it.copy(form = it.form.copy(message = SampleData.NEW_AUTOTEXT_MESSAGE)) } },
    ),
    TutorialStep(
        id = "atm_4",
        guide = Guide("Ketuk Simpan. Shortcut dan pesan wajib diisi.", Mood.Happy),
        spotlight = listOf(TargetId.AtSimpan),
        gate = Gate.Tap(TargetId.AtSimpan),
        effect = { s ->
            s.withAutoText {
                it.copy(
                    view = AutoTextView.List,
                    rows = (it.rows + AutoTextRow(it.form.shortcut, it.form.message)).sortedByShortcut(),
                    form = AutoTextForm(),
                )
            }
        },
    ),
    TutorialStep(
        id = "atm_5",
        guide = Guide("Auto-Text barumu sudah masuk daftar. Mau mengubahnya? Ketuk titik tiga di barisnya.", Mood.Flat),
        spotlight = listOf(TargetId.AtRowMenu),
        gate = Gate.Tap(TargetId.AtRowMenu),
        effect = { s -> s.withAutoText { it.copy(menuRow = s.lessonRowIndex()) } },
    ),
    TutorialStep(
        id = "atm_6",
        guide = Guide("Muncul tombol Edit dan Hapus. Ketuk 'Edit'.", Mood.Flat),
        spotlight = listOf(TargetId.AtEdit),
        gate = Gate.Tap(TargetId.AtEdit),
        effect = { s ->
            val index = s.lessonRowIndex()
            val row = s.autoText.rows[index]
            s.withAutoText {
                it.copy(
                    view = AutoTextView.Form,
                    menuRow = null,
                    form = AutoTextForm(editingRow = index, shortcut = row.shortcut, message = row.resolved(s.storeName)),
                )
            }
        },
    ),
    TutorialStep(
        id = "atm_7",
        guide = Guide("Formnya terbuka dengan isi lama. Aku tambahkan satu kalimat di akhir pesan.", Mood.Flat),
        spotlight = listOf(TargetId.AtMessage),
        gate = Gate.Auto(),
        typing = listOf(
            Typing(
                FieldId.AtMessage,
                SampleData.NEW_AUTOTEXT_MESSAGE + SampleData.NEW_AUTOTEXT_ADDED,
                charsPerSecond = 22,
                keep = SampleData.NEW_AUTOTEXT_MESSAGE.length,
            ),
        ),
        effect = { s ->
            s.withAutoText { it.copy(form = it.form.copy(message = SampleData.NEW_AUTOTEXT_MESSAGE + SampleData.NEW_AUTOTEXT_ADDED)) }
        },
    ),
    TutorialStep(
        id = "atm_8",
        guide = Guide("Ketuk Simpan lagi untuk menyimpan perubahannya.", Mood.Happy),
        spotlight = listOf(TargetId.AtSimpan),
        gate = Gate.Tap(TargetId.AtSimpan),
        effect = { s ->
            s.withAutoText { a ->
                val form = a.form
                a.copy(
                    view = AutoTextView.List,
                    rows = a.rows.mapIndexed { i, r -> if (i == form.editingRow) AutoTextRow(form.shortcut, form.message) else r }.sortedByShortcut(),
                    form = AutoTextForm(),
                )
            }
        },
    ),
    TutorialStep(
        id = "atm_9",
        guide = Guide("Beres! Auto-Text bawaan hanya bisa diedit. Buatanmu sendiri juga bisa dihapus.", Mood.Happy),
        spotlight = listOf(TargetId.AtList),
    ),
)

/** What a second buyer sends back after being given the "Format Order" form. */
private const val FILLED_FORM = "Form Order\nNama : Dina\nNo Hp : 0857-1111-2222\nAlamat : Jl. Kenanga 12, Surabaya\nOrder : Kaos Polos M x1"
private val DINA = FormOrderOffer(nama = "Dina", wa = "0857-1111-2222", alamat = "Jl. Kenanga 12, Surabaya")

/**
 * Auto-Text, last: send the "Format Order" form to a new buyer, copy the form she fills in and see it
 * offered to the Invoice tab ("Form Order Terdeteksi" -> Tempel -> the customer fields are filled), and
 * finally the suggestion strip that replaces the toolbar while typing a shortcut.
 */
internal val AutoTextChapter = ChapterDef(
    id = ChapterId.AutoText,
    title = "Auto-Text",
    steps = listOf(
        TutorialStep(
            id = "at_1",
            guide = Guide("Terakhir: Auto-Text, jalan pintas untuk pesan yang sering dikirim.", Mood.Happy),
            spotlight = listOf(TargetId.TabAutoText),
            gate = Gate.Tap(TargetId.TabAutoText),
            effect = { it.copy(panel = PanelTab.AutoText) },
        ),
        TutorialStep(
            id = "at_2",
            guide = Guide("Ketuk baris untuk mengirimnya. Titik tiga untuk edit/hapus, '+' untuk membuat sendiri.", Mood.Flat),
            spotlight = listOf(TargetId.AtList),
        ),
        TutorialStep(
            id = "at_3",
            guide = Guide("Capek tanya data satu-satu? Kirim 'Format Order' agar pembeli mengisinya sendiri.", Mood.Flat),
            spotlight = listOf(TargetId.AtRowFormOrder),
            gate = Gate.Tap(TargetId.AtRowFormOrder),
            effect = { it.copy(panel = null, chatInput = FORM_ORDER.resolved(it.storeName)) },
        ),
        TutorialStep(
            id = "at_4",
            guide = Guide("Form masuk kolom chat. Tekan Kirim.", Mood.Flat),
            spotlight = listOf(TargetId.ChatSend),
            gate = Gate.Tap(TargetId.ChatSend),
            effect = { it.sendChatInput(buyerReply = FILLED_FORM, replyDelayMs = 2200, replyTag = ChatTag.FormOrder) },
        ),
        TutorialStep(
            id = "at_5",
            guide = Guide("Pembeli sudah mengisi form. Tahan pesannya untuk menyalin.", Mood.Flat),
            spotlight = listOf(TargetId.FormBubble),
            gate = Gate.Tap(TargetId.FormBubble),
            effect = { it.copy(copyMenuOpen = true) },
        ),
        TutorialStep(
            id = "at_6",
            guide = Guide("Pilih 'Salin'.", Mood.Flat),
            spotlight = listOf(TargetId.CopyButton),
            gate = Gate.Tap(TargetId.CopyButton),
            effect = { it.copy(copyMenuOpen = false, formOrderOffer = DINA) },
        ),
        TutorialStep(
            id = "at_7",
            guide = Guide("Teks tersalin. Sellby mendeteksi form itu saat kamu membuka tab Invoice.", Mood.Flat),
            spotlight = listOf(TargetId.TabInvoice),
            gate = Gate.Tap(TargetId.TabInvoice),
            entry = { it.copy(toast = "Teks disalin") },
            effect = { it.copy(toast = null, panel = PanelTab.Invoice, invoice = InvoiceState()) },
        ),
        TutorialStep(
            id = "at_8",
            guide = Guide("Ketuk 'Tempel' untuk mengisi data pelanggan dari form tadi.", Mood.Happy),
            spotlight = listOf(TargetId.OfferTempel),
            gate = Gate.Tap(TargetId.OfferTempel),
            effect = { s ->
                s.copy(
                    formOrderOffer = null,
                    invoice = s.invoice.copy(nama = DINA.nama, wa = DINA.wa.filter { it.isDigit() }.removePrefix("0"), alamat = DINA.alamat),
                )
            },
        ),
        TutorialStep(
            id = "at_9",
            guide = Guide("Nama dan nomor Dina sudah terisi sendiri di Invoice.", Mood.Happy),
            spotlight = listOf(TargetId.InvoiceNama, TargetId.InvoiceWa),
        ),
        TutorialStep(
            id = "at_10",
            guide = Guide("Alamatnya juga. Tinggal pilih produk lalu buat invoice!", Mood.Happy),
            spotlight = listOf(TargetId.InvoiceAlamat),
        ),
        TutorialStep(
            id = "at_11",
            guide = Guide("Jalan pintas lain: ketik 'ter' di kolom chat.", Mood.Flat),
            spotlight = listOf(TargetId.ChatInput),
            gate = Gate.Auto(1000),
            typing = listOf(Typing(FieldId.ChatInput, "ter")),
            entry = { it.copy(panel = null, suggestionsActive = true) },
            effect = { it.copy(chatInput = "ter") },
        ),
        TutorialStep(
            id = "at_12",
            guide = Guide("Saran Auto-Text muncul. Ketuk 'Terima kasih' untuk memakainya.", Mood.Flat),
            spotlight = listOf(TargetId.AtSuggestPill),
            gate = Gate.Tap(TargetId.AtSuggestPill),
            effect = { it.copy(chatInput = TERIMA_KASIH.resolved(it.storeName), suggestionsActive = false) },
        ),
        TutorialStep(
            id = "at_13",
            guide = Guide("Ucapan terima kasih siap. Tekan Kirim.", Mood.Happy),
            spotlight = listOf(TargetId.ChatSend),
            gate = Gate.Tap(TargetId.ChatSend),
            effect = { it.sendChatInput(buyerReply = "Sama-sama Kak! 😊") },
        ),
    ),
)
