// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.guide

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.theme.SellbyColors
import helium314.keyboard.sellby.companion.tutorial.Gate
import helium314.keyboard.sellby.companion.tutorial.Mood
import helium314.keyboard.sellby.companion.tutorial.TutorialController
import kotlinx.coroutines.delay

private val GUIDE_BUBBLE_HEIGHT = 108.dp

private fun mascotRes(mood: Mood): Int = when (mood) {
    Mood.Happy -> R.drawable.sellby_companion_character_happy
    Mood.Flat -> R.drawable.sellby_companion_character_flat
    Mood.Sad -> R.drawable.sellby_companion_character_sad
    Mood.Sleep -> R.drawable.sellby_companion_character_sleep
}

/**
 * The top block of the tutorial screen: the mascot in a round white-ringed avatar next to a white
 * speech bubble. The text is "typed" out letter by letter, but the not-yet-revealed part is laid out
 * (just transparent) so the bubble never changes size mid-way; tapping the text reveals everything at
 * once, and a screen reader gets the full text from the start.
 *
 * The button row is always reserved (even when it is empty) so the bubble - and with it everything
 * below - doesn't jump between steps that have buttons and steps that don't.
 */
@Composable
internal fun GuideRow(
    controller: TutorialController,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 12.dp,
) {
    val step = controller.currentStep
    val text = step.guide.text
    var shown by remember(step.id) { mutableIntStateOf(0) }
    LaunchedEffect(step.id) {
        shown = 0
        while (shown < text.length) {
            delay(16)
            shown++
        }
    }
    val done = shown >= text.length
    val gate = step.gate

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.Bottom,
    ) {
        Avatar(step.guide.mood)
        Spacer(Modifier.width(8.dp))
        Surface(
            // FIXED height: the bubble must never change size between steps (the card above would
            // jump). Guide texts are therefore kept short (TutorialScriptTest enforces a length cap).
            modifier = Modifier
                .weight(1f)
                .height(GUIDE_BUBBLE_HEIGHT),
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            shadowElevation = 4.dp,
        ) {
            // Surface passes its min height down, so SpaceBetween keeps the buttons at the bubble's bottom.
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                val annotated = buildAnnotatedString {
                    append(text.take(shown))
                    withStyle(SpanStyle(color = Color.Transparent)) { append(text.drop(shown)) }
                }
                Text(
                    text = annotated,
                    color = SellbyColors.TextDark,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            shown = text.length
                            controller.finishTyping()
                        },
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!controller.isFirst) {
                        GuideTextButton("‹ Kembali", SellbyColors.TextMuted) { controller.back() }
                    }
                    if (gate is Gate.Tap) {
                        Text(
                            "Ketuk yang bersinar",
                            color = SellbyColors.TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    val showAssist = controller.assistVisible && (gate is Gate.Tap || !controller.canAdvance)
                    val showNext = gate is Gate.Next && controller.canAdvance && done
                    if (showAssist) GuideTextButton("Bantu aku", SellbyColors.TealPrimary) { controller.assist() }
                    if (showNext) {
                        GuidePill(if (controller.isLast) "Selesai" else "Lanjut") {
                            if (controller.isLast) onFinish() else controller.next()
                        }
                    }
                }
            }
        }
    }
}

/** Round mascot avatar with a white ring, like a chat profile picture. */
@Composable
private fun Avatar(mood: Mood) {
    Box(
        Modifier
            .size(44.dp)
            .background(Color.White, CircleShape)
            .padding(2.5.dp)
            .clip(CircleShape)
            .background(Color(0xFFE6F4F9)),
    ) {
        Image(
            painter = painterResource(mascotRes(mood)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun GuidePill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .background(SellbyColors.TealDark, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuideTextButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
