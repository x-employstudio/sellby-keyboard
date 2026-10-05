// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial

import kotlin.math.roundToInt

/*
 * Pure data model of the interactive tutorial. Nothing here (or anywhere in this package) may touch
 * Room, SharedPreferences, the keyboard service or the system clipboard: the tutorial runs entirely
 * on in-memory dummy data and must never persist anything (enforced by TutorialPurityTest).
 */

enum class Mood { Happy, Flat, Sad, Sleep }

/**
 * Story order: Intro -> InvoiceAwal -> (detour) Produk -> InvoiceLanjut -> Status -> AutoText -> Recap.
 * A chapter is only a titled group of steps that may carry a badge; the dummy world flows across them.
 */
enum class ChapterId { Intro, InvoiceAwal, Produk, InvoiceLanjut, Status, AutoText, Recap }

/**
 * The four stand-alone lessons offered from the keyboard's Settings -> Tutorial. Each one is the
 * relevant part of the full story with its own ready-made dummy world (see TutorialScript.chaptersFor).
 */
enum class LessonId(val title: String) {
    Invoice("Invoice"),
    Status("Status"),
    Produk("Produk"),
    AutoText("Auto Text"),
}

/** The 5 toolbar tabs of the real keyboard (the gear/Settings is decorative in the tutorial). */
enum class PanelTab { Invoice, Ongkir, Status, Produk, AutoText }

/** Everything a step can spotlight, and everything the user may be asked to tap. */
enum class TargetId {
    TabInvoice, TabOngkir, TabStatus, TabProduk, TabAutoText,
    ChatSend,

    // Produk
    ProdukAdd, ProdukNama, ProdukDeskripsi, ProdukHarga, ProdukDiscount, ProdukVariasiToggle,
    VariasiRow0, VariasiRow1, VariasiAdd, ProdukSimpan, ProdukKirimDeskripsi,

    // Invoice
    InvoiceChannel, InvoiceNama, InvoiceWa, InvoiceSearch, SearchStepperPlus, SearchAddM, InvoiceItems,
    InvoiceAlamat, ExpeditionRow, ExpJne, SvcReguler, ExpSimpan, InvoiceOngkir, PayBca, BuatInvoice,

    // Status
    StatusTabs, StatusCard, DetailKembali, BtnIngatkan, BtnLunas, ConfirmLanjutkan, BtnProses, BtnSelesai, BtnArsip,

    // Auto-Text
    AtList, AtRowFormOrder, AtSuggestPill, OfferTempel, ChatInput, FormBubble, CopyButton,
    AtAdd, AtShortcut, AtMessage, AtSimpan, AtRowMenu, AtEdit,
}

/** Text fields that can be filled by the auto-typing animation. */
enum class FieldId {
    ProdukNama, ProdukDeskripsi, ProdukHarga, ProdukDiscount,
    VariasiNama0, VariasiStok0, VariasiNama1, VariasiStok1,
    InvoiceNama, InvoiceWa, InvoiceSearch, InvoiceAlamat, InvoiceOngkir,
    /** The chat's own input box (used to demo the Auto-Text suggestion strip). */
    ChatInput,
    AtShortcut, AtMessage,
}

/** What the user must do to finish a step. */
sealed interface Gate {
    /** Tap the guide's "Lanjut" button (it only appears once any typing animation has finished). */
    data object Next : Gate

    /** Tap exactly this highlighted element. */
    data class Tap(val target: TargetId) : Gate

    /** Move on by itself [delayMs] after the step's typing has finished (no button to tap). */
    data class Auto(val delayMs: Long = 900) : Gate
}

/**
 * Auto-typing: [text] is typed into [field] one character at a time on the dummy keyboard.
 * [keep] is the number of leading characters of [text] that are ALREADY in the field (an edit form that
 * opens prefilled): they stay on screen and only the rest is typed, instead of the field first emptying.
 */
data class Typing(val field: FieldId, val text: String, val charsPerSecond: Int = 13, val keep: Int = 0)

