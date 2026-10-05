// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.ChapterDef
import helium314.keyboard.sellby.companion.tutorial.ChapterId
import helium314.keyboard.sellby.companion.tutorial.DummyOrderStatus
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.Guide
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.PanelTab
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.StatusAction
import helium314.keyboard.sellby.companion.tutorial.StatusOverlay
import helium314.keyboard.sellby.companion.tutorial.StatusState
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import helium314.keyboard.sellby.companion.tutorial.TutorialStep
import helium314.keyboard.sellby.util.CurrencyFormat

/** Moves the order shown on the selected tab to [to]. */
private fun TutorialState.moveOrder(to: DummyOrderStatus): TutorialState =
    copy(orders = orders.map { if (it.status == status.tab) it.copy(status = to) else it })

private fun TutorialState.withStatus(change: (StatusState) -> StatusState): TutorialState = copy(status = change(status))

private fun TutorialState.pendingOrder() = orders.first { it.status == DummyOrderStatus.Pending }
private fun TutorialState.currentOrder() = orders.first { it.status == status.tab }

/** The default "Proses" message of StatusPanelView.handleProcessOrder. */
private fun prosesMessage(s: TutorialState): String =
    "Halo Kak *${s.currentOrder().customerName}*,\n" +
        "Pesanan Anda saat ini sedang *DIPROSES / DIPACKING* dan akan segera diserahkan ke kurir pengiriman. " +
        "Mohon ditunggu yaa. Terima kasih! 📦✨"

/**
 * The "Selesai" message of StatusPanelView.handleFinishOrder, with the store name the text means to
 * show (the real panel currently fills that slot from the order's notes field instead).
 */
private fun selesaiMessage(s: TutorialState): String =
    "Halo Kak *${s.currentOrder().customerName}*,\n" +
        "Pesanan Anda di *${s.storeName}* telah *SELESAI / DITERIMA*. Terima kasih banyak telah berbelanja! 😊"

/** The default "Ingatkan" message of StatusPanelView.handleRemindCustomer (WhatsApp markup, spaced "Rp 1.000"). */
private fun reminderMessage(s: TutorialState): String {
    val order = s.pendingOrder()
    return "Halo Kak *${order.customerName}*,\n" +
        "Kami mengingatkan untuk pesanan dengan total tagihan *${CurrencyFormat.rupiah(order.total.toDouble())}* " +
        "(${SampleData.INVOICE_DATE}) belum terselesaikan pembayarannya yaa.\n" +
        "Mohon segera konfirmasi bukti transfer jika sudah melakukan pembayaran. Terima kasih banyak! 🙏"
}

/** The default "Lunas" message of StatusPanelView.handleMarkAsLunas. */
private fun lunasMessage(s: TutorialState): String {
    val order = s.pendingOrder()
    return "Halo Kak *${order.customerName}*,\n" +
        "Pembayaran Anda sebesar *${CurrencyFormat.rupiah(order.total.toDouble())}* telah kami terima dan terkonfirmasi *LUNAS*. " +
        "Pesanan Anda sedang kami siapkan untuk dikirimkan. Terima kasih banyak! 🙏"
}

/**
 * Status, in the order of the buttons a card shows as its order moves on: Ingatkan (payment
 * reminder), Lunas, Proses Pesanan, Selesai - and finally the Detail Transaksi sheet. Every button
 * is practised in full: tap, confirmation (all but Ingatkan), the status message lands in the chat
 * input, the seller sends it and the buyer answers.
 */
