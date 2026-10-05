// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementRulesTest {
    private val now = 1_000_000_000_000L
    private val minute = 60_000L

    private fun premium(
        status: PurchaseStatus = PurchaseStatus.Purchased,
        token: String = "token-1",
        acknowledged: Boolean = false,
        products: List<String> = listOf(PREMIUM_PRODUCT_ID),
    ) = OwnedPurchase(products, status, token, acknowledged)

    private fun reduce(current: Entitlement, event: BillingEvent, at: Long = now) = EntitlementRules.reduce(current, event, at)

    // ---- granting ----------------------------------------------------------------------------------------------

    @Test
    fun purchasedSnapshotGrantsPremiumAndAsksForAcknowledgement() {
        val update = reduce(Entitlement(), BillingEvent.Snapshot(listOf(premium())))
        assertEquals(Entitlement(premium = true), update.entitlement)
        assertEquals(listOf("token-1"), update.acknowledgeTokens)
    }

    @Test
    fun purchasedDeltaGrantsPremiumToo() {
        val update = reduce(Entitlement(), BillingEvent.Delta(listOf(premium())))
        assertTrue(update.entitlement.premium)
        assertEquals(listOf("token-1"), update.acknowledgeTokens)
    }

    @Test
    fun anAlreadyAcknowledgedPurchaseIsNotAcknowledgedAgain() {
        val update = reduce(Entitlement(), BillingEvent.Snapshot(listOf(premium(acknowledged = true))))
        assertTrue(update.entitlement.premium)
        assertEquals(emptyList<String>(), update.acknowledgeTokens)
    }

    @Test
    fun theSameTokenListedTwiceIsAcknowledgedOnce() {
        val update = reduce(Entitlement(), BillingEvent.Snapshot(listOf(premium(), premium())))
        assertEquals(listOf("token-1"), update.acknowledgeTokens)
    }

    @Test
    fun grantingClearsPendingAndTheNotOwnedMarker() {
        val current = Entitlement(premium = true, pending = true, notOwnedSinceMillis = now - minute)
        val update = reduce(current, BillingEvent.Snapshot(listOf(premium(acknowledged = true))))
        assertEquals(Entitlement(premium = true, pending = false, notOwnedSinceMillis = 0L), update.entitlement)
    }

    @Test
    fun otherProductsNeverGivePremium() {
        val other = premium(products = listOf("something_else"))
        assertFalse(reduce(Entitlement(), BillingEvent.Snapshot(listOf(other))).entitlement.premium)
        assertFalse(reduce(Entitlement(), BillingEvent.Delta(listOf(other))).entitlement.premium)
    }

    @Test
    fun aPurchaseOfSeveralProductsCountsWhenPremiumIsOneOfThem() {
        val bundle = premium(products = listOf("something_else", PREMIUM_PRODUCT_ID))
        assertTrue(reduce(Entitlement(), BillingEvent.Snapshot(listOf(bundle))).entitlement.premium)
    }

    @Test
    fun anUnspecifiedStateGivesNothing() {
        val update = reduce(Entitlement(), BillingEvent.Snapshot(listOf(premium(status = PurchaseStatus.Unspecified))))
        assertEquals(Entitlement(), update.entitlement)
        assertEquals(emptyList<String>(), update.acknowledgeTokens)
    }

    // ---- pending -----------------------------------------------------------------------------------------------

    @Test
    fun aPendingPaymentIsNotPremiumButIsRemembered() {
        val pending = premium(status = PurchaseStatus.Pending)
        val fromSnapshot = reduce(Entitlement(), BillingEvent.Snapshot(listOf(pending)))
        assertEquals(Entitlement(premium = false, pending = true), fromSnapshot.entitlement)
        assertEquals(emptyList<String>(), fromSnapshot.acknowledgeTokens) // never acknowledge a pending purchase

        val fromDelta = reduce(Entitlement(), BillingEvent.Delta(listOf(pending)))
        assertEquals(Entitlement(premium = false, pending = true), fromDelta.entitlement)
    }

    @Test
    fun aPendingPaymentThatDisappearsClearsTheBanner() {
        val current = Entitlement(premium = false, pending = true)
        assertEquals(Entitlement(), reduce(current, BillingEvent.Snapshot(emptyList())).entitlement)
    }

    @Test
    fun pendingThenPurchasedEndsAsPremium() {
        val pending = reduce(Entitlement(), BillingEvent.Delta(listOf(premium(status = PurchaseStatus.Pending)))).entitlement
        val done = reduce(pending, BillingEvent.Snapshot(listOf(premium()))).entitlement
        assertEquals(Entitlement(premium = true), done)
    }

    @Test
    fun aPendingDeltaDoesNotDemoteExistingPremium() {
        val current = Entitlement(premium = true)
        val update = reduce(current, BillingEvent.Delta(listOf(premium(status = PurchaseStatus.Pending, token = "second"))))
        assertEquals(current, update.entitlement)
    }

    // ---- failures and the "can only give" rule ------------------------------------------------------------------

    @Test
    fun aFailedCheckChangesNothing() {
        val current = Entitlement(premium = true, notOwnedSinceMillis = now - minute)
        val update = reduce(current, BillingEvent.Failure)
        assertEquals(current, update.entitlement)
        assertEquals(emptyList<String>(), update.acknowledgeTokens)
    }

    @Test
    fun aDeltaNeverTakesPremiumAway() {
        val current = Entitlement(premium = true)
        assertTrue(reduce(current, BillingEvent.Delta(emptyList())).entitlement.premium)
        assertTrue(reduce(current, BillingEvent.Delta(listOf(premium(products = listOf("other"))))).entitlement.premium)
    }

    // ---- taking premium away (refund) --------------------------------------------------------------------------

    @Test
    fun theFirstSnapshotWithoutThePurchaseOnlyMarksIt() {
        val update = reduce(Entitlement(premium = true), BillingEvent.Snapshot(emptyList()))
        assertEquals(Entitlement(premium = true, notOwnedSinceMillis = now), update.entitlement)
    }

    @Test
    fun aSecondSnapshotTooSoonStillKeepsPremium() {
        val current = Entitlement(premium = true, notOwnedSinceMillis = now)
        val update = reduce(current, BillingEvent.Snapshot(emptyList()), at = now + EntitlementRules.REVOKE_CONFIRM_MS - 1)
        assertEquals(current, update.entitlement)
    }

    @Test
    fun aSecondSnapshotFiveMinutesLaterTakesPremiumAway() {
        val current = Entitlement(premium = true, notOwnedSinceMillis = now)
        val update = reduce(current, BillingEvent.Snapshot(emptyList()), at = now + EntitlementRules.REVOKE_CONFIRM_MS)
        assertEquals(Entitlement(premium = false), update.entitlement)
    }

    @Test
    fun seeingThePurchaseAgainResetsTheCountdown() {
        val marked = reduce(Entitlement(premium = true), BillingEvent.Snapshot(emptyList())).entitlement
        val back = reduce(marked, BillingEvent.Snapshot(listOf(premium(acknowledged = true))), at = now + minute).entitlement
        assertEquals(0L, back.notOwnedSinceMillis)
        // a later gap starts counting from scratch: one empty snapshot an hour later does not revoke
        val again = reduce(back, BillingEvent.Snapshot(emptyList()), at = now + 60 * minute).entitlement
        assertTrue(again.premium)
        assertEquals(now + 60 * minute, again.notOwnedSinceMillis)
    }

    @Test
    fun aClockSetBackNeverRevokesImmediately() {
        // the marker is in the future of "now": the phone's clock was moved back since it was written
        val current = Entitlement(premium = true, notOwnedSinceMillis = now + 10 * 60 * minute)
        val update = reduce(current, BillingEvent.Snapshot(emptyList()), at = now)
        assertTrue(update.entitlement.premium)
        assertEquals(now, update.entitlement.notOwnedSinceMillis)
    }

    @Test
    fun withoutPremiumAnEmptySnapshotChangesNothingVisible() {
        assertEquals(Entitlement(), reduce(Entitlement(), BillingEvent.Snapshot(emptyList())).entitlement)
    }

    @Test
    fun revokedUserWithAPendingRepurchaseSeesThePendingBanner() {
        val current = Entitlement(premium = true, notOwnedSinceMillis = now)
        val update = reduce(
            current,
            BillingEvent.Snapshot(listOf(premium(status = PurchaseStatus.Pending))),
            at = now + EntitlementRules.REVOKE_CONFIRM_MS,
        )
        assertEquals(Entitlement(premium = false, pending = true), update.entitlement)
    }
}