data class Guide(val text: String, val mood: Mood = Mood.Flat)

/**
 * One step of the script. [entry] runs when the step becomes current (e.g. the buyer says something),
 * [effect] runs once the gate is satisfied (e.g. the tapped button's result). Both must be pure and
 * deterministic - the state of any step is derived by folding ALL previous steps' effects over the
 * script's seed, so going back (even across the Produk detour) or restoring after process death is just
 * recomputing. [typing] segments are typed one after another within the step.
 */
data class TutorialStep(
    val id: String,
    val guide: Guide,
    val spotlight: List<TargetId> = emptyList(),
    val gate: Gate = Gate.Next,
    val typing: List<Typing> = emptyList(),
    val entry: (TutorialState) -> TutorialState = { it },
    val effect: (TutorialState) -> TutorialState = { it },
)

data class ChapterDef(
    val id: ChapterId,
    val title: String,
    /** Starting world of the whole script; only the FIRST chapter's seed is used. */
    val seed: TutorialState = TutorialState(),
    val steps: List<TutorialStep>,
)

// ---------------------------------------------------------------- dummy world state

enum class Sender { Seller, Buyer }

/** Marks a chat bubble the lesson interacts with. */
enum class ChatTag { FormOrder }

/**
 * A chat bubble. [appearDelayMs] holds a message back for a moment once it is on screen (a buyer's
 * reply shows up AFTER the seller's own bubble, like a real conversation).
 */
data class ChatMsg(
    val sender: Sender,
    val text: String,
    val appearDelayMs: Long = 0,
    val tag: ChatTag? = null,
)

data class DummyProduct(
    val name: String,
    val price: Long,
    val discount: Long,
    val stock: Int,
    val description: String,
) {
    val finalPrice: Long get() = (price - discount).takeIf { it > 0 } ?: price

    /** Same rounding as the real panel (ProdukPanelView): nearest whole percent, capped at 100. */
    val discountPercent: Int
        get() = if (price <= 0 || discount <= 0) 0 else ((discount * 100.0) / price).roundToInt().coerceAtMost(100)
}

enum class ProdukView { List, Form }

data class ProdukForm(
    val nama: String = "",
    val deskripsi: String = "",
    val harga: String = "",
    val discount: String = "",
    val variasi: Boolean = false,
    /** (nama variasi, stok) rows shown when [variasi] is on. */
    val variasiRows: List<Pair<String, String>> = emptyList(),
    val stok: String = "",
)

data class ProdukPanelState(
    val view: ProdukView = ProdukView.List,
    val products: List<DummyProduct> = emptyList(),
    val form: ProdukForm = ProdukForm(),
)

// ---- invoice

data class InvoiceItem(
    val name: String,
    val qty: Int,
    val unitPrice: Long,
    val originalUnitPrice: Long = unitPrice,
) {
    val subtotal: Long get() = unitPrice * qty
    val hasDiscount: Boolean get() = originalUnitPrice > unitPrice
    val discountPercent: Int
        get() = if (!hasDiscount) 0 else (((originalUnitPrice - unitPrice) * 100.0) / originalUnitPrice).roundToInt()
}

enum class InvoiceView { Form, Expedition }

data class InvoiceState(
    val view: InvoiceView = InvoiceView.Form,
    val nama: String = "",
    /** Local digits as typed (no "+62"), like the real phone field. */
    val wa: String = "",
    val alamat: String = "",
    val search: String = "",
    val searchOpen: Boolean = false,
    /** pcs shown on the search-result row of "Kaos Polos (M)" (the only row the lesson touches). */
    val searchPcs: Int = 1,
    val items: List<InvoiceItem> = emptyList(),
    val expedition: String? = null,
    val service: String? = null,
    /** Working copy inside the expedition sub-screen; committed by "Simpan", dropped by "Batalkan". */
    val pendingExpedition: String? = null,
    val pendingService: String? = null,
    /** Digits only, e.g. "15000". */
    val ongkir: String = "",
    val payment: String? = null,
) {
    val itemsTotal: Long get() = items.sumOf { it.subtotal }
    val shipping: Long get() = ongkir.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val grandTotal: Long get() = (itemsTotal + shipping).coerceAtLeast(0)
}

