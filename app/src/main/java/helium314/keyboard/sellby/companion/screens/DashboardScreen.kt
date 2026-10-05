// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.PREF_ONBOARDING_COMPLETED
import helium314.keyboard.sellby.companion.PREF_STORE_NAME
import helium314.keyboard.sellby.companion.SellbyLinks
import helium314.keyboard.sellby.companion.review.ReviewPrompter
import helium314.keyboard.sellby.companion.theme.SellbyColors
import helium314.keyboard.sellby.data.SellbyDatabase
import helium314.keyboard.sellby.data.dao.PeriodStats
import helium314.keyboard.sellby.util.CurrencyFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

private const val DAY_MILLIS = 24L * 60 * 60 * 1000L
private val MONTH_ABBR = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Ags", "Sep", "Okt", "Nov", "Des")
private val MONTH_FULL = arrayOf(
    "Januari", "Februari", "Maret", "April", "Mei", "Juni",
    "Juli", "Agustus", "September", "Oktober", "November", "Desember",
)
private val WEEKDAY_LETTERS = listOf("S", "S", "R", "K", "J", "S", "M") // Senin..Minggu
private const val BASE_PAGE_INDEX = 1200
private const val PAGE_COUNT = 2400
private val GREETING_CARD_HEIGHT = 123.dp

/** Ported field-for-field from dashboard_page.dart (acuan tunggal, re-read in full this round -
 *  not from a summary) after the user flagged the first Round-1 pass as not matching closely
 *  enough. Mechanism swaps that stay (disclosed, not visual deviations):
 *  MethodChannel('.../system_actions') -> UncachedInputMethodManagerUtils + ACTION_INPUT_METHOD_
 *  SETTINGS/showInputMethodPicker() (same as WelcomeWizard.kt already uses); the floating blurred
 *  test-input popup -> a plain inline field (no simulated/fake keyboard code). Two Flutter
 *  `Spacer()` flex-gaps (between status-strip/test-pill and between banner/support-card) become
 *  fixed-height spacers here - Compose's `verticalScroll` Column doesn't get the same "fill
 *  remaining sliver space" behavior `SliverFillRemaining` gives Flutter for free, and replicating
 *  it exactly wasn't worth the added complexity for a rarely-visible edge case (very tall
 *  screens/short content). Everything else - colors, radii, paddings, copy, and the whole custom
 *  date-range dialog's behavior (presets, swipeable month grid, month/year dropdown overlays,
 *  range-selection logic) - is a direct 1:1 port. "Detail Transaksi"/"Detail Pelanggan" buttons
 *  are NEW (not in the Flutter reference), added per the user's own mockup - see [DetailButtonsRow]. */
