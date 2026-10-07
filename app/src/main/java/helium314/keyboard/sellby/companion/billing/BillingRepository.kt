// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProductState {
    data object Loading : ProductState
    data class Ready(val formattedPrice: String) : ProductState
    data object Failed : ProductState
}

/** What the last check of the purchases with Google Play found (shown by the hidden diagnosis on "Tentang & Lisensi"):
 *  when, whether Play answered at all, and which premium purchases it listed. */
data class CheckReport(val atMillis: Long, val answered: Boolean, val premiumPurchases: List<String>)

/** What the purchase page shows. [premium] and [pending] mirror the stored [Entitlement]. */
data class BillingUiState(
    val premium: Boolean = false,
    val pending: Boolean = false,
    val product: ProductState = ProductState.Loading,
    /** A check of the user's purchases with Play is running. */
    val checking: Boolean = false,
    /** Play's purchase sheet is open (or about to be). */
    val purchasing: Boolean = false,
    /** The last purchase attempt failed (cancelling is not a failure). */
    val purchaseFailed: Boolean = false,
    /** Diagnosis only: the result of the most recent check, and the stored "first check that did not list the purchase". */
    val lastCheck: CheckReport? = null,
    val notOwnedSinceMillis: Long = 0L,
)

/** Ties Play ([BillingBackend]), the pure decision logic ([EntitlementRules]) and storage ([EntitlementStorage])
 *  together. App-wide singleton ([get]): the companion screens call [reconcile] / [loadProduct] /
 *  [launchPurchase]; nothing in the keyboard's typing path ever does, the keyboard only reads the stored flag.
 *
 *  Work that must finish no matter which screen asked for it (the check of the purchases, acknowledging) runs in
 *  [scope], not in the caller's coroutine, so leaving a screen never cancels half of a purchase. */
class BillingRepository(
    private val backend: BillingBackend,
    private val storage: EntitlementStorage,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(storage.load().toUi())
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    private val entitlementLock = Any()
    private var checkInFlight: Deferred<Boolean>? = null
    private val acknowledging = HashSet<String>()

    init {
        backend.setUpdateListener(::onPurchaseUpdate)
    }

    /** Starts (or joins) a check of the user's purchases with Play. The Deferred is true when Play answered, false
     *  when it could not be reached (then nothing was changed). Awaiting it does not cancel it. */
    @Synchronized
    fun reconcileAsync(): Deferred<Boolean> {
        checkInFlight?.takeIf { it.isActive }?.let { return it }
        return scope.async { doReconcile() }.also { checkInFlight = it }
    }

    suspend fun reconcile(): Boolean = reconcileAsync().await()

    private suspend fun doReconcile(): Boolean {
        _state.update { it.copy(checking = true) }
        var answered = false
        try {
            val fetched = backend.queryPurchases()
            answered = fetched is Fetch.Ok
            val listed = (fetched as? Fetch.Ok)?.value.orEmpty().filter { PREMIUM_PRODUCT_ID in it.productIds }
            val report = CheckReport(
                atMillis = now(),
                answered = answered,
                premiumPurchases = listed.map { it.status.name + if (it.acknowledged) " (acknowledged)" else " (not acknowledged)" },
            )
            val tokens = apply(
                when (fetched) {
                    is Fetch.Ok -> BillingEvent.Snapshot(fetched.value)
                    Fetch.Failed -> BillingEvent.Failure
                }
            )
            _state.update { it.copy(lastCheck = report) }
            acknowledgeInBackground(tokens)
        } finally {
            // A successful check is the truth about any purchase flow that was still marked as running.
            _state.update { it.copy(checking = false, purchasing = if (answered) false else it.purchasing) }
        }
        return answered
    }

    /** Loads the price shown on the purchase page; call it every time that page opens. */
    suspend fun loadProduct() {
        _state.update { it.copy(product = ProductState.Loading) }
        val product = when (val fetched = backend.queryProduct()) {
            is Fetch.Ok -> ProductState.Ready(fetched.value.formattedPrice)
            Fetch.Failed -> ProductState.Failed
        }
        _state.update { it.copy(product = product) }
    }

    /** Opens Play's purchase sheet. Call from the main thread (a Compose click handler's scope is). */
    suspend fun launchPurchase(activity: Activity): LaunchResult {
        if (_state.value.purchasing) return LaunchResult.Started // a second tap while the sheet opens
        _state.update { it.copy(purchasing = true, purchaseFailed = false) }
        val result = backend.launchPurchase(activity)
        when (result) {
            LaunchResult.Started -> Unit // stays "purchasing" until Play reports the outcome (or the next check)
            LaunchResult.AlreadyOwned -> {
                _state.update { it.copy(purchasing = false) }
                reconcileAsync()
            }
            LaunchResult.NotReady -> _state.update { it.copy(purchasing = false) }
            LaunchResult.Failed -> _state.update { it.copy(purchasing = false, purchaseFailed = true) }
        }
        return result
    }

    private fun onPurchaseUpdate(update: PurchaseUpdate) {
        when (update) {
            is PurchaseUpdate.Purchases -> {
                val tokens = apply(BillingEvent.Delta(update.purchases))
                _state.update { it.copy(purchasing = false) }
                acknowledgeInBackground(tokens)
            }
            PurchaseUpdate.UserCanceled -> _state.update { it.copy(purchasing = false) }
            PurchaseUpdate.AlreadyOwned -> {
                _state.update { it.copy(purchasing = false) }
                reconcileAsync()
            }
            PurchaseUpdate.Error -> _state.update { it.copy(purchasing = false, purchaseFailed = true) }
        }
    }

    /** Runs the event through the rules, saves the result and publishes it; returns the tokens to acknowledge. */
    private fun apply(event: BillingEvent): List<String> = synchronized(entitlementLock) {
        val current = storage.load()
        val update = EntitlementRules.reduce(current, event, now())
        if (update.entitlement != current) storage.save(update.entitlement)
        _state.update {
            it.copy(
                premium = update.entitlement.premium,
                pending = update.entitlement.pending,
                notOwnedSinceMillis = update.entitlement.notOwnedSinceMillis,
            )
        }
        update.acknowledgeTokens
    }

    /** After the purchase is saved. A failed acknowledgement is retried by the next check (the purchase is then still
     *  listed as not acknowledged); Play only refunds one that stays unacknowledged for 3 days. */
    private fun acknowledgeInBackground(tokens: List<String>) {
        for (token in tokens) {
            val first = synchronized(acknowledging) { acknowledging.add(token) }
            if (!first) continue
            scope.launch {
                try {
                    backend.acknowledge(token)
                } finally {
                    synchronized(acknowledging) { acknowledging.remove(token) }
                }
            }
        }
    }

    private fun Entitlement.toUi() = BillingUiState(premium = premium, pending = pending, notOwnedSinceMillis = notOwnedSinceMillis)

    companion object {
        @Volatile private var instance: BillingRepository? = null

        fun get(context: Context): BillingRepository = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(appContext: Context) = BillingRepository(
            backend = PlayBillingBackend(appContext),
            storage = EntitlementStore(appContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
