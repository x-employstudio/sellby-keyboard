// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial

/**
 * Height budget of the tutorial screen, in dp. Pure function so it can be unit tested.
 *
 * The screen is a column: the focus card (dummy chat on top, then the open panel and the toolbar -
 * the tutorial draws NO keys, so panels get the room), the guide bubble (fixed height), a dashed
 * line and the skip button. The chat gets whatever the panel leaves; on short screens the panel
 * shrinks (down to [PANEL_MIN]) and below that the screen shows a "too short" card instead.
 */
internal data class StageMetrics(
    val panelHeight: Float,
    /** False when even the smallest panel doesn't fit; the screen then shows a "too short" card. */
    val fits: Boolean,
) {
    companion object {
        /** Top gap 20 + gap 18 + guide bubble (fixed 108) + gap 21 + dashed line + skip link (48). */
        const val OUTSIDE = 216f
        const val TOOLBAR = 41f
        const val PANEL_WANT = 330f
        const val PANEL_MIN = 190f

        /** Smallest chat area that still shows its header, the input row and one message. */
        const val CHAT_MIN = 130f

        fun compute(totalHeight: Float): StageMetrics {
            val room = totalHeight - OUTSIDE - CHAT_MIN - TOOLBAR
            return StageMetrics(panelHeight = room.coerceIn(PANEL_MIN, PANEL_WANT), fits = room >= PANEL_MIN)
        }
    }
}