@Composable
fun DashboardScreen(onExit: () -> Unit, onDataReset: () -> Unit, onReplayTutorial: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onExit() }
    var showTestKeyboardPopup by remember { mutableStateOf(false) }
    // Takes priority over the plain BackHandler above while the popup is open - Compose dispatches
    // back events to the most recently composed *enabled* handler first, so this closes just the
    // popup instead of exiting the whole app.
    BackHandler(enabled = showTestKeyboardPopup) { showTestKeyboardPopup = false }

    var storeName by remember { mutableStateOf(context.prefs().getString(PREF_STORE_NAME, "")?.ifEmpty { "...." } ?: "....") }
    // The keyboard's Settings panel and this screen run in the same process but are two separate
    // UIs writing/reading the same SharedPreferences key - a plain `remember` only ever reads it
    // once at first composition, so a name change made from the in-keyboard panel would never show
    // up here until the whole Activity restarted. Listening for the actual pref change instead makes
    // the greeting card update the moment it's saved, without needing to navigate away and back.
    DisposableEffect(context) {
        val prefs = context.prefs()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                PREF_STORE_NAME -> storeName = sp.getString(PREF_STORE_NAME, "")?.ifEmpty { "...." } ?: "...."
                PREF_ONBOARDING_COMPLETED -> if (!sp.getBoolean(PREF_ONBOARDING_COMPLETED, false)) onDataReset()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // startMillis/endMillis are ALWAYS date-only (midnight) values - exactly like the Dart
    // source's own _startDate/_endDate, which are plain DateTime(year,month,day) throughout the
    // parent widget's lifetime. Full-day boundaries (00:00:00.000 / 23:59:59.999) are computed
    // ONLY as local scratch values at the query call site below, mirroring _loadDashboardData's
    // startBoundary/endBoundary - never stored back. Keeping them date-only here is what lets the
    // date-range dialog re-open with a range that matches its own day-cell comparisons exactly
    // (the dialog's rangeStart/rangeEnd are date-only too) - storing an end-of-day timestamp here
    // was the earlier attempt, and it broke the dialog's own boundary-highlight on reopen since no
    // day cell's midnight timestamp could ever equal an end-of-day timestamp again.
    var startMillis by remember { mutableStateOf(startOfDay(System.currentTimeMillis()) - 6 * DAY_MILLIS) }
    var endMillis by remember { mutableStateOf(startOfDay(System.currentTimeMillis())) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showFaq by remember { mutableStateOf(false) }
    var showDataTransaksi by remember { mutableStateOf(false) }
    var showDataPelanggan by remember { mutableStateOf(false) }
    var testKeyboardText by remember { mutableStateOf("") }

    // Trial day 3: ask for a Play Store review once (no-op outside that window, and on any build
    // that isn't installed from Google Play - see ReviewPrompter).
    LaunchedEffect(Unit) { ReviewPrompter.maybePrompt(context) }

    val db = remember { SellbyDatabase.getInstance(context) }
    var stats by remember { mutableStateOf<PeriodStats?>(null) }
    var prevStats by remember { mutableStateOf<PeriodStats?>(null) }
    LaunchedEffect(startMillis, endMillis) {
        val startBoundary = startOfDay(startMillis)
        val endBoundary = endOfDay(endMillis)
        // Day count via integer division (matches Dart's `.inDays + 1`), not a raw ms subtraction -
        // e.g. a single selected day spans endBoundary-startBoundary=86399999ms, which is 0 whole
        // days plus a remainder, +1 = 1 day; a 3-day range (26-28) spans 259199999ms = 2 whole days
        // plus a remainder, +1 = 3 days. Both match the number of calendar days actually selected.
        val rangeDays = (endBoundary - startBoundary) / DAY_MILLIS + 1
        val prevStart = startBoundary - rangeDays * DAY_MILLIS
        val prevEnd = startBoundary - 1
        launch { db.orderDao().getPeriodStats(startBoundary, endBoundary).collect { stats = it } }
        launch { db.orderDao().getPeriodStats(prevStart, prevEnd).collect { prevStats = it } }
    }

    val curStats = stats
    val hasData = curStats != null && (curStats.pendingCount > 0 || curStats.lunasCount > 0 ||
        curStats.prosesCount > 0 || curStats.selesaiCount > 0 || curStats.totalRevenue > 0.0)
    val prevOmzet = prevStats?.totalRevenue ?: 0.0
    val curOmzet = curStats?.totalRevenue ?: 0.0
    val growth = when {
        prevOmzet > 0.0 -> (curOmzet - prevOmzet) / prevOmzet * 100.0
        curOmzet > 0.0 -> 100.0
        else -> 0.0
    }

    // Structural fix for the "scroll content bleeds through the sticky header" bug: rather than
    // overlaying a fixed header on top of a full-height scrollable Column and trying to measure +
    // reserve matching space with a Spacer (which depends on state updating in sync with layout -
    // a one-frame lag there is exactly what let scrolled content peek through), the sticky header
    // and the scrollable area are now just two SEQUENTIAL children of a plain Column. Compose
    // guarantees sequential Column children never overlap, so there is no seam to desync in the
    // first place - the weighted scrollable child's top edge is always exactly the sticky header's
    // bottom edge, by construction.
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(SellbyColors.White)) {
        // Sticky header: teal strip + greeting card overlap deliberately (card floats over the top
        // of the strip, per the original design), the pill row below is opaque so nothing can show
        // through its own gaps.
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(statusBarHeight + 70.dp).background(SellbyColors.TealPrimary))
            Column {
                Spacer(Modifier.height(statusBarHeight + 10.dp))
                GreetingCard(storeName, hasData, growth)
                Box(Modifier.fillMaxWidth().background(SellbyColors.White)) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        DateRangePillButton(dateRangeLabel(startMillis, endMillis)) { showDatePicker = true }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        // Scrollable rest of the dashboard - takes all remaining vertical space.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            StatCardsRow(curStats, growth, hasData, dateRangeLabel(startMillis, endMillis))
            Spacer(Modifier.height(10.dp))
            StatusStrip(curStats)
            Spacer(Modifier.height(14.dp))
            DetailButtonsRow(
                onDetailTransaksi = { showDataTransaksi = true },
                onDetailPelanggan = { showDataPelanggan = true },
            )
            Spacer(Modifier.height(24.dp)) // fixed stand-in for Flutter's flexible Spacer()
            TestKeyboardField(testKeyboardText) { showTestKeyboardPopup = true }
            Spacer(Modifier.height(10.dp))
            Text(
                "Pasang Keyboard kamu sekarang!",
                color = Color(0xFF0F172A),
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            KeyboardActivationSection()
            Spacer(Modifier.height(24.dp)) // fixed stand-in for Flutter's flexible Spacer()
            SupportFaqCard(
                onFaqClick = { showFaq = true },
                onTutorialClick = onReplayTutorial,
                onSupportClick = { contactSupport(context) },
                onAboutClick = onOpenAbout,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // In-tree (not a Dialog/ModalBottomSheet) so it has no entrance animation of its own to race
    // against the keyboard's - its only motion comes from `Modifier.imePadding()`, which tracks the
    // exact same live IME inset value driving the real keyboard's own rise, so the two move in
    // lockstep with zero perceptible lag between them.
    if (showTestKeyboardPopup) {
        TestKeyboardOverlay(
            text = testKeyboardText,
            onTextChange = { testKeyboardText = it },
            onDismiss = { showTestKeyboardPopup = false },
        )
    }
    }

    if (showFaq) FaqBottomSheet(onDismiss = { showFaq = false })
    if (showDataTransaksi) DataTransaksiDialog(onDismiss = { showDataTransaksi = false })
    if (showDataPelanggan) DataPelangganDialog(onDismiss = { showDataPelanggan = false })
    if (showDatePicker) {
        CustomDateRangeDialog(
            initialStart = startMillis,
            initialEnd = endMillis,
            onDismiss = { showDatePicker = false },
            onApply = { s, e -> startMillis = s; endMillis = e; showDatePicker = false },
        )
    }
}

@Composable
private fun GreetingCard(storeName: String, hasData: Boolean, growth: Double) {
    val (title, titleSize) = greetingTitle(storeName)
    val subtitle: String
    val characterRes: Int
    when {
        !hasData -> {
            subtitle = "Ayo kita mulai, biar\ndashboard kamu terisi!"
            characterRes = R.drawable.sellby_companion_character_flat
        }
        growth > 0 -> {
            subtitle = "Wah hari ini jualannya laris\nbanget, ayo lebih semangat!"
            characterRes = R.drawable.sellby_companion_character_happy
        }
        growth < 0 -> {
            subtitle = "Lagi sepi nih kayaknya,\nayo lebih semangat lagi!"
            characterRes = R.drawable.sellby_companion_character_sad
        }
        else -> {
            subtitle = "Omset kamu diem ditempat nih,\nyuk cek yang belum ke-followup!"
            characterRes = R.drawable.sellby_companion_character_sleep
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(GREETING_CARD_HEIGHT)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.horizontalGradient(listOf(SellbyColors.GreetingGradientStart, SellbyColors.GreetingGradientEnd))),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 18.dp, end = 105.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, color = SellbyColors.White, fontSize = titleSize.sp, fontWeight = FontWeight.ExtraBold, lineHeight = (titleSize * 1.15).sp, maxLines = 3)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = SellbyColors.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 16.sp)
        }
        Image(
            painter = painterResource(characterRes),
            contentDescription = null,
            contentScale = ContentScale.FillHeight,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).height(114.dp),
        )
    }
}

