// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.theme.SellbyColors
import helium314.keyboard.sellby.companion.tutorial.PanelTab
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialDecoy
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialTarget

/*
 * Visual copy of the real Sellby toolbar (the 5 tabs + gear) with the dark theme colours of
 * KeyboardTheme.kt THEME_DARK. The tutorial draws no keys - only this bar - so the panels get the room.
 * Mirrors: SellbyToolbarView (tab icons/labels) and its Auto-Text suggestion strip.
 */

/** Colours of one keyboard theme. Dark is the default (matches the tutorial mock-up). */
internal data class KeyboardPalette(
    val background: Color,
    val tile: Color,
    val text: Color,
    val toolbarDivider: Color,
    val tabInactive: Color,
) {
    companion object {
        val Dark = KeyboardPalette(
            background = Color(0xFF17181C),
            tile = Color(0xFF26272C),
            text = Color.White,
            toolbarDivider = Color(0xFF2E3037),
            tabInactive = Color(0xFFCBD5E1),
        )
        val Light = KeyboardPalette(
            background = Color.White,
            tile = Color(0xFFE2E8F0),
            text = Color(0xFF1E293B),
            toolbarDivider = Color(0xFFE2E8F0),
            tabInactive = Color(0xFF475569),
        )
    }
}

/** Toolbar text must not grow with the user's font scale (fixed-size bar). */
@Composable
private fun fixedSp(value: Float): TextUnit = (value / LocalDensity.current.fontScale).sp

private data class TabSpec(val tab: PanelTab, val target: TargetId, val label: String, val icon: Int)

private val Tabs = listOf(
    TabSpec(PanelTab.Invoice, TargetId.TabInvoice, "Invoice", R.drawable.ic_toolbar_invoice_sellby),
    TabSpec(PanelTab.Ongkir, TargetId.TabOngkir, "Ongkir", R.drawable.ic_toolbar_ongkir_sellby),
    TabSpec(PanelTab.Status, TargetId.TabStatus, "Status", R.drawable.ic_toolbar_status_sellby),
    TabSpec(PanelTab.Produk, TargetId.TabProduk, "Produk", R.drawable.ic_toolbar_produk_sellby),
    TabSpec(PanelTab.AutoText, TargetId.TabAutoText, "Auto-Text", R.drawable.ic_toolbar_autotext_sellby),
)

// Proportional to the real bar (which is also smaller than it first looked): 40dp tall, 17dp icons, 8.5sp labels.
private val BarHeight = 40.dp

/** The 5-tab strip, with the (decorative) Settings gear on the right. */
@Composable
internal fun DummyToolbar(
    active: PanelTab?,
    modifier: Modifier = Modifier,
    palette: KeyboardPalette = KeyboardPalette.Dark,
) {
    Column(modifier.fillMaxWidth().background(palette.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.toolbarDivider))
        Row(Modifier.fillMaxWidth().height(BarHeight), verticalAlignment = Alignment.CenterVertically) {
            Tabs.forEach { spec ->
                val color = if (spec.tab == active) SellbyColors.TealPrimary else palette.tabInactive
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .tutorialTarget(spec.target),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(spec.icon), contentDescription = spec.label, tint = color, modifier = Modifier.size(17.dp))
                    Text(spec.label, color = color, fontSize = fixedSp(8.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Box(Modifier.width(1.dp).height(26.dp).background(palette.toolbarDivider))
            Box(
                Modifier.width(44.dp).fillMaxHeight().tutorialDecoy(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_toolbar_settings_sellby),
                    contentDescription = "Settings",
                    tint = palette.tabInactive,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** One suggestion pill of the strip: the message preview, plus whether it is the one the lesson taps. */
internal data class SuggestionPill(val preview: String, val isLessonTarget: Boolean)

/**
 * Replaces the tab bar while the user types something that matches an Auto-Text shortcut: a round
 * dismiss button and one pill per matching Auto-Text (SellbyToolbarView's suggestion strip).
 */
@Composable
internal fun DummySuggestionStrip(
    pills: List<SuggestionPill>,
    modifier: Modifier = Modifier,
    palette: KeyboardPalette = KeyboardPalette.Dark,
) {
    Column(modifier.fillMaxWidth().background(palette.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.toolbarDivider))
        Row(
            Modifier.fillMaxWidth().height(BarHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.size(28.dp).background(SellbyColors.TealPrimary, CircleShape).tutorialDecoy(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_settings_chevron_left_sellby), null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            pills.forEach { pill ->
                Text(
                    pill.preview,
                    color = palette.text,
                    fontSize = fixedSp(11f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 150.dp)
                        .background(palette.tile, RoundedCornerShape(16.dp))
                        .let { if (pill.isLessonTarget) it.tutorialTarget(TargetId.AtSuggestPill) else it.tutorialDecoy() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
