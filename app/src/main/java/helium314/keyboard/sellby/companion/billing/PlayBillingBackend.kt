// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The one and only class that talks to Google Play Billing (a test keeps every `com.android.billingclient` import
 *  in this file). Everything is "best effort": a missing Play Store, no network or a timeout is [Fetch.Failed],
 *  never an exception, because the keyboard and the companion app must keep working without Play.
 *
 *  The connection is opened when something needs it and closed again after [IDLE_CLOSE_MS] without use, so the
 *  long-lived keyboard process does not stay bound to Play for nothing. A closed BillingClient cannot be reused,
 *  so a fresh one is created for the next connection. */
internal class PlayBillingBackend(context: Context) : BillingBackend {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectLock = Mutex()
    private val activeOperations = AtomicInteger(0)

    @Volatile private var client: BillingClient? = null
    @Volatile private var premiumDetails: ProductDetails? = null
    @Volatile private var offerToken: String? = null
    @Volatile private var updateListener: ((PurchaseUpdate) -> Unit)? = null
    private var idleClose: Job? = null

    override fun setUpdateListener(listener: (PurchaseUpdate) -> Unit) {
        updateListener = listener
    }

    override suspend fun queryPurchases(): Fetch<List<OwnedPurchase>> = withClient { c ->
        val result = c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        )
        if (result.billingResult.responseCode == BillingResponseCode.OK) Fetch.Ok(result.purchasesList.map(::toOwned))
        else Fetch.Failed
    }

    override suspend fun queryProduct(): Fetch<ProductInfo> = withClient { c ->
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PREMIUM_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = c.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
        val details = result.productDetailsList?.firstOrNull { it.productId == PREMIUM_PRODUCT_ID }
        // The newer one-time product model can carry several purchase options/offers; the first is the default.
        val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: details?.oneTimePurchaseOfferDetails
        val price = offer?.formattedPrice
        if (result.billingResult.responseCode != BillingResponseCode.OK || details == null || offer == null || price.isNullOrBlank()) {
            Fetch.Failed
        } else {
            premiumDetails = details
            offerToken = offer.offerToken
            Fetch.Ok(ProductInfo(price))
        }
    }

    override suspend fun launchPurchase(activity: Activity): LaunchResult {
        var c = client?.takeIf { it.isReady }
        var details = premiumDetails
        if (c == null || details == null) {
            // The connection was closed while the page stayed open (or the product never loaded): reconnect now.
            if (queryProduct() is Fetch.Failed) return LaunchResult.NotReady
            c = client?.takeIf { it.isReady }
            details = premiumDetails
        }
        if (c == null || details == null) return LaunchResult.NotReady
        return try {
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
            offerToken?.takeIf { it.isNotEmpty() }?.let { productParams.setOfferToken(it) }
            val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams.build())).build()
            when (c.launchBillingFlow(activity, params).responseCode) {
                BillingResponseCode.OK -> {
                    // The purchase sheet can stay open for minutes; do not close the connection under it.
                    scheduleIdleClose(extraMs = PURCHASE_KEEP_OPEN_MS)
                    LaunchResult.Started
                }
                BillingResponseCode.ITEM_ALREADY_OWNED -> LaunchResult.AlreadyOwned
                else -> LaunchResult.Failed
            }
        } catch (e: Exception) {
            Log.d(TAG, "launchBillingFlow failed: $e")
            LaunchResult.Failed
        }
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean = withClient { c ->
        val result = c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build())
        if (result.responseCode == BillingResponseCode.OK) Fetch.Ok(Unit) else Fetch.Failed
    } is Fetch.Ok

    // ---- connection ---------------------------------------------------------------------------------------------

    /** Runs [block] with a connected client, under a time limit; any problem becomes [Fetch.Failed]. */
    private suspend fun <T> withClient(block: suspend (BillingClient) -> Fetch<T>): Fetch<T> {
        activeOperations.incrementAndGet()
        try {
            val c = readyClient() ?: return Fetch.Failed
            return withTimeoutOrNull(OPERATION_TIMEOUT_MS) { block(c) } ?: Fetch.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "billing call failed: $e")
            return Fetch.Failed
        } finally {
            activeOperations.decrementAndGet()
            scheduleIdleClose()
        }
    }

    private suspend fun readyClient(): BillingClient? {
        client?.takeIf { it.isReady }?.let { return it }
        return connectLock.withLock {
            client?.takeIf { it.isReady }?.let { return@withLock it }
            var backoffMs = RETRY_FIRST_DELAY_MS
            for (attempt in 1..CONNECT_ATTEMPTS) {
                val candidate = client ?: newClient().also { client = it }
                val code = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connect(candidate) }
                if (code == BillingResponseCode.OK) return@withLock candidate
                discardClient() // a client that failed or timed out is not worth keeping
                if (code != null && !isRetryable(code)) break // e.g. no Play Store on this device
                if (attempt < CONNECT_ATTEMPTS) {
                    delay(backoffMs)
                    backoffMs *= 2
                }
            }
            null
        }
    }

    private suspend fun connect(c: BillingClient): Int = suspendCancellableCoroutine { continuation ->
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (continuation.isActive) continuation.resume(result.responseCode)
            }

            override fun onBillingServiceDisconnected() {
                if (continuation.isActive) continuation.resume(BillingResponseCode.SERVICE_DISCONNECTED)
            }
        })
    }

    private fun newClient(): BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        // Payments that complete later (minimarket, bank transfer, e-wallet top-up) arrive as PENDING purchases.
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    @Synchronized
    private fun discardClient() {
        client?.let { runCatching { it.endConnection() } }
        client = null
    }

    @Synchronized
    private fun scheduleIdleClose(extraMs: Long = 0L) {
        idleClose?.cancel()
        idleClose = scope.launch {
            delay(IDLE_CLOSE_MS + extraMs)
            if (activeOperations.get() == 0) discardClient() else scheduleIdleClose()
        }
    }

    private fun isRetryable(code: Int) = code == BillingResponseCode.SERVICE_UNAVAILABLE ||
        code == BillingResponseCode.SERVICE_DISCONNECTED ||
        code == BillingResponseCode.SERVICE_TIMEOUT ||
        code == BillingResponseCode.NETWORK_ERROR ||
        code == BillingResponseCode.ERROR

    // ---- results ------------------------------------------------------------------------------------------------

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val update = when (result.responseCode) {
            BillingResponseCode.OK -> PurchaseUpdate.Purchases(purchases.orEmpty().map(::toOwned))
            BillingResponseCode.USER_CANCELED -> PurchaseUpdate.UserCanceled
            BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseUpdate.AlreadyOwned
            else -> PurchaseUpdate.Error
        }
        scheduleIdleClose() // the purchase flow is over: back to the normal idle rule
        updateListener?.invoke(update)
    }

    private fun toOwned(purchase: Purchase) = OwnedPurchase(
        productIds = purchase.products,
        status = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseStatus.Purchased
            Purchase.PurchaseState.PENDING -> PurchaseStatus.Pending
            else -> PurchaseStatus.Unspecified
        },
        token = purchase.purchaseToken,
        acknowledged = purchase.isAcknowledged,
    )

    private companion object {
        const val TAG = "SellbyBilling"
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val OPERATION_TIMEOUT_MS = 20_000L
        const val RETRY_FIRST_DELAY_MS = 1_000L
        const val CONNECT_ATTEMPTS = 3
        const val IDLE_CLOSE_MS = 60_000L
        const val PURCHASE_KEEP_OPEN_MS = 10 * 60_000L
    }
}