private fun greetingTitle(storeName: String): Pair<String, Float> {
    val trimmed = storeName.trim().ifEmpty { "...." }
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "Hallo\n$trimmed!" to 16.5f
        trimmed.length >= 11 -> "Hallo $trimmed!" to 15.5f
        else -> "Hallo $trimmed!" to 20f
    }
}

@Composable
private fun DateRangePillButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(SellbyColors.White)
            .border(1.4.dp, SellbyColors.TealPrimary, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = SellbyColors.TealPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Image(
                painter = painterResource(R.drawable.ic_dashboard_calendar),
                contentDescription = null,
                colorFilter = ColorFilter.tint(SellbyColors.TealPrimary),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StatCardsRow(stats: PeriodStats?, growth: Double, hasData: Boolean, dateRangeLabel: String) {
    // Height is derived from Penjualan's OWN computed width (not a magic-number percentage cut) -
    // that makes it exactly square by construction, on any screen width, and Total Omzet (wider,
    // same height) automatically reads as a landscape rectangle, per the request.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        val gap = 10.dp
        val penjualanSize = (maxWidth - gap) * 0.42f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        // Penjualan - re-matched to the user's own redesign mockup: bigger dark-navy icon badge,
        // growth badge moved to the top-right (was bottom-right), much larger count number.
        Box(
            Modifier.weight(0.42f).height(penjualanSize).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(SellbyColors.TealPrimary, SellbyColors.PenjualanCard)))
                .padding(14.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.size(26.dp).background(SellbyColors.OmzetCard, CircleShape), contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(R.drawable.ic_dashboard_cart),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(SellbyColors.White),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    GrowthBadge(growth, hasData)
                }
                // Pinned to the box's own bottom-left corner - a plain Column relying on the outer
                // SpaceBetween arrangement only sits flush at the bottom when its own content plus
                // the row above it happens to add up to exactly the available height; anchoring it
                // directly guarantees it always sits flush against the card's bottom-left corner
                // (with just the card's own 14dp padding as margin), regardless of that math.
                // Font sizes on all 3 lines are matched to Total Omzet's corresponding line (below) -
                // since both blocks are bottom-anchored, equal per-row font size/line-height is what
                // makes the two cards' rows actually line up horizontally with each other.
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text("Penjualan", color = SellbyColors.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        formatCompactCount(stats?.totalSales ?: 0), color = SellbyColors.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(y = (-4).dp),
                    )
                    Text("Pesanan", color = SellbyColors.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.offset(y = (-7).dp))
                }
            }
        }
        // Total Omzet - growth badge + "dari periode lalu" moved to top-right (was bottom-right),
        // and the applied date range is now shown at the bottom, per the mockup.
        Box(
            Modifier.weight(0.58f).height(penjualanSize).clip(RoundedCornerShape(20.dp))
                .background(SellbyColors.OmzetCard)
                .padding(14.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopStart), horizontalArrangement = Arrangement.End) {
                    Column(horizontalAlignment = Alignment.End) {
                        GrowthBadge(growth, hasData)
                        Text(
                            "dari periode lalu", color = SellbyColors.White.copy(alpha = 0.6f), fontSize = 9.5.sp,
                            modifier = Modifier.offset(y = (-2).dp),
                        )
                    }
                }
                // Pinned to the box's bottom-left corner (was a plain Column with a manual 14dp
                // spacer above it, sitting at the box's default top). That layout's total content -
                // badge block + spacer + label + amount + date range - could add up to more than the
                // fixed square height leaves room for, and since nothing anchored it, the overflow
                // silently got clipped off the BOTTOM by the card's rounded-corner clip - which is
                // exactly why dateRangeLabel (the very last, bottom-most line) was disappearing.
                // Anchoring this block to the bottom instead means dateRangeLabel is always the last
                // thing to get clipped, not the first, and the block sits flush at the corner with no
                // extra margin beyond the card's own 14dp padding.
                var omzetFontSize by remember(stats?.totalRevenue) { mutableStateOf(20.sp) }
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text("Total Omzet", color = SellbyColors.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    // Auto-shrinks on overflow (e.g. Rp 1.000.000.000+) instead of a fixed size that
                    // would either clip or force a wrap for large revenue figures.
                    Text(
                        CurrencyFormat.rupiah(stats?.totalRevenue ?: 0.0),
                        color = SellbyColors.White,
                        fontSize = omzetFontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.offset(y = (-3).dp),
                        onTextLayout = { result -> if (result.didOverflowWidth && omzetFontSize > 14.sp) omzetFontSize *= 0.9f },
                    )
                    Text(dateRangeLabel, color = SellbyColors.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.offset(y = (-6).dp))
                }
            }
        }
        }
    }
}

