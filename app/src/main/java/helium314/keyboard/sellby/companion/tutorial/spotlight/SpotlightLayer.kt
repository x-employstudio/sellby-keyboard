// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.spotlight

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.theme.SellbyColors
import helium314.keyboard.sellby.companion.tutorial.TargetId

/*
 * The spotlight is two layers drawn from the same live target positions:
 *  - [SpotlightScrim] lives INSIDE the focus card (so the dimming never spills outside it);
 *  - [SpotlightMarkers] (the ring and the tap hand) lives on top of EVERYTHING, so they are never
 *    cut off when the target sits right at the card's edge.
 * Both are purely visual - no pointer handling, so taps fall through to the dummy elements, which
 * decide (via the controller) whether the tap was the one the step asks for.
 *
 * The hole is computed in the DRAW phase from live [LayoutCoordinates] relative to each layer;
 * `positionInRoot`/`boundsInWindow` would be wrong while the NavHost slide transition (or the panel
 * open animation) is still moving things. The infinite animations keep redrawing, so it follows.
 */

private const val HOLE_PAD_DP = 5

/** One box around everything the step points at (padded), in the coordinates of [overlay]; null if none is on screen. */
private fun holeOf(targets: List<TargetId>, registry: TargetRegistry, overlay: LayoutCoordinates?, pad: Float): Rect? {
    if (overlay == null || !overlay.isAttached) return null
    val rects = targets.mapNotNull { id ->
        val c = registry.coordinates(id) ?: return@mapNotNull null
        val r = overlay.localBoundingBoxOf(c, clipBounds = false)
        if (r.width <= 0f || r.height <= 0f) null else r
    }
    if (rects.isEmpty()) return null
    return Rect(
        rects.minOf { it.left } - pad,
        rects.minOf { it.top } - pad,
        rects.maxOf { it.right } + pad,
        rects.maxOf { it.bottom } + pad,
    )
}

/** Dims the whole layer except ONE rounded hole around the targets. Draws nothing without targets. */
@Composable
internal fun SpotlightScrim(
    targets: List<TargetId>,
    registry: TargetRegistry,
    visible: Boolean,
    frame: LongState,
    modifier: Modifier = Modifier,
) {
    var overlay by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Canvas(modifier.onGloballyPositioned { overlay = it }) {
        // Redrawn every frame (like the ring) so the hole follows a target that moves or resizes.
        @Suppress("UNUSED_VARIABLE") val tick = frame.longValue
        if (!visible || targets.isEmpty()) return@Canvas
        val hole = holeOf(targets, registry, overlay, HOLE_PAD_DP.dp.toPx())
        if (hole == null) {
            // A target that isn't on screen (yet): dim fully rather than flash undimmed.
            drawRect(Color.Black.copy(alpha = 0.62f))
            return@Canvas
        }
        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(hole, CornerRadius(12.dp.toPx())))
        }
        drawPath(scrim, Color.Black.copy(alpha = 0.62f))
    }
}

/** The pulsing ring around the targets and, for steps the user must tap, the animated "tap here" hand. */
@Composable
internal fun SpotlightMarkers(
    targets: List<TargetId>,
    registry: TargetRegistry,
    visible: Boolean,
    showHand: Boolean,
    frame: LongState,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "spotlight")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    // 0 -> 1 then restart: the hand glides in, presses, and a ripple spreads from the tap point.
    val tap by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "tap",
    )
    val hand = painterResource(R.drawable.ic_hand_click_sellby)
    var overlay by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Canvas(modifier.onGloballyPositioned { overlay = it }) {
        @Suppress("UNUSED_VARIABLE") val tick = frame.longValue
        if (!visible || targets.isEmpty()) return@Canvas
        val hole = holeOf(targets, registry, overlay, HOLE_PAD_DP.dp.toPx()) ?: return@Canvas

        drawRoundRect(
            color = SellbyColors.TealPrimary.copy(alpha = 0.55f + 0.45f * pulse),
            topLeft = hole.topLeft,
            size = hole.size,
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = (2.2f + pulse * 1.6f).dp.toPx()),
        )

        // The hand points at the bottom-right corner of the area to tap (a little inside it); close to
        // the right edge it is mirrored to the bottom-left corner so it never leaves the screen.
        if (showHand) {
            val inset = 8.dp.toPx()
            val handReach = HAND_SIZE_DP.dp.toPx()
            val flip = hole.right + handReach * 0.85f > size.width
            val corner = if (flip) Offset(hole.left + inset, hole.bottom - inset) else Offset(hole.right - inset, hole.bottom - inset)
            drawTapHand(hand, corner, tap, flip)
        }
    }
}

private const val HAND_SIZE_DP = 30

/** Where the pointing fingertip sits inside the 24x24 icon, as a fraction of its size. */
private const val TIP_X = 0.175f
private const val TIP_Y = 0.208f
private val HandOutline = Color(0xFF0B5F75)

private fun DrawScope.drawTapHand(painter: Painter, target: Offset, t: Float, flip: Boolean) {
    val handSize = HAND_SIZE_DP.dp.toPx()
    val dir = if (flip) -1f else 1f

    val approach = (t / 0.5f).coerceIn(0f, 1f)
    val eased = 1f - (1f - approach) * (1f - approach)
    val away = (1f - eased) * 12.dp.toPx()
    val pressed = if (t in 0.5f..0.72f) 0.9f else 1f

    val tip = Offset(target.x + dir * away, target.y + away)
    val left = if (flip) tip.x - (1f - TIP_X) * handSize else tip.x - TIP_X * handSize
    val top = tip.y - TIP_Y * handSize

    // Ripple spreading from the tap point right after the "press".
    if (t > 0.5f) {
        val r = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
        drawCircle(
            color = Color.White.copy(alpha = 0.8f * (1f - r)),
            radius = 5.dp.toPx() + r * 16.dp.toPx(),
            center = target,
            style = Stroke(width = 2.5.dp.toPx()),
        )
    }

    scale(pressed, pressed, pivot = tip) {
        scale(if (flip) -1f else 1f, 1f, pivot = Offset(left + handSize / 2, top + handSize / 2)) {
            val box = Size(handSize, handSize)
            // A dark outline (the icon is white) so the hand also reads over white panels.
            val o = 1.dp.toPx()
            for (d in listOf(Offset(-o, -o), Offset(o, -o), Offset(-o, o), Offset(o, o), Offset(0f, o * 1.6f))) {
                translate(left + d.x, top + d.y) {
                    with(painter) { draw(box, colorFilter = ColorFilter.tint(HandOutline)) }
                }
            }
            translate(left, top) {
                with(painter) { draw(box) }
            }
        }
    }
}
