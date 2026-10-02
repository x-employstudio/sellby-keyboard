// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.toolbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.sellby.data.entity.AutoText
import helium314.keyboard.sellby.input.AutoTextSuggestionEngine
import helium314.keyboard.sellby.ui.AutoTextPanelView
import helium314.keyboard.sellby.ui.InvoicePanelView
import helium314.keyboard.sellby.ui.OngkirPanelView
import helium314.keyboard.sellby.ui.ProdukPanelView
import helium314.keyboard.sellby.ui.SettingsPanelView
import helium314.keyboard.sellby.ui.StatusPanelView

/**
 * Fixed 6-tab business toolbar (Invoice/Ongkir/Status/Produk/Auto-Text + Settings), sitting between
 * the panel-region overlay and the physical keyboard rows - see sellby_keyboard.dart's
 * _buildTopToolbar/_buildToolbarItem. Fase 3 scope: shell only - each tab opens a placeholder panel
 * (real per-panel content/forms/CRUD is Fase 4, after the Room DB data layer exists).
 */
class SellbyToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    // visibleKeysHeightPercent: the "keys visible" resting height for tabs that DO dynamically hide
    // physical keys on field focus (everything except Status, which is always keys-visible and uses
    // lockedHeightPercent instead). Default literal 0.430f here can't reference the
    // DEFAULT_HEIGHT_PERCENT companion constant below (enum entries initialize before the companion
    // object) - keep the two in sync by hand. Produk/Auto-Text override this to match Status's
    // 0.370f (visual consistency, requested by user) while keeping their own dynamic
    // hide-keys-while-typing behavior, which Status never had a need for.
    private enum class Tab(val label: String, val icon: Int, val lockedHeightPercent: Float?, val visibleKeysHeightPercent: Float = 0.430f) {
        INVOICE("Invoice", R.drawable.ic_toolbar_invoice_sellby, null),
        ONGKIR("Ongkir", R.drawable.ic_toolbar_ongkir_sellby, null),
        // History: 0.315f (Fase 3 kerangka, before real content existed) -> 0.430f (matched
        // DEFAULT_HEIGHT_PERCENT, still clipped the card's buttons - was hitting
        // computePanelHeight()'s ceiling, not the requested percent, see
        // STATUS_MAX_TOTAL_SCREEN_FRACTION below) -> 0.520f (fixed the clipping, but overshot -
        // left a visible empty gap below the card's bottom) -> 0.460f (still some gap left, this
        // time between the card and the indicator dots below it - root cause was StatusPanelView's
        // ViewPager2 stretching to fill all leftover space via weight=1 instead of sizing to the
        // card's own height; fixed there with a fixed pager height instead) -> 0.400f (tighter
        // internal layout allowed lowering it). User requested one more explicit reduction from
        // here (40% -> 35% -> 37%, small back-up bump), not chasing a clipping/gap complaint.
        STATUS("Status", R.drawable.ic_toolbar_status_sellby, 0.370f),
        PRODUK("Produk", R.drawable.ic_toolbar_produk_sellby, null, visibleKeysHeightPercent = 0.370f),
        AUTO_TEXT("Auto-Text", R.drawable.ic_toolbar_autotext_sellby, null, visibleKeysHeightPercent = 0.370f),
    }

    private lateinit var panelRegion: FrameLayout
    private lateinit var panelLabel: TextView
    private lateinit var settingsPanelView: SettingsPanelView
    private lateinit var invoicePanelView: InvoicePanelView
    private lateinit var ongkirPanelView: OngkirPanelView
    private lateinit var statusPanelView: StatusPanelView
    private lateinit var produkPanelView: ProdukPanelView
    private lateinit var autoTextPanelView: AutoTextPanelView
    private lateinit var physicalKeys: View
    private var initialized = false

    private var activeTab: Tab? = null
    private var settingsActive = false
    private val tabIcons = mutableMapOf<Tab, ImageView>()
    private val tabLabels = mutableMapOf<Tab, TextView>()
    private lateinit var settingsIcon: ImageView

    // Auto-Text suggestion strip (Toolbar mode-2) - normalRow (tabs+divider+settings, unchanged)
    // and suggestionStrip are siblings inside `this`, only one ever VISIBLE at a time. See
    // updateAutoTextSuggestions()/clearAutoTextSuggestions().
    private lateinit var normalRow: LinearLayout
    private lateinit var suggestionStrip: LinearLayout
    private lateinit var suggestionsRow: LinearLayout
    private var suggestionBarDismissed = false

    private var heightAnimator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Called once from KeyboardSwitcher.onCreateInputView() after all sibling views are bound. */
    fun initialize(panelRegion: FrameLayout, physicalKeys: View) {
        if (initialized) return
        initialized = true
        this.panelRegion = panelRegion
        this.physicalKeys = physicalKeys

        setBackgroundColor(Settings.getValues().mColors.get(ColorType.STRIP_BACKGROUND))

        panelLabel = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
        }
        // Fixed white, NOT ColorType.KEY_BACKGROUND: this sits directly behind every Sellby panel,
        // none of which paint their own opaque root background (only their individual cards/rows
        // do) - so whatever color lands here shows through every gap between them. User wants
        // panels to stay visually unchanged regardless of theme (only the toolbar/keyboard should
        // adapt), so this must never follow the adaptive key-background color.
        panelRegion.setBackgroundColor(0xFFFFFFFF.toInt())
        panelRegion.addView(panelLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        settingsPanelView = SettingsPanelView(context).apply { visibility = View.GONE }
        panelRegion.addView(settingsPanelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        invoicePanelView = InvoicePanelView(context).apply { visibility = View.GONE }
        panelRegion.addView(invoicePanelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ongkirPanelView = OngkirPanelView(context).apply { visibility = View.GONE }
        panelRegion.addView(ongkirPanelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        statusPanelView = StatusPanelView(context).apply { visibility = View.GONE }
        panelRegion.addView(statusPanelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        produkPanelView = ProdukPanelView(context).apply { visibility = View.GONE }
        panelRegion.addView(produkPanelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        autoTextPanelView = AutoTextPanelView(context).apply { visibility = View.GONE }
        panelRegion.addView(autoTextPanelView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val tabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        }
        Tab.values().forEach { tab -> tabsRow.addView(buildTabItem(tab)) }
        normalRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(tabsRow)
            addView(buildDivider())
            addView(buildSettingsButton())
        }
        addView(normalRow)
        suggestionStrip = buildSuggestionStrip()
        addView(suggestionStrip)

        AutoTextSuggestionEngine.startWatching(context)
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private fun buildTabItem(tab: Tab): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onTabTapped(tab) }
        }
        val icon = ImageView(context).apply {
            setImageResource(tab.icon)
            layoutParams = LinearLayout.LayoutParams(dp(17f), dp(17f))
        }
        val label = TextView(context).apply {
            text = tab.label
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) }
        }
        tabIcons[tab] = icon
        tabLabels[tab] = label
        updateTabColors(tab, active = false)
        container.addView(icon)
        container.addView(label)
        return container
    }

    private fun buildDivider(): View = View(context).apply {
        layoutParams = LayoutParams(dp(1.2f), dp(24f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(4f)
            marginEnd = dp(4f)
        }
        setBackgroundColor(Settings.getValues().mColors.get(ColorType.EMOJI_CATEGORY))
    }

    private fun buildSettingsButton(): View {
        settingsIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_toolbar_settings_sellby)
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f))
        }
        updateSettingsColor()
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onSettingsTapped() }
            addView(settingsIcon)
        }
    }

    /** Toolbar mode-2 shell - a dismiss button + horizontally-scrollable row of suggestion pills,
     *  sized to the exact same 48dp row as [normalRow] (see TOOLBAR_ROW_HEIGHT_DP) so nothing else
     *  shifts when one replaces the other. Starts GONE; [updateAutoTextSuggestions] toggles it. */
    private fun buildSuggestionStrip(): LinearLayout {
        val dismissBtn = ImageView(context).apply {
            // Teal circle + white icon - same style as every other header circle button across the
            // app (ic_settings_chevron_left_sellby is already an all-white glyph, no tint needed),
            // not Flutter's neutral-gray dismiss button here.
            setImageResource(R.drawable.ic_settings_chevron_left_sellby)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, R.color.calculator_accent))
            }
            layoutParams = LayoutParams(dp(28f), dp(28f)).apply { marginStart = dp(10f); marginEnd = dp(8f) }
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                suggestionBarDismissed = true
                clearAutoTextSuggestions()
            }
        }
        suggestionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            // Giving suggestionsRow the scroll view's own full MATCH_PARENT height and letting ITS
            // OWN gravity=CENTER_VERTICAL (already set above) center each pill within that height is
            // a plain, guaranteed LinearLayout behavior - relying on HorizontalScrollView/FrameLayout
            // to center a WRAP_CONTENT-height child on its own turned out not to take effect.
            addView(suggestionsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(dismissBtn)
            addView(scroll)
        }
    }

    private fun buildSuggestionPill(item: AutoText, onPick: (AutoText) -> Unit): View =
        TextView(context).apply {
            text = AutoTextSuggestionEngine.resolveTokens(context, item.message).replace("\n", " ")
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 12f
            setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Settings.getValues().mColors.get(ColorType.KEY_BACKGROUND))
                setStroke(dp(0.8f).coerceAtLeast(1), Settings.getValues().mColors.get(ColorType.EMOJI_CATEGORY))
            }
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8f)
            }
            maxWidth = dp(220f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onPick(item)
                clearAutoTextSuggestions()
            }
        }

    /** Public: called by KeyboardActionListenerImpl.updateAutoTextSuggestions() after every
     *  keystroke that could have changed the currently-focused text (Sellby panel field or a real
     *  app via InputConnection). [queryEmpty] resets the "dismissed" flag (mirrors
     *  sellby_keyboard.dart's _isSuggestionBarDismissed reset-on-empty), matching Flutter's rule
     *  that a manual dismiss stays in effect until the field goes empty again, not just until the
     *  next keystroke. */
    fun updateAutoTextSuggestions(matches: List<AutoText>, queryEmpty: Boolean, onPick: (AutoText) -> Unit) {
        if (!::suggestionStrip.isInitialized) return
        if (queryEmpty) suggestionBarDismissed = false
        if (queryEmpty || matches.isEmpty() || suggestionBarDismissed) {
            clearAutoTextSuggestions()
            return
        }
        normalRow.visibility = View.GONE
        suggestionStrip.visibility = View.VISIBLE
        suggestionsRow.removeAllViews()
        matches.forEach { suggestionsRow.addView(buildSuggestionPill(it, onPick)) }
    }

    /** Forces the row back to its normal tabs+settings state. Safe to call unconditionally - also
     *  used as a cleanup net from SellbyInputRouter.unfocus(), closePanel() and
     *  collapseImmediately(), the same "clean up transient UI state on every close path" lesson
     *  from the earlier root-overlay incident. */
    fun clearAutoTextSuggestions() {
        if (!::suggestionStrip.isInitialized) return
        suggestionStrip.visibility = View.GONE
        normalRow.visibility = View.VISIBLE
    }

    private fun updateTabColors(tab: Tab, active: Boolean) {
        val icon = tabIcons[tab] ?: return
        val label = tabLabels[tab] ?: return
        if (active) {
            val accent = ContextCompat.getColor(context, R.color.calculator_accent)
            icon.setColorFilter(accent)
            label.setTextColor(accent)
        } else {
            icon.setColorFilter(Settings.getValues().mColors.get(ColorType.EMOJI_CATEGORY))
            label.setTextColor(Settings.getValues().mColors.get(ColorType.FUNCTIONAL_KEY_TEXT))
        }
    }

    private fun updateSettingsColor() {
        val color = if (settingsActive) ContextCompat.getColor(context, R.color.calculator_accent)
        else Settings.getValues().mColors.get(ColorType.EMOJI_CATEGORY)
        settingsIcon.setColorFilter(color)
    }

    private fun onTabTapped(tab: Tab) {
        // Leave Emoji/Clipboard mode first if we're currently browsing one of those - the panel
        // this tab opens needs the physical alphabet keyboard (via SellbyInputRouter) for its own
        // input fields, not the emoji grid or clipboard list. No-op otherwise. See KeyboardSwitcher.
        KeyboardSwitcher.getInstance().exitUtilityModeIfNeeded()
        val opening = activeTab != tab
        activeTab?.let { updateTabColors(it, active = false) }
        if (settingsActive) {
            settingsActive = false
            updateSettingsColor()
        }
        if (!opening) {
            activeTab = null
            closePanel()
            return
        }
        activeTab = tab
        updateTabColors(tab, active = true)
        settingsPanelView.visibility = View.GONE
        when (tab) {
            Tab.INVOICE -> {
                panelLabel.visibility = View.GONE
                invoicePanelView.visibility = View.VISIBLE
                ongkirPanelView.visibility = View.GONE
                statusPanelView.visibility = View.GONE
                produkPanelView.visibility = View.GONE
                autoTextPanelView.visibility = View.GONE
                invoicePanelView.refresh()
            }
            Tab.ONGKIR -> {
                panelLabel.visibility = View.GONE
                invoicePanelView.visibility = View.GONE
                ongkirPanelView.visibility = View.VISIBLE
                statusPanelView.visibility = View.GONE
                produkPanelView.visibility = View.GONE
                autoTextPanelView.visibility = View.GONE
                ongkirPanelView.refresh()
            }
            Tab.STATUS -> {
                panelLabel.visibility = View.GONE
                invoicePanelView.visibility = View.GONE
                ongkirPanelView.visibility = View.GONE
                statusPanelView.visibility = View.VISIBLE
                produkPanelView.visibility = View.GONE
                autoTextPanelView.visibility = View.GONE
                statusPanelView.refresh()
            }
            Tab.PRODUK -> {
                panelLabel.visibility = View.GONE
                invoicePanelView.visibility = View.GONE
                ongkirPanelView.visibility = View.GONE
                statusPanelView.visibility = View.GONE
                produkPanelView.visibility = View.VISIBLE
                autoTextPanelView.visibility = View.GONE
                produkPanelView.refresh()
            }
            Tab.AUTO_TEXT -> {
                panelLabel.visibility = View.GONE
                invoicePanelView.visibility = View.GONE
                ongkirPanelView.visibility = View.GONE
                statusPanelView.visibility = View.GONE
                produkPanelView.visibility = View.GONE
                autoTextPanelView.visibility = View.VISIBLE
                autoTextPanelView.refresh()
            }
        }
        // Fase 3 shell: remaining placeholder panels have no real fields, so keys stay visible (52%)
        // by default - matches sellby_keyboard.dart's _isKeyboardKeysVisible starting/resetting to
        // true on every tab tap; only a real field's onFieldUnfocused ever hides them.
        physicalKeys.visibility = View.VISIBLE
        openPanel(tab.lockedHeightPercent ?: tab.visibleKeysHeightPercent, raisedMaxTotalFractionFor(tab))
    }

    /** Status/Produk/Auto-Text share the raised ceiling so Produk/Auto-Text's keys-visible height
     *  actually matches Status's (see STATUS_MAX_TOTAL_SCREEN_FRACTION's comment) - Invoice/Ongkir
     *  were never asked to match, so they keep the default ceiling. */
    private fun raisedMaxTotalFractionFor(tab: Tab): Float =
        if (tab == Tab.STATUS || tab == Tab.PRODUK || tab == Tab.AUTO_TEXT) STATUS_MAX_TOTAL_SCREEN_FRACTION
        else MAX_TOTAL_SCREEN_FRACTION

    private fun onSettingsTapped() {
        KeyboardSwitcher.getInstance().exitUtilityModeIfNeeded()
        val opening = !settingsActive
        activeTab?.let { updateTabColors(it, active = false) }
        activeTab = null
        settingsActive = opening
        updateSettingsColor()
        if (!opening) {
            closePanel()
            return
        }
        openSettingsPanel { it.resetToMenu() }
    }

    /** Shared by [onSettingsTapped] and [openSettingsPaymentMethods]: hide every other panel, show
     *  Settings, then let the caller decide where inside it to land ([SettingsPanelView.resetToMenu]
     *  for the normal gear-tap entry, [SettingsPanelView.openPaymentMethodsDirectly] for Invoice's
     *  "+ Tambah Metode" shortcut). */
    private fun openSettingsPanel(entry: (SettingsPanelView) -> Unit) {
        panelLabel.visibility = View.GONE
        invoicePanelView.visibility = View.GONE
        ongkirPanelView.visibility = View.GONE
        statusPanelView.visibility = View.GONE
        produkPanelView.visibility = View.GONE
        autoTextPanelView.visibility = View.GONE
        settingsPanelView.visibility = View.VISIBLE
        settingsPanelView.initialize()
        entry(settingsPanelView)
        physicalKeys.visibility = View.VISIBLE
        openPanel(DEFAULT_HEIGHT_PERCENT)
    }

    /** Invoice's "+ Tambah Metode" circle (buildPaymentCarousel) calls this - same effect as
     *  tapping the Settings gear, except it lands straight on Metode Pembayaran instead of the main
     *  menu first. */
    fun openSettingsPaymentMethods() {
        KeyboardSwitcher.getInstance().exitUtilityModeIfNeeded()
        activeTab?.let { updateTabColors(it, active = false) }
        activeTab = null
        settingsActive = true
        updateSettingsColor()
        openSettingsPanel { it.openPaymentMethodsDirectly() }
    }

    /** Fase 4 hook: real panels call this from their field focus/unfocus callbacks. */
    fun setPhysicalKeysVisible(visible: Boolean) {
        physicalKeys.visibility = if (visible) View.VISIBLE else View.GONE
        val tab = activeTab ?: return
        if (tab.lockedHeightPercent != null) return
        openPanel(if (visible) tab.visibleKeysHeightPercent else HIDDEN_KEYS_HEIGHT_PERCENT, raisedMaxTotalFractionFor(tab))
    }

    /** Called by KeyboardSwitcher when switching away to Emoji/Clipboard/Calculator/hardware-kb mode. */
    fun collapseImmediately() {
        heightAnimator?.cancel()
        clearAutoTextSuggestions()
        activeTab?.let { updateTabColors(it, active = false) }
        activeTab = null
        if (settingsActive) {
            settingsActive = false
            updateSettingsColor()
        }
        if (::panelRegion.isInitialized) {
            panelRegion.visibility = View.GONE
            val lp = panelRegion.layoutParams
            lp.height = 0
            panelRegion.layoutParams = lp
        }
        if (::physicalKeys.isInitialized) physicalKeys.visibility = View.VISIBLE
    }

    private fun openPanel(percent: Float, maxTotalFraction: Float = MAX_TOTAL_SCREEN_FRACTION) {
        panelRegion.visibility = View.VISIBLE
        animatePanelHeight(computePanelHeight(percent, maxTotalFraction))
    }

    private fun closePanel() {
        clearAutoTextSuggestions()
        animatePanelHeight(0) { panelRegion.visibility = View.GONE }
    }

    /**
     * The panel stacks on top of the toolbar row (48dp) and, while placeholders never hide the
     * physical keys, on top of those too (~205.6dp) - a raw percent-of-screen-height would push
     * the total input view close to covering the whole screen. Cap the WHOLE stack (panel +
     * toolbar + keys) at [maxTotalFraction] of the screen (default MAX_TOTAL_SCREEN_FRACTION),
     * leaving room for the app above. Status passes a higher override (see onTabTapped) - its
     * physical keys never hide, so the default ceiling was the actual thing clipping its card
     * content, not the requested percent.
     */
    private fun computePanelHeight(percent: Float, maxTotalFraction: Float = MAX_TOTAL_SCREEN_FRACTION): Int {
        val screenHeight = resources.displayMetrics.heightPixels
        val keysHeight = if (physicalKeys.visibility == View.VISIBLE)
            ResourceUtils.getKeyboardHeight(resources, Settings.getValues()) else 0
        val overhead = dp(TOOLBAR_ROW_HEIGHT_DP) + keysHeight
        val totalBudget = (screenHeight * maxTotalFraction).toInt()
        val desired = (screenHeight * percent).toInt()
        return desired.coerceAtMost((totalBudget - overhead).coerceAtLeast(dp(MIN_PANEL_HEIGHT_DP)))
    }

    /** Back-button hook (via KeyboardActionListenerImpl.onKeyDown) - closes an open panel/Settings
     *  and reports it consumed the event; returns false when nothing was open (back should fall
     *  through to its normal default behaviour then). */
    fun closeIfOpen(): Boolean {
        if (activeTab == null && !settingsActive) return false
        activeTab?.let { updateTabColors(it, active = false) }
        activeTab = null
        if (settingsActive) {
            settingsActive = false
            updateSettingsColor()
        }
        closePanel()
        return true
    }

    /** Sellby: KeyboardSwitcher's QRIS-photo-picked broadcast receiver calls this once the picker
     *  Activity reports back - delegates straight to SettingsPanelView (Room write + thumbnail
     *  refresh live there, same as every other Metode Pembayaran mutation). No-op if the panel
     *  hasn't been initialized yet (picker was somehow triggered and returned before Settings ever
     *  opened - shouldn't happen in practice, but safe either way). */
    fun notifyQrisPhotoPicked(paymentMethodId: String, path: String?) {
        if (::settingsPanelView.isInitialized) settingsPanelView.onQrisPhotoPicked(paymentMethodId, path)
    }

    /** Sellby: KeyboardSwitcher's contact-picked broadcast receiver calls this once
     *  ContactPickerActivity reports back - delegates to InvoicePanelView (the only current caller
     *  of the contact-picker button), same "no-op if the panel hasn't been initialized yet" guard
     *  as notifyQrisPhotoPicked() above. */
    fun notifyContactPicked(name: String, phone: String) {
        if (::invoicePanelView.isInitialized) invoicePanelView.onContactPicked(name, phone)
    }

    private fun animatePanelHeight(target: Int, onEnd: (() -> Unit)? = null) {
        heightAnimator?.cancel()
        val current = panelRegion.layoutParams.height.coerceAtLeast(0)
        heightAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val lp = panelRegion.layoutParams
                lp.height = it.animatedValue as Int
                panelRegion.layoutParams = lp
            }
            if (onEnd != null) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = onEnd()
                })
            }
            start()
        }
    }

    companion object {
        // Sellby: Flutter-derived targets, tuned across several rounds of device feedback
        // (-20%, +15%, -10%).
        private const val DEFAULT_HEIGHT_PERCENT = 0.430f
        private const val HIDDEN_KEYS_HEIGHT_PERCENT = 0.646f
        private const val MAX_TOTAL_SCREEN_FRACTION = 0.662f
        // Raised ceiling, shared by Status/Produk/Auto-Text (see raisedMaxTotalFractionFor below).
        // Originally added for Status only (physical keys visible always there, locked height, so
        // the default ceiling was the real limit clipping its card's button row) - later found the
        // SAME default ceiling was silently clipping Produk/Auto-Text's keys-visible height below
        // Status's even though both request the identical 0.370f visibleKeysHeightPercent, which is
        // why those two panels looked shorter than Status despite matching percents. Invoice/Ongkir
        // intentionally still use the default ceiling (never asked to match Status's height).
        private const val STATUS_MAX_TOTAL_SCREEN_FRACTION = 0.74f
        private const val TOOLBAR_ROW_HEIGHT_DP = 48f
        private const val MIN_PANEL_HEIGHT_DP = 80f
    }
}