@Composable
private fun GrowthBadge(growth: Double, hasData: Boolean) {
    val (bg, fg) = when {
        !hasData || growth == 0.0 -> SellbyColors.White to SellbyColors.TextDark
        growth > 0 -> SellbyColors.GrowthPositiveBg to SellbyColors.GrowthPositiveFg
        else -> SellbyColors.GrowthNegativeBg to SellbyColors.GrowthNegativeFg
    }
    val arrow = if (!hasData || growth == 0.0) "–" else if (growth > 0) "↑" else "↓"
    val text = if (!hasData || growth == 0.0) "0%" else "${round(abs(growth)).toInt()}%"
    Row(
        // Percent-based (stadium/capsule) rounding, not a fixed dp radius - guarantees a perfectly
        // round-sided pill regardless of content height, instead of looking like a rounded
        // rectangle if it ever gets slightly taller (a fixed 10dp radius only reads as a full pill
        // at one exact height). maxLines=1 + softWrap=false on both Texts below is the actual fix
        // for the reported "100%" wrapping onto 3 lines - without them, tight available width lets
        // the text wrap instead of just staying on one line.
        Modifier.background(bg, RoundedCornerShape(percent = 50)).padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The previous padding-only shrink was barely visible because Android's default text
        // layout reserves extra ascent/descent space around a line even when the box padding
        // around it is tiny - `includeFontPadding = false` + a `lineHeight` pinned to the font
        // size itself removes that reserved space, which is what actually controls how tall this
        // pill looks (the box padding above was never the dominant factor).
        val badgeTextStyle = LocalTextStyle.current.copy(
            lineHeight = 9.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
        )
        Text(arrow, color = fg, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false, style = badgeTextStyle)
        Spacer(Modifier.width(1.dp))
        Text(text, color = fg, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false, style = badgeTextStyle)
    }
}

@Composable
private fun StatusStrip(stats: PeriodStats?) {
    val entries = listOf(
        "Pending" to (stats?.pendingCount ?: 0),
        "Lunas" to (stats?.lunasCount ?: 0),
        "Proses" to (stats?.prosesCount ?: 0),
        "Selesai" to (stats?.selesaiCount ?: 0),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(SellbyColors.StatusStripStart, SellbyColors.StatusStripEnd)))
            .padding(vertical = 11.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        entries.forEachIndexed { index, (label, count) ->
            if (index > 0) Box(Modifier.width(1.dp).height(42.dp).background(SellbyColors.White.copy(alpha = 0.25f)))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(label, color = SellbyColors.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(formatCompactCount(count), color = SellbyColors.White, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("Pesanan", color = SellbyColors.White.copy(alpha = 0.7f), fontSize = 9.sp)
            }
        }
    }
}

/** New, not in the Flutter reference - added per the user's own dashboard mockup. Each button opens
 *  a popup (see [DataTransaksiDialog]/[DataPelangganDialog]) listing all orders/customers with
 *  search+filter and an Excel export - not a "coming soon" placeholder anymore. */
@Composable
private fun DetailButtonsRow(onDetailTransaksi: () -> Unit, onDetailPelanggan: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailPillButton("Data Transaksi", Modifier.weight(1f), onDetailTransaksi)
        DetailPillButton("Data Pelanggan", Modifier.weight(1f), onDetailPelanggan)
    }
}

@Composable
private fun DetailPillButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = SellbyColors.TealPrimary, modifier = modifier) {
        Text(
            text,
            color = SellbyColors.White,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 11.dp).fillMaxWidth(),
        )
    }
}

