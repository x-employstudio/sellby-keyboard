// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

import android.app.Activity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** [BillingRepository] with a fake Play ([BillingBackend]) and in-memory storage: the flows around the pure
 *  rules (check, purchase, pending, acknowledge, single flight). The rules themselves are in
 *  [EntitlementRulesTest]. */
@RunWith(RobolectricTestRunner::class)
class BillingRepositoryTest {
    private class FakeBackend : BillingBackend {
        var listener: ((PurchaseUpdate) -> Unit)? = null
        var purchases: Fetch<List<OwnedPurchase>> = Fetch.Ok(emptyList())
        var product: Fetch<ProductInfo> = Fetch.Ok(ProductInfo("Rp149.000"))
        var launchResult = LaunchResult.Started
        var acknowledgeSucceeds = true
        var queryCount = 0
        var productQueries = 0
        /** The next N product queries fail (to test the automatic retry). */
        var productFailuresFirst = 0
        val acknowledged = mutableListOf<String>()
        /** When set, queryPurchases waits for it (to hold a check "in flight"). */
        var gate: CompletableDeferred<Unit>? = null
        /** When set, queryProduct waits for it (to hold a price load "in flight"). */
        var productGate: CompletableDeferred<Unit>? = null

        override fun setUpdateListener(listener: (PurchaseUpdate) -> Unit) {
            this.listener = listener
        }

        override suspend fun queryPurchases(): Fetch<List<OwnedPurchase>> {
            queryCount++
            gate?.await()
            return purchases
        }

        override suspend fun queryProduct(): Fetch<ProductInfo> {
            productQueries++
            productGate?.await()
            if (productFailuresFirst > 0) {
                productFailuresFirst--
                return Fetch.Failed
            }
            return product
        }
        override suspend fun launchPurchase(activity: Activity): LaunchResult = launchResult
        override suspend fun acknowledge(purchaseToken: String): Boolean {
            if (acknowledgeSucceeds) acknowledged += purchaseToken
            return acknowledgeSucceeds
        }
    }

    private class MemoryStorage(var value: Entitlement = Entitlement()) : EntitlementStorage {
        var saves = 0
        override fun load() = value
        override fun save(entitlement: Entitlement) {
            value = entitlement
            saves++
        }
    }

    private class MemoryPriceCache(var price: String? = null) : PriceCache {
        override fun loadPrice() = price
        override fun savePrice(price: String) {
            this.price = price
        }
    }

