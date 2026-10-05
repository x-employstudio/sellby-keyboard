// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.spotlight

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialController

/**
 * Where every spotlightable element currently is. Positions are NOT stored as numbers: the spotlight
 * asks the live [LayoutCoordinates] in the draw phase (relative to its own overlay), because the
 * NavHost slide transition and the panel open animation both move things after layout.
 */
internal class TargetRegistry {
    private val coords = HashMap<TargetId, LayoutCoordinates>()
    private val scrollable = HashSet<TargetId>()

    /** Bumped whenever a target appears or disappears so draw/composition observers re-read. */
    var version by mutableIntStateOf(0)
        private set

    fun register(id: TargetId, c: LayoutCoordinates) {
        if (coords[id] !== c) {
            coords[id] = c
            version++
        }
    }

    /** [inScroll]: the element lives inside a panel's scrolling body (so the tutorial may scroll to it). */
    fun markInScroll(id: TargetId, inScroll: Boolean) {
        if (inScroll) scrollable += id else scrollable -= id
    }

    fun unregister(id: TargetId, c: LayoutCoordinates?) {
        // Ignore a stale dispose (the id may already be owned by a newer element).
        if (c == null || coords[id] === c) {
            coords.remove(id)
            scrollable -= id
            version++
        }
    }

    fun coordinates(id: TargetId): LayoutCoordinates? {
        @Suppress("UNUSED_VARIABLE") val observe = version
        return coords[id]?.takeIf { it.isAttached }
    }

    fun isInScroll(id: TargetId): Boolean = id in scrollable
}

/**
 * The scrolling body of the panel that is currently open: its [ScrollState] and its viewport. The
 * panels never scroll by touch; the tutorial scrolls them itself (see TutorialScreen) so the thing a
 * step points at sits comfortably inside the viewport instead of flush against an edge.
 */
internal class PanelScrollHost {
    var scroll: ScrollState? = null
    var viewport: LayoutCoordinates? = null
}

internal val LocalTargetRegistry = staticCompositionLocalOf<TargetRegistry> { error("no TargetRegistry") }
internal val LocalTutorial = staticCompositionLocalOf<TutorialController> { error("no TutorialController") }
internal val LocalPanelScrollHost = staticCompositionLocalOf<PanelScrollHost> { error("no PanelScrollHost") }

/** True inside a panel's scrolling body; targets created there are remembered as scrollable-to. */
internal val LocalInScroll = staticCompositionLocalOf { false }

/**
 * Marks an element as a spotlight/tap target. Tapping it reports to the controller: if it is the
 * current step's gate it advances, otherwise it counts as a miss.
 */
@Composable
internal fun Modifier.tutorialTarget(id: TargetId, tappable: Boolean = true): Modifier {
    val registry = LocalTargetRegistry.current
    val controller = LocalTutorial.current
    val inScroll = LocalInScroll.current
    val last = remember { arrayOfNulls<LayoutCoordinates>(1) }
    SideEffect { registry.markInScroll(id, inScroll) }
    DisposableEffect(id) {
        onDispose { registry.unregister(id, last[0]) }
    }
    val base = this.onGloballyPositioned {
        last[0] = it
        registry.register(id, it)
    }
    return if (tappable) {
        base.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { controller.tap(id) }
    } else {
        base
    }
}

/** An element that looks tappable but is not part of the lesson: tapping it is always a miss. */
@Composable
internal fun Modifier.tutorialDecoy(): Modifier {
    val controller = LocalTutorial.current
    return this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { controller.tap(null) }
}
