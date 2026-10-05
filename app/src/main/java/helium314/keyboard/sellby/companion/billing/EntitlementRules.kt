// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

/** The one-time product in Play Console (Monetize -> Products -> One-time products). */
const val PREMIUM_PRODUCT_ID = "sellby_premium"

enum class PurchaseStatus { Purchased, Pending, Unspecified }

/** What Google Play reports about one purchase, reduced to the three facts the rules need. */
data class OwnedPurchase(
    val productIds: List<String>,
    val status: PurchaseStatus,
    val token: String,
    val acknowledged: Boolean,
)

/** What the app currently believes about premium. Persisted by [EntitlementStore]; [premium] is the flag the
 *  keyboard's feature lock reads. */
data class Entitlement(
    val premium: Boolean = false,
    /** A payment is waiting to complete (minimarket, bank transfer...): not premium yet. */
    val pending: Boolean = false,
    /** First time a full Play check did not list the purchase while [premium] was on; 0 = never / cleared. */
    val notOwnedSinceMillis: Long = 0L,
)

/** The three things that can come back from Google Play. */
sealed interface BillingEvent {
    /** The COMPLETE list of the user's purchases from a successful query. Only this can take premium away. */
    data class Snapshot(val purchases: List<OwnedPurchase>) : BillingEvent

    /** Purchases delivered by the purchase flow's callback. Can only give, never take away. */
    data class Delta(val purchases: List<OwnedPurchase>) : BillingEvent

    /** A query or connection failed (no network, no Play, timeout...). Changes nothing. */
    data object Failure : BillingEvent
}

/** The new state, and the purchase tokens that must be acknowledged (Play refunds a purchase that is not
 *  acknowledged within 3 days). Acknowledge only AFTER the new state is saved. */
data class EntitlementUpdate(val entitlement: Entitlement, val acknowledgeTokens: List<String>)

/** The pure decision logic of the purchase: no Android, no Play types, unit tested directly. Everything that
 *  talks to Play lives in [PlayBillingBackend]; everything that stores lives in [EntitlementStore].
 *
 *  Principles:
 *  - Only a PURCHASED premium purchase gives premium. A PENDING one only sets [Entitlement.pending].
 *  - Premium is taken away only by a [BillingEvent.Snapshot] that does not list it, and only the SECOND time,
 *    at least [REVOKE_CONFIRM_MS] after the first: Play's local cache can briefly answer with an empty list
 *    (right after Play updates or the account switches), and a wrong "not owned" must not lock a paying user.
 *    A refund therefore takes effect on the next check that is 5+ minutes later (for example the next app start).
 *  - A failed check changes nothing. */
object EntitlementRules {
    const val REVOKE_CONFIRM_MS = 5 * 60_000L

    fun reduce(current: Entitlement, event: BillingEvent, nowMillis: Long): EntitlementUpdate = when (event) {
        BillingEvent.Failure -> EntitlementUpdate(current, emptyList())
        is BillingEvent.Delta -> reduceDelta(current, event.purchases)
        is BillingEvent.Snapshot -> reduceSnapshot(current, event.purchases, nowMillis)
    }

    private fun reduceDelta(current: Entitlement, purchases: List<OwnedPurchase>): EntitlementUpdate {
        val premium = purchases.filter { it.isPremium() }
        val bought = premium.filter { it.status == PurchaseStatus.Purchased }
        if (bought.isNotEmpty()) return grant(bought)
        if (!current.premium && premium.any { it.status == PurchaseStatus.Pending }) {
            return EntitlementUpdate(current.copy(pending = true), emptyList())
        }
        return EntitlementUpdate(current, emptyList())
    }

    private fun reduceSnapshot(current: Entitlement, purchases: List<OwnedPurchase>, now: Long): EntitlementUpdate {
        val premium = purchases.filter { it.isPremium() }
        val bought = premium.filter { it.status == PurchaseStatus.Purchased }
        if (bought.isNotEmpty()) return grant(bought)

        val pending = premium.any { it.status == PurchaseStatus.Pending }
        if (!current.premium) {
            return EntitlementUpdate(Entitlement(premium = false, pending = pending), emptyList())
        }
        // Premium is on, but Play does not list the purchase.
        val since = current.notOwnedSinceMillis
        val firstTime = since <= 0L || since > now // 0 = never seen; in the future = the clock was set back
        if (firstTime) {
            return EntitlementUpdate(current.copy(pending = false, notOwnedSinceMillis = now), emptyList())
        }
        if (now - since >= REVOKE_CONFIRM_MS) {
            return EntitlementUpdate(Entitlement(premium = false, pending = pending), emptyList())
        }
        return EntitlementUpdate(current, emptyList())
    }

    private fun grant(bought: List<OwnedPurchase>): EntitlementUpdate = EntitlementUpdate(
        Entitlement(premium = true, pending = false, notOwnedSinceMillis = 0L),
        bought.filter { !it.acknowledged }.map { it.token }.distinct(),
    )

    private fun OwnedPurchase.isPremium() = PREMIUM_PRODUCT_ID in productIds
}
