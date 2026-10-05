// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.theme.SellbyColors
import helium314.keyboard.sellby.companion.tutorial.chat.DummyChatHeader
import helium314.keyboard.sellby.companion.tutorial.chat.DummyChatInput
import helium314.keyboard.sellby.companion.tutorial.chat.DummyChatMessages
import helium314.keyboard.sellby.companion.tutorial.chat.LocalChatSound
import helium314.keyboard.sellby.companion.tutorial.chat.rememberChatSound
import helium314.keyboard.sellby.companion.tutorial.guide.GuideRow
import helium314.keyboard.sellby.companion.tutorial.keyboard.DummySuggestionStrip
import helium314.keyboard.sellby.companion.tutorial.keyboard.DummyToolbar
import helium314.keyboard.sellby.companion.tutorial.keyboard.KeyboardPalette
import helium314.keyboard.sellby.companion.tutorial.keyboard.SuggestionPill
import helium314.keyboard.sellby.companion.tutorial.panels.DummyAutoTextPanel
import helium314.keyboard.sellby.companion.tutorial.panels.DummyInvoicePanel
import helium314.keyboard.sellby.companion.tutorial.panels.DummyProdukPanel
import helium314.keyboard.sellby.companion.tutorial.panels.DummyStatusPanel
import helium314.keyboard.sellby.companion.tutorial.panels.PanelShell
import helium314.keyboard.sellby.companion.tutorial.spotlight.LocalPanelScrollHost
import helium314.keyboard.sellby.companion.tutorial.spotlight.LocalTargetRegistry
import helium314.keyboard.sellby.companion.tutorial.spotlight.LocalTutorial
import helium314.keyboard.sellby.companion.tutorial.spotlight.PanelScrollHost
import helium314.keyboard.sellby.companion.tutorial.spotlight.SpotlightMarkers
import helium314.keyboard.sellby.companion.tutorial.spotlight.SpotlightScrim
import helium314.keyboard.sellby.companion.tutorial.spotlight.TargetRegistry
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

enum class TutorialMode {
    /** Shown once after Profil Toko; finishing continues the onboarding. */
    Onboarding,

    /** Opened again from the Dashboard; finishing just goes back. */
    Replay,

    /** One of the four stand-alone lessons opened from the keyboard's Settings; finishing closes the page. */
    Lesson,
}

private val CardShape = RoundedCornerShape(16.dp)

/** Side margin shared by the focus card, the guide row and the dashed line so they line up. */
private val SideMargin = 32.dp