internal val StatusChapter = ChapterDef(
    id = ChapterId.Status,
    title = "Status",
    steps = listOf(
        TutorialStep(
            id = "st_1",
            guide = Guide("Rina belum bayar. Kelola pesanannya di tab Status.", Mood.Flat),
            spotlight = listOf(TargetId.TabStatus),
            gate = Gate.Tap(TargetId.TabStatus),
            effect = { it.copy(panel = PanelTab.Status, status = StatusState()) },
        ),
        TutorialStep(
            id = "st_2",
            guide = Guide("Pesanan baru ada di tab Pending. Tab di atas memisahkan status pesanan.", Mood.Flat),
            spotlight = listOf(TargetId.StatusTabs),
        ),
        TutorialStep(
            id = "st_3",
            guide = Guide("Belum dibayar? Ketuk 'Ingatkan' untuk menagih dengan sopan.", Mood.Flat),
            spotlight = listOf(TargetId.BtnIngatkan),
            gate = Gate.Tap(TargetId.BtnIngatkan),
            effect = { it.copy(panel = null, chatInput = reminderMessage(it)) },
        ),
        TutorialStep(
            id = "st_4",
            guide = Guide("Pesan pengingat sudah siap. Tekan Kirim.", Mood.Flat),
            spotlight = listOf(TargetId.ChatSend),
            gate = Gate.Tap(TargetId.ChatSend),
            effect = { it.sendChatInput(buyerReply = "Kak, aku sudah transfer ya 🙏") },
        ),
        TutorialStep(
            id = "st_5",
            guide = Guide("Rina sudah transfer. Buka tab Status lagi.", Mood.Flat),
            spotlight = listOf(TargetId.TabStatus),
            gate = Gate.Tap(TargetId.TabStatus),
            effect = { it.copy(panel = PanelTab.Status) },
        ),
        TutorialStep(
            id = "st_6",
            guide = Guide("Pembayaran sudah masuk. Ketuk 'Lunas'.", Mood.Happy),
            spotlight = listOf(TargetId.BtnLunas),
            gate = Gate.Tap(TargetId.BtnLunas),
            effect = { s -> s.withStatus { it.copy(overlay = StatusOverlay.Confirm, confirm = StatusAction.Lunas) } },
        ),
        TutorialStep(
            id = "st_7",
            guide = Guide("Konfirmasi dulu, lalu ketuk 'Lanjutkan'.", Mood.Flat),
            spotlight = listOf(TargetId.ConfirmLanjutkan),
            gate = Gate.Tap(TargetId.ConfirmLanjutkan),
            effect = { s ->
                s.moveOrder(DummyOrderStatus.Lunas)
                    .withStatus { StatusState(tab = DummyOrderStatus.Lunas) }
                    .copy(panel = null, chatInput = lunasMessage(s))
            },
        ),
        TutorialStep(
            id = "st_8",
            guide = Guide("Pesan 'lunas' otomatis siap. Tekan Kirim untuk memberi tahu Rina.", Mood.Flat),
            spotlight = listOf(TargetId.ChatSend),
            gate = Gate.Tap(TargetId.ChatSend),
            effect = { it.sendChatInput(buyerReply = "Makasih Kak! 😊") },
        ),
        TutorialStep(
            id = "st_9",
            guide = Guide("Pesanan pindah ke tab Lunas. Buka Status lagi.", Mood.Flat),
            spotlight = listOf(TargetId.TabStatus),
            gate = Gate.Tap(TargetId.TabStatus),
            effect = { it.copy(panel = PanelTab.Status) },
        ),
        TutorialStep(
            id = "st_10",
            guide = Guide("Saatnya packing. Ketuk 'Proses Pesanan'.", Mood.Flat),
            spotlight = listOf(TargetId.BtnProses),
            gate = Gate.Tap(TargetId.BtnProses),
            effect = { s -> s.withStatus { it.copy(overlay = StatusOverlay.Confirm, confirm = StatusAction.Proses) } },
        ),
        TutorialStep(
            id = "st_11",
            guide = Guide("Ketuk 'Lanjutkan' untuk menandai pesanan sedang diproses.", Mood.Flat),
            spotlight = listOf(TargetId.ConfirmLanjutkan),
            gate = Gate.Tap(TargetId.ConfirmLanjutkan),
            effect = { s ->
                s.moveOrder(DummyOrderStatus.Proses)
                    .withStatus { StatusState(tab = DummyOrderStatus.Proses) }
                    .copy(panel = null, chatInput = prosesMessage(s))
            },
        ),
        TutorialStep(
            id = "st_12",
            guide = Guide("Pesan 'diproses' sudah siap. Tekan Kirim.", Mood.Flat),
            spotlight = listOf(TargetId.ChatSend),
            gate = Gate.Tap(TargetId.ChatSend),
            effect = { it.sendChatInput(buyerReply = "Siap Kak, aku tunggu ya 🙏") },
        ),
        TutorialStep(
            id = "st_13",
            guide = Guide("Pesanan pindah ke tab Proses. Buka Status lagi.", Mood.Flat),
            spotlight = listOf(TargetId.TabStatus),
            gate = Gate.Tap(TargetId.TabStatus),
            effect = { it.copy(panel = PanelTab.Status) },
        ),
        TutorialStep(
            id = "st_14",
            guide = Guide("Barang sudah sampai? Ketuk 'Selesai'.", Mood.Flat),
            spotlight = listOf(TargetId.BtnSelesai),
            gate = Gate.Tap(TargetId.BtnSelesai),
            effect = { s -> s.withStatus { it.copy(overlay = StatusOverlay.Confirm, confirm = StatusAction.Selesai) } },
        ),
        TutorialStep(
            id = "st_15",
            guide = Guide("Ketuk 'Lanjutkan' untuk menandai pesanan selesai.", Mood.Flat),
            spotlight = listOf(TargetId.ConfirmLanjutkan),
            gate = Gate.Tap(TargetId.ConfirmLanjutkan),
            effect = { s ->
                s.moveOrder(DummyOrderStatus.Selesai)
                    .withStatus { StatusState(tab = DummyOrderStatus.Selesai) }
                    .copy(panel = null, chatInput = selesaiMessage(s))
            },
        ),
        TutorialStep(
            id = "st_16",
            guide = Guide("Pesan 'selesai' sudah siap. Tekan Kirim.", Mood.Flat),
            spotlight = listOf(TargetId.ChatSend),
            gate = Gate.Tap(TargetId.ChatSend),
            effect = { it.sendChatInput(buyerReply = "Barangnya sudah sampai, makasih Kak! 🥰") },
        ),
        TutorialStep(
            id = "st_17",
            guide = Guide("Pesanan sudah selesai. Buka Status sekali lagi.", Mood.Flat),
            spotlight = listOf(TargetId.TabStatus),
            gate = Gate.Tap(TargetId.TabStatus),
            effect = { it.copy(panel = PanelTab.Status) },
        ),
        TutorialStep(
            id = "st_18",
            guide = Guide("Terakhir, ketuk kartu untuk melihat Detail Transaksi.", Mood.Flat),
            spotlight = listOf(TargetId.StatusCard),
            gate = Gate.Tap(TargetId.StatusCard),
            effect = { s -> s.withStatus { it.copy(overlay = StatusOverlay.Detail) } },
        ),
        TutorialStep(
            id = "st_19",
            guide = Guide("Rincian pembelian, alamat, dan total ada di sini. Ketuk Kembali.", Mood.Happy),
            spotlight = listOf(TargetId.DetailKembali),
            gate = Gate.Tap(TargetId.DetailKembali),
            effect = { s -> s.withStatus { it.copy(overlay = StatusOverlay.None) } },
        ),
    ),
)