    private val backend = FakeBackend()
    private val storage = MemoryStorage()
    private val priceCache = MemoryPriceCache()
    private var clock = 1_000_000_000_000L
    private val repository = BillingRepository(
        backend, storage, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), now = { clock },
        priceCache = priceCache, retryDelayMs = 0L,
    )
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    private fun bought(token: String = "t1", acknowledged: Boolean = false, status: PurchaseStatus = PurchaseStatus.Purchased) =
        OwnedPurchase(listOf(PREMIUM_PRODUCT_ID), status, token, acknowledged)

    // ---- checking purchases ------------------------------------------------------------------------------------

    @Test
    fun aCheckThatFindsThePurchaseUnlocksAndAcknowledges() = runBlocking {
        backend.purchases = Fetch.Ok(listOf(bought()))
        assertTrue(repository.reconcile())
        assertTrue(repository.state.value.premium)
        assertTrue(storage.value.premium)
        assertEquals(listOf("t1"), backend.acknowledged)
    }

    @Test
    fun anAcknowledgedPurchaseIsNotAcknowledgedAgain() = runBlocking {
        backend.purchases = Fetch.Ok(listOf(bought(acknowledged = true)))
        repository.reconcile()
        repository.reconcile()
        assertTrue(repository.state.value.premium)
        assertEquals(emptyList<String>(), backend.acknowledged)
    }

    @Test
    fun aFailedAcknowledgementIsRetriedByTheNextCheck() = runBlocking {
        backend.purchases = Fetch.Ok(listOf(bought()))
        backend.acknowledgeSucceeds = false
        repository.reconcile()
        assertTrue(repository.state.value.premium) // access never waits for the acknowledgement
        assertEquals(emptyList<String>(), backend.acknowledged)

        backend.acknowledgeSucceeds = true
        repository.reconcile()
        assertEquals(listOf("t1"), backend.acknowledged)
    }

    @Test
    fun aCheckThatCannotReachPlayChangesNothing() = runBlocking {
        storage.value = Entitlement(premium = true)
        backend.purchases = Fetch.Failed
        assertFalse(repository.reconcile())
        assertEquals(Entitlement(premium = true), storage.value)
        assertEquals(0, storage.saves)
    }

    @Test
    fun theStoredPremiumIsVisibleBeforeAnyCheck() {
        val fresh = BillingRepository(backend, MemoryStorage(Entitlement(premium = true)), CoroutineScope(Dispatchers.Unconfined))
        assertTrue(fresh.state.value.premium)
    }

    @Test
    fun aRefundTakesEffectOnlyOnTheSecondCheckFiveMinutesLater() = runBlocking {
        storage.value = Entitlement(premium = true)
        repository.reconcile() // Play lists nothing: only marked
        assertTrue(repository.state.value.premium)

        clock += EntitlementRules.REVOKE_CONFIRM_MS
        repository.reconcile()
        assertFalse(repository.state.value.premium)
        assertFalse(storage.value.premium)
    }

    @Test
    fun checksThatOverlapShareOneQuery() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        backend.gate = gate
        val first = repository.reconcileAsync()
        val second = repository.reconcileAsync()
        assertSame(first, second)
        assertTrue(repository.state.value.checking)
        gate.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, backend.queryCount)
        assertFalse(repository.state.value.checking)
    }

    @Test
    fun aNewCheckStartsAfterTheLastOneFinished() = runBlocking {
        repository.reconcile()
        repository.reconcile()
        assertEquals(2, backend.queryCount)
    }

    @Test
    fun aCheckRecordsWhatPlayReturnedForTheDiagnosis() = runBlocking {
        backend.purchases = Fetch.Ok(listOf(bought(acknowledged = true)))
        repository.reconcile()
        val report = repository.state.value.lastCheck!!
        assertTrue(report.answered)
        assertEquals(listOf("Purchased (acknowledged)"), report.premiumPurchases)

        backend.purchases = Fetch.Failed
        repository.reconcile()
        assertFalse(repository.state.value.lastCheck!!.answered)
    }

    @Test
    fun theStateShowsWhenTheRevocationCountdownStarted() = runBlocking {
        storage.value = Entitlement(premium = true)
        repository.reconcile() // Play lists nothing: the countdown starts now
        assertEquals(clock, repository.state.value.notOwnedSinceMillis)
    }

    @Test
    fun aPriceKnownFromBeforeIsShownAtOnce() {
        val cached = BillingRepository(
            backend, MemoryStorage(), CoroutineScope(Dispatchers.Unconfined), priceCache = MemoryPriceCache("Rp149.000"), retryDelayMs = 0L,
        )
        assertEquals(ProductState.Ready("Rp149.000", live = false), cached.state.value.product)
    }

    @Test
    fun theLivePriceIsRememberedForNextTime() = runBlocking {
        backend.product = Fetch.Ok(ProductInfo("Rp150.000"))
        repository.loadProduct()
        assertEquals(ProductState.Ready("Rp150.000"), repository.state.value.product)
        assertEquals("Rp150.000", priceCache.price)
    }

    @Test
    fun aFailedRefreshKeepsThePriceThatIsAlreadyShown() = runBlocking {
        priceCache.price = "Rp149.000"
        val repo = BillingRepository(backend, storage, CoroutineScope(Dispatchers.Unconfined), priceCache = priceCache, retryDelayMs = 0L)
        backend.product = Fetch.Failed
        repo.loadProduct()
        assertEquals(ProductState.Ready("Rp149.000", live = false), repo.state.value.product)
    }

    @Test
    fun aFailedLoadIsRetriedBeforeGivingUp() = runBlocking {
        backend.productFailuresFirst = 2
        repository.loadProduct()
        assertEquals(ProductState.Ready("Rp149.000"), repository.state.value.product)
        assertEquals(3, backend.productQueries) // two failures, then success

        backend.productQueries = 0
        backend.productFailuresFirst = 10
        val fresh = BillingRepository(backend, MemoryStorage(), CoroutineScope(Dispatchers.Unconfined), retryDelayMs = 0L)
        fresh.loadProduct()
        assertEquals(ProductState.Failed, fresh.state.value.product) // no price was ever known, so it is reported
        assertEquals(4, backend.productQueries) // gave up after four tries
    }

    @Test
    fun loadingTheProductTwiceAtOnceAsksPlayOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        backend.productGate = gate
        val first = repository.loadProductAsync()
        val second = repository.loadProductAsync()
        assertSame(first, second)
        gate.complete(Unit)
        first.await()
        assertEquals(1, backend.productQueries)
    }

    // ---- the product / price -----------------------------------------------------------------------------------

    @Test
    fun theProductPriceComesFromPlay() = runBlocking {
        repository.loadProduct()
        assertEquals(ProductState.Ready("Rp149.000"), repository.state.value.product)
    }

    @Test
    fun aFirstRunInventsNoPriceItWaitsForPlay() {
        assertEquals(ProductState.Loading, repository.state.value.product)
    }

    @Test
    fun aProductThatCannotBeLoadedIsReportedAndCanBeRetried() = runBlocking {
        backend.product = Fetch.Failed
        repository.loadProduct()
        assertEquals(ProductState.Failed, repository.state.value.product)

        backend.product = Fetch.Ok(ProductInfo("Rp149.000"))
        repository.loadProduct()
        assertEquals(ProductState.Ready("Rp149.000", live = true), repository.state.value.product)
    }

    // ---- the purchase flow -------------------------------------------------------------------------------------

    @Test
    fun aCompletedPurchaseUnlocksAndAcknowledges() = runBlocking {
        assertEquals(LaunchResult.Started, repository.launchPurchase(activity))
        assertTrue(repository.state.value.purchasing)

        backend.listener!!(PurchaseUpdate.Purchases(listOf(bought("t9"))))
        assertTrue(repository.state.value.premium)
        assertFalse(repository.state.value.purchasing)
        assertEquals(listOf("t9"), backend.acknowledged)
    }

    @Test
    fun aPendingPaymentShowsTheBannerAndUnlocksLater() = runBlocking {
        repository.launchPurchase(activity)
        backend.listener!!(PurchaseUpdate.Purchases(listOf(bought(status = PurchaseStatus.Pending))))
        assertFalse(repository.state.value.premium)
        assertTrue(repository.state.value.pending)
        assertFalse(repository.state.value.purchasing)
        assertEquals(emptyList<String>(), backend.acknowledged)

        // later (next app start, or Play's callback): the payment went through
        backend.purchases = Fetch.Ok(listOf(bought()))
        repository.reconcile()
        assertTrue(repository.state.value.premium)
        assertFalse(repository.state.value.pending)
        assertEquals(listOf("t1"), backend.acknowledged)
    }

    @Test
    fun cancellingIsSilent() = runBlocking {
        repository.launchPurchase(activity)
        backend.listener!!(PurchaseUpdate.UserCanceled)
        assertFalse(repository.state.value.purchasing)
        assertFalse(repository.state.value.purchaseFailed)
        assertFalse(repository.state.value.premium)
    }

    @Test
    fun aPurchaseErrorIsReported() = runBlocking {
        repository.launchPurchase(activity)
        backend.listener!!(PurchaseUpdate.Error)
        assertFalse(repository.state.value.purchasing)
        assertTrue(repository.state.value.purchaseFailed)
    }

    @Test
    fun alreadyOwnedFromPlayRestoresThePurchase() = runBlocking {
        backend.purchases = Fetch.Ok(listOf(bought(acknowledged = true)))
        repository.launchPurchase(activity)
        backend.listener!!(PurchaseUpdate.AlreadyOwned)
        assertTrue(repository.state.value.premium)
        assertFalse(repository.state.value.purchasing)
    }

    @Test
    fun alreadyOwnedWhenLaunchingRestoresThePurchaseToo() = runBlocking {
        backend.purchases = Fetch.Ok(listOf(bought(acknowledged = true)))
        backend.launchResult = LaunchResult.AlreadyOwned
        repository.launchPurchase(activity)
        assertTrue(repository.state.value.premium)
        assertFalse(repository.state.value.purchasing)
    }

    @Test
    fun aPurchaseThatCouldNotStartDoesNotStayBusy() = runBlocking {
        backend.launchResult = LaunchResult.NotReady
        repository.launchPurchase(activity)
        assertFalse(repository.state.value.purchasing)

        backend.launchResult = LaunchResult.Failed
        repository.launchPurchase(activity)
        assertFalse(repository.state.value.purchasing)
        assertTrue(repository.state.value.purchaseFailed)
    }

    @Test
    fun aSecondTapWhileThePurchaseSheetOpensDoesNothing() = runBlocking {
        repository.launchPurchase(activity)
        backend.launchResult = LaunchResult.Failed
        assertEquals(LaunchResult.Started, repository.launchPurchase(activity))
        assertFalse(repository.state.value.purchaseFailed)
    }

    @Test
    fun aSuccessfulCheckEndsAPurchaseFlowThatNeverReported() = runBlocking {
        repository.launchPurchase(activity)
        assertTrue(repository.state.value.purchasing)
        repository.reconcile()
        assertFalse(repository.state.value.purchasing)
    }

    @Test
    fun aFailedCheckKeepsAPurchaseFlowThatIsStillRunning() = runBlocking {
        repository.launchPurchase(activity)
        backend.purchases = Fetch.Failed
        repository.reconcile()
        assertTrue(repository.state.value.purchasing)
    }
}
