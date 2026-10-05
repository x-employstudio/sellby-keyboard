// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial

/** Dummy world shared by every chapter. Plain constants only - never read from or written to storage. */
internal object SampleData {
    const val BUYER_NAME = "Rina"
    const val BUYER_WA_DIGITS = "081234567890"
    const val BUYER_ADDRESS = "Jl. Melati No. 5, Bandung"

    /** Fixed on purpose: the script is a pure fold, so it must not read the clock. */
    const val INVOICE_DATE = "2 Oktober 2026"

    /**
     * Rina's first message: just the order. Her name, number and address are NOT in it - the seller
     * would normally ask for them (which is exactly what the Form Order Auto-Text is for).
     */
    val buyerOrder = ChatMsg(
        Sender.Buyer,
        "Halo Kak, aku mau pesan Kaos Polos ukuran M 2 pcs ya 😊",
        appearDelayMs = 400,
    )

    /**
     * The Form Order Rina fills in and sends (Invoice lesson: the seller copies it and the Invoice tab
     * offers to paste it). Her number is written the way a buyer types it, with dashes - the paste keeps
     * only the digits, like the real panel does.
     */
    const val RINA_FORM_WA = "0812-3456-7890"
    const val RINA_FILLED_FORM =
        "Form Order\nNama : $BUYER_NAME\nNo Hp : $RINA_FORM_WA\nAlamat : $BUYER_ADDRESS\nOrder : Kaos Polos M x2"
    val rinaFormOffer = FormOrderOffer(nama = BUYER_NAME, wa = RINA_FORM_WA, alamat = BUYER_ADDRESS)

    const val KAOS_NAMA = "Kaos Polos"
    const val KAOS_HARGA = "75000"
    const val KAOS_DISCOUNT = "15000"
    const val KAOS_DESKRIPSI = "Kaos katun combed 24s, adem dan nyaman dipakai seharian."

    /** The variation the buyer orders (see ProdukChapter: "Kaos Polos" is saved as (L) and (M)). */
    const val KAOS_M = "Kaos Polos (M)"

    const val EXPEDITION = "JNE"
    const val SERVICE = "Reguler"
    const val ONGKIR = "15000"

    const val BANK = "BCA"
    const val BANK_ACCOUNT = "1234567890"

    /** The Auto-Text the lesson creates, then edits (no "ter" in the shortcut: "ter" is the suggestion-strip demo). */
    const val NEW_AUTOTEXT_SHORTCUT = "Rekening"
    const val NEW_AUTOTEXT_MESSAGE = "Transfer ke BCA 1234567890 ya Kak, terima kasih! 🙏"
    const val NEW_AUTOTEXT_ADDED = " Mohon kirim bukti transfernya."

    /** The products the Produk lesson "creates": one per variation, saved as separate products (like the real panel). */
    fun kaosProducts(): List<DummyProduct> = listOf("M" to 10, "L" to 14).map { (variant, stock) ->
        DummyProduct(
            name = "$KAOS_NAMA ($variant)",
            price = KAOS_HARGA.toLong(),
            discount = KAOS_DISCOUNT.toLong(),
            stock = stock,
            description = KAOS_DESKRIPSI,
        )
    }

    /** The finished invoice of the main story, as the Status lessons start with it: Rina's 2 x Kaos (M) + shipping. */
    fun rinaPendingOrder() = DummyOrder(
        customerName = BUYER_NAME,
        contact = "+62" + BUYER_WA_DIGITS.removePrefix("0"),
        items = listOf(KAOS_M to 2),
        expedition = "$EXPEDITION - $SERVICE",
        address = BUYER_ADDRESS,
        payment = BANK,
        total = 2 * (KAOS_HARGA.toLong() - KAOS_DISCOUNT.toLong()) + ONGKIR.toLong(),
    )
}
