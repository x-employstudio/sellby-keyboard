// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.sellby.companion.theme.SellbyColors
import helium314.keyboard.sellby.companion.tutorial.ChatMsg
import helium314.keyboard.sellby.companion.tutorial.ChatTag
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.Sender
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialTarget
import kotlinx.coroutines.delay

/*
 * A deliberately GENERIC messenger look (not a copy of any real app): header, bubbles, input row.
 * The three pieces are stacked inside one rounded "chat card" by the screen. The only interactive
 * piece is the send button, which is a tutorial target.
 */

private val ChatBackground = Color(0xFFE9EEF3)
private val SellerBubble = Color(0xFFD9F3F8)

/** The chat's top bar in the "messenger green" (it is meant to look like a WhatsApp chat). */
private val HeaderGreen = Color(0xFF008069)

/** Name and status share one tight, vertically centred block - no extra font padding above/below. */
private val TightLine = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

@Composable
internal fun DummyChatHeader(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(HeaderGreen)
            .height(46.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(SampleData.BUYER_NAME.take(1), color = HeaderGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                SampleData.BUYER_NAME,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                style = TextStyle(lineHeight = 16.sp, lineHeightStyle = TightLine),
            )
            Text(
                "online",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
                style = TextStyle(lineHeight = 12.sp, lineHeightStyle = TightLine),
            )
        }
        VideoCallGlyph(Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        MoreDotsGlyph(Modifier.size(22.dp))
    }
}

@Composable
private fun VideoCallGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // The camera body ...
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.04f, h * 0.27f),
            size = Size(w * 0.58f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.1f),
        )
        // ... and its lens/viewfinder.
        val lens = Path().apply {
            moveTo(w * 0.68f, h * 0.43f)
            lineTo(w * 0.96f, h * 0.27f)
            lineTo(w * 0.96f, h * 0.73f)
            lineTo(w * 0.68f, h * 0.57f)
            close()
        }
        drawPath(lens, Color.White)
    }
}

@Composable
private fun MoreDotsGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.width * 0.09f
        for (fraction in listOf(0.22f, 0.5f, 0.78f)) {
            drawCircle(Color.White, radius = r, center = Offset(size.width / 2, size.height * fraction))
        }
    }
}

/**
 * Bottom-anchored message list: newest message sits just above the input, older ones scroll up.
 * Messages that arrive while this is on screen slide/fade in (a buyer's after its own delay); the ones
 * that were already there when it first appeared just show.
 * [copyMenuOpen] floats a "Salin" pill above the buyer's filled-in form bubble.
 */
@Composable
internal fun DummyChatMessages(messages: List<ChatMsg>, copyMenuOpen: Boolean, modifier: Modifier = Modifier) {
    val initialCount = remember { messages.size }
    Column(
        modifier
            .fillMaxSize()
            .background(ChatBackground)
            .verticalScroll(rememberScrollState(), reverseScrolling = true)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        messages.forEachIndexed { i, msg ->
            key(i) { ChatBubble(msg, animate = i >= initialCount, copyMenuOpen = copyMenuOpen) }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMsg, animate: Boolean, copyMenuOpen: Boolean) {
    val visible = remember { MutableTransitionState(!animate) }
    val playSound = LocalChatSound.current
    LaunchedEffect(Unit) {
        if (animate) {
            delay(msg.appearDelayMs)
            visible.targetState = true
            if (msg.sender == Sender.Buyer) playSound()
        }
    }
    val seller = msg.sender == Sender.Seller
    val isForm = msg.tag == ChatTag.FormOrder
    // The "Salin" pill lives outside the AnimatedVisibility (which clips its content).
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 2 } + expandVertically(tween(280)),
            modifier = Modifier.align(if (seller) Alignment.CenterEnd else Alignment.CenterStart),
        ) {
            Box(
                Modifier
                    .widthIn(max = 270.dp)
                    .background(if (seller) SellerBubble else Color.White, RoundedCornerShape(12.dp))
                    .border(1.dp, if (seller) Color(0xFFB6E3EC) else SellbyColors.SurfaceMuted, RoundedCornerShape(12.dp))
                    .let { if (isForm) it.tutorialTarget(TargetId.FormBubble) else it }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(chatMarkup(msg.text), color = SellbyColors.TextDark, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
        if (isForm && copyMenuOpen) {
            Text(
                "Salin",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 14.dp, y = (-34).dp)
                    .background(Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .tutorialTarget(TargetId.CopyButton)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

/** Input pill + round send button, sitting on the chat background (like most messengers). */
@Composable
internal fun DummyChatInput(text: String, focused: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(ChatBackground)
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 38.dp, max = 76.dp)
                .background(Color.White, RoundedCornerShape(19.dp))
                .then(if (focused) Modifier.border(1.5.dp, SellbyColors.TealPrimary, RoundedCornerShape(19.dp)) else Modifier)
                .tutorialTarget(TargetId.ChatInput, tappable = false)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text("Ketik pesan", color = SellbyColors.TextHint, fontSize = 12.5.sp)
            } else {
                Text(
                    text = chatMarkup(text),
                    color = SellbyColors.TextDark,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val hasText = text.isNotEmpty()
        Box(
            Modifier
                .size(40.dp)
                .background(if (hasText) SellbyColors.TealPrimary else Color.White, CircleShape)
                .tutorialTarget(TargetId.ChatSend),
            contentAlignment = Alignment.Center,
        ) {
            SendGlyph(Modifier.size(18.dp), if (hasText) Color.White else SellbyColors.TextHint)
        }
    }
}

@Composable
private fun SendGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.10f)
            lineTo(w * 0.95f, h * 0.50f)
            lineTo(w * 0.08f, h * 0.90f)
            lineTo(w * 0.22f, h * 0.52f)
            close()
        }
        drawPath(path, color)
    }
}

/** WhatsApp-style inline markup used by the real invoice/product texts: *bold* and ~strike~. */
internal fun chatMarkup(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '*' || c == '~') {
            val end = text.indexOf(c, i + 1)
            val lineEnd = text.indexOf('\n', i + 1)
            if (end > i + 1 && (lineEnd == -1 || end < lineEnd)) {
                val style = if (c == '*') SpanStyle(fontWeight = FontWeight.Bold)
                else SpanStyle(textDecoration = TextDecoration.LineThrough)
                withStyle(style) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(c)
        i++
    }
}