/**
 * The interactive tutorial. Layout (top to bottom) on the Sellby blue gradient: ONE rounded "focus
 * card" holding the dummy chat on top and, below it, the open panel and the toolbar (no keys - the
 * panels get the room); under it the mascot + speech bubble, a dashed line and a small "Lewati
 * Tutorial" pill. The spotlight dimming lives inside the focus card only, and only while something
 * is highlighted. Runs entirely on in-memory dummy data (see the purity note in TutorialModel.kt).
 * [storeName] is only used to personalise the dummy texts. [onFinished] gets `skipped = true` when
 * the user left early.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TutorialScreen(
    mode: TutorialMode,
    storeName: String,
    onFinished: (skipped: Boolean) -> Unit,
    onBack: () -> Unit,
    /** The script to play: the full story by default, or one lesson's chapters (see TutorialScript.chaptersFor). */
    chapters: List<ChapterDef> = TutorialScript.chapters,
) {
    val controller = rememberSaveable(saver = TutorialController.saver(chapters, storeName)) {
        TutorialController(chapters, storeName)
    }
    val registry = remember { TargetRegistry() }
    var showSkipDialog by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun finish(skipped: Boolean) {
        if (finished) return
        finished = true
        onFinished(skipped)
    }

    // The real keyboard may still be up from the previous (Profil Toko) screen; it must not sit over the dummy one.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    BackHandler { if (controller.isFirst) onBack() else controller.back() }

    LaunchedEffect(controller.index) { controller.runTyping() }
    LaunchedEffect(controller.index) { controller.runSpotlightHold() }
    LaunchedEffect(controller.index) {
        delay(9_000)
        controller.showAssist()
    }
    // Scroll everything the step points at (as ONE area) to a comfortable spot of the open panel's body
    // before the user - or the typing animation - gets to it. The panels don't scroll by touch, so this
    // is the only thing that moves them. Waits out a hold (a buyer's message arriving, panel closed).
    val scrollHost = remember { PanelScrollHost() }
    val density = LocalDensity.current
    // One frame clock for both spotlight layers (scrim + ring/hand): they read it while drawing, so they
    // always redraw in the same frame from the same live target positions and can never drift apart.
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame.longValue = it }
    }
    val playChatSound = rememberChatSound()
    LaunchedEffect(controller.index) {
        val ids = controller.currentStep.spotlight
        val firstId = ids.firstOrNull() ?: return@LaunchedEffect
        while (!controller.spotlightVisible) delay(100)
        repeat(24) {
            if (registry.coordinates(firstId) != null) {
                delay(300)
                scrollTargetsIntoComfort(ids, registry, scrollHost, with(density) { 14.dp.toPx() })
                return@LaunchedEffect
            }
            delay(50)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SellbyColors.GreetingGradientStart, SellbyColors.GreetingGradientEnd))),
        contentAlignment = Alignment.TopCenter,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxHeight()
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val metrics = StageMetrics.compute(maxHeight.value)
            if (!metrics.fits) {
                TooShortCard(onSkip = { finish(true) })
            } else {
                CompositionLocalProvider(
                    LocalTutorial provides controller,
                    LocalTargetRegistry provides registry,
                    LocalPanelScrollHost provides scrollHost,
                    LocalChatSound provides playChatSound,
                ) {
                    // Spacing from the mock-up: 20 below the status bar, 18 card -> guide, 21 guide -> dashed line.
                    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
                        FocusCard(
                            controller = controller,
                            registry = registry,
                            frame = frame,
                            panelHeight = metrics.panelHeight.dp,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = SideMargin),
                        )
                        GuideRow(
                            controller = controller,
                            onFinish = { finish(false) },
                            modifier = Modifier.padding(top = 18.dp),
                            horizontalPadding = SideMargin,
                        )
                        DashedLine(Modifier.padding(top = 21.dp, start = SideMargin, end = SideMargin))
                        SkipLink(onClick = { if (controller.index == 0) finish(true) else showSkipDialog = true })
                    }
                    // Ring + tap hand on top of EVERYTHING: never cut off by the card's edge.
                    SpotlightMarkers(
                        targets = controller.currentStep.spotlight,
                        registry = registry,
                        visible = controller.spotlightVisible,
                        showHand = controller.currentStep.gate is Gate.Tap,
                        frame = frame,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text("Lewati tutorial?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (mode == TutorialMode.Lesson) "Kamu bisa membukanya lagi kapan saja dari Settings → Tutorial."
                    else "Kamu bisa membukanya lagi kapan saja dari Dashboard.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSkipDialog = false
                    finish(true)
                }) { Text("Lewati", color = SellbyColors.ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) { Text("Lanjut belajar", color = SellbyColors.TealPrimary) }
            },
        )
    }
}

/**
 * Scrolls the open panel's body so the area the step points at sits [margin] px below the viewport's
 * top - unless it is already comfortably inside (not flush against an edge, and not hidden behind the
 * panel's sticky bar). Targets outside the scrolling body (sticky bars, the toolbar) are left alone.
 */
private suspend fun scrollTargetsIntoComfort(ids: List<TargetId>, registry: TargetRegistry, host: PanelScrollHost, margin: Float) {
    val scroll = host.scroll ?: return
    val viewport = host.viewport?.takeIf { it.isAttached } ?: return
    var top = Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    ids.filter { registry.isInScroll(it) }.forEach { id ->
        val c = registry.coordinates(id) ?: return@forEach
        val r = viewport.localBoundingBoxOf(c, clipBounds = false)
        top = minOf(top, r.top)
        bottom = maxOf(bottom, r.bottom)
    }
    if (top == Float.MAX_VALUE) return
    if (top >= margin && bottom <= viewport.size.height - margin) return
    scroll.animateScrollTo((scroll.value + top - margin).roundToInt().coerceIn(0, scroll.maxValue))
}

// ---------------------------------------------------------------- the focus card

