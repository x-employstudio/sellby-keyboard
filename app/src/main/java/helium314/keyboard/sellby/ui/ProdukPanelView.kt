// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.data.SellbyDatabase
import helium314.keyboard.sellby.data.entity.Product
import helium314.keyboard.sellby.input.SellbyInputRouter
import helium314.keyboard.sellby.input.enableSellbyRouting
import helium314.keyboard.sellby.util.CurrencyFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Panel Produk (`sellby_panel_region`, tab "Produk") - ported from produk_panel.dart (1547 lines,
 * read in full - acuan tunggal). 2 view modes in one panel (list/form, like SettingsPanelView's
 * menu/submenu), CRUD backed by Room's ProductDao.
 *
 * Key finding from reading the full source (not the old research summary): saving a product with
 * "Dengan Variasi" does NOT store one Product with a variations list - Flutter explodes it into N
 * separate FLAT ProductModel rows (name "Original (Varian)", hasVariations always false). The
 * ProductVariation table/relation that exists on ProductModel is dead code in the actual save path.
 * Confirmed with the user this round: ported 1:1 (flatten on save), so Room's already-built
 * ProductVariation table (from the Fase 4 data-layer setup) is intentionally left unused by this
 * panel - the schema stays as-is, just not exercised this way.
 */
class ProdukPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private enum class ViewMode { LIST, FORM }

    private class VariationRowRefs(val container: View, val nameField: EditText, val stockField: EditText, val removeBtn: View)

    private val db by lazy { SellbyDatabase.getInstance(context.applicationContext) }
    private var initialized = false
    private lateinit var content: FrameLayout
    private lateinit var panelOverlay: FrameLayout
    private var toastView: View? = null
    private var toastRunnable: Runnable? = null
    private var confirmOverlay: View? = null
    private var countdownRunnable: Runnable? = null

    private var loadJob: Job? = null
    private var allProducts: List<Product> = emptyList()
    private var viewMode = ViewMode.LIST
    private var isEditing = false
    private var editingProductId: String? = null
    private var hasVariations = false
    private val variationRows = mutableListOf<VariationRowRefs>()

    private lateinit var headerBack: ImageView
    private lateinit var headerReset: ImageView

    private lateinit var searchField: EditText
    private lateinit var searchClearButton: View
    private lateinit var listContainer: LinearLayout
    private lateinit var listScroll: View
    private lateinit var emptyStateView: TextView

    private lateinit var nameField: EditText
    private lateinit var descField: EditText
    private lateinit var priceField: EditText
    private lateinit var discountField: EditText
    private lateinit var discountPercentLabel: TextView
    private lateinit var noVariationPill: TextView
    private lateinit var hasVariationPill: TextView
    private lateinit var variablePartContainer: FrameLayout
    private lateinit var variationRowsContainer: LinearLayout
    private lateinit var stockField: EditText

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun teal() = ContextCompat.getColor(context, R.color.calculator_accent)

    fun initialize() {
        if (initialized) return
        initialized = true
        orientation = VERTICAL

        // column+panelOverlay: same local, self-contained pattern as Invoice/StatusPanelView - all
        // dialogs (delete-confirm, reset-confirm) render above the whole panel (header included),
        // matching the standing "every popup in every panel stays consistent" request, without
        // touching any shared file.
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
    }

    /** Called by SellbyToolbarView every time the Produk tab is (re)opened - always resets to list
     *  mode (matches produk_panel.dart's _viewMode defaulting to list on every fresh mount), then
     *  starts (once) a continuous Room Flow collector - CRUD happens inside this same panel
     *  session, so the list needs to auto-refresh after every add/edit/delete without a manual
     *  reload call, same reasoning as StatusPanelView. */
    fun refresh() {
        initialize()
        confirmOverlay?.let { panelOverlay.removeView(it); confirmOverlay = null }
        countdownRunnable?.let { removeCallbacks(it) }
        showList()
        if (loadJob == null) {
            loadJob = CoroutineScope(Dispatchers.IO).launch {
                db.productDao().getAll().collect { list ->
                    withContext(Dispatchers.Main) {
                        allProducts = list
                        if (viewMode == ViewMode.LIST) renderList()
                    }
                }
            }
        } else {
            renderList()
        }
    }

    // ---------------------------------------------------------------- header / mode switch

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
            setOnClickListener {
                if (viewMode == ViewMode.FORM) showList()
                else KeyboardSwitcher.getInstance().sellbyToolbarView?.closeIfOpen()
            }
        }
        val title = TextView(context).apply {
            text = "Produk"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        headerReset = circleButton(R.drawable.ic_toolbar_reset_sellby).apply {
            setOnClickListener { handleResetAllProducts() }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            addView(headerBack)
            addView(title)
            addView(headerReset)
        }
    }

    private fun showList() {
        viewMode = ViewMode.LIST
        isEditing = false
        editingProductId = null
        headerBack.setImageResource(R.drawable.ic_settings_chevron_down_sellby)
        if (::headerReset.isInitialized) headerReset.visibility = View.VISIBLE
        SellbyInputRouter.unfocus()
        content.removeAllViews()
        content.addView(buildListView())
        renderList()
    }

    private fun showForm() {
        viewMode = ViewMode.FORM
        headerBack.setImageResource(R.drawable.ic_settings_chevron_left_sellby)
        headerReset.visibility = View.INVISIBLE
        SellbyInputRouter.unfocus()
        content.removeAllViews()
        content.addView(buildFormView())
    }

    private fun openAddForm() {
        isEditing = false
        editingProductId = null
        hasVariations = false
        variationRows.clear()
        showForm()
    }

    private fun openEditForm(product: Product) {
        isEditing = true
        editingProductId = product.id
        hasVariations = false
        variationRows.clear()
        showForm()
        nameField.setText(product.name)
        descField.setText(product.description)
        if (product.originalPrice > 0) priceField.setText(CurrencyFormat.liveDigitsToGrouped(product.originalPrice.toLong().toString()))
        if (product.discountAmount > 0) discountField.setText(CurrencyFormat.liveDigitsToGrouped(product.discountAmount.toLong().toString()))
        if (product.stock > 0) stockField.setText(product.stock.toString())
    }

    // ---------------------------------------------------------------- list view

    private fun buildListView(): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8f)) })
            addView(buildSearchBar())
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4f)) })

            val inner = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            listContainer = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, dp(4f), 0, dp(4f))
            }
            listScroll = ScrollView(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                setPadding(dp(14f), 0, dp(14f), 0)
                addView(listContainer)
            }
            emptyStateView = TextView(context).apply {
                text = "Belum ada produk tersimpan"
                textSize = 12f
                setTypeface(typeface, Typeface.ITALIC)
                setTextColor(0xFF94A3B8.toInt())
            }
            inner.addView(listScroll)
            inner.addView(emptyStateView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(inner)

            addView(buildTambahProdukBar())
        }
    }

    private fun buildSearchBar(): View {
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
        val clearGlyph = TextView(context).apply {
            text = "×"
            textSize = 16f
            setTextColor(0xFF94A3B8.toInt())
            setPadding(dp(4f), 0, dp(2f), 0)
            isClickable = true; isFocusable = true
            visibility = View.GONE
            setOnClickListener { searchField.text?.clear() }
        }
        searchClearButton = clearGlyph
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)).apply {
                marginStart = dp(14f); marginEnd = dp(14f); topMargin = dp(4f)
            }
            setPadding(dp(10f), 0, dp(10f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(0xFFF1F5F9.toInt())
                setStroke(dp(0.8f).coerceAtLeast(1), 0xFFCBD5E1.toInt())
            }
            addView(searchIcon)
            addView(searchField)
            addView(clearGlyph)
        }
        searchField.enableSellbyRouting { hasFocus ->
            (row.background as GradientDrawable).setStroke(if (hasFocus) dp(1.4f) else dp(0.8f).coerceAtLeast(1), if (hasFocus) teal() else 0xFFCBD5E1.toInt())
        }
        searchField.doAfterTextChanged {
            searchClearButton.visibility = if (searchField.text.isNotEmpty()) View.VISIBLE else View.GONE
            renderList()
        }
        return row
    }

    private fun buildTambahProdukBar(): View {
        val addButton = TextView(context).apply {
            text = "+  Tambah Produk"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0xFF8CE623.toInt()) }
            setPadding(dp(28f), dp(8f), dp(28f), dp(8f))
            isClickable = true; isFocusable = true
            setOnClickListener { openAddForm() }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(teal())
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            addView(addButton)
        }
    }

    private fun renderList() {
        if (!::listContainer.isInitialized) return
        val query = searchField.text.toString().trim().lowercase()
        val filtered = if (query.isEmpty()) allProducts else allProducts.filter { it.name.lowercase().contains(query) }
        listContainer.removeAllViews()
        filtered.forEach { listContainer.addView(buildProductCard(it)) }
        val isEmpty = filtered.isEmpty()
        listScroll.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- product card

    private fun smallPillButton(text: String, bgColor: Int, textSizeSp: Float = 11f, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            textSize = textSizeSp
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(bgColor) }
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun buildProductCard(product: Product): View {
        val hasDiscount = product.discountAmount > 0.0
        val finalPrice = (product.originalPrice - product.discountAmount).coerceAtLeast(0.0)
        val discountPercent = if (product.originalPrice > 0 && product.discountAmount > 0)
            ((product.discountAmount / product.originalPrice) * 100).roundToInt().coerceAtMost(100) else 0

        val nameRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = product.name
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (hasDiscount) addView(TextView(context).apply {
                text = "Disc $discountPercent%"
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFF97316.toInt()) }
                setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            })
        }
        val priceColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) }
            if (hasDiscount) {
                addView(TextView(context).apply {
                    text = CurrencyFormat.rupiah(product.originalPrice)
                    textSize = 11f
                    setTextColor(0xFF94A3B8.toInt())
                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                })
            }
            addView(TextView(context).apply {
                text = CurrencyFormat.rupiah(finalPrice.takeIf { hasDiscount } ?: product.originalPrice)
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(teal())
            })
        }
        val stockText = TextView(context).apply {
            text = "Stock ${product.stock} Pcs"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF64748B.toInt())
            isClickable = true; isFocusable = true
            setOnClickListener { sendProductToChat(product) }
        }
        val actionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(smallPillButton("Hapus", 0xFFEF4444.toInt()) { triggerDeleteConfirm(product) })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(5f), 0) })
            addView(smallPillButton("Edit", 0xFF475569.toInt()) { openEditForm(product) })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(5f), 0) })
            addView(smallPillButton("Kirim Deskripsi", teal(), 10.5f) { sendProductDescriptionToChat(product) })
        }
        val bottomRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
            addView(stockText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionsRow)
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1.1f).coerceAtLeast(1), 0xFFCBD5E1.toInt())
            }
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) }
            addView(nameRow)
            addView(priceColumn)
            addView(bottomRow)
        }
    }

    // ---------------------------------------------------------------- form view

    private fun formFieldBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        setColor(0xFFE2E8F0.toInt())
    }

    private fun buildTextField(hint: String, isNumeric: Boolean = false, suffix: String? = null): EditText {
        val field = EditText(context).apply {
            this.hint = hint
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
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
            setPadding(dp(10f), 0, dp(10f), 0)
            background = formFieldBackground()
            addView(field)
            if (suffix != null) addView(TextView(context).apply {
                text = suffix; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
            })
        }
        field.enableSellbyRouting(isNumeric = isNumeric) { hasFocus ->
            (row.background as GradientDrawable).setStroke(if (hasFocus) dp(1.4f) else 0, teal())
        }
        row.tag = field
        return field
    }

    /** Price/discount fields: live thousands-grouping via CurrencyFormat, "Rp " prefix, same
     *  reentrancy/draft-load guards InvoicePanelView's rupiahField() established (TYPE_CLASS_TEXT
     *  not TYPE_CLASS_NUMBER - DigitsKeyListener silently strips the grouping dots otherwise). */
    private fun buildRupiahField(hint: String): EditText {
        val field = EditText(context).apply {
            this.hint = hint
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
        var isReformatting = false
        field.doAfterTextChanged { editable ->
            if (isReformatting) return@doAfterTextChanged
            val raw = editable?.toString() ?: return@doAfterTextChanged
            val grouped = CurrencyFormat.liveDigitsToGrouped(raw)
            if (grouped != raw) {
                isReformatting = true
                editable.replace(0, editable.length, grouped)
                field.setSelection(grouped.length.coerceAtMost(editable.length))
                isReformatting = false
            }
            updateDiscountPercentLabel()
        }
        val prefix = TextView(context).apply {
            text = "Rp "; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f)
            setPadding(dp(10f), 0, dp(10f), 0)
            background = formFieldBackground()
            addView(prefix)
            addView(field)
        }
        field.enableSellbyRouting(isNumeric = true) { hasFocus ->
            (row.background as GradientDrawable).setStroke(if (hasFocus) dp(1.4f) else 0, teal())
        }
        row.tag = field
        return field
    }

    private fun updateDiscountPercentLabel() {
        if (!::discountPercentLabel.isInitialized) return
        val price = CurrencyFormat.parseDigits(priceField.text.toString())
        val discount = CurrencyFormat.parseDigits(discountField.text.toString())
        val percent = if (price > 0 && discount > 0) ((discount / price) * 100).roundToInt().coerceAtMost(100) else 0
        discountPercentLabel.text = "Discount $percent%"
    }

    private fun togglePill(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (selected) Color.WHITE else 0xFF64748B.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(if (selected) teal() else 0xFFE2E8F0.toInt()) }
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun buildFormView(): View {
        nameField = buildTextField("Nama Produk*")

        descField = EditText(context).apply {
            hint = "Deskripsi Produk (3-4 baris)..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            gravity = Gravity.TOP or Gravity.START
            textSize = 12f
            setTextColor(0xFF1E293B.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val descBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76f))
            background = formFieldBackground()
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            addView(descField)
        }
        descField.enableSellbyRouting { hasFocus ->
            (descBox.background as GradientDrawable).setStroke(if (hasFocus) dp(1.4f) else 0, teal())
        }

        priceField = buildRupiahField("Harga*")
        discountField = buildRupiahField("Discount")
        val priceRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(priceField.parent as View)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 0) })
            addView(discountField.parent as View)
        }

        discountPercentLabel = TextView(context).apply {
            textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(teal())
        }
        updateDiscountPercentLabel()
        noVariationPill = togglePill("Tanpa Variasi", !hasVariations) { setHasVariations(false) }
        hasVariationPill = togglePill("Dengan Variasi", hasVariations) { setHasVariations(true) }
        val toggleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) }
            val pills = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(noVariationPill)
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6f), 0) })
                addView(hasVariationPill)
            }
            addView(pills)
            addView(discountPercentLabel)
        }

        variablePartContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) }
        }
        rebuildVariablePart()

        val saveButton = TextView(context).apply {
            text = "Simpan"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(teal()) }
            layoutParams = LinearLayout.LayoutParams(dp(160f), dp(36f)).apply { topMargin = dp(18f); gravity = Gravity.CENTER_HORIZONTAL }
            isClickable = true; isFocusable = true
            setOnClickListener { handleSaveProduct() }
        }

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14f), dp(6f), dp(14f), dp(10f))
            addView(TextView(context).apply {
                text = if (isEditing) "Edit Produk" else "Tambah Produk"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(teal())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) }
            })
            addView(nameField.parent as View)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8f)) })
            addView(descBox)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8f)) })
            addView(priceRow)
            addView(toggleRow)
            addView(variablePartContainer)
            addView(saveButton)
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    private fun setHasVariations(value: Boolean) {
        if (hasVariations == value) return
        hasVariations = value
        noVariationPill.apply {
            setTextColor(if (!value) Color.WHITE else 0xFF64748B.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(if (!value) teal() else 0xFFE2E8F0.toInt()) }
        }
        hasVariationPill.apply {
            setTextColor(if (value) Color.WHITE else 0xFF64748B.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(if (value) teal() else 0xFFE2E8F0.toInt()) }
        }
        SellbyInputRouter.unfocus()
        rebuildVariablePart()
    }

    private fun rebuildVariablePart() {
        variablePartContainer.removeAllViews()
        if (!hasVariations) {
            stockField = buildTextField("Stock Produk", isNumeric = true, suffix = "Pcs")
            variablePartContainer.addView(stockField.parent as View)
        } else {
            variablePartContainer.addView(buildVariationsSection())
        }
    }

    private fun buildVariationsSection(): View {
        val addPill = TextView(context).apply {
            text = "Tambah Variasi"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(teal()) }
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            isClickable = true; isFocusable = true
            setOnClickListener { addVariationRow() }
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(context).apply {
                text = "Variasi"; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1E293B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(addPill)
        }
        variationRowsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
        }
        if (variationRows.isEmpty()) {
            addVariationRow()
        } else {
            variationRows.forEach { variationRowsContainer.addView(it.container) }
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(header)
            addView(variationRowsContainer)
        }
    }

    private fun addVariationRow(name: String = "", stock: String = "") {
        val nameField = buildTextField("Nama Variasi").apply { if (name.isNotEmpty()) setText(name) }
        val stockField = buildTextField("Pcs", isNumeric = true, suffix = "Pcs").apply { if (stock.isNotEmpty()) setText(stock) }
        lateinit var rowRefs: VariationRowRefs
        val removeBtn = TextView(context).apply {
            text = "×"
            textSize = 20f
            setTextColor(0xFFEF4444.toInt())
            setPadding(dp(6f), 0, dp(2f), 0)
            isClickable = true; isFocusable = true
            setOnClickListener { removeVariationRow(rowRefs) }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) }
            addView(nameField.parent as View, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 0) })
            addView(stockField.parent as View, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(removeBtn)
        }
        rowRefs = VariationRowRefs(row, nameField, stockField, removeBtn)
        variationRows.add(rowRefs)
        variationRowsContainer.addView(row)
        updateRemoveButtonsVisibility()
    }

    private fun removeVariationRow(row: VariationRowRefs) {
        if (variationRows.size <= 1) return
        variationRowsContainer.removeView(row.container)
        variationRows.remove(row)
        updateRemoveButtonsVisibility()
    }

    private fun updateRemoveButtonsVisibility() {
        val show = variationRows.size > 1
        variationRows.forEach { it.removeBtn.visibility = if (show) View.VISIBLE else View.GONE }
    }

    // ---------------------------------------------------------------- save / send-to-chat

    private fun handleSaveProduct() {
        val name = nameField.text.toString().trim()
        val price = CurrencyFormat.parseDigits(priceField.text.toString())
        val discount = CurrencyFormat.parseDigits(discountField.text.toString())
        val description = descField.text.toString().trim()

        if (name.isEmpty()) { showToast("Nama produk wajib diisi!"); return }
        if (price <= 0.0) { showToast("Harga produk wajib diisi!"); return }

        val wasEditing = isEditing
        val editId = editingProductId
        val nowMillis = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            if (hasVariations) {
                val newProducts = variationRows.mapIndexedNotNull { i, row ->
                    val vName = row.nameField.text.toString().trim()
                    if (vName.isEmpty()) return@mapIndexedNotNull null
                    val vStock = row.stockField.text.toString().toIntOrNull() ?: 0
                    val fullName = if (name.contains("(")) name else "$name ($vName)"
                    Product(
                        id = "prod_${nowMillis}_$i", name = fullName, description = description,
                        originalPrice = price, discountAmount = discount, hasVariations = false, stock = vStock,
                    )
                }
                if (newProducts.isNotEmpty()) {
                    if (wasEditing && editId != null) {
                        db.productDao().getById(editId)?.let { db.productDao().delete(it) }
                    }
                    newProducts.forEach { db.productDao().upsert(it) }
                }
            } else {
                val singleStock = stockField.text.toString().toIntOrNull() ?: 0
                if (!wasEditing) {
                    db.productDao().upsert(Product(
                        id = "prod_$nowMillis", name = name, description = description,
                        originalPrice = price, discountAmount = discount, hasVariations = false, stock = singleStock,
                    ))
                } else if (editId != null) {
                    db.productDao().getById(editId)?.let {
                        db.productDao().update(it.copy(
                            name = name, description = description, originalPrice = price,
                            discountAmount = discount, hasVariations = false, stock = singleStock,
                        ))
                    }
                }
            }
            withContext(Dispatchers.Main) { showList() }
        }
    }

    private fun sendProductToChat(product: Product) {
        val hasDiscount = product.discountAmount > 0.0
        val finalPrice = (product.originalPrice - product.discountAmount).coerceAtLeast(0.0)
        val discountPercent = if (product.originalPrice > 0 && product.discountAmount > 0)
            ((product.discountAmount / product.originalPrice) * 100).roundToInt().coerceAtMost(100) else 0
        val text = buildString {
            appendLine("*${product.name}*")
            if (hasDiscount) {
                appendLine("Harga Normal: ~${CurrencyFormat.rupiah(product.originalPrice)}~")
                appendLine("Harga Promo ($discountPercent%): *${CurrencyFormat.rupiah(finalPrice)}*")
            } else {
                appendLine("Harga: *${CurrencyFormat.rupiah(product.originalPrice)}*")
            }
            // hasVariations is always false for anything saved via this panel (flattened on save,
            // matches Flutter) - kept as an explicit branch anyway for fidelity with the source.
            appendLine("Stok: ${product.stock} Pcs")
        }
        KeyboardSwitcher.getInstance().commitSellbyText(text)
        KeyboardSwitcher.getInstance().sellbyToolbarView?.closeIfOpen()
    }

    private fun sendProductDescriptionToChat(product: Product) {
        val hasDiscount = product.discountAmount > 0.0
        val finalPrice = (product.originalPrice - product.discountAmount).coerceAtLeast(0.0)
        val discountPercent = if (product.originalPrice > 0 && product.discountAmount > 0)
            ((product.discountAmount / product.originalPrice) * 100).roundToInt().coerceAtMost(100) else 0
        val text = buildString {
            appendLine("*Deskripsi Produk: ${product.name}*")
            appendLine(product.description.trim().ifEmpty { "Belum ada deskripsi untuk produk ini." })
            appendLine("")
            if (hasDiscount) {
                appendLine("Harga Normal: ~${CurrencyFormat.rupiah(product.originalPrice)}~")
                appendLine("Harga Promo ($discountPercent%): *${CurrencyFormat.rupiah(finalPrice)}*")
            } else {
                appendLine("Harga: *${CurrencyFormat.rupiah(product.originalPrice)}*")
            }
        }
        KeyboardSwitcher.getInstance().commitSellbyText(text)
        KeyboardSwitcher.getInstance().sellbyToolbarView?.closeIfOpen()
    }

    // ---------------------------------------------------------------- dialogs

    private fun showToast(message: String) {
        toastView?.let { panelOverlay.removeView(it) }
        toastRunnable?.let { removeCallbacks(it) }
        val toast = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0xFF1E293B.toInt()) }
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        panelOverlay.addView(toast, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = dp(60f)
        })
        toastView = toast
        val runnable = Runnable {
            panelOverlay.removeView(toast)
            if (toastView === toast) toastView = null
        }
        toastRunnable = runnable
        postDelayed(runnable, 2800)
    }

    /** Hapus Produk confirm - deliberately its own distinct look (plain icon, no tinted circle
     *  backdrop) matching produk_panel.dart's delete dialog exactly, not [showConfirmOverlay]'s
     *  icon-circle+countdown style (that one's reserved for the Reset Semua Produk dialog below,
     *  same convention Invoice/Status already established). */
    private fun triggerDeleteConfirm(product: Product) {
        confirmOverlay?.let { panelOverlay.removeView(it) }
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
            text = "Hapus Produk?"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt()); gravity = Gravity.CENTER
        }
        val messageView = TextView(context).apply {
            text = "Hapus \"${product.name}\" dari daftar produk?"
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
            setOnClickListener {
                dismiss()
                CoroutineScope(Dispatchers.IO).launch { db.productDao().delete(product) }
            }
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

    /** Reset Semua Produk - same icon-circle+countdown style already established for Invoice's and
     *  Status's Reset dialogs, built locally rather than extracted (same "don't touch already-
     *  tested code for one more instance" reasoning used every previous round). */
    private fun handleResetAllProducts() {
        confirmOverlay?.let { panelOverlay.removeView(it) }
        countdownRunnable?.let { removeCallbacks(it) }
        lateinit var scrim: FrameLayout
        fun dismiss() {
            countdownRunnable?.let { removeCallbacks(it) }
            panelOverlay.removeView(scrim)
            if (confirmOverlay === scrim) confirmOverlay = null
        }
        val confirmColor = 0xFFEF4444.toInt()
        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            setColorFilter(confirmColor)
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8f) }
        }
        val titleView = TextView(context).apply {
            text = "Reset Semua Produk?"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt()); gravity = Gravity.CENTER
        }
        val messageView = TextView(context).apply {
            text = "PERINGATAN: Seluruh daftar produk yang pernah dibuat dan disimpan akan dihapus secara permanen."
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
        var remaining = 5
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
                if (!disabled) {
                    dismiss()
                    CoroutineScope(Dispatchers.IO).launch { db.productDao().deleteAll() }
                }
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
}
