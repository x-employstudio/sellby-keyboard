// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.content.Context
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.doOnLayout
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.widget.doAfterTextChanged
import android.graphics.drawable.GradientDrawable
import java.io.File
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.data.ExpeditionCatalog
import helium314.keyboard.sellby.data.ExpeditionCatalogItem
import helium314.keyboard.sellby.data.SellbyDatabase
import helium314.keyboard.sellby.data.entity.Order
import helium314.keyboard.sellby.data.entity.OrderItem
import helium314.keyboard.sellby.data.entity.OrderStatus
import helium314.keyboard.sellby.data.entity.PaymentMethod
import helium314.keyboard.sellby.data.entity.Product
import helium314.keyboard.sellby.input.SellbyInputRouter
import helium314.keyboard.sellby.input.enableSellbyRouting
import helium314.keyboard.sellby.util.Channel
import helium314.keyboard.sellby.util.CurrencyFormat
import helium314.keyboard.sellby.util.IdentifierKind
import helium314.keyboard.sellby.util.OrderFormParser
import helium314.keyboard.sellby.util.ParsedOrderForm
import helium314.keyboard.sellby.util.bold
import helium314.keyboard.sellby.util.strike
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Panel Invoice (`sellby_panel_region`, tab "Invoice" in SellbyToolbarView) - ported from
 * invoice_panel.dart (2665 lines), the most complex panel in the app. Round A scope (see plan file):
 * full end-to-end "build and send an invoice" flow with manual product entry, real Room-backed
 * payment methods/expeditions, exact text-generation template, order creation and stock decrement.
 * Deferred to Round B: catalog product search, stock-warning dialog, contact-picker dialog and
 * the Reset button/dialog - all disclosed, not silent gaps. (The Flutter original's "+ Cek Ongkir"
 * cross-panel button was built and later removed again on request.)
 */
class InvoicePanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private data class InvoiceItem(
        val id: String,
        val name: String,
        val quantity: Int,
        val unitPrice: Double,
        val originalUnitPrice: Double = unitPrice,
        val discountAmount: Double = 0.0,
    ) {
        val subtotal: Double get() = unitPrice * quantity
        val hasDiscount: Boolean get() = discountAmount > 0.0
        val discountPercent: Int
            get() = if (originalUnitPrice <= 0.0) 0
                else ((discountAmount / originalUnitPrice) * 100).roundToInt().coerceAtMost(100)
    }

    private data class SearchResultItem(val product: Product, var pcs: Int = 1)

    private val db by lazy { SellbyDatabase.getInstance(context.applicationContext) }
    private val items = mutableListOf<InvoiceItem>()
    private var manualQuantity = 1
    private var selectedExpedition: String? = null
    private var selectedService: String? = null
    private var activePayments: List<PaymentMethod> = emptyList()
    private val selectedPaymentIds = mutableSetOf<String>()
    private var enabledExpeditions: List<ExpeditionCatalogItem> = emptyList()
    private var suppressDraftSave = false
    private var catalogProducts: List<Product> = emptyList()
    private var isSearchExpanded = false
    private var searchResults: List<SearchResultItem> = emptyList()

    private var initialized = false
    private lateinit var content: FrameLayout
    private lateinit var panelOverlay: FrameLayout
    private var toastView: View? = null

    // Sellby: "Pilih Channel" feature (WhatsApp/WhatsApp Business/Telegram/Instagram) - no Flutter
    // equivalent, see the plan file. Default matches the app's pre-existing WhatsApp-only behavior.
    private var selectedChannel: Channel = Channel.WHATSAPP
    private lateinit var channelRow: LinearLayout
    private lateinit var contactBtn: ImageView
    private lateinit var phonePrefixLabel: TextView
    private lateinit var phonePrefixDivider: View
    private lateinit var nameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var manualNameField: EditText
    private lateinit var manualPriceField: EditText
    private lateinit var quantityField: EditText
    private lateinit var itemsContainer: LinearLayout
    private lateinit var bodyScrollView: ScrollView
    private lateinit var searchField: EditText
    private lateinit var searchRow: View
    private lateinit var searchClearButton: View
    private var searchResultsView: LinearLayout? = null
    private var searchPopup: PopupWindow? = null
    private lateinit var additionalDiscountField: EditText
    private lateinit var notesField: EditText
    private lateinit var addressField: EditText
    private lateinit var shippingCostField: EditText
    private lateinit var expeditionServiceSummaryLabel: TextView
    private var expeditionServicePicker: View? = null
    private lateinit var paymentRow: LinearLayout
    private lateinit var totalValueLabel: TextView

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun teal() = ContextCompat.getColor(context, R.color.calculator_accent)

    fun initialize() {
        if (initialized) return
        initialized = true
        orientation = VERTICAL

        // Everything (header + body + bottom bar) lives inside `column`; `panelOverlay` is a
        // sibling added AFTER it so it draws on top of the whole panel, not just `content` - EVERY
        // confirm/info dialog in this panel (Reset, stock-empty, Direct Ongkir) renders there now,
        // so they consistently sit over the header/chevron/reset AND the bottom "Total Pembayaran"
        // bar. Entirely local to this panel's own view tree, same pattern as StatusPanelView's - it
        // can never reach outside this panel's own bounds, so it can't cover the physical keyboard
        // below it.
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
            content.addView(buildBody())
            addView(content)
            addView(buildBottomBar())
        }
        panelOverlay = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        addView(FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(column)
            addView(panelOverlay)
        })
    }

    /** Called by SellbyToolbarView every time the Invoice tab is (re)opened - mirrors
     *  invoice_panel.dart's initState() re-running on every mount: reloads payment methods,
     *  enabled expeditions and the saved draft fresh from Room/SharedPreferences. */
    fun refresh() {
        initialize()
        confirmOverlay?.let { panelOverlay.removeView(it); confirmOverlay = null }
        expeditionServicePicker?.let { panelOverlay.removeView(it); expeditionServicePicker = null }
        CoroutineScope(Dispatchers.IO).launch {
            val active = db.paymentMethodDao().getActive().first()
            val payments = active.ifEmpty { listOf(FALLBACK_TUNAI) }
            val enabledIds = db.expeditionDao().getEnabledIds().first().toSet()
            val exps = ExpeditionCatalog.all.filter { it.id in enabledIds }
            val products = db.productDao().getAll().first()
            withContext(Dispatchers.Main) {
                activePayments = payments
                enabledExpeditions = exps
                catalogProducts = products
                isSearchExpanded = false
                if (selectedPaymentIds.isEmpty()) payments.firstOrNull()?.let { selectedPaymentIds.add(it.id) }
                loadDraft()
                rebuildPaymentRow()
                rebuildItemsList()
                collapseSearch()
                maybeOfferOrderFormPaste()
            }
        }
    }

    /** The system clipboard's current text clip as (text, time the clip was set), or null when it's
     *  empty / not text / unreadable. The time is 0 where Android doesn't provide it (API < 26).
     *  The keyboard is the active IME while this panel is open, which is what Android 10+ requires
     *  for a background-less app to read the clipboard at all. */
    private fun readClipboard(): Pair<String, Long>? = try {
        val clip = clipboardManager().primaryClip
        if (clip == null || clip.itemCount == 0 || clip.description?.hasMimeType("text/*") != true) null
        else clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
            text to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clip.description.timestamp else 0L)
        }
    } catch (e: Exception) {
        null
    }

    /** Opening the Invoice tab: if the clipboard holds a buyer-filled order form (see
     *  OrderFormParser), offer to fill Nama / No HP / Alamat from it. Each COPY is offered once -
     *  the answer (Tempel or Abaikan) is remembered by when the clip was set, so reopening the tab
     *  (or finishing the invoice, which leaves the same clip on the clipboard) doesn't ask again,
     *  but copying the very same text AGAIN is a new copy (new timestamp) and offers again. Where
     *  Android gives no timestamp the text's hash is the only identity left, so an identical
     *  re-copy can't be told apart there. */
    private fun maybeOfferOrderFormPaste() {
        val (text, timestamp) = readClipboard() ?: return
        if (text.length > MAX_ORDER_FORM_CLIP_CHARS) return
        val hash = if (timestamp > 0) "$timestamp:${text.hashCode()}" else text.hashCode().toString()
        val prefs = context.prefs()
        if (prefs.getString(PREF_LAST_OFFERED_CLIP, null) == hash) return
        // This can now fire while the panel is already in use (see clipboardListener): never cut
        // into another dialog or the expedition picker, and don't re-show the offer already on screen.
        // A newer copy DOES replace an older offer that's still unanswered (the newest copy wins).
        if (expeditionServicePicker != null) return
        if (confirmOverlay != null && confirmOverlay !== orderFormOffer) return
        if (confirmOverlay != null && offeringClipHash == hash) return
        val parsed = OrderFormParser.parse(text) ?: return
        // Instagram's field holds a username, so a phone number from the form has nowhere to go.
        val phone = parsed.phone?.takeIf { selectedChannel.identifierKind != IdentifierKind.USERNAME }
        val detail = buildList {
            parsed.name?.let { add("Nama" to it) }
            phone?.let { add("No HP" to it) }
            parsed.address?.let { add("Alamat" to it) }
        }
        if (detail.isEmpty()) return
        fun markHandled() = prefs.edit().putString(PREF_LAST_OFFERED_CLIP, hash).apply()
        showConfirmOverlay(
            title = "Form Order Terdeteksi",
            message = "Isi data pelanggan berikut ke Invoice?",
            confirmLabel = "Tempel",
            cancelLabel = "Abaikan",
            detail = detail,
            onCancel = { markHandled() },
        ) {
            markHandled()
            applyOrderForm(parsed)
        }
        orderFormOffer = confirmOverlay
        offeringClipHash = hash
    }

    // The offer currently on screen (if any) and which copy it is for - see maybeOfferOrderFormPaste().
    private var orderFormOffer: View? = null
    private var offeringClipHash: String? = null

    // Copying a form WHILE this panel is already open should offer straight away, not only the
    // next time the tab is opened. Registered for the view's whole attached lifetime (cheap) and
    // gated on the panel actually being on screen: the callback also fires while the keyboard is
    // hidden or another tab is showing, where an offer must not pop up - those copies are picked
    // up by refresh() / onWindowVisibilityChanged() once the panel is in front of the user again.
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (isOnScreen()) maybeOfferOrderFormPaste()
    }

    private fun isOnScreen() = initialized && isShown && windowVisibility == View.VISIBLE

    private fun clipboardManager() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        clipboardManager().addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onDetachedFromWindow() {
        clipboardManager().removePrimaryClipChangedListener(clipboardListener)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Keyboard shown again with this panel still open (it was hidden while the form got copied).
        if (visibility == View.VISIBLE && isOnScreen()) post { if (isOnScreen()) maybeOfferOrderFormPaste() }
    }

    private fun applyOrderForm(parsed: ParsedOrderForm) {
        parsed.name?.let { nameField.setText(it) }
        if (selectedChannel.identifierKind != IdentifierKind.USERNAME) {
            parsed.phone?.let { phoneField.setText(localPhoneDigits(it)) }
        }
        parsed.address?.let { addressField.setText(it) }
        saveDraft()
    }

    /** phoneField always holds the number WITHOUT the "+62" prefix (that's rendered separately as
     *  buildPhoneRow()'s static prefix), so strip non-digits, then a leading "62" or "0" - the same
     *  normalization generateAndSendInvoice() applies. */
    private fun localPhoneDigits(raw: String): String {
        var digits = raw.filter { it.isDigit() }
        if (digits.startsWith("62")) digits = digits.substring(2)
        if (digits.startsWith("0")) digits = digits.substring(1)
        return digits
    }

    // ---------------------------------------------------------------- header

    private fun circleButton(icon: Int): ImageView = ImageView(context).apply {
        setImageResource(icon)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(teal()) }
        layoutParams = LayoutParams(dp(32f), dp(32f))
        setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
        isClickable = true
        isFocusable = true
    }

    private fun buildHeader(): View {
        val back = circleButton(R.drawable.ic_settings_chevron_down_sellby).apply {
            setOnClickListener { KeyboardSwitcher.getInstance().sellbyToolbarView?.closeIfOpen() }
        }
        val title = TextView(context).apply {
            text = "Invoice"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val reset = circleButton(R.drawable.ic_toolbar_reset_sellby).apply {
            setOnClickListener {
                showConfirmOverlay(
                    title = "Reset Formulir Invoice?",
                    message = "Semua data yang telah diisi akan dihapus dan kembali ke tampilan awal.",
                    confirmLabel = "Lanjutkan",
                ) {
                    resetForm()
                    showToast("Form invoice berhasil di-reset!")
                }
            }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            addView(back)
            addView(title)
            addView(reset)
        }
    }

    // ---------------------------------------------------------------- shared field helpers

    private fun sectionHeader(title: String): View = TextView(context).apply {
        text = title
        textSize = 13.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(teal())
        gravity = Gravity.CENTER
        letterSpacing = 0.01f
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f); bottomMargin = dp(4f)
        }
    }

    private fun subHeader(title: String): View = TextView(context).apply {
        text = title
        textSize = 10.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF64748B.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f); bottomMargin = dp(3f)
        }
    }

    private fun spacer(heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(heightDp))
    }

    private fun fieldBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        setColor(0xFFE2E8F0.toInt())
    }

    private fun plainField(hintText: String, inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(context).apply {
            hint = hintText
            this.inputType = inputType
            textSize = 12f
            isSingleLine = true
            setTextColor(0xFF1E293B.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(10f), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
            background = fieldBackground()
            enableSellbyRouting { hasFocus ->
                (background as GradientDrawable).setStroke(if (hasFocus) dp(1.5f) else 0, teal())
            }
        }

    /** "Rp " prefix + digit-only EditText, live-grouped as the user types (ThousandsSeparatorInputFormatter). */
    private fun rupiahField(hintText: String): EditText {
        val field = EditText(context).apply {
            hint = hintText
            // Deliberately NOT TYPE_CLASS_NUMBER: Android attaches both an InputFilter[] AND a
            // separate KeyListener (DigitsKeyListener) for numeric inputType, and the KeyListener
            // restricts Editable changes to digits-only independently of `filters` - it was silently
            // stripping the "." our grouping formatter inserts below (clearing `filters` alone wasn't
            // enough, confirmed by device retest still showing "50000000" ungrouped). This field never
            // shows a system keyboard anyway (showSoftInputOnFocus=false, SellbyInputRouter drives all
            // input) so it doesn't need a numeric inputType's restrictions at all - plain text avoids
            // both mechanisms entirely.
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf()
            textSize = 12f
            isSingleLine = true
            setTextColor(0xFF1E293B.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Mutating the Editable synchronously from inside afterTextChanged is only safe when the
        // change came from a normal incremental edit (typing). When it comes from EditText.setText()
        // (loadDraft(), restoring a saved draft), TextView is still mid-swap to a new text buffer,
        // and replace()/setSelection() here raced against that and crashed (IndexOutOfBoundsException:
        // setSpan ends beyond length - confirmed via device crash log). Rather than defer the format
        // (which made it feel laggy), skip it entirely during a draft load via suppressDraftSave -
        // draft values were already formatted when saved, so there's nothing to reformat anyway.
        var isReformatting = false
        field.doAfterTextChanged { editable ->
            if (isReformatting || suppressDraftSave) return@doAfterTextChanged
            val raw = editable?.toString() ?: return@doAfterTextChanged
            val grouped = CurrencyFormat.liveDigitsToGrouped(raw)
            if (grouped != raw) {
                isReformatting = true
                editable.replace(0, editable.length, grouped)
                field.setSelection(grouped.length.coerceAtMost(editable.length))
                isReformatting = false
            }
        }
        val prefix = TextView(context).apply {
            text = "Rp "
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
            setPadding(dp(10f), 0, dp(10f), 0)
            background = fieldBackground()
            addView(prefix)
            addView(field)
        }
        field.enableSellbyRouting(isNumeric = true) { hasFocus ->
            (row.background as GradientDrawable).setStroke(if (hasFocus) dp(1.5f) else 0, teal())
        }
        row.tag = field
        return field
    }

    private fun dashedDivider(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f)).apply {
            topMargin = dp(8f); bottomMargin = dp(8f)
        }
        repeat(35) { i ->
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(1.5f), 1f)
                setBackgroundColor(if (i % 2 == 0) 0xFF7DD3FC.toInt() else Color.TRANSPARENT)
            })
        }
    }

    private fun smallActionButton(text: String, onClick: () -> Unit): View {
        val label = TextView(context).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36f))
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(teal()) }
            setPadding(dp(12f), 0, dp(12f), 0)
            isClickable = true; isFocusable = true
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { onClick() }
        }
    }

    private var toastRunnable: Runnable? = null

    private fun showToast(message: String) {
        toastView?.let { content.removeView(it) }
        toastRunnable?.let { removeCallbacks(it) }
        val toast = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0xFF1E293B.toInt()) }
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        content.addView(toast, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = dp(12f)
        })
        toastView = toast
        val runnable = Runnable {
            content.removeView(toast)
            if (toastView === toast) toastView = null
        }
        toastRunnable = runnable
        postDelayed(runnable, 2600)
    }

    private var confirmOverlay: View? = null

    /** In-panel confirm dialog (invoice_panel.dart's reset-confirm overlay: title/body/Batalkan/
     *  Lanjutkan) - an overlay on [panelOverlay] (above the WHOLE panel - header/chevron/reset AND
     *  the bottom "Total Pembayaran" bar, not just [content]) like [showToast], never a system
     *  Dialog. Entirely local to this panel's own view tree (see [panelOverlay] in initialize()),
     *  so it can never reach the physical keyboard below the panel. Every confirm dialog in this
     *  panel uses the same container/positioning now (user asked for consistency across all popups
     *  in every panel, not just Reset) - no icon circle (also what was pushing the card's height
     *  past the old content-only container's available space and clipping the button row). */
    private fun showConfirmOverlay(
        title: String, message: String, confirmLabel: String, cancelLabel: String = "Batalkan",
        detail: List<Pair<String, String>> = emptyList(), onCancel: (() -> Unit)? = null, onConfirm: () -> Unit,
    ) {
        // Sellby: PopupWindow (the search-results popup) always renders in its own window layer
        // ABOVE the rest of this panel's regular views, regardless of panelOverlay's own elevation -
        // reported bug, Reset/stock-empty confirmations were appearing BEHIND the search popup when
        // both happened to be showing. Dismissing it first removes the conflict entirely rather than
        // trying to out-elevate a PopupWindow (not possible from a regular View).
        dismissSearchPopup()
        confirmOverlay?.let { panelOverlay.removeView(it) }
        lateinit var scrim: FrameLayout

        fun dismiss() {
            panelOverlay.removeView(scrim)
            if (confirmOverlay === scrim) confirmOverlay = null
        }

        val titleView = TextView(context).apply {
            text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt()); gravity = Gravity.CENTER
        }
        val messageView = TextView(context).apply {
            text = message; textSize = 12f; setTextColor(0xFF64748B.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, if (detail.isEmpty()) dp(16f) else dp(10f))
        }
        // Optional "label: value" preview (left-aligned, bold label) between the message and the
        // buttons - used by the order-form paste offer to show exactly what's about to be filled in.
        // Capped in height and scrollable: a long value (an address) scrolls inside the box instead
        // of stretching the whole card. ScrollView has no maxHeight, so it's capped by measuring
        // with an AT_MOST spec - a short preview still wraps to its content.
        val detailBox = if (detail.isEmpty()) null else object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(100f), MeasureSpec.AT_MOST))
            }
        }.apply {
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFF1F5F9.toInt()) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16f)
            }
            // The card sits inside an outer ScrollView (cardScroll) that would otherwise steal the
            // vertical drag - keep it for this box whenever the box itself can scroll.
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN && (v.canScrollVertically(1) || v.canScrollVertically(-1)))
                    v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                detail.forEachIndexed { index, (label, value) ->
                    addView(TextView(context).apply {
                        text = SpannableStringBuilder("$label: $value").apply {
                            setSpan(StyleSpan(Typeface.BOLD), 0, label.length + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        textSize = 12f; setTextColor(0xFF1E293B.toInt())
                        if (index > 0) setPadding(0, dp(4f), 0, 0)
                    })
                }
            })
        }
        val cancelBtn = TextView(context).apply {
            text = cancelLabel; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF475569.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFE2E8F0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { dismiss(); onCancel?.invoke() }
        }
        val confirmBtn = TextView(context).apply {
            text = confirmLabel; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(teal()) }
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
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.WHITE) }
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER; marginStart = dp(22f); marginEnd = dp(22f)
            }
            elevation = dp(6f).toFloat()
            addView(titleView); addView(messageView)
            detailBox?.let { addView(it) }
            addView(buttonRow)
        }
        // Kept as a cheap safety net even though the icon circle (the actual cause of the button
        // row getting clipped before) is gone now - costs nothing when the card already fits.
        val cardScroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(card)
        }
        scrim = FrameLayout(context).apply {
            setBackgroundColor(0x73000000)
            isClickable = true
            addView(cardScroll)
        }
        panelOverlay.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        confirmOverlay = scrim
    }

    // ---------------------------------------------------------------- body sections

    private fun buildBody(): View {
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18f), dp(4f), dp(18f), dp(4f))
            addView(sectionHeader("Pilih Channel"))
            addView(buildChannelRow())
            addView(spacer(10f))

            addView(sectionHeader("Detail Pelanggan"))
            nameField = plainField("Nama Pelanggan*").also { it.doAfterTextChanged { saveDraft() } }
            addView(buildNameRow())
            addView(spacer(8f))
            addView(buildPhoneRow())
            addView(spacer(10f))

            addView(sectionHeader("Rincian Produk"))
            addView(subHeader("Input otomatis"))
            addView(buildCatalogSearchSection())
            addView(spacer(4f))
            addView(subHeader("Input Manual"))
            addView(buildManualProductRow())
            addView(dashedDivider())
            itemsContainer = LinearLayout(context).apply { orientation = VERTICAL }
            addView(itemsContainer)
            addView(dashedDivider())
            additionalDiscountField = rupiahField("Diskon Tambahan (Opsional)").also { it.doAfterTextChanged { saveDraft() } }
            addView(additionalDiscountField.parent as View)
            addView(spacer(6f))
            notesField = plainField("Catatan").also { it.doAfterTextChanged { saveDraft() } }
            addView(notesField)
            addView(spacer(10f))

            addView(sectionHeader("Detail Pengiriman"))
            addressField = plainField("Alamat Penerima").also { it.doAfterTextChanged { saveDraft() } }
            addView(addressField)
            addView(spacer(6f))
            addView(buildExpeditionServiceRow())
            addView(spacer(6f))
            shippingCostField = rupiahField("Ongkir").also { it.doAfterTextChanged { saveDraft() } }
            addView(shippingCostField.parent as View)
            addView(spacer(10f))

            addView(sectionHeader("Metode Pembayaran"))
            addView(TextView(context).apply {
                text = "*bisa pilih lebih dari satu"
                textSize = 10f
                setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(spacer(6f))
            addView(buildPaymentCarousel())
            addView(spacer(12f))
        }
        // All channel-dependent fields (phoneField/contactBtn/phonePrefixLabel) exist now that
        // buildNameRow()/buildPhoneRow() above have run - apply the initial (default WhatsApp,
        // unless loadDraft() overrides it right after) field treatment once.
        applyChannelToForm(selectedChannel)
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }.also { bodyScrollView = it }
    }

    // ---------------------------------------------------------------- channel picker

    /** Row of 4 circular channel icons (WhatsApp/WhatsApp Business/Telegram/Instagram) - visual
     *  structure copied from [buildPaymentCarousel]'s precedent (52dp white circle, colored ring
     *  when selected) but SINGLE-select (tapping a channel replaces the current selection, not a
     *  toggle-into-a-Set like payment methods) and no scroll/chevrons needed (always exactly 4
     *  fixed icons, unlike the open-ended payment-method list). */
    private fun buildChannelRow(): View {
        channelRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        rebuildChannelRow()
        return channelRow
    }

    private fun rebuildChannelRow() {
        channelRow.removeAllViews()
        Channel.entries.forEach { channel ->
            val isSelected = channel == selectedChannel
            val icon = ImageView(context).apply {
                setImageResource(channel.iconRes)
                layoutParams = FrameLayout.LayoutParams(dp(26f), dp(26f), Gravity.CENTER)
                // Inactive: colored pixels desaturate to gray, achromatic (white) pixels are
                // untouched by a saturation filter - exactly matches the requested "colored parts
                // turn gray, white parts stay white" without needing separate grayscale assets.
                colorFilter = if (isSelected) null
                    else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            val circle = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                    marginStart = dp(5f); marginEnd = dp(5f)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp(if (isSelected) 2.5f else 1.2f), if (isSelected) teal() else 0xFFCBD5E1.toInt())
                }
                isClickable = true; isFocusable = true
                addView(icon)
                setOnClickListener {
                    if (selectedChannel == channel) return@setOnClickListener
                    selectedChannel = channel
                    rebuildChannelRow()
                    applyChannelToForm(channel)
                    saveDraft()
                }
            }
            channelRow.addView(circle)
        }
    }

    /** Applies [channel]'s field treatment to the customer-contact row - called on every channel
     *  pick plus loadDraft()/resetForm(). PHONE (WhatsApp/WhatsApp Business): "+62" prefix,
     *  digit-only numeric keyboard. USERNAME (Instagram): "@" prefix, free-text keyboard, contact
     *  picker hidden (a phone contact's number is meaningless for a username field).
     *  PHONE_OR_USERNAME (Telegram, confirmed dual by the user's own device test): no fixed
     *  prefix (either is valid), free-text keyboard, contact picker STAYS visible since a picked
     *  phone number is a valid Telegram identifier too (auto-detected later by
     *  ChannelMessenger.openChat()). */
    private fun applyChannelToForm(channel: Channel) {
        phoneField.hint = channel.fieldLabel
        when (channel.identifierKind) {
            IdentifierKind.PHONE -> {
                phonePrefixLabel.text = "+62"
                phonePrefixLabel.visibility = View.VISIBLE
                phonePrefixDivider.visibility = View.VISIBLE
            }
            IdentifierKind.USERNAME -> {
                phonePrefixLabel.text = "@"
                phonePrefixLabel.visibility = View.VISIBLE
                phonePrefixDivider.visibility = View.VISIBLE
            }
            IdentifierKind.PHONE_OR_USERNAME -> {
                phonePrefixLabel.visibility = View.GONE
                phonePrefixDivider.visibility = View.GONE
            }
        }
        val isPhone = channel.identifierKind == IdentifierKind.PHONE
        phoneField.inputType = if (isPhone) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        contactBtn.visibility = if (channel.identifierKind == IdentifierKind.USERNAME) View.GONE else View.VISIBLE
        // setOnFocusChangeListener/setOnTouchListener replace the previous listener rather than
        // stacking (confirmed reading SellbyInputRouter.kt), so re-calling this is safe/idempotent.
        phoneField.enableSellbyRouting(isNumeric = isPhone) { hasFocus ->
            (phoneField.parent as? LinearLayout)?.background?.let { (it as GradientDrawable).setStroke(if (hasFocus) dp(1.5f) else 0, teal()) }
        }
        // If the field is currently focused when the channel changes, force the physical keyboard
        // layout to switch immediately rather than waiting for a focus-out/focus-in cycle.
        if (phoneField.hasFocus()) SellbyInputRouter.focus(phoneField, isPhone)
    }

    /** Matches invoice_panel.dart:1277-1329's name-field row exactly: the field + a 36x36 teal
     *  contact-book button beside it (radius 8, 18dp white icon, 8dp gap). Tapping it opens the
     *  PHONE's own Contacts picker (a deliberate deviation from Flutter's _pickContactFromAndroid,
     *  which despite its name actually reads Sellby's own saved customer list - confirmed with the
     *  user this is the wanted behavior) - see ContactPickerActivity/onContactPicked() below. */
    private fun buildNameRow(): View {
        contactBtn = ImageView(context).apply {
            // design-assets/icons/contact.svg - converted specifically for this button (a plain
            // no-fill-attribute source, no style="fill:" gotcha to work around this time).
            setImageResource(R.drawable.ic_invoice_contact_sellby)
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(teal()) }
            layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginStart = dp(8f) }
            setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
            isClickable = true; isFocusable = true
            setOnClickListener {
                // The picker hides the keyboard; the user comes back to this panel, so it must survive
                // every hide on the way and the keyboard must return with it once the picker is done
                // (see KeyboardSwitcher.beginSellbyHelper/endSellbyHelper).
                KeyboardSwitcher.getInstance().beginSellbyHelper(KeyboardSwitcher.SellbyHelperReturn.INVOICE_PANEL)
                context.startActivity(Intent(context, ContactPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        (nameField.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(nameField)
            addView(contactBtn)
        }
    }

    /** Called by SellbyToolbarView.notifyContactPicked() once ContactPickerActivity reports a
     *  contact back. Phone normalization matches generateAndSendInvoice()'s own logic exactly
     *  (strip non-digits, then strip a leading "62" or "0") - phoneField always holds the number
     *  WITHOUT the "+62" prefix, that's rendered separately as buildPhoneRow()'s static prefix. */
    fun onContactPicked(name: String, phone: String) {
        nameField.setText(name)
        phoneField.setText(localPhoneDigits(phone))
        saveDraft()
    }

    private fun buildPhoneRow(): View {
        phoneField = EditText(context).apply {
            hint = "Nomor Whatsapp*"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 12f
            isSingleLine = true
            setTextColor(0xFF1E293B.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // See rupiahField()'s comment: skip sanitizing during a draft load (suppressDraftSave) rather
        // than deferring it, so normal typing reformats instantly instead of one frame later.
        // Digit-only live-filtering only applies for PHONE channels (WhatsApp/WhatsApp Business) -
        // USERNAME (Instagram) and PHONE_OR_USERNAME (Telegram, dual) both need to accept letters.
        var isSanitizing = false
        phoneField.doAfterTextChanged { editable ->
            if (isSanitizing || suppressDraftSave) return@doAfterTextChanged
            if (selectedChannel.identifierKind != IdentifierKind.PHONE) { saveDraft(); return@doAfterTextChanged }
            val raw = editable?.toString() ?: return@doAfterTextChanged
            val digits = raw.filter { it.isDigit() }
            if (digits != raw) {
                isSanitizing = true
                editable.replace(0, editable.length, digits)
                phoneField.setSelection(digits.length.coerceAtMost(editable.length))
                isSanitizing = false
            }
            saveDraft()
        }
        phonePrefixLabel = TextView(context).apply {
            text = "+62"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
        }
        phonePrefixDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1f), dp(16f)).apply { marginStart = dp(8f); marginEnd = dp(8f) }
            setBackgroundColor(0xFFCBD5E1.toInt())
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
            setPadding(dp(10f), 0, dp(10f), 0)
            background = fieldBackground()
            addView(phonePrefixLabel); addView(phonePrefixDivider); addView(phoneField)
        }
        return row
    }

    // ---------------------------------------------------------------- catalog search ("Input otomatis")

    private fun buildCatalogSearchSection(): View {
        searchField = EditText(context).apply {
            hint = "Cari Produk..."
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 11.5f
            isSingleLine = true
            setTextColor(0xFF1E293B.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val searchIcon = ImageView(context).apply {
            setImageResource(R.drawable.sym_keyboard_search_rounded)
            setColorFilter(teal())
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { marginEnd = dp(6f) }
        }
        // Plain "×" glyph avoids needing another icon conversion, matches Icons.close_rounded's role.
        val clearGlyph = TextView(context).apply {
            text = "×"
            textSize = 16f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(dp(4f), 0, dp(2f), 0)
            isClickable = true; isFocusable = true
            visibility = GONE
            setOnClickListener {
                searchField.text.clear()
                collapseSearch()
            }
        }
        searchClearButton = clearGlyph

        searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f))
            setPadding(dp(10f), 0, dp(10f), 0)
            background = fieldBackground()
            addView(searchIcon)
            addView(searchField)
            addView(clearGlyph)
            isClickable = true; isFocusable = true
            setOnClickListener { searchField.requestFocus() }
        }
        // Reported bug: typing worked (focus + routing were fine, confirmed by the field's border
        // going teal and the typed text showing) but the results list never appeared - onClick()
        // on this EditText wasn't firing (EditText's internal touch/editor handling combined with
        // enableSellbyRouting's own OnTouchListener, inside a ScrollView, never resolved to a
        // click). Focus-gained is what's actually reliable here (proven by the border color
        // changing), so drive opening the results from that instead of onClick.
        searchField.enableSellbyRouting { hasFocus ->
            (searchRow.background as GradientDrawable).setStroke(if (hasFocus) dp(1.4f) else 0, teal())
            if (hasFocus) {
                openSearch()
                scrollSearchRowToTop()
            } else {
                dismissSearchPopup()
            }
        }
        searchField.doAfterTextChanged {
            searchClearButton.visibility = if (searchField.text.isNotEmpty()) VISIBLE else GONE
            filterSearchResults()
        }

        return searchRow
    }

    /** Scrolls the panel so [searchRow] sits at the very top of the visible area - requested so the
     *  floating results popup below it has room to show without reaching down into the physical
     *  keyboard rows. post() so it runs after this frame's layout has settled (searchRow.top isn't
     *  valid yet during construction). */
    private fun scrollSearchRowToTop() {
        bodyScrollView.post { bodyScrollView.smoothScrollTo(0, searchRow.top) }
    }

    private fun openSearch() {
        // Matches invoice_panel.dart: re-tapping the search box always reloads the catalog fresh
        // AND resets every row's pcs stepper back to 1, even if search was already open.
        searchResults = catalogProducts.map { SearchResultItem(it) }
        isSearchExpanded = true
        searchClearButton.visibility = if (searchField.text.isNotEmpty()) VISIBLE else GONE
    }

    private fun collapseSearch() {
        isSearchExpanded = false
        if (::searchField.isInitialized) searchField.text?.clear()
        if (::searchClearButton.isInitialized) searchClearButton.visibility = GONE
        dismissSearchPopup()
    }

    /** Called after a product is added from search results - same visible effect as
     *  [collapseSearch] (query text cleared, results popup closed), but DELIBERATELY leaves
     *  isSearchExpanded=true, unlike that one (reserved for the "×" button, which should fully exit
     *  search mode). Bug this fixes: isSearchExpanded only ever flips back to true inside
     *  [openSearch], which only runs on a genuine Android focus-GAINED transition - but
     *  searchField's focus never actually changes when a product gets added (the "+ Input" tap
     *  happens on a different view inside the results popup), so with collapseSearch() alone,
     *  isSearchExpanded stayed stuck at false and [filterSearchResults] bailed out immediately on
     *  the very next keystroke. That's why re-searching required tapping away to another field and
     *  back first - the only way to force a real focus-lost/focus-gained cycle. */
    private fun resetSearchAfterAdd() {
        if (::searchField.isInitialized) searchField.text?.clear()
        if (::searchClearButton.isInitialized) searchClearButton.visibility = GONE
        dismissSearchPopup()
    }

    private fun dismissSearchPopup() {
        searchPopup?.dismiss()
        searchPopup = null
        searchResultsView = null
    }

    /** Floating popup (PopupWindow, same technique as the Ekspedisi/Layanan dropdowns) instead of an
     *  inline view: an inline results list pushed "Input Manual" and everything below it further
     *  down the panel every time it appeared - requested to float on top instead, like the other
     *  dropdowns already do. Height-capped + its own ScrollView so many matches scroll internally
     *  rather than growing tall enough to reach the physical keyboard. Unlike the Ekspedisi/Layanan
     *  popups this one must NOT be focusable: searchField has to keep real Android view focus the
     *  whole time it's showing, or SellbyInputRouter.activeField would clear and typing would stop
     *  reaching it. Content is mutated in place (removeAllViews/addView) on every keystroke rather
     *  than recreating the popup, since it needs to update live while still showing. */
    private fun ensureSearchPopupShown() {
        if (searchPopup?.isShowing == true) return
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        searchResultsView = list
        val scroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(150f))
            addView(list)
        }
        val card = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(0xFFF8FAFC.toInt())
                setStroke(dp(1f), 0xFFCBD5E1.toInt())
            }
            addView(scroll)
        }
        val popup = PopupWindow(card, searchRow.width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            // Sellby: was false - reported bug, the panel couldn't be scrolled at all while this
            // popup was showing. This popup is non-focusable (must stay that way, see class comment
            // above - searchField needs to keep real Android focus the whole time so typing keeps
            // reaching it), and outsideTouchable=true is what lets touches outside its own bounds
            // pass through to the ScrollView underneath instead of being swallowed. It does NOT
            // register any dismiss-on-outside-touch listener, so this alone doesn't risk the popup
            // closing unexpectedly - it only restores scroll passthrough.
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8f).toFloat()
        }
        popup.showAsDropDown(searchRow, 0, dp(4f))
        searchPopup = popup
    }

    private fun filterSearchResults() {
        val query = searchField.text.toString()
        // Deliberate deviation from invoice_panel.dart (which shows the full catalog, or "tidak
        // ditemukan", immediately on tap): this is a real-time searchbar, so results (including the
        // empty state) only appear once the user has actually typed something - not before.
        if (!isSearchExpanded || query.isEmpty()) {
            dismissSearchPopup()
            return
        }
        ensureSearchPopupShown()
        val list = searchResultsView ?: return
        val filtered = searchResults.filter { it.product.name.lowercase().contains(query.lowercase()) }
        list.removeAllViews()
        if (filtered.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "Produk tidak ditemukan"
                textSize = 11f
                setTextColor(0xFF94A3B8.toInt())
                setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            })
        } else {
            filtered.forEach { item -> list.addView(buildSearchResultRow(item)) }
        }
    }

    private fun buildSearchResultRow(item: SearchResultItem): View {
        val pcsLabel = TextView(context).apply {
            text = "${item.pcs} pcs"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            setPadding(dp(4f), 0, dp(4f), 0)
        }
        val stepper = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(0xFFE2E8F0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32f))
            addView(stepperArrow("−") {
                if (item.pcs > 1) { item.pcs--; pcsLabel.text = "${item.pcs} pcs" }
            })
            addView(pcsLabel)
            addView(stepperArrow("+") {
                item.pcs++; pcsLabel.text = "${item.pcs} pcs"
            })
        }
        val addButton = TextView(context).apply {
            text = "+ Input"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(teal()) }
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            isClickable = true; isFocusable = true
            setOnClickListener { addCatalogProductToInvoice(item) }
        }
        val nameColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = item.product.name; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt()); maxLines = 2
            })
            addView(TextView(context).apply {
                text = CurrencyFormat.rupiah(catalogFinalPrice(item.product))
                textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
            })
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f); bottomMargin = dp(4f)
            }
            addView(nameColumn)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6f), 0) })
            addView(stepper)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 0) })
            addView(addButton)
        }
    }

    private fun catalogFinalPrice(product: Product): Double {
        val discounted = product.originalPrice - product.discountAmount
        return if (discounted > 0.0) discounted else product.originalPrice
    }

    private fun addCatalogProductToInvoice(item: SearchResultItem) {
        val product = item.product
        fun executeAdd() {
            items.add(InvoiceItem(
                id = System.currentTimeMillis().toString(),
                name = product.name,
                quantity = item.pcs,
                unitPrice = catalogFinalPrice(product),
                originalUnitPrice = product.originalPrice,
                discountAmount = product.discountAmount,
            ))
            resetSearchAfterAdd()
            rebuildItemsList()
            saveDraft()
            showToast("Produk \"${product.name}\" ditambahkan!")
        }
        if (product.stock <= 0) {
            showConfirmOverlay(
                title = "Stok Produk Kosong",
                message = "Stok untuk \"${product.name}\" saat ini kosong (0 Pcs). Apakah Kakak ingin tetap menambahkan produk ini ke invoice?",
                confirmLabel = "Lanjutkan",
                cancelLabel = "Batal",
            ) { executeAdd() }
        } else {
            executeAdd()
        }
    }

    private fun buildManualProductRow(): View {
        manualNameField = plainField("Nama Produk").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f)
        }
        // Typable via the numpad now (was a plain, non-editable label) - request was to keep the
        // chevrons fully working alongside direct typing, and to never let the value actually
        // become/stay 0 despite that. weight=1 (was a fixed 24dp) so this - not empty space after
        // the whole group - is what absorbs the width the stepper gained matching "+ Tambah": the
        // arrows stay pinned to the stepper's own left/right edges instead of drifting toward the
        // middle together.
        quantityField = EditText(context).apply {
            setText(manualQuantity.toString())
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf()
            textSize = 12f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            gravity = Gravity.CENTER
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        var isReformattingQty = false
        quantityField.doAfterTextChanged { editable ->
            if (isReformattingQty) return@doAfterTextChanged
            val raw = editable?.toString() ?: return@doAfterTextChanged
            val digits = raw.filter { it.isDigit() }
            if (digits != raw) {
                isReformattingQty = true
                editable.replace(0, editable.length, digits)
                quantityField.setSelection(digits.length.coerceAtMost(editable.length))
                isReformattingQty = false
            }
            // Live-updates the backing count as soon as it's a valid positive number, but doesn't
            // fight the user mid-edit (e.g. while the field is transiently empty after a backspace)
            // - the hard "can't end up at 0" clamp happens on blur below.
            digits.toIntOrNull()?.takeIf { it > 0 }?.let { manualQuantity = it }
        }
        val minus = stepperArrow("‹") {
            if (manualQuantity > 1) { manualQuantity--; quantityField.setText(manualQuantity.toString()) }
        }
        val plus = stepperArrow("›") {
            manualQuantity++; quantityField.setText(manualQuantity.toString())
        }
        val stepper = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBackground()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36f))
            setPadding(dp(4f), 0, dp(4f), 0)
            addView(minus); addView(quantityField); addView(plus)
        }
        quantityField.enableSellbyRouting(isNumeric = true) { hasFocus ->
            (stepper.background as GradientDrawable).setStroke(if (hasFocus) dp(1.4f) else 0, teal())
            if (!hasFocus) {
                // Can't be typed down to empty/0 - clamped back to the last valid quantity (never
                // below 1) once the field loses focus, same blur-clamp convention as this panel's
                // other numeric fields.
                val parsed = quantityField.text.toString().toIntOrNull()
                manualQuantity = if (parsed != null && parsed > 0) parsed else manualQuantity.coerceAtLeast(1)
                quantityField.setText(manualQuantity.toString())
            }
        }
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(manualNameField)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 0) })
            addView(stepper)
        }

        manualPriceField = rupiahField("Harga Satuan")
        val priceRow = manualPriceField.parent as View
        priceRow.layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f)
        val addButton = smallActionButton("+ Tambah") { addManualProduct() }
        val bottomRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(priceRow)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 0) })
            addView(addButton)
        }
        // "+ Tambah" sits directly below the pcs stepper (topRow's stepper vs. bottomRow's
        // addButton) and the screenshot shows them meant to line up as one right-hand column -
        // matches the stepper's width to this button's own natural width once both are laid out,
        // same doOnLayout-after-measure technique used elsewhere in this file.
        addButton.doOnLayout {
            val lp = stepper.layoutParams as LinearLayout.LayoutParams
            if (lp.width != addButton.width) {
                lp.width = addButton.width
                stepper.layoutParams = lp
            }
        }

        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(topRow)
            addView(spacer(6f))
            addView(bottomRow)
        }
    }

    private fun stepperArrow(symbol: String, onClick: () -> Unit): View = TextView(context).apply {
        text = symbol
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF64748B.toInt())
        setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun addManualProduct() {
        val name = manualNameField.text.toString().trim()
        val price = CurrencyFormat.parseDigits(manualPriceField.text.toString())
        if (name.isEmpty()) { showToast("Nama barang wajib diisi!"); return }
        if (price <= 0.0) { showToast("Harga satuan belum valid!"); return }
        items.add(InvoiceItem(id = System.currentTimeMillis().toString(), name = name, quantity = manualQuantity, unitPrice = price))
        manualNameField.text.clear()
        manualPriceField.text.clear()
        manualQuantity = 1
        quantityField.setText("1")
        rebuildItemsList()
        saveDraft()
        showToast("Produk \"$name\" berhasil ditambahkan!")
    }

    private fun rebuildItemsList() {
        itemsContainer.removeAllViews()
        if (items.isEmpty()) {
            itemsContainer.addView(TextView(context).apply {
                text = "Belum ada produk yang ditambahkan"
                textSize = 11.5f
                setTextColor(0xFF94A3B8.toInt())
                setTypeface(typeface, Typeface.ITALIC)
                gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, dp(8f))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        } else {
            items.forEachIndexed { index, item -> itemsContainer.addView(buildItemRow(item, index)) }
        }
        updateTotals()
    }

    private fun buildItemRow(item: InvoiceItem, index: Int): View {
        val nameQty = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(TextView(context).apply {
                text = item.name; textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1E293B.toInt())
            })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6f), 0) })
            addView(TextView(context).apply {
                text = "x${item.quantity}"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1E293B.toInt())
            })
        }
        val left = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(nameQty)
            addView(TextView(context).apply {
                text = CurrencyFormat.rupiah(item.unitPrice); textSize = 10.5f; setTextColor(0xFF64748B.toInt())
            })
        }
        val delete = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_delete_sellby)
            setColorFilter(0xFFEF4444.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(19f)).apply { marginStart = dp(8f) }
            isClickable = true; isFocusable = true
            setOnClickListener {
                items.removeAt(index)
                rebuildItemsList()
                saveDraft()
            }
        }
        val right = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = CurrencyFormat.rupiah(item.subtotal); textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
            })
            addView(delete)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFFEF9C3.toInt()) }
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6f)
            }
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right)
        }
    }

    // ---------------------------------------------------------------- shipping

    private fun buildOptionRow(label: String, isSelected: Boolean, onTap: () -> Unit): View = TextView(context).apply {
        text = label
        textSize = 12f
        setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (isSelected) teal() else 0xFF1E293B.toInt())
        setBackgroundColor(if (isSelected) 0xFFBCE3EB.toInt() else Color.TRANSPARENT)
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
    }


    /** Text shown on the merged ekspedisi+layanan button - both picked -> "{ekspedisi} - {layanan}",
     *  only ekspedisi picked -> just that name, neither -> the placeholder. */
    private fun expeditionServiceSummaryText(): String {
        val exp = selectedExpedition
        val svc = selectedService
        return when {
            exp != null && svc != null -> "$exp - $svc"
            exp != null -> exp
            svc != null -> svc
            else -> "Pilih ekspedisi dan layanan"
        }
    }

    private fun refreshExpeditionServiceSummary() {
        expeditionServiceSummaryLabel.text = expeditionServiceSummaryText()
        val hasSelection = selectedExpedition != null || selectedService != null
        expeditionServiceSummaryLabel.setTextColor(if (hasSelection) 0xFF1E293B.toInt() else 0xFF94A3B8.toInt())
    }

    /** Single merged row replacing the old 2 separate dropdown pills - opens
     *  showExpeditionServicePicker()'s full sub-screen instead of a small popup each. */
    private fun buildExpeditionServiceRow(): View {
        expeditionServiceSummaryLabel = TextView(context).apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        refreshExpeditionServiceSummary()
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBackground().apply { cornerRadius = dp(10f).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
            setPadding(dp(10f), 0, dp(10f), 0)
            isClickable = true; isFocusable = true
            addView(expeditionServiceSummaryLabel)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_settings_chevron_right_sellby)
                setColorFilter(0xFF94A3B8.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(14f), dp(14f))
            })
            setOnClickListener { showExpeditionServicePicker() }
        }
    }

    /** Full-panel sub-screen replacing the old 2 separate popups - matches the design the user sent
     *  (2 columns side by side, dashed-free solid divider between them, "Invoice" header identical
     *  to the main form's own so it reads as one continuous panel, not a different screen). Lives in
     *  panelOverlay (same "always render above the whole panel" convention every other overlay in
     *  this file already follows) so it fully covers the form underneath, including the bottom bar
     *  area - the screenshot still shows the bottom total/Buat-Invoice bar, which stays reachable
     *  since panelOverlay only covers `content` (the scrollable body), not buildBottomBar()'s own
     *  row below it. Picks are made on local pending copies and only written back by the sticky
     *  "Simpan" button; "Batalkan" and the header's chevron-left both just close, discarding them.
     *  Never auto-closes just because both got picked (explicit earlier confirmation). */
    private fun showExpeditionServicePicker() {
        expeditionServicePicker?.let { panelOverlay.removeView(it) }
        dismissSearchPopup()

        var pendingExpedition = selectedExpedition
        var pendingService = selectedService

        fun close() {
            expeditionServicePicker?.let { panelOverlay.removeView(it) }
            expeditionServicePicker = null
        }
        fun save() {
            selectedExpedition = pendingExpedition
            selectedService = pendingService
            close()
            refreshExpeditionServiceSummary()
            saveDraft()
        }

        val back = circleButton(R.drawable.ic_settings_chevron_left_sellby).apply {
            setOnClickListener { close() }
        }
        val title = TextView(context).apply {
            text = "Invoice"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            addView(back)
            addView(title)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f)) })
        }
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
            setBackgroundColor(0xFFE2E8F0.toInt())
        }

        lateinit var expeditionColumn: LinearLayout
        lateinit var serviceColumn: LinearLayout
        // A lambda var (not a local fun) because expeditionCard()'s tap handler and the rebuild
        // function call each other, and local funs can't forward-reference.
        lateinit var rebuildExpeditionColumn: () -> Unit
        fun expeditionCard(exp: ExpeditionCatalogItem, isFirstInRow: Boolean): View {
            val isSelected = pendingExpedition == exp.displayName
            val gap = dp(4f)
            return LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(if (isSelected) 1.6f else 1f).coerceAtLeast(1), if (isSelected) teal() else 0xFFCBD5E1.toInt())
                }
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply {
                    if (isFirstInRow) marginEnd = gap else marginStart = gap
                }
                isClickable = true; isFocusable = true
                addView(ImageView(context).apply {
                    setImageResource(expeditionIconRes(exp.id))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                })
                setOnClickListener {
                    // Tap the already-selected expedition again to clear it back to "not chosen"
                    // - covers the case where a customer ends up not using any shipping service
                    // at all, per explicit request (this row was previously select-only, with no
                    // way back to empty short of the whole-form Reset button).
                    pendingExpedition = if (pendingExpedition == exp.displayName) null else exp.displayName
                    rebuildExpeditionColumn()
                }
            }
        }
        // Two cards per row. The wider left column (see weights below) is what makes this fit;
        // an odd last card gets an empty weighted spacer so it keeps half-width instead of
        // stretching across the whole row.
        rebuildExpeditionColumn = {
            expeditionColumn.removeAllViews()
            enabledExpeditions.chunked(2).forEach { pair ->
                expeditionColumn.addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) }
                    addView(expeditionCard(pair[0], isFirstInRow = true))
                    if (pair.size > 1) addView(expeditionCard(pair[1], isFirstInRow = false))
                    else addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(4f) } })
                })
            }
        }
        fun rebuildServiceColumn() {
            serviceColumn.removeAllViews()
            SERVICES.drop(1).forEach { svc ->
                serviceColumn.addView(buildOptionRow(svc, pendingService == svc) {
                    // Same toggle-to-clear behavior as the expedition column above.
                    pendingService = if (pendingService == svc) null else svc
                    rebuildServiceColumn()
                })
            }
        }

        expeditionColumn = LinearLayout(context).apply { orientation = VERTICAL }
        serviceColumn = LinearLayout(context).apply { orientation = VERTICAL }
        rebuildExpeditionColumn()
        rebuildServiceColumn()

        // 60:40 (was 50:50) - the wider left column is what lets the expedition cards sit 2 per row.
        val body = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 60f)
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(12f), dp(10f), dp(8f), dp(10f))
                    addView(expeditionColumn)
                })
            })
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1f), LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(0xFFE2E8F0.toInt())
            })
            addView(ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 40f)
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(8f), dp(10f), dp(12f), dp(10f))
                    addView(serviceColumn)
                })
            })
        }

        // Sticky: a fixed row below the weighted body, so it stays put while both columns scroll.
        val cancelBtn = TextView(context).apply {
            text = "Batalkan"
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF475569.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFE2E8F0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { close() }
        }
        val saveBtn = TextView(context).apply {
            text = "Simpan"
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(teal()) }
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginStart = dp(10f) }
            isClickable = true; isFocusable = true
            setOnClickListener { save() }
        }
        val buttonBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            addView(cancelBtn); addView(saveBtn)
        }

        val screen = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(header)
            addView(divider)
            addView(body)
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
                setBackgroundColor(0xFFE2E8F0.toInt())
            })
            addView(buttonBar)
        }
        panelOverlay.addView(screen, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        expeditionServicePicker = screen
    }

    // Sellby: duplicated from OngkirPanelView.expeditionIconRes() - same "duplicate until a 3rd
    // caller forces extraction" convention already established across every Sellby panel.
    private fun expeditionIconRes(id: String): Int = when (id) {
        "jnt" -> R.drawable.ic_expedition_jnt_sellby
        "grab" -> R.drawable.ic_expedition_grab_sellby
        "pos" -> R.drawable.ic_expedition_pos_sellby
        "lalamove" -> R.drawable.ic_expedition_lalamove_sellby
        "tiki" -> R.drawable.ic_expedition_tiki_sellby
        "gosend" -> R.drawable.ic_expedition_gosend_sellby
        "anteraja" -> R.drawable.ic_expedition_anteraja_sellby
        "paxel" -> R.drawable.ic_expedition_paxel_sellby
        "jne" -> R.drawable.ic_expedition_jne_sellby
        "sicepat" -> R.drawable.ic_expedition_sicepat_sellby
        "sap" -> R.drawable.ic_expedition_sap_sellby
        "ninja" -> R.drawable.ic_expedition_ninja_sellby
        "wahana" -> R.drawable.ic_expedition_wahana_sellby
        "lion" -> R.drawable.ic_expedition_lion_sellby
        "idexpress" -> R.drawable.ic_expedition_idexpress_sellby
        "deliveree" -> R.drawable.ic_expedition_deliveree_sellby
        "superkul" -> R.drawable.ic_expedition_superkul_sellby
        "mrspeedy" -> R.drawable.ic_expedition_mrspeedy_sellby
        "rpx" -> R.drawable.ic_expedition_rpx_sellby
        "jet" -> R.drawable.ic_expedition_jet_sellby
        else -> R.drawable.ic_expedition_pribadi_sellby
    }

    // ---------------------------------------------------------------- payment

    /** Matches invoice_panel.dart's payment carousel exactly: left/right chevron nudge buttons
     *  (±110px smooth scroll) around a horizontally scrollable icon row. [paymentRow]'s minimumWidth
     *  is pinned to the scroll viewport's width once known, so with few payment methods the icons sit
     *  centered instead of hugging the left edge, while still scrolling normally once they overflow. */
    private fun buildPaymentCarousel(): View {
        paymentRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            // CENTER (not just CENTER_VERTICAL): with minimumWidth pinned to the scroll viewport
            // below, this centers the icons horizontally when they fit; once they overflow that
            // width, normal left-aligned scrolling takes over automatically.
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val paymentScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isHorizontalScrollBarEnabled = false
            addView(paymentRow)
        }
        paymentScroll.doOnLayout { paymentRow.minimumWidth = paymentScroll.width }
        val leftChevron = TextView(context).apply {
            text = "‹"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF64748B.toInt())
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            isClickable = true; isFocusable = true
            setOnClickListener { paymentScroll.smoothScrollBy(-dp(110f), 0) }
        }
        val rightChevron = TextView(context).apply {
            text = "›"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF64748B.toInt())
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            isClickable = true; isFocusable = true
            setOnClickListener { paymentScroll.smoothScrollBy(dp(110f), 0) }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(leftChevron)
            addView(paymentScroll)
            addView(rightChevron)
        }
    }

    private fun rebuildPaymentRow() {
        paymentRow.removeAllViews()
        activePayments.forEach { pm ->
            val isSelected = selectedPaymentIds.contains(pm.id)
            val icon = ImageView(context).apply {
                setImageResource(paymentIconRes(pm.bankType))
                layoutParams = FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.CENTER)
            }
            val circle = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52f), dp(52f)).apply {
                    marginStart = dp(4f); marginEnd = dp(4f)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp(if (isSelected) 2.5f else 1.2f), if (isSelected) teal() else 0xFFCBD5E1.toInt())
                }
                isClickable = true; isFocusable = true
                addView(icon)
                setOnClickListener {
                    if (isSelected) selectedPaymentIds.remove(pm.id) else selectedPaymentIds.add(pm.id)
                    rebuildPaymentRow()
                    saveDraft()
                }
            }
            paymentRow.addView(circle)
        }
        // "+ Tambah Metode" trailing circle - same 52dp white-circle shape as every payment option
        // above, but a fixed teal ring (not selectable/state-dependent) + the user's own plus.svg
        // icon (converted to ic_add_sellby, tinted teal via colorFilter same as searchIcon elsewhere
        // in this file) instead of a bank icon. Jumps straight to Settings > Metode Pembayaran so
        // the user doesn't have to hunt for it themselves mid-invoice.
        val addIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_add_sellby)
            setColorFilter(teal())
            layoutParams = FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER)
        }
        val addCircle = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52f), dp(52f)).apply {
                marginStart = dp(4f); marginEnd = dp(4f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1.6f), teal())
            }
            isClickable = true; isFocusable = true
            addView(addIcon)
            setOnClickListener { KeyboardSwitcher.getInstance().sellbyToolbarView?.openSettingsPaymentMethods() }
        }
        paymentRow.addView(addCircle)
    }

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

    private fun bankName(pm: PaymentMethod): String {
        val key = pm.bankType.lowercase().trim()
        return when {
            key == "tunai" -> "Tunai"
            key == "qris" -> "QRIS"
            key == "custombank" || key == "lainnya" -> pm.name
            else -> pm.bankType.uppercase()
        }
    }

    /** Wraps a QRIS photo (a plain file under filesDir/qris_photos/, see QrisPhotoPickerActivity)
     *  as rich content for KeyboardSwitcher.commitSellbyContent() - mirrors ClipboardHistoryEntry.
     *  getContentInfo()/getContentUri(), the existing, proven pattern for turning a local file into
     *  an InputContentInfoCompat via the SAME FileProvider (clipboard_provider_path.xml now also
     *  covers qris_photos/, no new provider needed). Always image/jpeg since
     *  QrisPhotoPickerActivity always saves as .jpg. */
    private fun buildQrisContentInfo(path: String): InputContentInfoCompat {
        val uri = FileProvider.getUriForFile(context, context.getString(R.string.clipboard_provider_authority), File(path))
        return InputContentInfoCompat(uri, ClipDescription("QRIS", arrayOf("image/jpeg")), null)
    }

    // ---------------------------------------------------------------- bottom bar

    private fun buildBottomBar(): View {
        val totalLabel = TextView(context).apply {
            text = "Total Pembayaran"; textSize = 10f; setTextColor(Color.WHITE)
        }
        totalValueLabel = TextView(context).apply {
            text = "Rp 0"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        }
        val totalColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(totalLabel); addView(totalValueLabel)
        }
        val cta = TextView(context).apply {
            text = "Buat Invoice"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0xFFE2DC74.toInt()) }
            setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
            isClickable = true; isFocusable = true
            setOnClickListener { generateAndSendInvoice() }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(teal())
            setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
            addView(totalColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cta)
        }
    }

    private fun itemsTotal() = items.sumOf { it.subtotal }
    private fun shippingCostValue() = CurrencyFormat.parseDigits(shippingCostField.text.toString())
    private fun additionalDiscountValue() = CurrencyFormat.parseDigits(additionalDiscountField.text.toString())
    private fun grandTotal() = (itemsTotal() + shippingCostValue() - additionalDiscountValue()).coerceAtLeast(0.0)

    private fun updateTotals() {
        if (::totalValueLabel.isInitialized) totalValueLabel.text = CurrencyFormat.rupiah(grandTotal())
    }

    // ---------------------------------------------------------------- draft persistence

    private fun saveDraft() {
        if (suppressDraftSave) return
        updateTotals()
        val itemsJson = JSONArray()
        items.forEach { item ->
            itemsJson.put(JSONObject().apply {
                put("id", item.id); put("name", item.name); put("quantity", item.quantity)
                put("unitPrice", item.unitPrice); put("originalUnitPrice", item.originalUnitPrice)
                put("discountAmount", item.discountAmount)
            })
        }
        context.prefs().edit()
            .putString("inv_draft_cust_name", nameField.text.toString())
            .putString("inv_draft_cust_phone", phoneField.text.toString())
            .putString("inv_draft_channel", selectedChannel.id)
            .putString("inv_draft_notes", notesField.text.toString())
            .putString("inv_draft_add_discount", additionalDiscountField.text.toString())
            .putString("inv_draft_address", addressField.text.toString())
            .putString("inv_draft_expedition", selectedExpedition ?: "")
            .putString("inv_draft_service", selectedService ?: "")
            .putString("inv_draft_shipping_cost", shippingCostField.text.toString())
            .putStringSet("inv_draft_payments", selectedPaymentIds.toSet())
            .putString("inv_draft_items", itemsJson.toString())
            .apply()
    }

    private fun loadDraft() {
        suppressDraftSave = true
        val prefs = context.prefs()
        // Restore the channel BEFORE the identifier text below, so the field's input
        // type/hint/prefix are already correct for whatever value gets set into it.
        selectedChannel = Channel.fromId(prefs.getString("inv_draft_channel", null))
        applyChannelToForm(selectedChannel)
        rebuildChannelRow()
        prefs.getString("inv_draft_cust_name", "")?.let { if (it.isNotEmpty()) nameField.setText(it) }
        prefs.getString("inv_draft_cust_phone", "")?.let { if (it.isNotEmpty()) phoneField.setText(it) }
        prefs.getString("inv_draft_notes", "")?.let { if (it.isNotEmpty()) notesField.setText(it) }
        prefs.getString("inv_draft_add_discount", "")?.let { if (it.isNotEmpty()) additionalDiscountField.setText(it) }
        prefs.getString("inv_draft_address", "")?.let { if (it.isNotEmpty()) addressField.setText(it) }
        prefs.getString("inv_draft_shipping_cost", "")?.let { if (it.isNotEmpty()) shippingCostField.setText(it) }
        prefs.getString("inv_draft_expedition", "")?.let { saved ->
            if (saved.isNotEmpty() && enabledExpeditions.any { it.displayName == saved }) {
                selectedExpedition = saved
            }
        }
        prefs.getString("inv_draft_service", "")?.let { saved ->
            if (saved.isNotEmpty() && SERVICES.contains(saved)) {
                selectedService = saved
            }
        }
        refreshExpeditionServiceSummary()
        val savedPayments = prefs.getStringSet("inv_draft_payments", null)
        if (!savedPayments.isNullOrEmpty()) {
            selectedPaymentIds.clear()
            selectedPaymentIds.addAll(savedPayments)
        }
        prefs.getString("inv_draft_items", null)?.let { raw ->
            if (raw.isNotEmpty()) {
                try {
                    val arr = JSONArray(raw)
                    items.clear()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        items.add(InvoiceItem(
                            id = o.getString("id"), name = o.getString("name"), quantity = o.getInt("quantity"),
                            unitPrice = o.getDouble("unitPrice"),
                            originalUnitPrice = o.optDouble("originalUnitPrice", o.getDouble("unitPrice")),
                            discountAmount = o.optDouble("discountAmount", 0.0),
                        ))
                    }
                } catch (_: Exception) { }
            }
        }
        suppressDraftSave = false
    }

    private fun clearDraft() {
        context.prefs().edit()
            .remove("inv_draft_cust_name").remove("inv_draft_cust_phone").remove("inv_draft_channel").remove("inv_draft_notes")
            .remove("inv_draft_add_discount").remove("inv_draft_address").remove("inv_draft_expedition")
            .remove("inv_draft_service").remove("inv_draft_shipping_cost").remove("inv_draft_payments")
            .remove("inv_draft_items")
            .apply()
    }

    private fun resetForm() {
        suppressDraftSave = true
        collapseSearch()
        // Matches the spec's "default channel = WhatsApp" for every fresh/reset form.
        selectedChannel = Channel.WHATSAPP
        applyChannelToForm(selectedChannel)
        rebuildChannelRow()
        nameField.text.clear()
        phoneField.text.clear()
        manualNameField.text.clear()
        manualPriceField.text.clear()
        manualQuantity = 1
        quantityField.setText("1")
        items.clear()
        rebuildItemsList()
        notesField.text.clear()
        additionalDiscountField.text.clear()
        addressField.text.clear()
        shippingCostField.text.clear()
        selectedExpedition = null
        selectedService = null
        refreshExpeditionServiceSummary()
        // Matches invoice_panel.dart's _resetInvoice() exactly: selections are cleared, not
        // re-defaulted - the next invoice in this same session requires picking a method again.
        selectedPaymentIds.clear()
        rebuildPaymentRow()
        suppressDraftSave = false
        clearDraft()
    }

    // ---------------------------------------------------------------- generate & send

    private fun generateAndSendInvoice() {
        val custName = nameField.text.toString().trim()
        // Identifier building branches on the channel: PHONE keeps the exact original logic
        // (strip a leading "62"/"0", re-prefix with "+62"); USERNAME/PHONE_OR_USERNAME store
        // whatever was typed as-is (trimmed) - for Telegram (dual) the phone-vs-username
        // detection happens later, in ChannelMessenger.openChat(), not here.
        val contactIdentifier = when (selectedChannel.identifierKind) {
            IdentifierKind.PHONE -> {
                var cleanPhone = phoneField.text.toString().trim().filter { it.isDigit() }
                if (cleanPhone.startsWith("62")) cleanPhone = cleanPhone.substring(2)
                if (cleanPhone.startsWith("0")) cleanPhone = cleanPhone.substring(1)
                if (cleanPhone.isEmpty()) "" else "+62$cleanPhone"
            }
            IdentifierKind.USERNAME -> phoneField.text.toString().trim().removePrefix("@")
            IdentifierKind.PHONE_OR_USERNAME -> phoneField.text.toString().trim()
        }

        if (custName.isEmpty()) { showToast("Nama pelanggan wajib diisi!"); nameField.requestFocus(); return }
        if (contactIdentifier.isEmpty()) { showToast("${selectedChannel.requiredFieldName} wajib diisi!"); phoneField.requestFocus(); return }
        if (items.isEmpty()) { showToast("Harap tambahkan minimal 1 produk!"); return }
        if (selectedPaymentIds.isEmpty()) { showToast("Pilih minimal 1 metode pembayaran!"); return }

        val storeName = context.prefs().getString("store_name", "")?.ifEmpty { "Toko Kami" } ?: "Toko Kami"
        val address = addressField.text.toString().trim().ifEmpty { "-" }
        val notes = notesField.text.toString().trim().ifEmpty { "-" }
        val shippingCost = shippingCostValue()
        val additionalDiscount = additionalDiscountValue()
        val grandTotal = grandTotal()

        val selectedPaymentMethods = activePayments.filter { selectedPaymentIds.contains(it.id) }
        val selectedLabels = if (selectedPaymentMethods.isNotEmpty())
            selectedPaymentMethods.joinToString("\n") { p ->
                if (p.bankType == "tunai" || p.bankType == "qris") "• ${p.name}"
                else "• ${bankName(p)} a/n ${p.name.trim()} ${p.accountNumber.trim()}".trim()
            }
        else "-"
        val shortBankNames = if (selectedPaymentMethods.isNotEmpty())
            selectedPaymentMethods.joinToString(", ") { bankName(it) } else "-"
        // Hoisted out of the default-template buildString{} below (where it used to live, local to
        // that branch only) - the actual QR photo needs to send regardless of which text template
        // is used (custom #metode-pembayaran token replacement above, or this default), not just
        // when the default template happens to run.
        val qrisItem = selectedPaymentMethods.firstOrNull { it.bankType == "qris" || it.name.lowercase().contains("qris") }

        val rincianProdukStr = items.mapIndexed { i, item ->
            if (item.hasDiscount) {
                val origSub = item.originalUnitPrice * item.quantity
                "${i + 1}. ${selectedChannel.bold(item.name)} × ${item.quantity} = ${selectedChannel.bold(CurrencyFormat.rupiahCompact(item.subtotal))} (Normal: ${selectedChannel.strike(CurrencyFormat.rupiahCompact(origSub))}, Disc ${item.discountPercent}%)"
            } else {
                "${i + 1}. ${selectedChannel.bold(item.name)} × ${item.quantity} = ${CurrencyFormat.rupiahCompact(item.subtotal)}"
            }
        }.joinToString("\n")

        val displayExpedition = selectedExpedition ?: "-"
        val displayService = selectedService ?: "-"
        val fullShippingService = when {
            displayExpedition == "-" && displayService == "-" -> "-"
            displayService == "-" -> displayExpedition
            displayExpedition == "-" -> displayService
            else -> "$displayExpedition - $displayService"
        }

        val customTemplate = context.prefs().getString("sellby_template_invoice", "") ?: ""
        val invoiceText = if (customTemplate.trim().isNotEmpty()) {
            customTemplate
                .replace("#nama-toko", storeName)
                .replace("#nama-pelanggan", custName)
                .replace("#tanggal-invoice", formatDateToday())
                .replace("#rincian-produk", rincianProdukStr)
                .replace("#total-harga-produk", CurrencyFormat.rupiah(itemsTotal()))
                .replace("#alamat-penerima", address)
                .replace("#nomor-pelanggan", contactIdentifier)
                .replace("#ekspedisi", displayExpedition)
                .replace("#layanan-ekspedisi", displayService)
                .replace("#ongkir", CurrencyFormat.rupiah(shippingCost))
                .replace("#catatan", notes)
                .replace("#total-pembayaran", CurrencyFormat.rupiah(grandTotal))
                .replace("#metode-pembayaran", selectedLabels)
        } else {
            // Sellby's own redesigned default message (not a Flutter port - the user specified this
            // exact layout, replacing invoice_panel.dart's plainer default). Field labels are
            // padEnd(14)'d so their colons line up; money uses CurrencyFormat.rupiahCompact ("Rp100.000",
            // no space) specifically here, distinct from the "Rp " spaced form used elsewhere in the UI.
            val divider = "-------------------------------------"
            buildString {
                appendLine("Terima kasih sudah berbelanja di ${selectedChannel.bold(storeName)} yaa! 👋")
                appendLine()
                appendLine("Halo Kak ${selectedChannel.bold(custName)}, berikut rincian pesanannya")
                appendLine("📅 ${formatDateToday()}")
                appendLine()
                appendLine(divider)
                appendLine("🧾 ${selectedChannel.bold("RINCIAN PESANAN")}")
                appendLine()
                appendLine(rincianProdukStr)
                appendLine()
                appendLine("Total Harga Barang")
                appendLine(selectedChannel.bold(CurrencyFormat.rupiahCompact(itemsTotal())))
                appendLine(divider)
                appendLine()
                appendLine("🚚 ${selectedChannel.bold("PENGIRIMAN")}")
                appendLine()
                appendLine("${"Alamat".padEnd(14)}: $address")
                appendLine("${selectedChannel.messageLineLabel.padEnd(14)}: $contactIdentifier")
                appendLine("${"Ekspedisi".padEnd(14)}: $fullShippingService")
                appendLine("${"Ongkos Kirim".padEnd(14)}: ${CurrencyFormat.rupiahCompact(shippingCost)}")
                appendLine()
                appendLine("${"Catatan".padEnd(14)}: $notes")
                if (additionalDiscount > 0) appendLine("${"Diskon".padEnd(14)}: -${CurrencyFormat.rupiahCompact(additionalDiscount)}")
                appendLine()
                appendLine(divider)
                appendLine("💰 ${selectedChannel.bold("TOTAL PEMBAYARAN")}")
                appendLine(selectedChannel.bold(CurrencyFormat.rupiahCompact(grandTotal)))
                appendLine()
                if (selectedPaymentMethods.isNotEmpty()) {
                    appendLine("💳 ${selectedChannel.bold("Metode Pembayaran")}")
                    appendLine(selectedLabels)
                }
                if (qrisItem != null) {
                    appendLine()
                    appendLine("📷 Untuk pembayaran QRIS, silakan scan foto QR yang terlampir")
                }
                appendLine()
                appendLine("Silakan lakukan pembayaran dan")
                appendLine("konfirmasi dengan mengirimkan")
                append("bukti pembayaran yaa. 🙏")
            }
        }

        val snapshotItems = items.toList()
        val itemCount = snapshotItems.size

        CoroutineScope(Dispatchers.IO).launch {
            autoSaveCustomer(custName, contactIdentifier, address, selectedChannel)
            recordOrder(custName, contactIdentifier, itemCount, grandTotal, address, fullShippingService, notes, shortBankNames, snapshotItems, selectedChannel)
        }

        // BUG FIX: this used to commit the QR photo BEFORE the text, on the assumption that's how
        // a person would do it manually (attach photo, then type a caption). On device, that order
        // made the invoice TEXT never show up at all - commitContent() likely pushes the target
        // chat app into an image-attach/preview UI state, and firing commitText() immediately after
        // (before that app has settled into its new screen) apparently doesn't land anywhere
        // visible, or hits a transitional/invalid connection state. Text is the essential part of
        // this feature and must never be put at risk by the image - so it now commits FIRST
        // (guaranteed unaffected by whatever the image attach does afterward), with the image as a
        // best-effort addition after it. Still wrapped defensively either way - an image failure
        // (e.g. the file got deleted outside the app) must never surface as an error to the user.
        KeyboardSwitcher.getInstance().commitSellbyText(invoiceText)
        val qrisPath = qrisItem?.qrisImagePath
        if (qrisPath != null) {
            try {
                KeyboardSwitcher.getInstance().commitSellbyContent(buildQrisContentInfo(qrisPath))
            } catch (e: Exception) {
                // Silently skipped - see comment above.
            }
        }
        resetForm()
        showToast("Invoice berhasil dibuat!")
        // Matches sellby_keyboard.dart's onInvoiceCreated wiring exactly: after inserting the text,
        // the Flutter parent calls _setPanel(null), closing the whole panel back to the toolbar -
        // this isn't a stray side effect to hunt down, it's the actual intended flow. resetForm()
        // above already cleared the draft, so reopening Invoice later starts from a blank form.
        KeyboardSwitcher.getInstance().sellbyToolbarView?.closeIfOpen()
    }

    private suspend fun autoSaveCustomer(name: String, identifier: String, address: String, channel: Channel) {
        if (name.isBlank() || identifier.isBlank()) return
        // The "0"-prefixed reformatting only makes sense for a genuine phone number (WhatsApp/
        // WhatsApp Business) - Instagram usernames and Telegram's dual field (which may hold
        // either a phone or a username, not disambiguated until ChannelMessenger.openChat()
        // actually opens the chat) are stored exactly as typed instead.
        val storedIdentifier = if (channel.identifierKind == IdentifierKind.PHONE) {
            val digits = identifier.filter { it.isDigit() }
            when {
                digits.startsWith("0") -> digits
                digits.startsWith("62") -> "0${digits.substring(2)}"
                else -> "0$digits"
            }
        } else identifier
        val last8 = if (channel.identifierKind == IdentifierKind.PHONE) {
            val digits = storedIdentifier.filter { it.isDigit() }
            if (digits.length > 8) digits.substring(digits.length - 8) else digits
        } else ""
        val existing = db.customerDao().getAll().first().firstOrNull { c ->
            (last8.isNotEmpty() && c.phone.filter { it.isDigit() }.endsWith(last8)) ||
                c.name.trim().equals(name.trim(), ignoreCase = true)
        }
        if (existing != null) {
            db.customerDao().update(existing.copy(
                name = name, phone = storedIdentifier, channel = channel,
                address = if (address.isNotBlank() && address != "-") address else existing.address,
            ))
        } else {
            db.customerDao().upsert(helium314.keyboard.sellby.data.entity.Customer(
                id = "cust_${System.currentTimeMillis()}", name = name, phone = storedIdentifier, address = address, channel = channel,
            ))
        }
    }

    private suspend fun recordOrder(
        custName: String, custPhone: String, productCount: Int, grandTotal: Double,
        address: String, expedition: String, notes: String, paymentMethod: String,
        orderItems: List<InvoiceItem>, channel: Channel,
    ) {
        val orderId = "ord_${System.currentTimeMillis()}"
        db.orderDao().upsert(Order(
            id = orderId, customerName = custName, customerPhone = custPhone, dateMillis = System.currentTimeMillis(),
            productCount = productCount, productsSummary = "$productCount Produk", totalAmount = grandTotal,
            status = OrderStatus.PENDING, address = address, expedition = expedition, notes = notes, paymentMethod = paymentMethod,
            channel = channel,
        ))
        db.orderDao().insertItems(orderItems.map { OrderItem(orderId = orderId, name = it.name, quantity = it.quantity) })
        orderItems.forEach { item ->
            val product = db.productDao().getAll().first().firstOrNull { it.name.trim().equals(item.name.trim(), ignoreCase = true) }
            if (product != null) {
                val newStock = (product.stock - item.quantity).coerceAtLeast(0)
                db.productDao().update(product.copy(stock = newStock))
            }
        }
    }

    private fun formatDateToday(): String {
        // Matches invoice_panel.dart's _formatDateToday() month-name array exactly (not system
        // locale data, to avoid depending on a device's Indonesian locale strings being correct).
        val cal = java.util.Calendar.getInstance()
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = MONTHS[cal.get(java.util.Calendar.MONTH)]
        val year = cal.get(java.util.Calendar.YEAR)
        return "$day $month $year"
    }

    companion object {
        private const val PREF_LAST_OFFERED_CLIP = "inv_last_offered_order_form_clip"
        // A real order form is a handful of short lines - skip anything bigger without even parsing it.
        private const val MAX_ORDER_FORM_CLIP_CHARS = 2000
        private val MONTHS = listOf(
            "Januari", "Februari", "Maret", "April", "Mei", "Juni",
            "Juli", "Agustus", "September", "Oktober", "November", "Desember",
        )
        private val SERVICES = listOf("Pilih Layanan", "Reguler", "Next Day", "Same Day", "Kargo", "Ekonomis", "Instan")
        private val FALLBACK_TUNAI = PaymentMethod(
            id = "pm_tunai", name = "Tunai", accountNumber = "Pembayaran Tunai", bankType = "tunai", isActive = true,
        )
    }
}