/** Chat (top) + panel + toolbar in one clipped card; the spotlight scrim is drawn inside it only. */
@Composable
private fun FocusCard(
    controller: TutorialController,
    registry: TargetRegistry,
    frame: LongState,
    panelHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    val palette = KeyboardPalette.Dark
    // Remembered so the panel keeps its content while it animates closed (state.panel is already null then).
    val shownPanel = remember { arrayOfNulls<PanelTab>(1) }
    if (state.panel != null) shownPanel[0] = state.panel
    // While the buyer's new message is arriving the panel steps aside (the chat gets the room), then
    // opens again - spotlightVisible is false exactly during that hold.
    val openPanel = if (controller.spotlightVisible) state.panel else null

    // What the chat input shows right now: the text being auto-typed, or what a panel put there.
    val chatInput = controller.fieldText(FieldId.ChatInput, state.chatInput)
    val pills = if (state.suggestionsActive && chatInput.isNotBlank()) {
        state.autoText.rows
            .filter { it.shortcut.contains(chatInput.trim(), ignoreCase = true) }
            .map { SuggestionPill(it.resolved(state.storeName).replace("\n", " "), isLessonTarget = it.shortcut == "Terima kasih") }
    } else {
        emptyList()
    }

    Box(modifier.clip(CardShape)) {
        Column(Modifier.fillMaxSize()) {
            DummyChatHeader()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DummyChatMessages(state.chat, copyMenuOpen = state.copyMenuOpen)
                ChatToast(state.toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            DummyChatInput(chatInput, focused = controller.typingField == FieldId.ChatInput)
            Column(Modifier.fillMaxWidth().background(palette.background)) {
                AnimatedVisibility(
                    visible = openPanel != null,
                    enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(160)) + fadeOut(tween(160)),
                ) {
                    when (shownPanel[0]) {
                        PanelTab.Produk -> DummyProdukPanel(state.produk, panelHeight)
                        PanelTab.Invoice -> DummyInvoicePanel(state, panelHeight)
                        PanelTab.Status -> DummyStatusPanel(state, panelHeight)
                        PanelTab.AutoText -> DummyAutoTextPanel(state, panelHeight)
                        else -> PlaceholderPanel(shownPanel[0], panelHeight)
                    }
                }
                if (pills.isEmpty()) {
                    DummyToolbar(openPanel, palette = palette)
                } else {
                    DummySuggestionStrip(pills, palette = palette)
                }
            }
        }
        SpotlightScrim(
            targets = controller.currentStep.spotlight,
            registry = registry,
            visible = controller.spotlightVisible,
            frame = frame,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A short dark pill at the top of the chat (e.g. "Teks disalin") that fades away by itself. */
@Composable
private fun ChatToast(text: String?, modifier: Modifier = Modifier) {
    var shown by remember(text) { mutableStateOf(text != null) }
    LaunchedEffect(text) {
        if (text != null) {
            delay(1800)
            shown = false
        }
    }
    AnimatedVisibility(visible = shown && text != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(300)), modifier = modifier) {
        Text(
            text.orEmpty(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Color(0xFF1E293B), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Panels the tutorial has no lesson for (Ongkir): never reachable, but never crash either. */
@Composable
private fun PlaceholderPanel(tab: PanelTab?, height: Dp) {
    PanelShell(
        title = tab?.name ?: "",
        height = height,
        leadingIcon = R.drawable.ic_settings_chevron_down_sellby,
        trailingIcon = null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Panel contoh", color = SellbyColors.TextHint, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- skip + too-short

@Composable
private fun DashedLine(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = Color(0xFF6FD0E6).copy(alpha = 0.85f),
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
        )
    }
}

/** Plain light-blue text on the right, aligned with the card's edge (as in the mock-up); >= 48dp tall tap target. */
@Composable
private fun SkipLink(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(end = SideMargin),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            "Lewati Tutorial",
            color = Color(0xFFD6EEFF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun TooShortCard(onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Layar kamu terlalu pendek untuk simulasi ini.",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            "Tidak apa-apa, kamu bisa langsung mulai memakai Sellby. Tutorial bisa dibuka lagi dari Dashboard di layar yang lebih besar.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Lewati Tutorial",
            color = SellbyColors.TextDark,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(top = 16.dp)
                .background(Color.White, RoundedCornerShape(20.dp))
                .clickable(onClick = onSkip)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}
