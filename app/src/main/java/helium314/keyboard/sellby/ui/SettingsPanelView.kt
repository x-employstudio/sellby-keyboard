// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.doOnLayout
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.companion.CompanionLauncher
import helium314.keyboard.sellby.companion.TrialPolicy
import helium314.keyboard.sellby.companion.TrialState
import helium314.keyboard.sellby.companion.tutorial.LessonId
import helium314.keyboard.sellby.data.ExpeditionCatalog
import helium314.keyboard.sellby.data.SellbyDatabase
import helium314.keyboard.sellby.data.entity.Customer
import helium314.keyboard.sellby.data.entity.Expedition
import helium314.keyboard.sellby.data.entity.PaymentMethod
import helium314.keyboard.sellby.input.SellbyInputRouter
import helium314.keyboard.sellby.input.enableSellbyRouting
import helium314.keyboard.sellby.util.Channel
import helium314.keyboard.sellby.util.ChannelMessenger
import helium314.keyboard.sellby.util.IdentifierKind
import helium314.keyboard.sellby.util.KeyboardSizeScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How often, at most, a drag of the "Ukuran keyboard" slider rebuilds the keyboard (see buildKeyboardSizeRow). */
private const val SIZE_COMMIT_INTERVAL_MS = 60L

/**
 * Settings panel content (tab "Settings" in SellbyToolbarView). Layout/spacing/icons/colors copied
 * as closely as possible from sellby_keyboard.dart's SettingsPanel main menu (_buildMainSettingsView/
 * _buildSectionHeader/_buildSettingTile/header bar, settings_panel.dart:1495-2058) - acuan tunggal
 * for design, not just mechanism. Text/icon/chevron/divider colors are fixed literals (matching
 * every other Sellby panel's convention), NOT the adaptive ColorType system - user explicitly wants
 * every Sellby panel to stay visually unchanged regardless of system dark/light mode (only the
 * toolbar row and physical keyboard should adapt). An earlier revision mapped these to ColorType on
 * the assumption the opposite was wanted; once panelRegion's background was fixed to permanent white
 * instead of following ColorType.KEY_BACKGROUND, colors here that were STILL adaptive (e.g.
 * KEY_TEXT, which turns light/white in dark mode - meant for text sitting on a dark key background)
 * became invisible against that now-permanent white panel background. Teal accent and the red delete
 * button were always fixed either way, matching how teal/red are used as fixed brand/danger colors
 * everywhere else in this app (Enter key, active tab, etc).
 *
 * Sub-sections are added one at a time across rounds (see the plan file); unimplemented ones show
 * "Segera hadir" rather than being silently missing from the menu.
 */
class SettingsPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    /** [headerAccessory] optionally replaces the header's right-side theme-toggle slot while this
     *  item's screen is open (icon resource + click action) - used by the Template Pesan screens for
     *  their reset button. Null keeps the slot hidden, matching every other sub-screen. */
    private data class MenuItem(
        val label: String,
        val icon: Int,
        val builder: (SettingsPanelView) -> View,
        val headerAccessory: Pair<Int, (SettingsPanelView) -> Unit>? = null,
    )
    private data class MenuSection(val header: String, val items: List<MenuItem>)

    private val sections = listOf(
        MenuSection("Profil", listOf(
            MenuItem("Toko", R.drawable.ic_settings_toko_sellby, SettingsPanelView::buildTokoView),
            MenuItem("Metode Pembayaran", R.drawable.ic_settings_payment_sellby, { it.openMetodePembayaranList(); FrameLayout(it.context) }),
            MenuItem("Atur Data Pelanggan", R.drawable.ic_settings_pelanggan_sellby, { it.openDataPelangganList(); FrameLayout(it.context) }),
        )),
        MenuSection("Template Pesan", listOf(
            MenuItem("Invoice", R.drawable.ic_toolbar_invoice_sellby, SettingsPanelView::buildTemplateInvoiceView,
                headerAccessory = R.drawable.ic_toolbar_reset_sellby to { it.handleResetInvoiceTemplate() }),
            MenuItem("Status", R.drawable.ic_toolbar_status_sellby, SettingsPanelView::buildTemplateStatusView,
                headerAccessory = R.drawable.ic_toolbar_reset_sellby to { it.handleResetStatusTemplate() }),
        )),
        MenuSection("Ekspedisi", listOf(
            MenuItem("List Ekspedisi", R.drawable.ic_toolbar_ongkir_sellby, { it.openListEkspedisiSubmenu(); FrameLayout(it.context) }),
        )),
        MenuSection("Keyboard", listOf(
            MenuItem("Atur Keyboard", R.drawable.ic_settings_keyboard_sellby, SettingsPanelView::buildAturKeyboardView),
        )),
        MenuSection("Tutorial", listOf(
            MenuItem("Cara penggunaan", R.drawable.ic_settings_tutorial_sellby, SettingsPanelView::buildTutorialListView),
        )),
    )

    private lateinit var headerBack: ImageView
    private lateinit var headerTitle: TextView
    private lateinit var headerTheme: ImageView
    private lateinit var content: FrameLayout
    private lateinit var panelOverlay: FrameLayout
    private var confirmOverlay: View? = null
    private var countdownRunnable: Runnable? = null
    private var initialized = false
    private var atMainMenu = true
    // Default back-action for any single-level sub-entry (Toko, Template Invoice/Status, List
    // Ekspedisi) is "return to the main menu". Screens with their own list<->form depth (Metode
    // Pembayaran, Atur Data Pelanggan) override this while showing their form, so chevron-back steps
    // up one level at a time instead of always jumping straight to the main menu.
    private var currentBackAction: () -> Unit = { showMenu() }
    private val db by lazy { SellbyDatabase.getInstance(context.applicationContext) }

    init {
        orientation = VERTICAL
    }

    fun initialize() {
        if (initialized) return
        initialized = true

        // column+panelOverlay: same local, self-contained pattern as Invoice/Status/Produk/Auto-Text
        // - retrofitted here now that this panel is gaining several new dialogs (delete-confirm,
        // bank picker, reset-confirm) that must render above the WHOLE panel, not just `content`,
        // matching the standing "every popup in every panel stays consistent" instruction.
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(buildHeader())
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
                setBackgroundColor(0xFFE2E8F0.toInt())
            })
            content = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            addView(content)
        }
        panelOverlay = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        addView(FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(column)
            addView(panelOverlay)
        })
        showMenu()
    }

    /** Called by SellbyToolbarView every time the Settings tab is (re)opened. */
    fun resetToMenu() {
        if (initialized) showMenu()
    }

    /** Invoice's "+ Tambah Metode" payment-circle calls this (via SellbyToolbarView.
     *  openSettingsPaymentMethods()) to land straight on this screen instead of the main Settings
     *  menu first - reuses the exact MenuSection/MenuItem already wired into [sections], same path
     *  [showEntry] takes when the row is tapped by hand, so chevron-back/header/etc. all behave
     *  identically either way. */
    fun openPaymentMethodsDirectly() {
        val section = sections.first { it.header == "Profil" }
        val item = section.items.first { it.label == "Metode Pembayaran" }
        showEntry(section, item)
    }

    /** Lands on Settings -> Tutorial (the list of lessons) - used when a lesson ends and the keyboard
     *  comes back (see KeyboardSwitcher.endSellbyHelper). Same [showEntry] path as tapping the row. */
    fun openTutorialListDirectly() {
        val section = sections.first { it.header == "Tutorial" }
        showEntry(section, section.items.first())
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private fun teal() = ContextCompat.getColor(context, R.color.calculator_accent)

    private fun circleButton(icon: Int): ImageView = ImageView(context).apply {
        setImageResource(icon)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(teal()) }
        layoutParams = LayoutParams(dp(32f), dp(32f))
        setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
        isClickable = true
        isFocusable = true
    }

    private fun buildHeader(): View {
        headerBack = circleButton(R.drawable.ic_settings_chevron_down_sellby).apply {
            // Same convention every other Sellby panel uses for its own chevron: chevron-down (at
            // the top level, here the main menu) closes the whole panel; chevron-left (inside any
            // sub-entry) just steps back to the menu. Previously this always called showMenu(),
            // which was a no-op while already on the main menu - the panel never actually closed.
            setOnClickListener {
                if (atMainMenu) KeyboardSwitcher.getInstance().sellbyToolbarView?.closeIfOpen()
                else currentBackAction()
            }
        }
        headerTitle = TextView(context).apply {
            text = "Settings"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        headerTheme = circleButton(R.drawable.ic_settings_moon_sellby).apply {
            setOnClickListener { toggleTheme() }
        }
        refreshThemeIcon()
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            addView(headerBack)
            addView(headerTitle)
            addView(headerTheme)
        }
    }

    /** Relocates the existing manual dark/light toggle (Settings.PREF_THEME_DARK_MODE, previously
     *  only reachable from the separate Settings app's AppearanceScreen) here - reuses the exact
     *  same pref key/reload call, no new toggle logic invented. */
    private fun isDark() = context.prefs().getBoolean(Settings.PREF_THEME_DARK_MODE, Defaults.PREF_THEME_DARK_MODE)

    private fun toggleTheme() {
        context.prefs().edit { putBoolean(Settings.PREF_THEME_DARK_MODE, !isDark()) }
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
        refreshThemeIcon()
    }

    private fun refreshThemeIcon() {
        // Icon shows the mode a tap will switch TO, matching sellby_keyboard.dart exactly.
        headerTheme.setImageResource(if (isDark()) R.drawable.ic_settings_sun_sellby else R.drawable.ic_settings_moon_sellby)
    }

    private fun showMenu() {
        SellbyInputRouter.unfocus()
        dismissOverlay()
        atMainMenu = true
        currentBackAction = { showMenu() }
        headerTitle.text = "Settings"
        headerBack.setImageResource(R.drawable.ic_settings_chevron_down_sellby)
        headerTheme.visibility = View.VISIBLE
        headerTheme.setOnClickListener { toggleTheme() }
        refreshThemeIcon()
        content.removeAllViews()
        toastView = null
        content.addView(buildMenuList())
    }

    private fun showEntry(section: MenuSection, item: MenuItem) {
        SellbyInputRouter.unfocus()
        dismissOverlay()
        atMainMenu = false
        currentBackAction = { showMenu() }
        headerTitle.text = section.header
        headerBack.setImageResource(R.drawable.ic_settings_chevron_left_sellby)
        val accessory = item.headerAccessory
        if (accessory != null) {
            headerTheme.visibility = View.VISIBLE
            headerTheme.setImageResource(accessory.first)
            headerTheme.setOnClickListener { accessory.second(this) }
        } else {
            headerTheme.visibility = View.INVISIBLE
        }
        content.removeAllViews()
        toastView = null
        content.addView(item.builder(this))
    }

    private fun dismissOverlay() {
        countdownRunnable?.let { removeCallbacks(it) }
        confirmOverlay?.let { panelOverlay.removeView(it); confirmOverlay = null }
    }

    private fun buildMenuList(): View {
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
        }
        // The purchase entry: first thing in Settings, deliberately unlike the plain rows below, and gone once premium is on
        // (the menu is rebuilt every time it is shown, so it disappears after a purchase without any extra wiring).
        if (TrialPolicy.state(context) !is TrialState.Premium) {
            list.addView(buildPremiumRow())
            list.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(16f)) })
        }
        sections.forEachIndexed { index, section ->
            if (index > 0) list.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(16f)) })
            list.addView(buildSectionHeader(section.header))
            section.items.forEach { item -> list.addView(buildSettingTile(section, item)) }
        }
        list.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(20f)) })
        list.addView(buildDeleteAllButton())
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    /** "Beli Premium": a blue gradient card (the same blues as the purchase page) with the premium seal, a title, a one
     *  line promise and a chevron. A tap opens the purchase page of the companion app (CompanionLauncher.openPurchase,
     *  the same page a locked tab opens; "Kembali" there returns to the app being typed in). The keyboard never talks
     *  to Google Play itself: the page does, and it writes the premium flag this row reads through TrialPolicy. */
    private fun buildPremiumRow(): View {
        val seal = ImageView(context).apply {
            setImageResource(R.drawable.ic_premium_sellby)
            setColorFilter(0xFFD9F99D.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
        }
        val title = TextView(context).apply {
            text = "Beli Premium"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val subtitle = TextView(context).apply {
            text = "Sekali bayar, semua fitur terbuka selamanya"
            textSize = 10.5f
            setTextColor(0xCCFFFFFF.toInt())
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f) }
            addView(title)
            addView(subtitle)
        }
        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_chevron_right_sellby)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f))
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(11f), dp(12f), dp(11f))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF3D82C4.toInt(), 0xFF0B2247.toInt())).apply {
                cornerRadius = dp(12f).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { CompanionLauncher.openPurchase(context) }
            addView(seal)
            addView(texts)
            addView(chevron)
        }
    }

    private fun buildSectionHeader(title: String): View {
        val label = TextView(context).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            setPadding(dp(4f), 0, 0, dp(6f))
        }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label)
            addView(divider)
        }
    }

    private fun buildSettingTile(section: MenuSection, item: MenuItem): View =
        buildLinkTile(item.icon, item.label) { showEntry(section, item) }

    /** One menu row: icon, bold label, right chevron and a divider below; [onClick] runs on tap. Shared by
     *  the main menu and the Tutorial list so both look identical. */
    private fun buildLinkTile(iconRes: Int, labelText: String, onClick: () -> Unit): View {
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(0xFF1E293B.toInt())
        }
        val iconBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f))
            addView(icon, FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER))
        }
        val label = TextView(context).apply {
            text = labelText
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12f), 0, 0, 0)
        }
        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_chevron_right_sellby)
            setColorFilter(0xFF94A3B8.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f))
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(11f), dp(6f), dp(11f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(iconBox)
            addView(label)
            addView(chevron)
        }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            addView(row)
            addView(divider)
        }
    }

    /** Settings -> Tutorial -> Cara penggunaan: the four stand-alone lessons. A tap opens that lesson as a
     *  full page of the companion app (see CompanionLauncher.openTutorial - the tutorial is a full-screen
     *  Compose UI, it can't live in this panel). The launch hides the keyboard; when the lesson ends the
     *  user is back in the app they were typing in and the keyboard returns on this same list
     *  (KeyboardSwitcher.beginSellbyHelper/endSellbyHelper). */
    private fun buildTutorialListView(): View {
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
        }
        listOf(
            LessonId.Invoice to R.drawable.ic_toolbar_invoice_sellby,
            LessonId.Status to R.drawable.ic_toolbar_status_sellby,
            LessonId.Produk to R.drawable.ic_toolbar_produk_sellby,
            LessonId.AutoText to R.drawable.ic_toolbar_autotext_sellby,
        ).forEach { (lesson, icon) ->
            list.addView(buildLinkTile(icon, lesson.title) {
                // The lesson hides the keyboard; when it ends the keyboard comes back on THIS list.
                KeyboardSwitcher.getInstance().beginSellbyHelper(KeyboardSwitcher.SellbyHelperReturn.SETTINGS_TUTORIAL_LIST)
                CompanionLauncher.openTutorial(context, lesson)
            })
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    private fun buildDeleteAllButton(): View {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_delete_sellby)
            layoutParams = LinearLayout.LayoutParams(dp(14f), dp(17f)).apply { marginEnd = dp(8f) }
        }
        val label = TextView(context).apply {
            text = "Hapus Semua Data dan Settingan"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFEF4444.toInt()) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38f))
            isClickable = true
            isFocusable = true
            addView(icon)
            addView(label)
            setOnClickListener { triggerDeleteAllConfirm() }
        }
    }

    /** settings_panel.dart's _handleResetAllDataAndSettings uses the same shared confirm-dialog
     *  mechanism (icon-circle) as every other reset, with a 5s countdown there - by explicit request
     *  this one specifically uses 15s instead (a deliberate deviation, not a port: this action is far
     *  more destructive than a template reset, wiping every business record permanently). */
    private fun triggerDeleteAllConfirm() {
        showCountdownConfirm(
            "Hapus Semua Data?",
            "PERINGATAN: Seluruh data transaksi, produk, pelanggan, profil toko, dan pengaturan akan dihapus secara permanen.",
            0xFFEF4444.toInt(),
            countdownSeconds = 15
        ) {
            performDeleteAll()
            showToast("Semua data dan settingan berhasil dibersihkan!")
            // Companion app reacts to this reset by navigating back to Welcome/Profil Toko (see
            // DashboardScreen.kt's onDataReset) - the keyboard's own panel has to close itself here,
            // same convention Invoice's generateAndSendInvoice() uses, or it stays sitting open on
            // top of the Profil Toko fields the user is about to fill back in.
            KeyboardSwitcher.getInstance().sellbyToolbarView?.closeIfOpen()
        }
    }

    private fun performDeleteAll() {
        context.prefs().edit {
            remove("store_name"); remove("store_address"); remove("store_phone")
            remove("sellby_template_invoice"); remove("sellby_template_status_pending")
            remove("sellby_template_status_lunas"); remove("sellby_template_status_proses")
            remove("sellby_template_status_selesai")
            remove("sellby_onboarding_completed")
        }
        CoroutineScope(Dispatchers.IO).launch {
            SellbyDatabase.getInstance(context.applicationContext).clearAllTables()
            // The QRIS photos are plain files next to the database (QrisPhotoPickerActivity): "Hapus Semua Data"
            // used to leave every one of them on the phone, and they are included in Auto Backup.
            File(context.applicationContext.filesDir, "qris_photos").deleteRecursively()
        }
    }


    // --- Sub-bagian: Profil Toko (settings_panel.dart:1988-2053, _buildTokoProfilView) ---

    private fun spacer(heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(heightDp))
    }

    private fun fieldBackground(focused: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        setColor(0xFFF1F5F9.toInt())
        if (focused) setStroke(dp(1.4f).coerceAtLeast(1), teal())
    }

    private fun buildTextField(hintText: String, initial: String, isNumeric: Boolean = false): EditText = EditText(context).apply {
        setText(initial)
        setSelection(initial.length)
        hint = hintText
        textSize = 12f
        isSingleLine = true
        setTextColor(0xFF1E293B.toInt())
        setHintTextColor(0xFF94A3B8.toInt())
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10f), 0, dp(10f), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
        background = fieldBackground()
        enableSellbyRouting(isNumeric = isNumeric) { hasFocus -> background = fieldBackground(hasFocus) }
    }

    /** Returns the row (add this to the layout) plus the inner EditText (read .text from this). */
    private fun buildPhoneField(initial: String): Pair<View, EditText> {
        val phoneField = EditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            hint = "Nomor Telepon Toko"
            textSize = 12f
            isSingleLine = true
            setTextColor(0xFF1E293B.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val prefix = TextView(context).apply {
            text = "+62"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
        }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1f), dp(16f)).apply {
                marginStart = dp(8f); marginEnd = dp(8f)
            }
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
            setPadding(dp(10f), 0, dp(10f), 0)
            background = fieldBackground()
            addView(prefix)
            addView(divider)
            addView(phoneField)
        }
        phoneField.enableSellbyRouting(isNumeric = true) { hasFocus -> row.background = fieldBackground(hasFocus) }
        return row to phoneField
    }

    private fun buildPillButton(text: String, fillColor: Int = teal(), onClick: () -> Unit): View {
        val label = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(160f), dp(36f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(fillColor) }
            isClickable = true
            isFocusable = true
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { onClick() }
        }
    }

    private var toastView: View? = null

    /** In-panel toast (settings_panel.dart's `_showInCardWarning`) - overlaid on [content], which is
     *  already a FrameLayout so this needs no extra Stack-equivalent scaffolding. Never a system
     *  Dialog/Toast: every Flutter panel avoids those specifically to dodge BadTokenException when
     *  running inside a host app's window (see plan file, Round 3). */
    private fun showToast(message: String) {
        toastView?.let { panelOverlay.removeView(it) }
        val toast = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            maxWidth = dp(280f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0xFF1E293B.toInt()) }
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = dp(12f)
        }
        panelOverlay.addView(toast, params)
        toastView = toast
        toast.postDelayed({
            panelOverlay.removeView(toast)
            if (toastView === toast) toastView = null
        }, 2800)
    }

    /** Plain delete-confirm dialog - same style ProdukPanelView/AutoTextPanelView established (icon
     *  `ic_settings_delete_sellby` red 30dp, no tinted circle backdrop), reused here for Metode
     *  Pembayaran and Data Pelanggan's delete flows. */
    private fun showDeleteConfirm(title: String, message: String, onConfirm: () -> Unit) {
        dismissOverlay()
        lateinit var scrim: FrameLayout
        fun dismiss() {
            panelOverlay.removeView(scrim)
            if (confirmOverlay === scrim) confirmOverlay = null
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_delete_sellby)
            setColorFilter(0xFFEF4444.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8f) }
        }
        val titleView = TextView(context).apply {
            text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt()); gravity = Gravity.CENTER
        }
        val messageView = TextView(context).apply {
            text = message
            textSize = 12f; setTextColor(0xFF64748B.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, dp(16f))
        }
        val cancelBtn = TextView(context).apply {
            text = "Batal"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF475569.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFE2E8F0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { dismiss() }
        }
        val confirmBtn = TextView(context).apply {
            text = "Hapus"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFEF4444.toInt()) }
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginStart = dp(10f) }
            isClickable = true; isFocusable = true
            setOnClickListener { dismiss(); onConfirm() }
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(cancelBtn); addView(confirmBtn)
        }
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.WHITE) }
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER; marginStart = dp(24f); marginEnd = dp(24f)
            }
            elevation = dp(6f).toFloat()
            addView(icon); addView(titleView); addView(messageView); addView(buttonRow)
        }
        scrim = FrameLayout(context).apply {
            setBackgroundColor(0x73000000)
            isClickable = true
            addView(card)
        }
        panelOverlay.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        confirmOverlay = scrim
    }

    /** Icon-circle+5s-countdown confirm dialog - same style Invoice/Status/Produk/AutoText's Reset
     *  dialogs established, reused here for the Template Pesan reset actions. */
    private fun showCountdownConfirm(title: String, message: String, confirmColor: Int, countdownSeconds: Int = 5, onConfirm: () -> Unit) {
        dismissOverlay()
        lateinit var scrim: FrameLayout
        fun dismiss() {
            countdownRunnable?.let { removeCallbacks(it) }
            panelOverlay.removeView(scrim)
            if (confirmOverlay === scrim) confirmOverlay = null
        }
        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            setColorFilter(confirmColor)
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8f) }
        }
        val titleView = TextView(context).apply {
            text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt()); gravity = Gravity.CENTER
        }
        val messageView = TextView(context).apply {
            text = message
            textSize = 12f; setTextColor(0xFF64748B.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, dp(16f))
        }
        val cancelBtn = TextView(context).apply {
            text = "Batalkan"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF475569.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFE2E8F0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { dismiss() }
        }
        var remaining = countdownSeconds
        val confirmBtn = TextView(context).apply {
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginStart = dp(10f) }
            isClickable = true; isFocusable = true
        }
        fun updateConfirmBtn() {
            val disabled = remaining > 0
            confirmBtn.text = if (disabled) "Lanjutkan (${remaining}s)" else "Lanjutkan"
            confirmBtn.setTextColor(if (disabled) 0xFF64748B.toInt() else Color.WHITE)
            confirmBtn.background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(if (disabled) 0xFFCBD5E1.toInt() else confirmColor) }
            confirmBtn.isEnabled = !disabled
            confirmBtn.setOnClickListener {
                if (!disabled) { dismiss(); onConfirm() }
            }
        }
        updateConfirmBtn()
        val tick = object : Runnable {
            override fun run() {
                remaining--
                updateConfirmBtn()
                if (remaining > 0) postDelayed(this, 1000)
            }
        }
        countdownRunnable = tick
        postDelayed(tick, 1000)
        val buttonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(cancelBtn); addView(confirmBtn)
        }
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.WHITE) }
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER; marginStart = dp(22f); marginEnd = dp(22f)
            }
            elevation = dp(6f).toFloat()
            addView(icon); addView(titleView); addView(messageView); addView(buttonRow)
        }
        scrim = FrameLayout(context).apply {
            setBackgroundColor(0x73000000)
            isClickable = true
            addView(card)
        }
        panelOverlay.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        confirmOverlay = scrim
    }

    private fun buildTokoView(): View {
        val nameField = buildTextField("Nama Toko", context.prefs().getString("store_name", "") ?: "")
        val addressField = buildTextField("Alamat Toko", context.prefs().getString("store_address", "") ?: "")
        val (phoneRow, phoneField) = buildPhoneField(context.prefs().getString("store_phone", "") ?: "")

        val saveButton = buildPillButton("Simpan") {
            val name = nameField.text.toString().trim()
            if (name.isEmpty()) {
                showToast("Nama toko wajib diisi!")
                return@buildPillButton
            }
            context.prefs().edit {
                putString("store_name", name)
                putString("store_address", addressField.text.toString().trim())
                putString("store_phone", phoneField.text.toString().trim())
            }
            SellbyInputRouter.unfocus()
            showMenu()
            showToast("Profil toko berhasil diperbarui!")
        }

        val title = TextView(context).apply {
            text = "Profil Toko"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(title)
            addView(spacer(14f))
            addView(nameField)
            addView(spacer(10f))
            addView(addressField)
            addView(spacer(10f))
            addView(phoneRow)
            addView(spacer(20f))
            addView(saveButton)
            addView(spacer(10f))
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    // ==================================================================================
    // Sub-bagian: Metode Pembayaran (settings_panel.dart:2471-2913, _buildMetodePembayaranListView/
    // _buildMetodePembayaranFormView) - Room-backed (PaymentMethodDao), autosave on every mutation
    // (add/edit/delete/toggle), matching Flutter's own autosave-on-every-change behavior for this
    // screen (unlike List Ekspedisi below, which only persists on an explicit Simpan tap).
    // ==================================================================================

    // Sellby: set (only) while the QRIS edit form is on screen - see buildMetodePembayaranFormView's
    // isQris branch. KeyboardSwitcher's QRIS-picker broadcast receiver calls onQrisPhotoPicked()
    // below via SellbyToolbarView.notifyQrisPhotoPicked() once the picker Activity reports back;
    // this callback lets that reach the live form's thumbnail without rebuilding the whole form
    // (which would drop any unsaved edit to the account name field next to it). A null path means
    // "photo removed" (clearQrisPhoto) - the picker itself only ever reports a really saved photo.
    private var onQrisPhotoUpdated: ((paymentMethodId: String, path: String?) -> Unit)? = null

    /** Public: KeyboardSwitcher/SellbyToolbarView call this once QrisPhotoPickerActivity reports
     *  back. Writes qrisImagePath directly to Room (independent of the form's own Simpan button -
     *  this is the only way that field ever gets populated) and refreshes the form's thumbnail if
     *  it's still showing the same payment method. */
    fun onQrisPhotoPicked(paymentMethodId: String, path: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = db.paymentMethodDao().getById(paymentMethodId) ?: return@launch
            db.paymentMethodDao().update(pm.copy(qrisImagePath = path))
            withContext(Dispatchers.Main) { onQrisPhotoUpdated?.invoke(paymentMethodId, path) }
        }
    }

    /** Downscaled decode (sample size picked so the result is still >= targetPx on both sides) -
     *  a gallery photo decoded at full resolution is tens of MB and noticeably stalls the UI
     *  thread, but this only ever feeds a 120dp thumbnail. Call off the main thread. */
    private fun decodeSampledBitmap(path: String, targetPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun buildQrisUploadSection(paymentMethodId: String, photoPath: String?): View {
        val hasPhoto = !photoPath.isNullOrEmpty()
        val thumbnail = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120f), dp(120f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat(); setColor(0xFFF1F5F9.toInt()); setStroke(dp(1f).coerceAtLeast(1), 0xFFCBD5E1.toInt())
            }
        }
        val caption = TextView(context).apply {
            textSize = 11f; setTextColor(0xFF94A3B8.toInt()); gravity = Gravity.CENTER
            text = if (hasPhoto) "Foto QRIS sudah diunggah - tap tombol di bawah untuk mengganti."
            else "Belum ada foto QRIS - upload dulu sebelum metode ini bisa diaktifkan."
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f); bottomMargin = dp(10f)
            }
        }
        if (hasPhoto) {
            // The section is rebuilt from scratch for every change (see rebuildVariablePart), so the
            // decode result only ever belongs to this one instance - no stale-result bookkeeping.
            val targetPx = dp(240f)
            CoroutineScope(Dispatchers.IO).launch {
                val bitmap = decodeSampledBitmap(photoPath!!, targetPx)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) thumbnail.setImageBitmap(bitmap)
                    else caption.text = "Foto QRIS tidak bisa dibuka - upload ulang fotonya."
                }
            }
        }
        val uploadButton = buildPillButton("Upload Foto QRIS") {
            try {
                // The picker hides the keyboard; the user comes straight back to this form, so the
                // panel must survive that one hide (see KeyboardSwitcher.keepSellbyPanelThroughNextHide).
                KeyboardSwitcher.getInstance().keepSellbyPanelThroughNextHide()
                context.startActivity(Intent(context, QrisPhotoPickerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(QrisPhotoPickerActivity.EXTRA_PAYMENT_METHOD_ID, paymentMethodId)
                })
            } catch (e: Exception) {
                showToast("Tidak bisa membuka galeri.")
            }
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            addView(thumbnail)
            addView(caption)
            addView(uploadButton)
            if (hasPhoto) {
                addView(spacer(8f))
                addView(buildPillButton("Hapus Foto QRIS", fillColor = 0xFFEF4444.toInt()) {
                    showDeleteConfirm(
                        "Hapus Foto QRIS?",
                        "Foto QRIS akan dihapus dan metode QRIS dinonaktifkan sampai foto baru diunggah."
                    ) { clearQrisPhoto(paymentMethodId) }
                })
            }
        }
    }

    /** Removes the QRIS photo (file + Room path) and switches QRIS off - an active QRIS without a
     *  photo would make invoices promise an attached QR image that doesn't exist (togglePaymentActive
     *  already refuses to activate QRIS without one, this keeps the already-active case consistent
     *  with that rule). Applied immediately, independent of the form's Simpan button, same as an
     *  upload (see onQrisPhotoPicked). */
    private fun clearQrisPhoto(paymentMethodId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = db.paymentMethodDao().getById(paymentMethodId)
            pm?.qrisImagePath?.let { runCatching { File(it).delete() } }
            if (pm != null) db.paymentMethodDao().update(pm.copy(qrisImagePath = null, isActive = false))
            withContext(Dispatchers.Main) {
                onQrisPhotoUpdated?.invoke(paymentMethodId, null)
                showToast("Foto QRIS dihapus.")
            }
        }
    }

    private val bankOptions = listOf(
        "shopeepay" to "ShopeePay", "ovo" to "OVO", "dana" to "DANA", "linkaja" to "LinkAja",
        "bca" to "BCA", "mandiri" to "Bank Mandiri", "bni" to "BNI", "bri" to "BRI",
        "btn" to "Bank BTN", "bsi" to "Bank BSI", "btpn" to "BTPN / Jenius", "cimb" to "CIMB Niaga",
        "danamon" to "Bank Danamon", "hsbc" to "HSBC Indonesia", "panin" to "Panin Bank",
        "dbs" to "DBS Bank", "mega" to "Bank Mega", "lainnya" to "Lainnya",
    )

    // Duplicated from InvoicePanelView.paymentIconRes() - same "duplicate until a 3rd caller forces
    // extraction" convention already established across every Sellby panel in this project.
    private fun paymentIconRes(bankType: String): Int {
        val key = bankType.lowercase().trim()
        return when {
            key.contains("cash") || key.contains("tunai") -> R.drawable.ic_payment_cash_sellby
            key.contains("shopeepay") -> R.drawable.ic_payment_shopeepay_sellby
            key.contains("ovo") -> R.drawable.ic_payment_ovo_sellby
            key.contains("dana") -> R.drawable.ic_payment_dana_sellby
            key.contains("linkaja") -> R.drawable.ic_payment_linkaja_sellby
            key.contains("bca") -> R.drawable.ic_payment_bca_sellby
            key.contains("mandiri") -> R.drawable.ic_payment_mandiri_sellby
            key.contains("bni") -> R.drawable.ic_payment_bni_sellby
            key.contains("bri") -> R.drawable.ic_payment_bri_sellby
            key.contains("btn") -> R.drawable.ic_payment_btn_sellby
            key.contains("bsi") -> R.drawable.ic_payment_bsi_sellby
            key.contains("btpn") || key.contains("jenius") -> R.drawable.ic_payment_btpn_sellby
            key.contains("cimb") -> R.drawable.ic_payment_cimb_sellby
            key.contains("danamon") -> R.drawable.ic_payment_danamon_sellby
            key.contains("hsbc") -> R.drawable.ic_payment_hsbc_sellby
            key.contains("panin") -> R.drawable.ic_payment_panin_sellby
            key.contains("dbs") -> R.drawable.ic_payment_dbs_sellby
            key.contains("mega") -> R.drawable.ic_payment_mega_sellby
            key.contains("qris") -> R.drawable.ic_payment_qris_sellby
            else -> R.drawable.ic_payment_bank_sellby
        }
    }

    private fun openMetodePembayaranList() {
        CoroutineScope(Dispatchers.IO).launch {
            db.paymentMethodDao().seedIfAbsent(listOf(
                PaymentMethod("pm_tunai", "Tunai", "Pembayaran Tunai", "tunai", null, true),
                PaymentMethod("pm_qris", "QRIS", "Scan QRIS Code", "qris", null, false),
            ))
            val all = db.paymentMethodDao().getAll().first()
            withContext(Dispatchers.Main) {
                currentBackAction = { showMenu() }
                content.removeAllViews()
                content.addView(buildMetodePembayaranListView(all))
            }
        }
    }

    private fun buildMetodePembayaranListView(items: List<PaymentMethod>): View {
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) }
            addView(TextView(context).apply {
                text = "Metode Pembayaran"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "+ Tambah"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(teal()) }
                setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
                isClickable = true; isFocusable = true
                setOnClickListener { openMetodePembayaranForm(null) }
            })
        }
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(headerRow)
        }
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Belum ada metode pembayaran"
                textSize = 12f; setTypeface(typeface, Typeface.ITALIC); setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24f) }
            })
        } else {
            items.forEach { list.addView(buildPaymentCard(it)) }
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    /** Pill toggle switch (44x24 track + 18dp thumb, green when on / gray when off) - shared by
     *  Metode Pembayaran's active toggle and Atur Keyboard's 3 behavior toggles. Manages its own
     *  visual state; [onToggle] is only for the caller's side effect (persisting the new value). */
    private fun buildToggleSwitch(initial: Boolean, onToggle: (Boolean) -> Unit): View {
        lateinit var toggleThumb: View
        lateinit var toggleTrack: FrameLayout
        var isOn = initial
        fun render() {
            toggleTrack.background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(if (isOn) 0xFF8CE623.toInt() else 0xFFCBD5E1.toInt())
            }
            (toggleThumb.layoutParams as FrameLayout.LayoutParams).gravity =
                if (isOn) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.CENTER_VERTICAL or Gravity.START
            toggleThumb.requestLayout()
        }
        toggleThumb = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            layoutParams = FrameLayout.LayoutParams(dp(18f), dp(18f)).apply { marginStart = dp(3f); marginEnd = dp(3f) }
        }
        toggleTrack = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(24f))
            isClickable = true; isFocusable = true
            addView(toggleThumb)
            setOnClickListener {
                isOn = !isOn
                render()
                onToggle(isOn)
            }
        }
        render()
        return toggleTrack
    }

    private fun buildPaymentCard(pm: PaymentMethod): View {
        val hasData = pm.accountNumber.trim().isNotEmpty() || (pm.bankType == "qris" && pm.qrisImagePath != null)
        val iconView = ImageView(context).apply {
            setImageResource(paymentIconRes(pm.bankType))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(36f))
        }
        val infoColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10f), 0, dp(8f), 0)
            addView(TextView(context).apply {
                text = pm.name; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
            })
            addView(TextView(context).apply {
                text = if (hasData) pm.accountNumber else "Data akun belum diisi"
                textSize = 11f
                setTextColor(if (hasData) 0xFF64748B.toInt() else 0xFFEF4444.toInt())
            })
        }
        // Not buildToggleSwitch: this toggle is guarded (togglePaymentActive can refuse and toast
        // instead of flipping - e.g. QRIS with no photo yet), so it must NOT optimistically flip its
        // own visual state on tap the way a plain on/off switch does. It only ever repaints via a
        // full list refresh after a DB write actually succeeds.
        lateinit var toggleThumb: View
        val toggleTrack = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(24f))
            isClickable = true; isFocusable = true
        }
        toggleThumb = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            layoutParams = FrameLayout.LayoutParams(dp(18f), dp(18f)).apply { marginStart = dp(3f); marginEnd = dp(3f) }
        }
        toggleTrack.addView(toggleThumb)
        (toggleThumb.layoutParams as FrameLayout.LayoutParams).gravity =
            if (pm.isActive) Gravity.CENTER_VERTICAL or Gravity.END else Gravity.CENTER_VERTICAL or Gravity.START
        toggleTrack.background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(if (pm.isActive) 0xFF8CE623.toInt() else 0xFFCBD5E1.toInt())
        }
        toggleTrack.setOnClickListener { togglePaymentActive(pm) }
        val deleteIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_delete_sellby)
            setColorFilter(0xFFEF4444.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(19f)).apply { marginStart = dp(12f) }
            isClickable = true; isFocusable = true
            setOnClickListener { triggerDeletePaymentConfirm(pm) }
        }
        // Returned directly as the card (no wrapper) - a wrapper LinearLayout around this row had
        // no explicit layoutParams of its own, so it collapsed to WRAP_CONTENT width instead of
        // filling the panel edge-to-edge like every other panel's cards do.
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) }
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat(); setColor(Color.WHITE); setStroke(dp(1f).coerceAtLeast(1), 0xFFE2E8F0.toInt())
            }
            isClickable = true; isFocusable = true
            setOnClickListener { openMetodePembayaranForm(pm) }
            addView(iconView); addView(infoColumn); addView(toggleTrack); addView(deleteIcon)
        }
    }

    private fun togglePaymentActive(pm: PaymentMethod) {
        val turningOn = !pm.isActive
        if (turningOn) {
            if (pm.bankType == "qris") {
                if (pm.qrisImagePath == null) { showToast("Upload foto QRIS terlebih dahulu sebelum mengaktifkan!"); return }
            } else if (pm.bankType != "tunai") {
                if (pm.accountNumber.trim().isEmpty() || pm.name.trim().isEmpty()) {
                    showToast("Isi data akun bank terlebih dahulu sebelum mengaktifkan!"); return
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            db.paymentMethodDao().update(pm.copy(isActive = turningOn))
            withContext(Dispatchers.Main) { openMetodePembayaranList() }
        }
    }

    private fun triggerDeletePaymentConfirm(pm: PaymentMethod) {
        if (pm.id == "pm_tunai" || pm.id == "pm_qris") {
            showToast("Metode pembayaran ${pm.name} tidak dapat dihapus")
            return
        }
        showDeleteConfirm("Hapus Metode Pembayaran?", "Hapus \"${pm.name}\" dari daftar metode pembayaran?") {
            CoroutineScope(Dispatchers.IO).launch {
                db.paymentMethodDao().delete(pm)
                withContext(Dispatchers.Main) { openMetodePembayaranList() }
            }
        }
    }

    private fun openMetodePembayaranForm(existing: PaymentMethod?) {
        currentBackAction = { openMetodePembayaranList() }
        content.removeAllViews()
        content.addView(buildMetodePembayaranFormView(existing))
    }

    private fun buildMetodePembayaranFormView(existing: PaymentMethod?): View {
        val isTunai = existing?.id == "pm_tunai"
        val isQris = existing?.id == "pm_qris"
        // New entries are always a bank/wallet - settings_panel.dart's bank picker never offers
        // tunai/qris as choices, they only ever exist as the 2 fixed seeded rows.
        var selectedBankType: String? = if (!isTunai && !isQris) existing?.bankType?.takeIf { it.isNotEmpty() } else null
        // Source of truth for qrisImagePath while this form is open - starts from the DB value,
        // updated live by onQrisPhotoUpdated (below) once the picker Activity reports back. The
        // Simpan handler reads THIS (not existing?.qrisImagePath) so a photo picked during this
        // session isn't clobbered back to its old value if Simpan is tapped afterward - Room
        // already has the new value too (onQrisPhotoPicked writes it directly), this just keeps
        // the form's own save payload consistent with it.
        var currentQrisPath: String? = existing?.qrisImagePath
        // Same idea for isActive: deleting the QRIS photo switches QRIS off directly in Room
        // (clearQrisPhoto), so Simpan must not write the stale value captured in `existing` back.
        var currentIsActive: Boolean = existing?.isActive ?: false

        val accountNameField = buildTextField("Nama Akun Pembayaran*", existing?.name ?: "")
        lateinit var accountNumberField: EditText
        lateinit var customBankNameField: EditText
        var hasAccountNumberField = false
        var hasCustomBankNameField = false

        val variablePart = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        fun bankLabel(key: String?) = bankOptions.firstOrNull { it.first == key }?.second ?: "Pilih Bank/wallet"

        fun rebuildVariablePart() {
            variablePart.removeAllViews()
            hasAccountNumberField = false
            hasCustomBankNameField = false
            when {
                isQris -> {
                    variablePart.addView(buildQrisUploadSection(existing.id, currentQrisPath))
                }
                isTunai -> { /* no extra fields */ }
                else -> {
                    lateinit var pickerLabel: TextView
                    val pickerRow = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
                        setPadding(dp(10f), 0, dp(10f), 0)
                        background = fieldBackground()
                        isClickable = true; isFocusable = true
                    }
                    pickerLabel = TextView(context).apply {
                        text = bankLabel(selectedBankType)
                        textSize = 12f
                        setTextColor(
                            if (selectedBankType != null) 0xFF1E293B.toInt()
                            else 0xFF94A3B8.toInt()
                        )
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    pickerRow.addView(pickerLabel)
                    pickerRow.addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_settings_chevron_right_sellby)
                        setColorFilter(0xFF94A3B8.toInt())
                        layoutParams = LinearLayout.LayoutParams(dp(14f), dp(14f))
                        rotation = 90f
                    })
                    pickerRow.setOnClickListener {
                        showBankPickerOverlay(selectedBankType) { picked ->
                            selectedBankType = picked
                            rebuildVariablePart()
                        }
                    }
                    variablePart.addView(pickerRow)
                    variablePart.addView(spacer(10f))
                    accountNumberField = buildTextField("Nomor Akun Bank/Wallet*", existing?.accountNumber ?: "", isNumeric = true)
                    hasAccountNumberField = true
                    variablePart.addView(accountNumberField)
                    if (selectedBankType == "lainnya") {
                        variablePart.addView(spacer(10f))
                        customBankNameField = buildTextField("Nama Bank/Wallet*", existing?.takeIf { it.bankType == "lainnya" }?.name ?: "")
                        hasCustomBankNameField = true
                        variablePart.addView(customBankNameField)
                    }
                }
            }
        }
        rebuildVariablePart()
        onQrisPhotoUpdated = if (isQris) { pickedId, path ->
            if (pickedId == existing.id) {
                currentQrisPath = path
                if (path == null) currentIsActive = false
                rebuildVariablePart()
            }
        } else null

        val saveButton = buildPillButton(if (existing == null) "+ Tambah" else "Simpan") {
            val accountName = accountNameField.text.toString().trim()
            if (accountName.isEmpty()) { showToast("Nama akun pembayaran wajib diisi!"); return@buildPillButton }

            val finalBankType: String
            val finalAccountNumber: String
            val finalName: String
            when {
                isQris -> { finalBankType = "qris"; finalAccountNumber = "Scan QRIS Code"; finalName = accountName }
                isTunai -> { finalBankType = "tunai"; finalAccountNumber = "Pembayaran Tunai"; finalName = accountName }
                else -> {
                    val bt = selectedBankType
                    if (bt.isNullOrEmpty()) { showToast("Silakan pilih Bank/Wallet terlebih dahulu!"); return@buildPillButton }
                    var resolvedName = accountName
                    if (bt == "lainnya") {
                        val customName = if (hasCustomBankNameField) customBankNameField.text.toString().trim() else ""
                        if (customName.isEmpty()) { showToast("Nama bank/wallet wajib diisi!"); return@buildPillButton }
                        resolvedName = customName
                    }
                    val accNum = if (hasAccountNumberField) accountNumberField.text.toString().trim() else ""
                    if (accNum.isEmpty()) { showToast("Nomor akun bank/wallet wajib diisi!"); return@buildPillButton }
                    finalBankType = bt; finalAccountNumber = accNum; finalName = resolvedName
                }
            }

            val id = existing?.id ?: "pm_${System.currentTimeMillis()}"
            val model = PaymentMethod(id, finalName, finalAccountNumber, finalBankType, currentQrisPath, currentIsActive)
            CoroutineScope(Dispatchers.IO).launch {
                if (existing == null) db.paymentMethodDao().upsert(model) else db.paymentMethodDao().update(model)
                withContext(Dispatchers.Main) {
                    SellbyInputRouter.unfocus()
                    showToast("Metode pembayaran berhasil disimpan!")
                    openMetodePembayaranList()
                }
            }
        }

        val title = TextView(context).apply {
            text = when {
                existing == null -> "Tambah Metode Pembayaran"
                isQris -> "Edit QRIS"
                isTunai -> "Edit Tunai"
                else -> "Edit Metode Pembayaran"
            }
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(title)
            addView(spacer(14f))
            addView(accountNameField)
            addView(spacer(10f))
            addView(variablePart)
            addView(spacer(20f))
            addView(saveButton)
            addView(spacer(10f))
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    /** Bank/Wallet picker overlay (settings_panel.dart:1480-1574) - modal card, target `panelOverlay`
     *  same as every other dialog in this file now. */
    private fun showBankPickerOverlay(selected: String?, onPick: (String) -> Unit) {
        dismissOverlay()
        lateinit var scrim: FrameLayout
        fun dismiss() {
            panelOverlay.removeView(scrim)
            if (confirmOverlay === scrim) confirmOverlay = null
        }
        val titleView = TextView(context).apply {
            text = "Pilih Bank / Wallet"; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt()); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10f))
        }
        val optionsColumn = LinearLayout(context).apply { orientation = VERTICAL }
        bankOptions.forEach { (key, label) ->
            val isSelected = key == selected
            optionsColumn.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(if (isSelected) 0xFFBCE3EB.toInt() else Color.TRANSPARENT)
                }
                isClickable = true; isFocusable = true
                addView(ImageView(context).apply {
                    setImageResource(paymentIconRes(key))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(26f), dp(20f)).apply { marginEnd = dp(10f) }
                })
                addView(TextView(context).apply {
                    text = label; textSize = 12.5f
                    setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isSelected) teal() else 0xFF1E293B.toInt())
                })
                setOnClickListener { dismiss(); onPick(key) }
            })
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(optionsColumn)
        }
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.WHITE) }
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            elevation = dp(6f).toFloat()
            addView(titleView); addView(scroll)
        }
        scrim = FrameLayout(context).apply {
            setBackgroundColor(0x73000000)
            isClickable = true
            addView(card)
            setOnClickListener { dismiss() }
        }
        panelOverlay.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        confirmOverlay = scrim
        // Fixed 250dp scroll + no vertical margin used to make the card hug (or on shorter devices,
        // actually exceed) panelOverlay's top/bottom edges - Metode Pembayaran's panel height is well
        // under what that needed, so the card's bottom (including the last option, "Lainnya") ended
        // up clipped outside the touchable/visible area entirely, not just visually tight. Sizing both
        // the card's width and the scroll's max height off the ACTUAL measured scrim bounds (same
        // doOnLayout technique already used for Invoice's Direct-Ongkir dialog and Status's Detail
        // Transaksi sheet) guarantees the whole card - title, every option including Lainnya, and real
        // top/bottom margins - always fits inside whatever panel height is available, on any device.
        scrim.doOnLayout {
            val cardLp = card.layoutParams as FrameLayout.LayoutParams
            cardLp.width = (scrim.width * 0.8f).toInt()
            cardLp.topMargin = dp(24f)
            cardLp.bottomMargin = dp(24f)
            card.layoutParams = cardLp
            val nonScrollHeight = titleView.height + dp(32f) // card's own top+bottom padding
            val maxScrollHeight = scrim.height - dp(24f) * 2 - nonScrollHeight
            val scrollLp = scroll.layoutParams
            scrollLp.height = maxScrollHeight.coerceIn(dp(120f), dp(220f))
            scroll.layoutParams = scrollLp
        }
    }

    // ==================================================================================
    // Sub-bagian: Atur Data Pelanggan (settings_panel.dart:2916-3222) - Room-backed (CustomerDao).
    // Deviation (disclosed in plan): row actions are text pills (Chat/+Kontak/Hapus) instead of
    // Flutter's icon-only buttons - matches the row-action style already established in
    // ProdukPanelView's cards, avoids converting 2 new icons (WA bubble, person-add) just for this
    // one card style.
    // ==================================================================================

    private var customerSearchQuery = ""

    private fun openDataPelangganList() {
        CoroutineScope(Dispatchers.IO).launch {
            val all = db.customerDao().getAll().first()
            withContext(Dispatchers.Main) {
                currentBackAction = { showMenu() }
                content.removeAllViews()
                content.addView(buildDataPelangganListView(all))
            }
        }
    }

    private fun buildDataPelangganListView(all: List<Customer>): View {
        lateinit var listContainer: LinearLayout
        lateinit var emptyState: TextView

        fun render() {
            val query = customerSearchQuery.trim().lowercase()
            val filtered = if (query.isEmpty()) all else all.filter {
                it.name.lowercase().contains(query) || it.phone.contains(query)
            }
            listContainer.removeAllViews()
            filtered.forEach { listContainer.addView(buildCustomerCard(it)) }
            emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            listContainer.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        }

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) }
            addView(TextView(context).apply {
                text = "Data Pelanggan"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "+ Tambah"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(teal()) }
                setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
                isClickable = true; isFocusable = true
                setOnClickListener { openDataPelangganForm(null) }
            })
        }

        val searchField = EditText(context).apply {
            hint = "Cari Pelanggan..."
            textSize = 11.5f
            isSingleLine = true
            setTextColor(0xFF1E293B.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)).apply { bottomMargin = dp(8f) }
            setPadding(dp(10f), 0, dp(10f), 0)
            background = fieldBackground()
            addView(ImageView(context).apply {
                setImageResource(R.drawable.sym_keyboard_search_rounded)
                setColorFilter(teal())
                layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { marginEnd = dp(6f) }
            })
            addView(searchField)
        }
        searchField.enableSellbyRouting { hasFocus -> searchRow.background = fieldBackground(hasFocus) }
        searchField.doAfterTextChanged {
            customerSearchQuery = searchField.text.toString()
            render()
        }

        listContainer = LinearLayout(context).apply { orientation = VERTICAL }
        emptyState = TextView(context).apply {
            text = "Belum ada data pelanggan"
            textSize = 12f; setTypeface(typeface, Typeface.ITALIC); setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24f) }
        }

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(headerRow)
            addView(searchRow)
            addView(listContainer)
            addView(emptyState)
        }
        render()
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    private fun buildCustomerCard(c: Customer): View {
        val infoColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { openDataPelangganForm(c) }
            addView(TextView(context).apply {
                text = c.name; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
            })
            addView(TextView(context).apply {
                text = "${c.phone} · ${c.channel.displayLabel}"; textSize = 11f; setTextColor(0xFF64748B.toInt())
            })
        }
        fun actionPill(text: String, bg: Int, onClick: () -> Unit) = TextView(context).apply {
            this.text = text; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(bg) }
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        // Actions moved beside the info column (same row, right-aligned, vertically centered) per
        // request - previously stacked below as a 2nd row.
        val actionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(actionPill("Chat", 0xFF25D366.toInt()) { openCustomerWhatsApp(c) })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(5f), 0) })
            // "+Kontak" (save to phone Contacts) only makes sense for a real phone number -
            // hidden for Instagram (username-only); Telegram stays visible since its field may
            // legitimately hold a phone number (dual field, see Channel.kt).
            if (c.channel != Channel.INSTAGRAM) {
                addView(actionPill("+Kontak", teal()) { saveCustomerToContacts(c) })
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(5f), 0) })
            }
            addView(actionPill("Hapus", 0xFFEF4444.toInt()) { triggerDeleteCustomerConfirm(c) })
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat(); setColor(Color.WHITE); setStroke(dp(1f).coerceAtLeast(1), 0xFFE2E8F0.toInt())
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) }
            addView(infoColumn); addView(actionsRow)
        }
    }

    /** Channel-aware chat, no pre-filled text - delegates to ChannelMessenger (shared with
     *  StatusPanelView's openCustomerChatHistory/sendTextMessageAndOpenWA). */
    private fun openCustomerWhatsApp(c: Customer) {
        if (!ChannelMessenger.openChat(context, c.channel, c.phone)) {
            showToast("Gagal membuka ${c.channel.displayLabel}.")
        }
    }

    /** Native equivalent of Flutter's MethodChannel('.../system_actions').addContact - that channel
     *  doesn't exist any more now everything is native Kotlin, so this is a plain standard
     *  ACTION_INSERT intent to the Contacts app, same fire-and-forget +FLAG_ACTIVITY_NEW_TASK pattern
     *  already proven safe from an IME context by OngkirPanelView.launchExpedition(). */
    private fun saveCustomerToContacts(c: Customer) {
        try {
            val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, c.name)
                putExtra(ContactsContract.Intents.Insert.PHONE, c.phone)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            showToast("Gagal membuka aplikasi Kontak HP.")
        }
    }

    private fun triggerDeleteCustomerConfirm(c: Customer) {
        showDeleteConfirm("Hapus Pelanggan?", "Hapus \"${c.name}\" dari daftar pelanggan?") {
            CoroutineScope(Dispatchers.IO).launch {
                db.customerDao().delete(c)
                withContext(Dispatchers.Main) { openDataPelangganList() }
            }
        }
    }

    private fun openDataPelangganForm(existing: Customer?) {
        currentBackAction = { openDataPelangganList() }
        content.removeAllViews()
        content.addView(buildDataPelangganFormView(existing))
    }

    private class ChannelIdentifierField(val row: View, val field: EditText, val prefixLabel: TextView, val divider: View)

    /** Channel-aware contact field for the Data Pelanggan form - built inline rather than reusing
     *  [buildPhoneField] (which also serves the unrelated Store phone number field, so widening
     *  its contract risks that separate, already-working call site). Mirrors
     *  InvoicePanelView.applyChannelToForm()'s exact treatment: PHONE = "+62" prefix + numeric
     *  keyboard, USERNAME = "@" prefix + free text, PHONE_OR_USERNAME (Telegram, dual) = no fixed
     *  prefix + free text. Mutates the field/prefix in place on a channel change rather than
     *  rebuilding, so in-progress typed text is never lost. */
    private fun buildChannelIdentifierField(initial: String, channel: Channel): ChannelIdentifierField {
        val identifierField = EditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            textSize = 12f
            isSingleLine = true
            setTextColor(0xFF1E293B.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val prefixLabel = TextView(context).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
        }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1f), dp(16f)).apply { marginStart = dp(8f); marginEnd = dp(8f) }
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
            setPadding(dp(10f), 0, dp(10f), 0)
            background = fieldBackground()
            addView(prefixLabel); addView(divider); addView(identifierField)
        }
        val result = ChannelIdentifierField(row, identifierField, prefixLabel, divider)
        applyChannelToIdentifierField(result, channel)
        return result
    }

    private fun applyChannelToIdentifierField(target: ChannelIdentifierField, channel: Channel) {
        target.field.hint = channel.fieldLabel
        when (channel.identifierKind) {
            IdentifierKind.PHONE -> { target.prefixLabel.text = "+62"; target.prefixLabel.visibility = View.VISIBLE; target.divider.visibility = View.VISIBLE }
            IdentifierKind.USERNAME -> { target.prefixLabel.text = "@"; target.prefixLabel.visibility = View.VISIBLE; target.divider.visibility = View.VISIBLE }
            IdentifierKind.PHONE_OR_USERNAME -> { target.prefixLabel.visibility = View.GONE; target.divider.visibility = View.GONE }
        }
        val isPhone = channel.identifierKind == IdentifierKind.PHONE
        target.field.inputType = if (isPhone) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        target.field.enableSellbyRouting(isNumeric = isPhone) { hasFocus -> target.row.background = fieldBackground(hasFocus) }
        if (target.field.hasFocus()) SellbyInputRouter.focus(target.field, isPhone)
    }

    /** Row of 4 circular channel icons, single-select - same visual precedent as
     *  InvoicePanelView's channel picker (52dp white circle, teal ring when selected, desaturated
     *  icon when not), duplicated here per this project's "duplicate until a 3rd caller" convention
     *  rather than extracted into a shared UI builder. */
    private fun buildChannelPickerRow(initial: Channel, onChange: (Channel) -> Unit): LinearLayout {
        lateinit var row: LinearLayout
        var current = initial
        fun rebuild() {
            row.removeAllViews()
            Channel.entries.forEach { channel ->
                val isSelected = channel == current
                val icon = ImageView(context).apply {
                    setImageResource(channel.iconRes)
                    layoutParams = FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.CENTER)
                    colorFilter = if (isSelected) null
                        else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                }
                val circle = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(52f), dp(52f)).apply { marginStart = dp(6f); marginEnd = dp(6f) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                        setStroke(dp(if (isSelected) 2.5f else 1.2f), if (isSelected) teal() else 0xFFCBD5E1.toInt())
                    }
                    isClickable = true; isFocusable = true
                    addView(icon)
                    setOnClickListener {
                        if (current == channel) return@setOnClickListener
                        current = channel
                        rebuild()
                        onChange(channel)
                    }
                }
                row.addView(circle)
            }
        }
        row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        rebuild()
        return row
    }

    private fun buildDataPelangganFormView(existing: Customer?): View {
        var selectedChannel = existing?.channel ?: Channel.WHATSAPP
        val nameField = buildTextField("Nama Pelanggan*", existing?.name ?: "")
        val addressField = buildTextField("Alamat Pelanggan", existing?.address ?: "")
        // Stored identifier for a PHONE channel always starts with "0" (see save logic below);
        // shown here with the leading 0 stripped next to the "+62" prefix so the field doesn't
        // read like a doubled prefix. USERNAME/PHONE_OR_USERNAME (Telegram dual) identifiers are
        // stored exactly as typed, with no "0"-stripping convention to undo.
        val initialIdentifier = if (existing?.channel?.identifierKind == IdentifierKind.PHONE)
            existing.phone.removePrefix("0") else existing?.phone ?: ""
        val identifier = buildChannelIdentifierField(initialIdentifier, selectedChannel)

        val channelPickerRow = buildChannelPickerRow(selectedChannel) { channel ->
            selectedChannel = channel
            applyChannelToIdentifierField(identifier, channel)
        }

        val saveButton = buildPillButton(if (existing == null) "+ Tambah" else "Simpan") {
            val name = nameField.text.toString().trim()
            if (name.isEmpty()) { showToast("Nama pelanggan wajib diisi!"); return@buildPillButton }
            val rawIdentifier = identifier.field.text.toString().trim()
            if (rawIdentifier.isEmpty()) { showToast("${selectedChannel.requiredFieldName} wajib diisi!"); return@buildPillButton }
            val storedIdentifier = when (selectedChannel.identifierKind) {
                IdentifierKind.PHONE -> {
                    val digits = rawIdentifier.filter { it.isDigit() }
                    when {
                        digits.startsWith("0") -> digits
                        digits.startsWith("62") -> "0" + digits.substring(2)
                        else -> "0$digits"
                    }
                }
                IdentifierKind.USERNAME -> rawIdentifier.removePrefix("@")
                IdentifierKind.PHONE_OR_USERNAME -> rawIdentifier
            }
            val address = addressField.text.toString().trim()
            val id = existing?.id ?: "cust_${System.currentTimeMillis()}"
            val model = Customer(id, name, address, storedIdentifier, channel = selectedChannel)
            CoroutineScope(Dispatchers.IO).launch {
                if (existing == null) db.customerDao().upsert(model) else db.customerDao().update(model)
                withContext(Dispatchers.Main) {
                    SellbyInputRouter.unfocus()
                    showToast("Data pelanggan berhasil disimpan!")
                    openDataPelangganList()
                }
            }
        }

        val title = TextView(context).apply {
            text = if (existing == null) "Tambah Pelanggan" else "Edit Pelanggan"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(title)
            addView(spacer(14f))
            addView(channelPickerRow)
            addView(spacer(14f))
            addView(nameField)
            addView(spacer(10f))
            addView(addressField)
            addView(spacer(10f))
            addView(identifier.row)
            addView(spacer(20f))
            addView(saveButton)
            addView(spacer(10f))
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    // ==================================================================================
    // Sub-bagian: Template Pesan - Invoice & Status (settings_panel.dart:2187-2433) - pure
    // SharedPreferences, no Room. Writes to the exact same keys InvoicePanelView.kt/
    // StatusPanelView.kt already read from (sellby_template_invoice / sellby_template_status_
    // {pending,lunas,proses,selesai}) - confirmed via direct read of both files, not guessed - so
    // those 2 panels pick up whatever's saved here with zero changes needed in them.
    // ==================================================================================

    private val defaultInvoiceTemplate = """
        Terima kasih sudah berbelanja di #nama-toko yaa!
        Berikut adalah rincian pembelian Kak #nama-pelanggan pada tanggal #tanggal-invoice
        List produk :
        #rincian-produk

        Total Harga Barang : #total-harga-produk

        Pengiriman :
        Alamat : #alamat-penerima
        Nomor Telpon : #nomor-pelanggan
        Ekspedisi : #ekspedisi - #layanan-ekspedisi
        Ongkos Kirim : #ongkir

        Catatan : #catatan

        *Total Pembayaran : #total-pembayaran*
        Metode Pembayaran:
        #metode-pembayaran

        Silakan lakukan pembayaran dan konfirmasi dengan mengirimkan bukti transfer.
    """.trimIndent()

    private val invoiceTokens = setOf(
        "#nama-toko", "#nama-pelanggan", "#tanggal-invoice", "#rincian-produk", "#total-harga-produk",
        "#alamat-penerima", "#nomor-pelanggan", "#ekspedisi", "#layanan-ekspedisi", "#ongkir",
        "#catatan", "#total-pembayaran", "#metode-pembayaran",
    )
    private val statusTokens = setOf(
        "#nama-toko", "#nama-pelanggan", "#tanggal-invoice", "#total-pembayaran", "#ekspedisi",
        "#alamat-penerima", "#nomor-pelanggan", "#catatan", "#metode-pembayaran",
    )

    private fun sampleTokenMap(): Map<String, String> {
        val storeName = context.prefs().getString("store_name", "")?.ifEmpty { "Toko Kami" } ?: "Toko Kami"
        return mapOf(
            "#nama-toko" to storeName,
            "#nama-pelanggan" to "Agus",
            "#tanggal-invoice" to "20 September 2026",
            "#rincian-produk" to "1. Produk A (x2) : Rp 20.000\n2. Produk B (x1) : Rp 15.000",
            "#total-harga-produk" to "Rp 35.000",
            "#alamat-penerima" to "Jl. Merdeka No. 12, Bandung",
            "#nomor-pelanggan" to "082123456789",
            "#ekspedisi" to "JNE",
            "#layanan-ekspedisi" to "Reguler",
            "#ongkir" to "Rp 10.000",
            "#catatan" to "Catatan pesanan",
            "#total-pembayaran" to "Rp 45.000",
            "#metode-pembayaran" to "- BCA a/n Toko 02816416227",
        )
    }

    private fun renderTemplatePreview(template: String, allowedTokens: Set<String>): String {
        var result = template
        sampleTokenMap().filterKeys { it in allowedTokens }.forEach { (token, value) -> result = result.replace(token, value) }
        return result
    }

    private fun buildTemplateField(initial: String): EditText = EditText(context).apply {
        setText(initial)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        isSingleLine = false
        gravity = Gravity.TOP or Gravity.START
        textSize = 12f
        setTextColor(0xFF1E293B.toInt())
        background = null
        setPadding(0, 0, 0, 0)
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun buildTemplateEditorBox(field: EditText): View {
        val box = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170f))
            background = fieldBackground()
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            addView(field)
        }
        field.enableSellbyRouting { hasFocus -> box.background = fieldBackground(hasFocus) }
        return box
    }

    private fun buildTemplateSectionDivider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
        setBackgroundColor(0xFFE2E8F0.toInt())
    }

    private fun buildTemplateInvoiceView(): View {
        val current = context.prefs().getString("sellby_template_invoice", "")?.takeIf { it.isNotBlank() } ?: defaultInvoiceTemplate
        val field = buildTemplateField(current)
        val editorBox = buildTemplateEditorBox(field)
        val previewLabel = TextView(context).apply {
            textSize = 11f; setTextColor(0xFF64748B.toInt())
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(0xFFF1F5F9.toInt()) }
        }
        fun updatePreview() { previewLabel.text = renderTemplatePreview(field.text.toString(), invoiceTokens) }
        field.doAfterTextChanged { updatePreview() }
        updatePreview()

        val saveButton = buildPillButton("Simpan") {
            context.prefs().edit { putString("sellby_template_invoice", field.text.toString()) }
            SellbyInputRouter.unfocus()
            showToast("Template Invoice berhasil disimpan!")
            showMenu()
        }

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(TextView(context).apply {
                text = "Invoice Text"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(spacer(12f))
            addView(TextView(context).apply {
                text = "Template Pesan Invoice Text"; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
            })
            addView(spacer(6f))
            addView(editorBox)
            addView(spacer(14f))
            addView(buildTemplateSectionDivider())
            addView(spacer(14f))
            addView(TextView(context).apply {
                text = "Template Pesan Invoice Text (Pratinjau)"; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
            })
            addView(spacer(6f))
            addView(previewLabel)
            addView(spacer(20f))
            addView(saveButton)
            addView(spacer(10f))
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    private fun handleResetInvoiceTemplate() {
        showCountdownConfirm(
            "Reset Template Invoice?",
            "PERINGATAN: Susunan teks template invoice akan dikembalikan ke susunan bawaan awal pabrik (default).",
            0xFFEF4444.toInt()
        ) {
            context.prefs().edit { remove("sellby_template_invoice") }
            showToast("Template Invoice berhasil di-reset ke default!")
            content.removeAllViews()
            content.addView(buildTemplateInvoiceView())
        }
    }

    private fun statusTemplateKey(tab: Int): String = when (tab) {
        0 -> "sellby_template_status_pending"
        1 -> "sellby_template_status_lunas"
        2 -> "sellby_template_status_proses"
        else -> "sellby_template_status_selesai"
    }

    private fun statusTemplateDefault(tab: Int): String = when (tab) {
        0 -> "Halo Kak #nama-pelanggan, pesanan Kakak di #nama-toko dengan total #total-pembayaran saat ini sedang dalam status Pending. Silakan lakukan pembayaran yaa!"
        1 -> "Terima kasih Kak #nama-pelanggan! Pembayaran pesanan Kakak sebesar #total-pembayaran di #nama-toko telah kami terima (LUNAS). Pesanan Kakak akan segera kami proses yaa!"
        2 -> "Halo Kak #nama-pelanggan, pesanan Kakak di #nama-toko sedang DIPROSES / dikemas dan akan segera dikirim melalui #ekspedisi."
        else -> "Halo Kak #nama-pelanggan, pesanan Kakak di #nama-toko telah SELESAI / terkirim. Terima kasih banyak sudah berbelanja di toko kami!"
    }

    private var selectedStatusTab = 0

    private fun buildTemplateStatusView(): View {
        selectedStatusTab = 0
        val tabLabels = listOf("Pending Text", "Lunas Text", "Proses Text", "Selesai Text")
        val fieldsByTab = (0..3).associateWith { tab ->
            val stored = context.prefs().getString(statusTemplateKey(tab), "")?.takeIf { it.isNotBlank() } ?: statusTemplateDefault(tab)
            buildTemplateField(stored)
        }
        val editorHolder = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val previewLabel = TextView(context).apply {
            textSize = 11f; setTextColor(0xFF64748B.toInt())
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(0xFFF1F5F9.toInt()) }
        }
        val tabViews = mutableListOf<TextView>()

        fun updatePreview() {
            val field = fieldsByTab[selectedStatusTab] ?: return
            previewLabel.text = renderTemplatePreview(field.text.toString(), statusTokens)
        }
        fun updateTabColors() {
            tabViews.forEachIndexed { index, tv ->
                val active = index == selectedStatusTab
                tv.setTextColor(if (active) Color.WHITE else 0xFF1E293B.toInt())
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    setColor(if (active) teal() else 0xFFE2E8F0.toInt())
                }
            }
        }
        fun showTab(tab: Int) {
            selectedStatusTab = tab
            editorHolder.removeAllViews()
            editorHolder.addView(buildTemplateEditorBox(fieldsByTab.getValue(tab)))
            updateTabColors()
            updatePreview()
        }

        val tabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        tabLabels.forEachIndexed { index, label ->
            val tv = TextView(context).apply {
                text = label; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(4f)
                }
                isClickable = true; isFocusable = true
                setOnClickListener { showTab(index) }
            }
            tabViews.add(tv)
            tabsRow.addView(tv)
        }
        fieldsByTab.values.forEach { f -> f.doAfterTextChanged { updatePreview() } }

        val saveButton = buildPillButton("Simpan") {
            context.prefs().edit {
                (0..3).forEach { tab -> putString(statusTemplateKey(tab), fieldsByTab.getValue(tab).text.toString()) }
            }
            SellbyInputRouter.unfocus()
            showToast("Template Status berhasil disimpan!")
            showMenu()
        }

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(TextView(context).apply {
                text = "Status Text"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(spacer(10f))
            addView(tabsRow)
            addView(spacer(12f))
            addView(TextView(context).apply {
                text = "Template Pesan Status Text"; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
            })
            addView(spacer(6f))
            addView(editorHolder)
            addView(spacer(14f))
            addView(buildTemplateSectionDivider())
            addView(spacer(14f))
            addView(TextView(context).apply {
                text = "Template Pesan Status Text (Pratinjau)"; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
            })
            addView(spacer(6f))
            addView(previewLabel)
            addView(spacer(20f))
            addView(saveButton)
            addView(spacer(10f))
        }
        showTab(0)
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    private fun handleResetStatusTemplate() {
        showCountdownConfirm(
            "Reset Template Status?",
            "PERINGATAN: Seluruh susunan teks template status akan dikembalikan ke susunan bawaan awal pabrik (default).",
            0xFFEF4444.toInt()
        ) {
            context.prefs().edit {
                remove("sellby_template_status_pending"); remove("sellby_template_status_lunas")
                remove("sellby_template_status_proses"); remove("sellby_template_status_selesai")
            }
            showToast("Template Status berhasil di-reset ke default!")
            content.removeAllViews()
            content.addView(buildTemplateStatusView())
        }
    }

    // ==================================================================================
    // Sub-bagian: List Ekspedisi (settings_panel.dart:2056-2184) - ExpeditionDao.setEnabled() already
    // existed (added when Panel Ongkir was built) - no new DAO needed. Unlike Metode Pembayaran/Data
    // Pelanggan (autosave per mutation), toggles here are LOCAL state only until "Simpan" is tapped,
    // matching Flutter's own _saveExpeditions() (only persisted on explicit save).
    // ==================================================================================

    private fun openListEkspedisiSubmenu() {
        CoroutineScope(Dispatchers.IO).launch {
            db.expeditionDao().seedIfAbsent(ExpeditionCatalog.all.map { Expedition(id = it.id, isEnabled = true) })
            // Cleans up couriers removed from the catalog (Rara/JDL) that may have been seeded
            // into the table before they were removed - see ExpeditionDao.deleteByIds().
            db.expeditionDao().deleteByIds(ExpeditionCatalog.REMOVED_IDS)
            val enabledIds = db.expeditionDao().getEnabledIds().first().toSet()
            withContext(Dispatchers.Main) {
                currentBackAction = { showMenu() }
                val state = ExpeditionCatalog.all.associate { it.id to (it.id in enabledIds) }.toMutableMap()
                content.removeAllViews()
                content.addView(buildListEkspedisiView(state))
            }
        }
    }

    private fun buildListEkspedisiView(state: MutableMap<String, Boolean>): View {
        val banner = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(0xFFFEF9C3.toInt()) }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f); bottomMargin = dp(10f)
            }
            addView(ImageView(context).apply {
                setImageResource(android.R.drawable.ic_dialog_alert)
                setColorFilter(0xFFF59E0B.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(8f) }
            })
            addView(TextView(context).apply {
                text = "Daftar Ekspedisi akan dimunculkan dibagian menu Cek ongkir, dan input data pengiriman di menu Invoice."
                textSize = 10.5f; setTextColor(0xFF92400E.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        val listContainer = LinearLayout(context).apply { orientation = VERTICAL }
        ExpeditionCatalog.all.forEach { item ->
            lateinit var box: FrameLayout
            lateinit var check: TextView
            fun render() {
                val enabled = state[item.id] == true
                box.background = GradientDrawable().apply {
                    cornerRadius = dp(6f).toFloat()
                    if (enabled) setColor(teal()) else { setColor(Color.WHITE); setStroke(dp(1.5f).coerceAtLeast(1), 0xFFCBD5E1.toInt()) }
                }
                check.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
            }
            check = TextView(context).apply {
                text = "✓"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            box = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
                addView(check, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            val label = TextView(context).apply {
                text = item.displayName; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(10f), 0, 0, 0)
            }
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(9f), dp(4f), dp(9f))
                isClickable = true; isFocusable = true
                setOnClickListener { state[item.id] = state[item.id] != true; render() }
                addView(box); addView(label)
            }
            render()
            listContainer.addView(row)
            listContainer.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
                setBackgroundColor(0xFFE2E8F0.toInt())
            })
        }
        val saveButton = buildPillButton("Simpan") {
            CoroutineScope(Dispatchers.IO).launch {
                state.forEach { (id, enabled) -> db.expeditionDao().setEnabled(id, enabled) }
                withContext(Dispatchers.Main) {
                    showToast("Daftar ekspedisi berhasil disimpan!")
                    showMenu()
                }
            }
        }
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(TextView(context).apply {
                text = "List Ekspedisi"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(banner)
            addView(listContainer)
            addView(spacer(16f))
            addView(saveButton)
            addView(spacer(10f))
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    // ==================================================================================
    // Sub-bagian: Atur Keyboard (new - not part of settings_panel.dart, no Flutter equivalent).
    // Exposes 3 keyboard-behavior prefs that already exist in this HeliBoard fork's own settings
    // system (Settings.PREF_AUTO_CAP/PREF_VIBRATE_ON/PREF_SOUND_ON) - same "relocate an existing
    // toggle, invent no new mechanism" approach as the dark/light theme toggle above. Settings.java
    // already registers an OnSharedPreferenceChangeListener that calls loadSettings() whenever any
    // of these keys changes (confirmed by reading it - reloadOnChanged() allows every key through
    // except a short emoji/subtype exclusion list), so a plain SharedPreferences write here is
    // picked up automatically - no reload call needed, unlike setThemeNeedsReload() for colors.
    // ==================================================================================

    private fun buildKeyboardToggleRow(label: String, prefKey: String, defaultValue: Boolean, onChanged: ((Boolean) -> Unit)? = null): View {
        val current = context.prefs().getBoolean(prefKey, defaultValue)
        val labelView = TextView(context).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggle = buildToggleSwitch(current) { newValue ->
            context.prefs().edit { putBoolean(prefKey, newValue) }
            onChanged?.invoke(newValue)
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(11f), dp(6f), dp(11f))
            addView(labelView)
            addView(toggle)
        }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            addView(row)
            addView(divider)
        }
    }

    /** settings_panel.dart has no equivalent (native-only addition). Gboard-style: the leftmost tick
     *  (progress 0) means "Default (mengikuti volume sistem)" and persists the exact same negative
     *  sentinel stock HeliBoard already defaults to (Defaults.PREF_KEYPRESS_SOUND_VOLUME = -0.01f,
     *  AudioManager's own "use system default" signal) - only past that point does it become an
     *  explicit override, position/100f mapped straight onto AudioManager.playSoundEffect's 0f-1f
     *  range. Plays a live preview click on release (using whichever of the two meanings currently
     *  applies) so the user can dial it in without leaving Settings; this talks to AudioManager
     *  directly rather than through AudioAndHapticFeedbackManager's cached SettingsValues (which only
     *  refreshes at LatinIME's own loadSettings() points), so the preview is never stale. */
    private fun buildVolumeSliderRow(): View {
        val label = TextView(context).apply {
            text = "Volume Suara Keyboard"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            setPadding(dp(6f), dp(8f), dp(6f), dp(2f))
        }
        val valueLabel = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
            setPadding(dp(6f), 0, dp(6f), 0)
        }
        fun describe(progress: Int) = if (progress <= 0) "Default (mengikuti volume sistem)" else "$progress%"

        val current = context.prefs().getFloat(Settings.PREF_KEYPRESS_SOUND_VOLUME, Defaults.PREF_KEYPRESS_SOUND_VOLUME)
        val initialProgress = if (current < 0f) 0 else (current * 100).toInt().coerceIn(1, 100)
        valueLabel.text = describe(initialProgress)

        // Thick, rounded-cap track drawn with plain Views instead of SeekBar's own
        // progressDrawable - a custom drawable assigned there rendered NOTHING at all on this
        // device (not even the default thin line), almost certainly MIUI's miuix widget layer
        // overriding SeekBar's track drawing regardless of what's set programmatically. Sidesteps
        // that entirely: the SeekBar below is kept only for touch-dragging and its own round
        // thumb (transparent progress drawable, draws no track of its own), and the visible
        // bar - a gray full-width pill with a teal pill on top sized to the current progress -
        // is 2 plain Views this code fully controls, stacked in a FrameLayout right behind it.
        val trackRadius = dp(4f).toFloat()
        val trackBg = View(context).apply {
            background = GradientDrawable().apply { cornerRadius = trackRadius; setColor(0xFFCBD5E1.toInt()) }
        }
        val trackFill = View(context).apply {
            background = GradientDrawable().apply { cornerRadius = trackRadius; setColor(teal()) }
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val trackStack = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(8f)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(12f); marginEnd = dp(12f)
            }
            addView(trackBg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(trackFill)
        }
        fun updateTrackFill(progress: Int) {
            trackStack.post {
                val totalWidth = trackStack.width
                if (totalWidth <= 0) return@post
                val lp = trackFill.layoutParams as FrameLayout.LayoutParams
                val newWidth = (totalWidth * (progress / 100f)).toInt()
                if (lp.width != newWidth) {
                    lp.width = newWidth
                    trackFill.layoutParams = lp
                }
            }
        }

        lateinit var seekBar: SeekBar
        seekBar = SeekBar(context).apply {
            max = 100
            progress = initialProgress
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            // No track of its own to draw (see comment above) - only the thumb should paint.
            progressDrawable = ColorDrawable(Color.TRANSPARENT)
            thumbTintList = ColorStateList.valueOf(teal())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueLabel.text = describe(progress)
                    updateTrackFill(progress)
                    if (!fromUser) return
                    val volume = if (progress <= 0) Defaults.PREF_KEYPRESS_SOUND_VOLUME else progress / 100f
                    context.prefs().edit { putFloat(Settings.PREF_KEYPRESS_SOUND_VOLUME, volume) }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val previewVolume = if (seekBar.progress <= 0) Defaults.PREF_KEYPRESS_SOUND_VOLUME else seekBar.progress / 100f
                    (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                        ?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, previewVolume)
                }
            })
        }
        val sliderStack = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(trackStack)
            addView(seekBar)
        }
        sliderStack.doOnLayout { updateTrackFill(seekBar.progress) }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label)
            addView(sliderStack)
            addView(valueLabel)
            addView(divider)
        }
    }

    /** A slider with [steps] + 1 discrete positions (0..[steps]). Same technique as the volume slider
     *  above - the track is drawn with plain Views because MIUI's widget layer overrides SeekBar's own
     *  progressDrawable, and the SeekBar on top only provides dragging and its round thumb - but kept
     *  as its own helper so the volume slider stays exactly as it was. [onGrab] fires when a touch
     *  starts, [onStep] on every user-driven change of position (drag or tap on the track). */
    private fun buildStepSlider(
        steps: Int,
        initialProgress: Int,
        onGrab: () -> Unit,
        onRelease: () -> Unit,
        onStep: (Int) -> Unit,
    ): View {
        val trackRadius = dp(4f).toFloat()
        val trackBg = View(context).apply {
            background = GradientDrawable().apply { cornerRadius = trackRadius; setColor(0xFFCBD5E1.toInt()) }
        }
        val trackFill = View(context).apply {
            background = GradientDrawable().apply { cornerRadius = trackRadius; setColor(teal()) }
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val trackStack = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(8f)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(12f); marginEnd = dp(12f)
            }
            addView(trackBg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(trackFill)
        }
        fun applyTrackFill(progress: Int) {
            val totalWidth = trackStack.width
            if (totalWidth <= 0) return
            val lp = trackFill.layoutParams as FrameLayout.LayoutParams
            val newWidth = (totalWidth * (progress / steps.toFloat())).toInt()
            if (lp.width != newWidth) {
                lp.width = newWidth
                trackFill.layoutParams = lp
            }
        }
        // Immediately once laid out (a drag must not trail a frame behind the thumb), deferred before that.
        fun updateTrackFill(progress: Int) {
            if (trackStack.width > 0) applyTrackFill(progress) else trackStack.post { applyTrackFill(progress) }
        }
        val seekBar = SeekBar(context).apply {
            max = steps
            progress = initialProgress
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            progressDrawable = ColorDrawable(Color.TRANSPARENT)
            thumbTintList = ColorStateList.valueOf(teal())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateTrackFill(progress)
                    if (fromUser) onStep(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = onGrab()
                override fun onStopTrackingTouch(bar: SeekBar?) = onRelease()
            })
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(trackStack)
            addView(seekBar)
            doOnLayout { updateTrackFill(seekBar.progress) }
        }
    }

    /** Settings -> Atur Keyboard -> "Ukuran keyboard": a continuous Pendek <-> Tinggi slider over the
     *  keyboard height scale (see [KeyboardSizeScale]). The value is written to the pref and applied to
     *  the keys while dragging - without hiding the keyboard window, which would close this panel (see
     *  KeyboardSwitcher.applyKeyboardHeightLive) - so the keyboard grows/shrinks under the user's finger.
     *  The slider has no steps, so a drag produces far more positions than the keyboard can be rebuilt
     *  for (every pref write reloads the settings, then the keys are rebuilt): the latest position is
     *  committed at most every [SIZE_COMMIT_INTERVAL_MS], and once more the moment the finger lifts.
     *  [onGrab] is called when a drag starts; the caller uses it to scroll this row to the top of the
     *  panel, because the panel above the keys gets shorter as the keys get taller and a row left near
     *  the panel's bottom edge would slide out of view in the middle of the drag. */
    private fun buildKeyboardSizeRow(onGrab: () -> Unit): View {
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isFolded = FoldableUtils.isFolded
        val prefs = context.prefs()
        val prefKey = KeyboardSizeScale.prefKey(isLandscape, isFolded)
        val label = TextView(context).apply {
            text = "Ukuran keyboard"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            setPadding(dp(6f), dp(8f), dp(6f), dp(2f))
        }
        fun endLabel(value: String) = TextView(context).apply {
            text = value
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
        }
        val stored = KeyboardSizeScale.current(prefs, isLandscape, isFolded)
        if (stored != KeyboardSizeScale.clamp(stored)) {
            // Left over from an earlier, wider version of this slider (or the stock settings screen): bring
            // the keyboard itself into the range too, so the thumb below and the keys agree.
            prefs.edit { putFloat(prefKey, KeyboardSizeScale.clamp(stored)) }
            KeyboardSwitcher.getInstance().applyKeyboardHeightLive()
        }

        val handler = Handler(Looper.getMainLooper())
        var pendingScale: Float? = null
        var lastCommitAt = 0L
        val commit = object : Runnable {
            override fun run() {
                val scale = pendingScale ?: return
                pendingScale = null
                lastCommitAt = SystemClock.uptimeMillis()
                prefs.edit { putFloat(prefKey, scale) }
                KeyboardSwitcher.getInstance().applyKeyboardHeightLive()
            }
        }
        val slider = buildStepSlider(
            KeyboardSizeScale.STEPS,
            KeyboardSizeScale.toProgress(KeyboardSizeScale.clamp(stored)),
            onGrab,
            onRelease = {
                handler.removeCallbacks(commit)
                commit.run()
            },
        ) { progress ->
            pendingScale = KeyboardSizeScale.toScale(progress)
            handler.removeCallbacks(commit)
            val wait = (lastCommitAt + SIZE_COMMIT_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            handler.postDelayed(commit, wait)
        }
        val sliderRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(6f), dp(6f))
            addView(endLabel("Pendek"))
            addView(slider)
            addView(endLabel("Tinggi"))
        }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
            setBackgroundColor(0xFFE2E8F0.toInt())
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label)
            addView(sliderRow)
            addView(divider)
        }
    }

    private fun buildAturKeyboardView(): View {
        val title = TextView(context).apply {
            text = "Atur Keyboard"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) }
        }
        val soundOn = context.prefs().getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)
        val volumeRow = buildVolumeSliderRow().apply {
            visibility = if (soundOn) View.VISIBLE else View.GONE
        }
        val scroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        lateinit var sizeRow: View
        sizeRow = buildKeyboardSizeRow(onGrab = {
            val top = sizeRow.top
            if (scroll.scrollY != top) scroll.smoothScrollTo(0, top)
        })
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            addView(title)
            // First in the list on purpose (not last): the panel above the keys gets shorter as the keys
            // get taller, so a row at the bottom of this list would slide out of view mid-drag - at the
            // top, the row stays exactly where the user's finger is (see buildKeyboardSizeRow).
            addView(sizeRow)
            // Reuses HeliBoard's own stock "key borders" theme toggle (AppearanceScreen.kt,
            // Settings.PREF_THEME_KEY_BORDERS) - draws a rounded-square outline around every key -
            // same "relocate an existing pref, invent no new mechanism" approach as the other
            // toggles here. Unlike those (plain SettingsValues fields, picked up live once
            // Settings.java's own change-listener reloads them), this one changes which key
            // background DRAWABLE gets built into the keyboard theme, so it needs the same
            // immediate setThemeNeedsReload() the dark/light toggle above already uses.
            addView(buildKeyboardToggleRow("Border pada Tombol", Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS) {
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            })
            addView(buildKeyboardToggleRow("Auto Kapital", Settings.PREF_AUTO_CAP, Defaults.PREF_AUTO_CAP))
            addView(buildKeyboardToggleRow("Getar saat disentuh", Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON))
            addView(buildKeyboardToggleRow("Suara Keyboard", Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON) { newValue ->
                volumeRow.visibility = if (newValue) View.VISIBLE else View.GONE
            })
            addView(volumeRow)
            addView(buildKeyboardToggleRow("Spasi Setelah Tanda Baca", Settings.PREF_AUTOSPACE_AFTER_PUNCTUATION, Defaults.PREF_AUTOSPACE_AFTER_PUNCTUATION))
        }
        scroll.addView(list)
        return scroll
    }
}
