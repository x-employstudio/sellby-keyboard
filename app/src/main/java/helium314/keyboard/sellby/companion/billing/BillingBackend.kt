// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

import android.app.Activity

/** The result of asking Play for something: the value, or "did not work" (no network, no Play, timeout...). */
sealed interface Fetch<out T> {
    data class Ok<T>(val value: T) : Fetch<T>
    data object Failed : Fetch<Nothing>
}

/** What the purchase page needs to show. [formattedPrice] comes from Play in the user's currency ("Rp149.000"). */
data class ProductInfo(val formattedPrice: String)

/** What came back from Play after the purchase flow was started. */
sealed interface PurchaseUpdate {
    data class Purchases(val purchases: List<OwnedPurchase>) : PurchaseUpdate
    data object UserCanceled : PurchaseUpdate
    /** Play says the user already owns it (an earlier purchase that was never recorded here). */
    data object AlreadyOwned : PurchaseUpdate
    data object Error : PurchaseUpdate
}

enum class LaunchResult {
    /** Play's purchase sheet is open; the outcome arrives through [BillingBackend.setUpdateListener]. */
    Started,
    /** No product loaded yet, or no connection to Play. */
    NotReady,
    AlreadyOwned,
    Failed,
}

/** Everything [BillingRepository] needs from Google Play, in plain types. The only implementation that talks to
 *  Play is [PlayBillingBackend]; tests use a fake. Implementations never throw: a problem is [Fetch.Failed]. */
interface BillingBackend {
    /** Called (on any thread) whenever Play reports the outcome of a purchase flow. */
    fun setUpdateListener(listener: (PurchaseUpdate) -> Unit)

    /** All of the user's current in-app purchases. */
    suspend fun queryPurchases(): Fetch<List<OwnedPurchase>>

    /** The premium product with its localized price; remembered for [launchPurchase]. */
    suspend fun queryProduct(): Fetch<ProductInfo>

    /** Call from the main thread: the purchase sheet is opened from the caller's thread. Loads the product first when
     *  the connection was closed in the meantime (the page can stay open for a long time). */
    suspend fun launchPurchase(activity: Activity): LaunchResult

    /** True when Play accepted the acknowledgement. */
    suspend fun acknowledge(purchaseToken: String): Boolean
}
