// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Reads and writes the free-trial state in the SharedPreferences both the companion app and the
 *  keyboard share (same process). Used by the companion screens AND by the keyboard toolbar's
 *  feature lock, so there is exactly one definition of "is the trial over".
 *
 *  Once per device: the start time is kept in SharedPreferences (never touched by "Hapus Semua
 *  Data") AND mirrored into Play services' Block Store (see [TrialBlockStore]), so reinstalling or
 *  clearing the app's data brings the old start back via [syncWithBlockStore]. Remaining limit: a
 *  phone without Google Play services, a factory reset without Google backup, or a different
 *  signing key gets a fresh trial - only a server could close that. */
object TrialPolicy {
    /** Rewrite "last seen" at most this often - [now] is called on every toolbar tap. */
    private const val LAST_SEEN_WRITE_INTERVAL_MS = 60_000L

    fun state(context: Context): TrialState {
        val prefs = context.prefs()
        return TrialRules.evaluate(
            premium = prefs.getBoolean(PREF_PREMIUM_PURCHASED, false),
            startMillis = prefs.getLong(PREF_TRIAL_START_MILLIS, 0L),
            nowMillis = now(prefs),
        )
    }

    /** Features lock only once the trial has run out. [TrialState.NotStarted] stays open on
     *  purpose: a keyboard enabled from system settings before onboarding finishes must not look broken. */
    fun isLocked(context: Context): Boolean = state(context) is TrialState.Expired

    /** Starts the trial if it never started; calling it again never restarts or extends it. */
    fun startIfNeeded(context: Context) {
        val prefs = context.prefs()
        if (prefs.getLong(PREF_TRIAL_START_MILLIS, 0L) > 0L) return
        val start = now(prefs)
        prefs.edit {
            putLong(PREF_TRIAL_START_MILLIS, start)
            putLong(PREF_TRIAL_LAST_SEEN_MILLIS, start)
        }
        TrialBlockStore.write(context, start)
    }

    fun hasStart(context: Context): Boolean = context.prefs().getLong(PREF_TRIAL_START_MILLIS, 0L) > 0L

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var runningSync: Deferred<Unit>? = null

    /** Runs [syncWithBlockStore] in the background, in a scope that outlives any screen: the splash used
     *  to run it as a child of its own coroutine, so leaving the splash after the 2 second wait cancelled
     *  a slow Block Store lookup and the restore never happened. Callers that need the answer await the
     *  returned [Deferred] with their own timeout (awaiting does not cancel it). A sync that is still
     *  running is reused instead of started again. */
    fun startSync(context: Context): Deferred<Unit> {
        runningSync?.takeIf { it.isActive }?.let { return it }
        val app = context.applicationContext
        return syncScope.async<Unit> {
            try {
                syncWithBlockStore(app)
            } catch (e: Exception) {
                Log.d("SellbyTrialPolicy", "Block Store sync failed: $e")
            }
        }.also { runningSync = it }
    }

    /** Makes the trial "once per device": reconciles the start time kept in the app's storage with the
     *  copy in Play services' Block Store, which outlives the app's own data. Start it once at app start
     *  through [startSync] (it never runs on the main thread) - suspending because Block Store is
     *  asynchronous, which is also why the keyboard side never calls it and just trusts the local copy.
     *
     *  - app data was wiped (reinstall / Clear data) but Block Store remembers -> the old start comes back;
     *  - the app knows a start but Block Store does not (installs from before this existed) -> it is saved there;
     *  - both exist -> the earlier one wins, and both end up equal. */
    suspend fun syncWithBlockStore(context: Context) {
        val prefs = context.prefs()
        val local = prefs.getLong(PREF_TRIAL_START_MILLIS, 0L)
        val stored = TrialBlockStore.read(context) ?: 0L
        val merged = TrialRules.mergeStart(local, stored)
        if (merged <= 0L) return
        if (merged != local) prefs.edit { putLong(PREF_TRIAL_START_MILLIS, merged) }
        if (merged != stored) TrialBlockStore.write(context, merged)
    }

    /** Installs that finished onboarding before the trial existed (dev phones, early testers) get
     *  their 3 days from their next app start instead of being locked out or open forever. */
    fun migrateIfNeeded(context: Context) {
        val prefs = context.prefs()
        if (prefs.getBoolean(PREF_ONBOARDING_COMPLETED, false)) startIfNeeded(context)
    }

    /** True once, during Hari 3, until [markReviewPrompted] is called. */
    fun shouldPromptReview(context: Context): Boolean {
        val prefs = context.prefs()
        if (prefs.getBoolean(PREF_PREMIUM_PURCHASED, false) || prefs.getBoolean(PREF_REVIEW_PROMPTED, false)) return false
        return TrialRules.inReviewWindow(prefs.getLong(PREF_TRIAL_START_MILLIS, 0L), now(prefs))
    }

    fun markReviewPrompted(context: Context) {
        context.prefs().edit { putBoolean(PREF_REVIEW_PROMPTED, true) }
    }

    private fun now(prefs: SharedPreferences): Long {
        val wall = System.currentTimeMillis()
        val lastSeen = prefs.getLong(PREF_TRIAL_LAST_SEEN_MILLIS, 0L)
        val effective = TrialRules.effectiveNow(wall, lastSeen)
        if (effective - lastSeen >= LAST_SEEN_WRITE_INTERVAL_MS) {
            prefs.edit { putLong(PREF_TRIAL_LAST_SEEN_MILLIS, effective) }
        }
        return effective
    }
}
