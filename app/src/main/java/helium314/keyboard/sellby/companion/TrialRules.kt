// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

/** Where the user stands in the 3-day free trial. */
sealed interface TrialState {
    /** Bought the app: everything is open, the trial no longer matters. */
    data object Premium : TrialState

    /** Onboarding hasn't reached the "Coba GRATIS" button yet. */
    data object NotStarted : TrialState

    /** [dayIndex] is 1..3 (Hari 1 = hour 0-24, Hari 2 = 24-48, Hari 3 = 48-72). */
    data class Active(val dayIndex: Int, val remainingMs: Long) : TrialState

    data object Expired : TrialState
}

/** The pure arithmetic of the trial - no Android types, so it is unit tested directly. Everything
 *  that touches SharedPreferences or the wall clock lives in [TrialPolicy]. */
object TrialRules {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val TRIAL_DAYS = 3
    const val TRIAL_DURATION_MS = TRIAL_DAYS * DAY_MS

    /** [startMillis] <= 0 means "never started". Premium always wins over an expired trial. */
    fun evaluate(premium: Boolean, startMillis: Long, nowMillis: Long): TrialState {
        if (premium) return TrialState.Premium
        if (startMillis <= 0L) return TrialState.NotStarted
        val elapsed = elapsed(startMillis, nowMillis)
        if (elapsed >= TRIAL_DURATION_MS) return TrialState.Expired
        return TrialState.Active(dayIndex = (elapsed / DAY_MS).toInt() + 1, remainingMs = TRIAL_DURATION_MS - elapsed)
    }

    /** Combines the start time kept in the app's own storage with the copy in Block Store (either may
     *  be <= 0 = none). The EARLIER one wins, so neither wiping the app's data nor a stale copy can
     *  ever push the start later and hand out extra time. 0 when neither exists. */
    fun mergeStart(localMillis: Long, storedMillis: Long): Long {
        val candidates = listOf(localMillis, storedMillis).filter { it > 0L }
        return candidates.minOrNull() ?: 0L
    }

    /** True during Hari 3 (hour 48-72), the only window where the review popup may appear. */
    fun inReviewWindow(startMillis: Long, nowMillis: Long): Boolean {
        if (startMillis <= 0L) return false
        val elapsed = elapsed(startMillis, nowMillis)
        return elapsed >= (TRIAL_DAYS - 1) * DAY_MS && elapsed < TRIAL_DURATION_MS
    }

    /** The trial clock never runs backwards: setting the phone's date back does not buy extra
     *  time, because the newest time we ever saw ([lastSeenMillis]) is the floor. */
    fun effectiveNow(wallMillis: Long, lastSeenMillis: Long): Long = maxOf(wallMillis, lastSeenMillis)

    private fun elapsed(startMillis: Long, nowMillis: Long): Long = (nowMillis - startMillis).coerceAtLeast(0L)
}