// Ported from dashboard_page.dart's own two-piece design: the pill visible in the dashboard body
// is just a trigger showing a preview of the typed text (Flutter: `_openTestInputPopup`), the real
// editable field only exists inside the popup below - not an inline TextField like before.
@Composable
private fun TestKeyboardField(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(SellbyColors.SurfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.ifEmpty { "Ketik sesuatu untuk menguji keyboard" },
            color = if (text.isEmpty()) SellbyColors.TextHint else SellbyColors.TextDark,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// Ported from dashboard_page.dart's `_buildInputBarCard` + the `_isTestPopupOpen` overlay branch
// for the "Sellby keyboard active" case (bottom: 0, straight above the real keyboard) - Flutter's
// "keyboard not active, floats above the system keyboard like Gboard" branch and its fake in-app
// SellbyKeyboard widget don't apply here since this is a real Android IME, not a simulation: the
// real system keyboard (Sellby's, once active, or whatever's currently selected) shows up on its own
// the moment the field below gets focus. This is composed directly in the screen's own tree (not a
// Dialog/ModalBottomSheet) specifically so it has no entrance animation of its own - a bottom sheet's
// own slide-up spring was visibly slower than the real keyboard's rise, so the card felt like it was
// lagging behind it. Rendered in-tree, the card just appears the instant `showTestKeyboardPopup`
// flips true, and its only motion afterwards comes from `Modifier.imePadding()`, which tracks the
// exact same live IME inset animating the real keyboard - so the two move in lockstep.
@Composable
private fun TestKeyboardOverlay(text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val maxCardHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
    ) {
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 52.dp, max = maxCardHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(SellbyColors.White)
                // Absorbs taps landing on the card so they don't fall through to the scrim behind it
                // and dismiss the popup - only the blank area *outside* the card should do that.
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {})
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Grows with the text (no maxLines cap) like Flutter's `maxLines: null`, and scrolls
            // internally once it hits maxCardHeight instead of pushing the card off-screen.
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = TextStyle(fontSize = 14.sp, color = SellbyColors.TextDark, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(SellbyColors.TealPrimary),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text("Ketik sesuatu untuk menguji keyboard", color = SellbyColors.TextHint, fontSize = 13.sp)
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).focusRequester(focusRequester),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(SellbyColors.SurfaceMuted)
                    .clickable { onTextChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = SellbyColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(SellbyColors.TealPrimary)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("Selesai", color = SellbyColors.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun KeyboardActivationSection() {
    val context = LocalContext.current
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    val lifecycleOwner = LocalLifecycleOwner.current

    // isThisImeCurrent (not isThisImeEnabled) is the real "is Sellby the active default IME right
    // now" signal - isThisImeEnabled just means it's been added to the system's enabled-IME list,
    // which stays true even after the user switches to a completely different default keyboard.
    //
    // It is a round trip to the system's input method service (getInputMethodList), and that service
    // is busiest exactly when this screen is opened - right after the user switched keyboards. Asked
    // on the main thread (as it used to be, while this screen was being composed) a slow answer froze
    // the whole app on its previous frame, which is the splash. So it is asked from a background
    // thread, and the banner simply appears once the answer is in.
    suspend fun isSellbyCurrent(): Boolean =
        withContext(Dispatchers.IO) { UncachedInputMethodManagerUtils.isThisImeCurrent(context, imm) }

    var isActive by remember { mutableStateOf<Boolean?>(null) }
    var showWizard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // IME activation has no SharedPreferences-change equivalent to listen to (unlike storeName
    // above), so re-check on every ON_RESUME instead - covers return paths nothing else here can:
    // system Settings or the IME picker dismissed/backgrounded via Recents, switched via another
    // app's long-press-spacebar switcher, etc. A new observer is replayed the current lifecycle
    // state, so this is also the first check, right after the screen is shown.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { isActive = isSellbyCurrent() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val active = isActive
    if (active != null) {
        KeyboardActivationBanner(active) {
            if (!active) {
                showWizard = true
            } else {
                imm.showInputMethodPicker()
                scope.launch {
                    repeat(40) {
                        delay(100)
                        val stillActive = isSellbyCurrent()
                        if (stillActive != isActive) { isActive = stillActive; return@launch }
                    }
                }
            }
        }
    }

    if (showWizard) {
        KeyboardSetupWizardDialog(
            onDismiss = {
                showWizard = false
                scope.launch { isActive = isSellbyCurrent() }
            },
        )
    }
}

@Composable
private fun KeyboardActivationBanner(isActive: Boolean, onToggleClick: () -> Unit) {
    val bg = if (isActive) SellbyColors.KeyboardActiveGreen else SellbyColors.KeyboardInactiveRed
    val btnBg = if (isActive) SellbyColors.KeyboardActiveGreenDark else SellbyColors.KeyboardInactiveRedDark
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(36.dp)).background(bg)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(44.dp).height(32.dp).background(SellbyColors.White, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_dashboard_keyboard),
                contentDescription = null,
                colorFilter = ColorFilter.tint(bg),
                modifier = Modifier.width(26.dp).height(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (isActive) "Keyboard Aktif" else "Keyboard Belum Aktif",
            color = SellbyColors.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Surface(onClick = onToggleClick, shape = RoundedCornerShape(20.dp), color = btnBg) {
            Text(
                if (isActive) "Nonaktif" else "Aktifkan",
                color = SellbyColors.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SupportFaqCard(onFaqClick: () -> Unit, onTutorialClick: () -> Unit, onSupportClick: () -> Unit, onAboutClick: () -> Unit) {
    // Left: the title and three close-together links of equal height (labels line up in one column, the
    // icons sit centred in a fixed-width slot); right: the explanation, its last line level with the
    // bottom link ("Hubungi Dukungan") - hence the Bottom alignment.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(22.dp))
            .background(SellbyColors.SupportCardBg).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Text("Support & FAQs", color = SellbyColors.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            SupportLink("?", "Cari FAQs", onFaqClick)
            SupportLink("▶", "Lihat Tutorial", onTutorialClick, iconSize = 9.sp)
            SupportLink("✉", "Hubungi Dukungan", onSupportClick)
            SupportLink("i", "Tentang & Lisensi", onAboutClick)
        }
        Spacer(Modifier.width(22.dp))
        Text(
            "Semua settingan berada di keyboard, dashboard ini hanya untuk menampilkan summary dan juga aktif/nonaktifkan keyboard",
            color = SellbyColors.White.copy(alpha = 0.7f),
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            // The link beside it centres its 11.5sp label in a 28dp row; this bottom padding puts the
            // paragraph's last line on that same baseline.
            modifier = Modifier.weight(1f).padding(bottom = 6.dp),
        )
    }
}

/** One link of the support card: a fixed-width icon slot + the label, a compact 28dp tap row. */
@Composable
private fun SupportLink(icon: String, label: String, onClick: () -> Unit, iconSize: TextUnit = 12.sp) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            Text(icon, color = SellbyColors.TealPrimary, fontSize = iconSize, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = SellbyColors.TealPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FaqBottomSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text("Pertanyaan Umum (FAQs)", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = SellbyColors.TextDark)
            Spacer(Modifier.height(12.dp))
            Text(
                "1. Bagaimana cara mengaktifkan keyboard Sellby?\nTekan tombol Aktifkan di bagian atas, lalu centang Sellby Keyboard di pengaturan bahasa & masukan HP Anda.\n\n" +
                    "2. Di mana mengatur template invoice dan produk?\nBuka keyboard Sellby di aplikasi chat apa saja, lalu tekan ikon Pengaturan di bar atas keyboard.\n\n" +
                    "3. Bagaimana setelah masa coba 3 hari habis?\nFitur panel dibuka dengan sekali bayar lewat Google Play, tanpa langganan. Kalau sebelumnya sudah membeli (misalnya setelah ganti HP atau pasang ulang), buka halaman pembelian lalu ketuk Pulihkan pembelian.\n\n" +
                    "4. Apakah teks yang saya ketik dikirim ke server?\nTidak. Teks diproses hanya di HP kamu, dan data toko disimpan di HP kamu.",
                fontSize = 13.sp, color = Color(0xFF475569), lineHeight = 18.85.sp,
            )
        }
    }
}

private fun contactSupport(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${SellbyLinks.SUPPORT_EMAIL}")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Bantuan Aplikasi Sellby Keyboard")
            putExtra(Intent.EXTRA_TEXT, "Halo Tim Support Sellby,\n\nSaya butuh bantuan terkait:\n")
        }
        context.startActivity(intent)
    } catch (_: Exception) { /* no mail client resolvable - fail silently, matches ChannelMessenger's best-effort pattern */ }
}

private fun formatCompactCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fm".format(value / 1_000_000.0)
    value >= 1000 -> "%.1fk".format(value / 1000.0)
    else -> value.toString()
}

