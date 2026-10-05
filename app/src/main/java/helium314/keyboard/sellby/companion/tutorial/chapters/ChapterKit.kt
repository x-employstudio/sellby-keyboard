// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chapters

import helium314.keyboard.sellby.companion.tutorial.ChatMsg
import helium314.keyboard.sellby.companion.tutorial.ChatTag
import helium314.keyboard.sellby.companion.tutorial.InvoiceState
import helium314.keyboard.sellby.companion.tutorial.ProdukForm
import helium314.keyboard.sellby.companion.tutorial.Sender
import helium314.keyboard.sellby.companion.tutorial.TutorialState

/** Small pure helpers shared by the chapter scripts. */

internal fun TutorialState.withInvoice(change: (InvoiceState) -> InvoiceState): TutorialState =
    copy(invoice = change(invoice))

internal fun TutorialState.withForm(change: (ProdukForm) -> ProdukForm): TutorialState =
    copy(produk = produk.copy(form = change(produk.form)))

/** A buyer message that arrives (with a small animation and the notification chime) [delayMs] after the step starts. */
internal fun TutorialState.buyerSays(text: String, delayMs: Long = 0, tag: ChatTag? = null): TutorialState =
    copy(chat = chat + ChatMsg(Sender.Buyer, text, appearDelayMs = delayMs, tag = tag))

/** How long after the seller's own bubble the buyer's reply shows up. */
internal const val REPLY_DELAY_MS = 1500L

/**
 * The seller presses the chat's send button: whatever sits in the input becomes a seller bubble
 * (nothing is ever sent anywhere - it only joins the dummy chat); the optional buyer reply then
 * follows [REPLY_DELAY_MS] later, like a real conversation.
 */
internal fun TutorialState.sendChatInput(
    buyerReply: String? = null,
    replyDelayMs: Long = REPLY_DELAY_MS,
    replyTag: ChatTag? = null,
): TutorialState {
    val sent = chat + ChatMsg(Sender.Seller, chatInput)
    return copy(
        chat = if (buyerReply == null) sent else sent + ChatMsg(Sender.Buyer, buyerReply, replyDelayMs, replyTag),
        chatInput = "",
    )
}