// ---- orders (Status panel)

enum class DummyOrderStatus { Pending, Lunas, Proses, Selesai, Arsip }

data class DummyOrder(
    val customerName: String,
    val contact: String,
    /** (product name, quantity) */
    val items: List<Pair<String, Int>>,
    val expedition: String,
    val address: String,
    val payment: String,
    val total: Long,
    val status: DummyOrderStatus = DummyOrderStatus.Pending,
) {
    val productsSummary: String get() = "${items.size} Produk"
}

enum class StatusAction { Lunas, Proses, Selesai, Arsip }
enum class StatusOverlay { None, Detail, Confirm }

data class StatusState(
    /** The tab whose first order the (carousel) card shows. */
    val tab: DummyOrderStatus = DummyOrderStatus.Pending,
    val overlay: StatusOverlay = StatusOverlay.None,
    val confirm: StatusAction? = null,
)

// ---- auto-text

/** The data of a filled-in "Form Order" the seller copied; offered for pasting when Invoice opens. */
data class FormOrderOffer(val nama: String, val wa: String, val alamat: String)

/** The 3 factory Auto-Text rows (AutoText.defaultSeedRows); "#nama-toko" is resolved with the store name. */
data class AutoTextRow(val shortcut: String, val message: String) {
    fun resolved(storeName: String) = message.replace("#nama-toko", storeName)
}

enum class AutoTextView { List, Form }

/** The add/edit form of the Auto-Text panel; [editingRow] is null when a NEW Auto-Text is being made. */
data class AutoTextForm(
    val editingRow: Int? = null,
    val shortcut: String = "",
    val message: String = "",
)

data class AutoTextPanelState(
    val view: AutoTextView = AutoTextView.List,
    /** Kept in the same (alphabetical by shortcut) order as the real panel's list. */
    val rows: List<AutoTextRow> = AutoTextRows,
    /** Index of the row whose "⋮" menu (Edit / Hapus / x) is open. */
    val menuRow: Int? = null,
    val form: AutoTextForm = AutoTextForm(),
)

internal val AutoTextRows = listOf(
    AutoTextRow("Format Order", "Form Order\nNama :\nNo Hp :\nAlamat :\nOrder :"),
    AutoTextRow("Hallo", "Halo Kak, selamat datang di #nama-toko! Ada yang bisa kami bantu hari ini?"),
    AutoTextRow("Terima kasih", "Terima Kasih telah berbelanja di #nama-toko!"),
)

/** The whole dummy world the tutorial renders; every field is derived from the script, never stored. */
data class TutorialState(
    /** Store name shown in generated texts (passed in by the host screen; never read from storage here). */
    val storeName: String = "Toko Kamu",
    val chat: List<ChatMsg> = emptyList(),
    /** Text currently sitting in the seller's chat input (what a panel "committed" - not yet sent). */
    val chatInput: String = "",
    /** Open keyboard panel, or null when only the toolbar is showing. */
    val panel: PanelTab? = null,
    val produk: ProdukPanelState = ProdukPanelState(),
    val invoice: InvoiceState = InvoiceState(),
    val orders: List<DummyOrder> = emptyList(),
    val status: StatusState = StatusState(),
    /** The Auto-Text panel: its rows (the lesson can add and edit one) and the open form/menu. */
    val autoText: AutoTextPanelState = AutoTextPanelState(),
    /** A copied filled-in Form Order waiting to be offered when the Invoice tab opens. */
    val formOrderOffer: FormOrderOffer? = null,
    /** While true, typing in the chat input swaps the toolbar for Auto-Text suggestions. */
    val suggestionsActive: Boolean = false,
    /** The little "Salin" menu above the buyer's filled-in form bubble. */
    val copyMenuOpen: Boolean = false,
    /** A short dark pill at the top of the chat ("Teks disalin"); it fades out by itself. */
    val toast: String? = null,
)