// ============================================================================================
// Custom date-range dialog - 1:1 port of dashboard_page.dart's `_CustomDateRangeDialog` +
// `_CalendarMonthGrid` + `_WeekdayLabel`: preset chips, a swipeable month grid (HorizontalPager
// standing in for Flutter's PageView.builder with the same "base page index" trick so both
// directions can page "infinitely"), month/year dropdowns rendered as positioned overlays (not
// Compose's own DropdownMenu, to match the exact overlap-the-calendar look in the source), and
// the same range-selection/highlight logic. Colors are the exact hex values from the Dart source.
// ============================================================================================

private data class CalendarDay(val millis: Long, val day: Int, val inMonth: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeDialog(initialStart: Long, initialEnd: Long, onDismiss: () -> Unit, onApply: (Long, Long) -> Unit) {
    val baseCal = remember { Calendar.getInstance().apply { timeInMillis = initialEnd } }
    val baseYear = remember { baseCal.get(Calendar.YEAR) }
    val baseMonth = remember { baseCal.get(Calendar.MONTH) } // 0-11, fixed anchor for the pager

    var rangeStart by remember { mutableStateOf<Long?>(initialStart) }
    var rangeEnd by remember { mutableStateOf<Long?>(initialEnd) }
    var selectedYear by remember { mutableStateOf(baseYear) }
    var selectedMonth by remember { mutableStateOf(baseMonth) }
    var activePreset by remember { mutableStateOf(detectPresetIndex(initialStart, initialEnd)) }
    var isMonthOpen by remember { mutableStateOf(false) }
    var isYearOpen by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = BASE_PAGE_INDEX) { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    var monthYearRowBottomPx by remember { mutableStateOf(0) }

    fun monthDateForPage(page: Int): Pair<Int, Int> { // (year, month0based)
        val offset = page - BASE_PAGE_INDEX
        val c = Calendar.getInstance().apply { clear(); set(baseYear, baseMonth, 1); add(Calendar.MONTH, offset) }
        return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
    }

    LaunchedEffect(pagerState.currentPage) {
        val (y, m) = monthDateForPage(pagerState.currentPage)
        selectedYear = y; selectedMonth = m
        isMonthOpen = false; isYearOpen = false
    }

    fun jumpTo(year: Int, month: Int) {
        val offset = (year - baseYear) * 12 + (month - baseMonth)
        scope.launch { pagerState.animateScrollToPage(BASE_PAGE_INDEX + offset) }
    }

    fun applyPreset(index: Int) {
        val today = System.currentTimeMillis()
        val todayStart = startOfDay(today)
        when (index) {
            0 -> { rangeStart = todayStart; rangeEnd = todayStart }
            1 -> { rangeStart = todayStart - 6 * DAY_MILLIS; rangeEnd = todayStart }
            2 -> { rangeStart = todayStart - 29 * DAY_MILLIS; rangeEnd = todayStart }
            3 -> { rangeStart = epoch2020(); rangeEnd = todayStart }
        }
        activePreset = index
        rangeEnd?.let { e -> val c = Calendar.getInstance().apply { timeInMillis = e }; jumpTo(c.get(Calendar.YEAR), c.get(Calendar.MONTH)) }
    }

    fun onDateSelected(dateMillis: Long) {
        activePreset = 4
        val s = rangeStart; val e = rangeEnd
        if (s == null || e != null) {
            rangeStart = dateMillis; rangeEnd = null
        } else {
            if (dateMillis < s) rangeStart = dateMillis else rangeEnd = dateMillis
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = SellbyColors.White, shadowElevation = 10.dp) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(20.dp)) {
                    // Square day cells sized from the actually-available width (matches the Dart
                    // grid delegate's default childAspectRatio:1.0 - a fixed 34dp height on a much
                    // wider weighted cell was the bug that made the light-blue range fill stick out
                    // past the round day marker). gridHeight (5 rows + 4x3dp gaps) doubles as the
                    // bottom limit for the month/year dropdown popups below, per the fix for #5.
                    val cellSize = maxWidth / 7
                    val gridHeight = cellSize * 5 + 12.dp

                    // Custom 2-role Layout instead of a plain Box: slot 0 (the real dialog content)
                    // is the ONLY thing that determines this container's reported size - the
                    // dropdown overlay slots are measured/placed too (so they draw ON TOP of the
                    // weekday header/calendar grid below, same paint-order rule as a plain Box)
                    // but their size is never fed back into `layout(...)`. A plain Box couldn't do
                    // this (any child, positioned or not, inflates a Box's measured size, which is
                    // exactly what made the whole dialog balloon downward when a dropdown opened -
                    // that was tried first and reverted). A real `Popup` was tried next to solve
                    // that the "proper" way, but Popup's position calculation turned out unreliable
                    // once nested inside this `Dialog` (a documented rough edge - Popup and Dialog
                    // use different window-attachment mechanisms) - it rendered miles away from its
                    // anchor. This Layout keeps everything in the Dialog's own window, so there's no
                    // cross-window coordinate math to get wrong.
                    Layout(
                        content = {
                            Column {
                                PresetChipsRow(activePreset, ::applyPreset)
                                Spacer(Modifier.height(14.dp))
                                Row(Modifier.onGloballyPositioned { coords ->
                                    monthYearRowBottomPx = coords.positionInParent().y.roundToInt() + coords.size.height
                                }) {
                                    MonthYearPill(MONTH_FULL[selectedMonth], Modifier.weight(1f)) { isMonthOpen = !isMonthOpen; isYearOpen = false }
                                    Spacer(Modifier.width(12.dp))
                                    MonthYearPill(selectedYear.toString(), Modifier.weight(1f)) { isYearOpen = !isYearOpen; isMonthOpen = false }
                                }
                                Spacer(Modifier.height(18.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                                    WEEKDAY_LETTERS.forEach { letter ->
                                        Box(Modifier.width(cellSize), contentAlignment = Alignment.Center) {
                                            Text(letter, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64748B))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                HorizontalPager(state = pagerState, modifier = Modifier.height(gridHeight)) { page ->
                                    val (y, m) = monthDateForPage(page)
                                    val grid = remember(y, m) { buildMonthGrid35(y, m) }
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        grid.chunked(7).forEach { week ->
                                            Row(Modifier.fillMaxWidth()) {
                                                week.forEach { cell -> DayCell(cell, cellSize, rangeStart, rangeEnd) { onDateSelected(cell.millis) } }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Row {
                                    Surface(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = Color(0xFFE2E8F0)) {
                                        Text(
                                            "Cancel",
                                            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                            textAlign = TextAlign.Center, color = Color(0xFF475569), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                        )
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Surface(
                                        onClick = {
                                            // Pass date-only values straight through (matches
                                            // dashboard_page.dart's DateTimeRange(start,end) - the
                                            // dialog's own rangeStart/rangeEnd are already
                                            // date-only). The caller normalizes to a full-day query
                                            // boundary only where it actually queries the database.
                                            rangeStart?.let { s -> onApply(s, rangeEnd ?: s) }
                                        },
                                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = Color(0xFFBCE3EB),
                                    ) {
                                        Text(
                                            "Apply",
                                            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                            textAlign = TextAlign.Center, color = SellbyColors.TealPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                                        )
                                    }
                                }
                            }
                            if (isMonthOpen) {
                                // Initial index set at state-creation time (not scrolled-to after
                                // the fact via LaunchedEffect) - scrolling post-first-frame drew one
                                // visible frame at Januari before snapping to the real month, which
                                // is exactly the glitch that was reported.
                                val monthListState = rememberLazyListState(initialFirstVisibleItemIndex = selectedMonth)
                                Surface(
                                    shape = RoundedCornerShape(16.dp), color = SellbyColors.White, shadowElevation = 10.dp,
                                    modifier = Modifier.width(140.dp).heightIn(max = gridHeight),
                                ) {
                                    LazyColumn(state = monthListState, modifier = Modifier.padding(vertical = 6.dp)) {
                                        items(MONTH_FULL.size) { idx ->
                                            DropdownRow(MONTH_FULL[idx], idx == selectedMonth, 12.5.sp) {
                                                selectedMonth = idx; isMonthOpen = false; jumpTo(selectedYear, idx)
                                            }
                                        }
                                    }
                                }
                            }
                            if (isYearOpen) {
                                val years = remember { (2000..2099).toList() }
                                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (years.indexOf(selectedYear)).coerceAtLeast(0))
                                Surface(
                                    shape = RoundedCornerShape(16.dp), color = SellbyColors.White, shadowElevation = 10.dp,
                                    modifier = Modifier.width(120.dp).heightIn(max = gridHeight),
                                ) {
                                    LazyColumn(state = listState, modifier = Modifier.padding(vertical = 6.dp)) {
                                        items(years) { year ->
                                            DropdownRow(year.toString(), year == selectedYear, 13.sp) {
                                                selectedYear = year; isYearOpen = false; jumpTo(year, selectedMonth)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) { measurables, constraints ->
                        val mainPlaceable = measurables[0].measure(constraints)
                        var idx = 1
                        val loose = Constraints()
                        val monthPlaceable = if (isMonthOpen) measurables[idx++].measure(loose) else null
                        val yearPlaceable = if (isYearOpen) measurables[idx++].measure(loose) else null
                        layout(mainPlaceable.width, mainPlaceable.height) {
                            mainPlaceable.placeRelative(0, 0)
                            monthPlaceable?.placeRelative(0, monthYearRowBottomPx + gapPx)
                            yearPlaceable?.placeRelative(mainPlaceable.width - yearPlaceable.width, monthYearRowBottomPx + gapPx)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChipsRow(activePreset: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Hari Ini", "7 Hari", "30 Hari", "Semua")
    Row {
        labels.forEachIndexed { index, label ->
            val selected = activePreset == index
            Box(
                Modifier
                    .weight(1f)
                    .padding(end = if (index < labels.size - 1) 6.dp else 0.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) SellbyColors.TealPrimary else Color(0xFFF1F5F9))
                    .border(0.8.dp, if (selected) SellbyColors.TealPrimary else Color(0xFFCBD5E1), RoundedCornerShape(16.dp))
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 11.sp, textAlign = TextAlign.Center,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) SellbyColors.White else Color(0xFF475569),
                )
            }
        }
    }
}

@Composable
private fun MonthYearPill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFE2E8F0))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
            Image(
                painter = painterResource(R.drawable.ic_settings_chevron_down_sellby),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFF475569)),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun DropdownRow(text: String, selected: Boolean, fontSize: androidx.compose.ui.unit.TextUnit, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFFBCE3EB) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        fontSize = fontSize,
        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
        color = if (selected) SellbyColors.TealPrimary else Color(0xFF1E293B),
    )
}

@Composable
private fun DayCell(cell: CalendarDay, cellSize: Dp, rangeStart: Long?, rangeEnd: Long?, onClick: () -> Unit) {
    val isStart = rangeStart != null && cell.millis == rangeStart
    val isEnd = rangeEnd != null && cell.millis == rangeEnd
    val isSingleDate = rangeStart != null && (rangeEnd == null || rangeStart == rangeEnd)
    val inRange = rangeStart != null && rangeEnd != null && cell.millis > rangeStart && cell.millis < rangeEnd
    val isBoundary = isStart || isEnd

    Box(Modifier.size(cellSize).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (inRange || isBoundary) {
            val shape = if (isSingleDate) {
                RoundedCornerShape(20.dp)
            } else {
                RoundedCornerShape(
                    topStart = if (isStart) 20.dp else 0.dp, bottomStart = if (isStart) 20.dp else 0.dp,
                    topEnd = if (isEnd) 20.dp else 0.dp, bottomEnd = if (isEnd) 20.dp else 0.dp,
                )
            }
            Box(Modifier.fillMaxSize().background(Color(0xFFBCE3EB), shape))
        }
        Box(
            Modifier.size(32.dp).background(if (isBoundary) SellbyColors.TealPrimary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                cell.day.toString(),
                fontSize = 12.sp,
                fontWeight = if (isBoundary) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (isBoundary) SellbyColors.White else if (cell.inMonth) Color(0xFF1E293B) else Color(0xFFCBD5E1),
            )
        }
    }
}

/** Exactly 35 cells (5 rows x 7 cols), matching `_CalendarMonthGrid`'s fixed `itemCount: 35` -
 *  including its accepted edge case where a 6-row month (rare: a 31-day month starting on
 *  Saturday/Sunday) loses its overflow days from this page's grid, same as the Dart source. */
private fun buildMonthGrid35(year: Int, month: Int): List<CalendarDay> {
    val first = Calendar.getInstance().apply { clear(); set(year, month, 1) }
    val dow = first.get(Calendar.DAY_OF_WEEK) // 1=Sun..7=Sat
    val mondayOffset = (dow + 5) % 7
    val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)

    val cells = mutableListOf<CalendarDay>()
    if (mondayOffset > 0) {
        val prev = (first.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val prevDays = prev.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (i in mondayOffset downTo 1) {
            val d = prevDays - i + 1
            val c = (prev.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
            cells.add(CalendarDay(c.timeInMillis, d, false))
        }
    }
    for (d in 1..daysInMonth) {
        val c = (first.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
        cells.add(CalendarDay(c.timeInMillis, d, true))
    }
    val next = (first.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
    var d = 1
    while (cells.size < 35) {
        val c = (next.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
        cells.add(CalendarDay(c.timeInMillis, d, false))
        d++
    }
    return cells.take(35)
}

private fun detectPresetIndex(start: Long?, end: Long?): Int {
    if (start == null || end == null) return 4
    val today = System.currentTimeMillis()
    val todayStart = startOfDay(today)
    val s = startOfDay(start); val e = startOfDay(end)
    val startYear = Calendar.getInstance().apply { timeInMillis = s }.get(Calendar.YEAR)
    return when {
        startYear <= 2020 -> 3
        s == todayStart && e == todayStart -> 0
        s == todayStart - 6 * DAY_MILLIS && e == todayStart -> 1
        s == todayStart - 29 * DAY_MILLIS && e == todayStart -> 2
        else -> 4
    }
}

private fun startOfDay(millis: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun endOfDay(millis: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
    return c.timeInMillis
}

private fun epoch2020(): Long {
    val c = Calendar.getInstance()
    c.set(2020, 0, 1, 0, 0, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun dateRangeLabel(startMillis: Long, endMillis: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = startMillis
    val startDay = c.get(Calendar.DAY_OF_MONTH); val startMonth = c.get(Calendar.MONTH); val startYear = c.get(Calendar.YEAR)
    c.timeInMillis = endMillis
    val endDay = c.get(Calendar.DAY_OF_MONTH); val endMonth = c.get(Calendar.MONTH); val endYear = c.get(Calendar.YEAR)
    val today = Calendar.getInstance()
    val todayDay = today.get(Calendar.DAY_OF_MONTH); val todayMonth = today.get(Calendar.MONTH); val todayYear = today.get(Calendar.YEAR)
    val sameDay = startDay == endDay && startMonth == endMonth && startYear == endYear
    return when {
        startYear <= 2020 -> "Semua Waktu"
        sameDay && startDay == todayDay && startMonth == todayMonth && startYear == todayYear -> "Hari Ini (${startDay} ${MONTH_ABBR[startMonth]})"
        sameDay -> "$startDay ${MONTH_ABBR[startMonth]} $startYear"
        else -> "$startDay ${MONTH_ABBR[startMonth]} $startYear - $endDay ${MONTH_ABBR[endMonth]} $endYear"
    }
}
