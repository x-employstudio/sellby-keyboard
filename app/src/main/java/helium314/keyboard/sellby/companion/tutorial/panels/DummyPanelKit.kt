// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.panels

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.sellby.companion.tutorial.spotlight.LocalInScroll
import helium314.keyboard.sellby.companion.tutorial.spotlight.LocalPanelScrollHost
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialDecoy

/*
 * Shared building blocks of the dummy keyboard panels. They imitate the real View-based panels
 * (the *PanelView.kt classes in sellby/ui): flat white sheet, 28dp round teal header buttons,
 * #E2E8F0 field tiles, pill buttons. Colours below are copied from those views.
 */

internal object PanelColors {
    val Teal = Color(0xFF0EA5C6)
    val Lime = Color(0xFF8CE623)
    val Field = Color(0xFFE2E8F0)
    val CardBorder = Color(0xFFCBD5E1)
    val Text = Color(0xFF1E293B)
    val Hint = Color(0xFF94A3B8)
    val Muted = Color(0xFF64748B)
    val Red = Color(0xFFEF4444)
    val Slate = Color(0xFF475569)
    val Orange = Color(0xFFF97316)
    val Divider = Color(0xFFE2E8F0)
}

/**
 * Panel sheet: header (round button, centered title, optional trailing round button) + divider + body.
 * Panel bodies never scroll by touch (the tutorial scrolls them itself) - see [lockedScroll].
 */
@Composable
internal fun PanelShell(
    title: String,
    height: Dp,
    leadingIcon: Int,
    trailingIcon: Int?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Color.White),
    ) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundHeaderButton(leadingIcon)
            Text(
                title,
                color = PanelColors.Teal,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            if (trailingIcon != null) RoundHeaderButton(trailingIcon) else Box(Modifier.size(28.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PanelColors.Divider))
        content()
    }
}

@Composable
private fun RoundHeaderButton(icon: Int) {
    Box(
        Modifier.size(28.dp).background(PanelColors.Teal, CircleShape).tutorialDecoy(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/**
 * A read-only field tile. [focused] draws the teal border and a blinking caret after the text, which
 * is how the auto-typing animation shows "this is the field being filled".
 */
@Composable
internal fun PanelField(
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    suffix: String? = null,
    focused: Boolean = false,
    height: Dp = 32.dp,
    multiline: Boolean = false,
    leadingIcon: Int? = null,
    /** Lines a multiline field may show before it ellipsizes (default: 2, like the product description). */
    multilineMaxLines: Int = 2,
) {
    Row(
        modifier
            // Fixed height, multiline or not: a field must not grow while text is typed into it.
            .height(height)
            .background(PanelColors.Field, RoundedCornerShape(8.dp))
            .then(if (focused) Modifier.border(1.4.dp, PanelColors.Teal, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = if (multiline) 7.dp else 0.dp),
        verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                painterResource(leadingIcon),
                contentDescription = null,
                tint = PanelColors.Hint,
                modifier = Modifier.size(14.dp).align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (prefix != null) {
            Text(prefix, color = PanelColors.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && !focused) {
                Text(hint, color = PanelColors.Hint, fontSize = 12.sp, maxLines = if (multiline) multilineMaxLines else 1, overflow = TextOverflow.Ellipsis)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        value,
                        color = PanelColors.Text,
                        fontSize = 12.sp,
                        maxLines = if (multiline) multilineMaxLines else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (focused) Caret()
                }
            }
        }
        if (suffix != null) {
            Text(suffix, color = PanelColors.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Blinking caret; its own composable so only the focused field recomposes with the animation. */
@Composable
private fun Caret() {
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        Modifier
            .padding(start = 1.dp)
            .width(1.5.dp)
            .height(15.dp)
            .alpha(blink)
            .background(PanelColors.Teal),
    )
}

@Composable
internal fun PillButton(
    text: String,
    background: Color,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    fontSize: TextUnit = 11.sp,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 5.dp,
) {
    Box(
        modifier
            .background(background, RoundedCornerShape(16.dp))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Panel bodies never scroll by touch; the tutorial scrolls them itself (see [PanelScrollColumn]). */
internal fun Modifier.lockedScroll(state: ScrollState): Modifier = this.verticalScroll(state, enabled = false)

/**
 * A panel's scrolling body. It never scrolls by touch; it registers its [ScrollState] and viewport with
 * the [PanelScrollHost] so the tutorial can scroll a step's target into a comfortable spot, and flags
 * everything inside as "scrollable-to" ([LocalInScroll]). Sticky bars stay outside it.
 */
@Composable
internal fun PanelScrollColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    state: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalPanelScrollHost.current
    DisposableEffect(state) {
        host.scroll = state
        onDispose {
            if (host.scroll === state) {
                host.scroll = null
                host.viewport = null
            }
        }
    }
    CompositionLocalProvider(LocalInScroll provides true) {
        Column(
            modifier.onGloballyPositioned { host.viewport = it }.lockedScroll(state).padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** Dim backdrop of an in-panel dialog; swallows every tap so nothing underneath can be hit. */
@Composable
internal fun PanelScrim(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x73000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
        content = content,
    )
}
