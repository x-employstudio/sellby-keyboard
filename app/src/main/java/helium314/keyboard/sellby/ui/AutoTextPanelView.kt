// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
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
import helium314.keyboard.sellby.data.entity.AutoText
import helium314.keyboard.sellby.input.AutoTextSuggestionEngine
import helium314.keyboard.sellby.input.SellbyInputRouter
import helium314.keyboard.sellby.input.enableSellbyRouting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Panel Auto-Text (`sellby_panel_region`, tab "Auto-Text") - ported from auto_text_panel.dart
 * (1250 lines, read in full - acuan tunggal) + relevant parts of sellby_keyboard.dart (the
 * typing-triggered suggestion strip, deferred - see class-level note near the bottom). Same
 * list/form 2-mode shell as ProdukPanelView, CRUD backed by Room's AutoTextDao.
 *
 * Two deliberate deviations from the Dart source, both disclosed in the approved plan:
 * 1. auto_text_panel.dart's _loadAutoTexts() overwrites the "Hallo"/"Terima kasih" defaults'
 *    stored message with the current store name EVERY time the panel opens - a real risk of
 *    silently discarding a user's edit to those 2 messages. Room's seed already stores a literal
 *    "#nama-toko" token instead of a baked-in name (see AutoText.defaultSeedRows()), so this
 *    panel never rewrites the stored message - the token is resolved only at send-to-chat time
 *    (resolveTokens()), matching how InvoicePanelView's Template Pesan placeholders work.
 * 2. Protected defaults (isDefault==true) can't be deleted (matches Flutter), but here that's
 *    enforced with a warning toast instead of the delete dialog - same as the source.
 */
class AutoTextPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private enum class ViewMode { LIST, FORM }

    private val db by lazy { SellbyDatabase.getInstance(context.applicationContext) }
    private var initialized = false
    private lateinit var content: FrameLayout
    private lateinit var panelOverlay: FrameLayout
    private var toastView: View? = null
    private var toastRunnable: Runnable? = null
    private var confirmOverlay: View? = null
    private var countdownRunnable: Runnable? = null

    private var loadJob: Job? = null
    private var allAutoTexts: List<AutoText> = emptyList()
    private var viewMode = ViewMode.LIST
    private var isEditing = false
    private var editingAutoText: AutoText? = null

    private lateinit var headerBack: ImageView
    private lateinit var headerReset: ImageView

    private lateinit var searchField: EditText
    private lateinit var searchClearButton: View
    private lateinit var totalCountLabel: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var listScroll: View
    private lateinit var emptyStateView: TextView

    private lateinit var shortcutField: EditText
    private lateinit var messageField: EditText

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun teal() = ContextCompat.getColor(context, R.color.calculator_accent)

    fun initialize() {
        if (initialized) return
        initialized = true
        orientation = VERTICAL

        // column+panelOverlay: same local, self-contained pattern as Invoice/Status/Produk - both
        // dialogs (delete-confirm, reset-confirm) render above the whole panel (header included),
        // matching the standing "every popup in every panel stays consistent" instruction, without
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

    /** Called by SellbyToolbarView every time the Auto-Text tab is (re)opened - always resets to
     *  list mode, then starts (once) a continuous Room Flow collector so add/edit/delete/reset
     *  (all happening inside this same panel session) auto-refresh the list without a manual
     *  reload call - same reasoning as StatusPanelView/ProdukPanelView. */
    fun refresh() {
        initialize()
        confirmOverlay?.let { panelOverlay.removeView(it); confirmOverlay = null }
        countdownRunnable?.let { removeCallbacks(it) }
        showList()
        if (loadJob == null) {
            loadJob = CoroutineScope(Dispatchers.IO).launch {
                db.autoTextDao().getAll().collect { list ->
                    withContext(Dispatchers.Main) {
                        allAutoTexts = list
                        if (viewMode == ViewMode.LIST) renderList()
                    }
                }
            }
        } else {
            renderList()
        }
    }

    private fun resolveTokens(message: String): String = AutoTextSuggestionEngine.resolveTokens(context, message)

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
            text = "Auto-Text"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        headerReset = circleButton(R.drawable.ic_toolbar_reset_sellby).apply {
            setOnClickListener { handleResetAllAutoTexts() }
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
        editingAutoText = null
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
        editingAutoText = null
        showForm()
    }

    private fun openEditForm(item: AutoText) {
        isEditing = true
        editingAutoText = item
        showForm()
        shortcutField.setText(item.shortcut)
        // Show the store name resolved (not the literal "#nama-toko" token) - by request, the token
        // is now purely an internal storage detail, never shown to the user. Saving this form
        // persists whatever's in the field verbatim (see handleSaveAutoText), so editing a default
        // and hitting Simpan bakes in today's store name literally for that row from then on - a
        // plain, unsurprising "what you see is what you get" edit, not a hidden re-templating step.
        messageField.setText(resolveTokens(item.message))
    }

    // ---------------------------------------------------------------- list view

    private fun buildListView(): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            totalCountLabel = TextView(context).apply {
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6f); bottomMargin = dp(2f)
                }
            }
            addView(totalCountLabel)
            addView(buildSearchBar())
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4f)) })

            val inner = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            listContainer = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, dp(2f), 0, dp(2f))
            }
            listScroll = ScrollView(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                setPadding(dp(14f), 0, dp(14f), 0)
                addView(listContainer)
            }
            emptyStateView = TextView(context).apply {
                text = "Belum ada auto-text tersimpan"
                textSize = 12f
                setTypeface(typeface, Typeface.ITALIC)
                setTextColor(0xFF94A3B8.toInt())
            }
            inner.addView(listScroll)
            inner.addView(emptyStateView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(inner)

            addView(buildBottomBar())
        }
    }

    private fun buildSearchBar(): View {
        searchField = EditText(context).apply {
            hint = "Cari Auto-Text..."
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

    private fun buildBottomBar(): View {
        val addButton = TextView(context).apply {
            text = "+  Tambah Auto Text"
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
        totalCountLabel.text = "Total ${allAutoTexts.size} Auto Text"
        val query = searchField.text.toString().trim().lowercase()
        val filtered = if (query.isEmpty()) allAutoTexts else allAutoTexts.filter {
            it.shortcut.lowercase().contains(query) || it.message.lowercase().contains(query)
        }
        listContainer.removeAllViews()
        filtered.forEach {
            listContainer.addView(buildAutoTextRow(it))
            listContainer.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
                setBackgroundColor(0xFFE2E8F0.toInt())
            })
        }
        val isEmpty = filtered.isEmpty()
        listScroll.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- row item

    /** Tap the shortcut/preview area -> insert immediately (auto_text_panel.dart's onTapMessage).
     *  The action area defaults to a lone "⋮" that reveals Edit/Hapus/✕ on tap, staggered in the
     *  same 3-phase order as _AutoTextRowItem's AnimationController (dots out -> X in -> Edit/Hapus
     *  in) and exactly reversed on collapse - see the animateTo()/applyProgress() pair below. */
    private fun buildAutoTextRow(item: AutoText): View {
        val shortcutLabel = TextView(context).apply {
            text = item.shortcut
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
        }
        val previewLabel = TextView(context).apply {
            text = resolveTokens(item.message).replace("\n", " ")
            textSize = 11.5f
            setTextColor(0xFF64748B.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val infoColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { sendAutoTextToChat(item) }
            addView(shortcutLabel)
            addView(previewLabel)
        }

        // Staggered 3-phase reveal, ported from _AutoTextRowItem's AnimationController: expanding
        // plays dots-slide-out -> X-slide-in -> Edit/Hapus-slide-in (each phase overlaps the next,
        // starting at 0%/30%/50% of the timeline); collapsing runs the exact same tween backwards
        // (Edit/Hapus retreat first, then X, then dots returns last) - a single 0..1 "progress"
        // value driving 3 independently-timed interval+easing calculations reproduces that emerging
        // /retreating order in either direction, instead of 1 all-at-once swap.
        lateinit var dotsView: View
        lateinit var closeBtn: View
        lateinit var editHapusRow: View
        lateinit var expandedRow: View
        var progress = 0f
        var runningAnimator: ValueAnimator? = null
        val dotsOffset = dp(28f).toFloat()
        val xOffset = dp(30f).toFloat()
        val actionsOffset = dp(36f).toFloat()

        fun easeIn(t: Float) = t * t * t
        fun easeOut(t: Float) = 1f - (1f - t).let { it * it * it }
        fun intervalT(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)

        fun applyProgress(p: Float) {
            val dotsSlide = easeIn(intervalT(p, 0f, 0.40f))
            val dotsFade = easeIn(intervalT(p, 0f, 0.35f))
            dotsView.translationX = dotsSlide * dotsOffset
            dotsView.alpha = 1f - dotsFade

            val xSlide = easeOut(intervalT(p, 0.30f, 0.70f))
            val xFade = easeOut(intervalT(p, 0.30f, 0.65f))
            closeBtn.translationX = (1f - xSlide) * xOffset
            closeBtn.alpha = xFade

            val actSlide = easeOut(intervalT(p, 0.50f, 1.0f))
            val actFade = easeOut(intervalT(p, 0.55f, 0.95f))
            editHapusRow.translationX = (1f - actSlide) * actionsOffset
            editHapusRow.alpha = actFade
        }

        fun animateTo(target: Float) {
            runningAnimator?.cancel()
            expandedRow.visibility = View.VISIBLE
            dotsView.visibility = View.VISIBLE
            val anim = ValueAnimator.ofFloat(progress, target)
            anim.duration = (230 * kotlin.math.abs(target - progress)).toLong().coerceAtLeast(1)
            anim.addUpdateListener {
                progress = it.animatedValue as Float
                applyProgress(progress)
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (target <= 0f) expandedRow.visibility = View.GONE
                    if (target >= 1f) dotsView.visibility = View.INVISIBLE
                }
            })
            runningAnimator = anim
            anim.start()
        }

        dotsView = TextView(context).apply {
            text = "⋮"
            textSize = 18f
            setTextColor(0xFF64748B.toInt())
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            isClickable = true; isFocusable = true
            setOnClickListener { animateTo(1f) }
        }
        val editPill = TextView(context).apply {
            text = "Edit"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(teal()) }
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            isClickable = true; isFocusable = true
            setOnClickListener { animateTo(0f); openEditForm(item) }
        }
        val deletePill = TextView(context).apply {
            text = "Hapus"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(0xFFEF4444.toInt()) }
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            isClickable = true; isFocusable = true
            setOnClickListener { animateTo(0f); triggerDeleteConfirm(item) }
        }
        editHapusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(editPill)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6f), 0) })
            addView(deletePill)
        }
        closeBtn = TextView(context).apply {
            text = "×"
            textSize = 15f
            includeFontPadding = false
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE2E8F0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginStart = dp(6f) }
            isClickable = true; isFocusable = true
            setOnClickListener { animateTo(0f) }
        }
        expandedRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(editHapusRow)
            addView(closeBtn)
        }
        val actionsHolder = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32f))
            addView(dotsView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
            addView(expandedRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
        }

        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
            addView(infoColumn)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 0) })
            addView(actionsHolder)
        }
    }

    // ---------------------------------------------------------------- form view

    private fun formFieldBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        setColor(0xFFE2E8F0.toInt())
    }

    private fun buildTextField(hint: String): EditText {
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
        }
        field.enableSellbyRouting { hasFocus ->
            (row.background as GradientDrawable).setStroke(if (hasFocus) dp(1.4f) else 0, teal())
        }
        row.tag = field
        return field
    }

    private fun buildFormView(): View {
        shortcutField = buildTextField("Masukan Shortcut*")

        messageField = EditText(context).apply {
            hint = "Masukan Pesan"
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
        val messageBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110f))
            background = formFieldBackground()
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            addView(messageField)
        }
        messageField.enableSellbyRouting { hasFocus ->
            (messageBox.background as GradientDrawable).setStroke(if (hasFocus) dp(1.4f) else 0, teal())
        }

        val saveButton = TextView(context).apply {
            text = "Simpan"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(teal()) }
            layoutParams = LinearLayout.LayoutParams(dp(160f), dp(36f)).apply { topMargin = dp(18f); gravity = Gravity.CENTER_HORIZONTAL }
            isClickable = true; isFocusable = true
            setOnClickListener { handleSaveAutoText() }
        }

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14f), dp(6f), dp(14f), dp(10f))
            addView(TextView(context).apply {
                text = if (isEditing) "Edit Auto Text" else "Tambah Auto Text"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(teal())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) }
            })
            addView(shortcutField.parent as View)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10f)) })
            addView(messageBox)
            addView(saveButton)
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    // ---------------------------------------------------------------- save / send-to-chat

    private fun handleSaveAutoText() {
        val shortcut = shortcutField.text.toString().trim()
        val message = messageField.text.toString().trim()

        if (shortcut.isEmpty()) { showToast("Shortcut wajib diisi!"); return }
        if (message.isEmpty()) { showToast("Pesan wajib diisi!"); return }

        val wasEditing = isEditing
        val editItem = editingAutoText
        val nowMillis = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            if (!wasEditing) {
                db.autoTextDao().upsert(AutoText(id = "at_$nowMillis", shortcut = shortcut, message = message, isDefault = false))
            } else if (editItem != null) {
                db.autoTextDao().update(editItem.copy(shortcut = shortcut, message = message))
            }
            withContext(Dispatchers.Main) { showList() }
        }
    }

    private fun sendAutoTextToChat(item: AutoText) {
        val resolved = resolveTokens(item.message)
        KeyboardSwitcher.getInstance().commitSellbyText(resolved)
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

    /** Hapus Auto-Text confirm - same plain-icon style ProdukPanelView established (no tinted
     *  circle backdrop), reserved for non-default items only; default items are blocked earlier
     *  in triggerDeleteConfirm() with a toast, matching auto_text_panel.dart's
     *  _triggerDeleteConfirmation exactly. */
    private fun triggerDeleteConfirm(item: AutoText) {
        if (item.isDefault) {
            showToast("Auto-Text bawaan tidak dapat dihapus, hanya dapat diedit.")
            return
        }
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
            text = "Hapus Auto-Text?"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt()); gravity = Gravity.CENTER
        }
        val messageView = TextView(context).apply {
            text = "Hapus \"${item.shortcut}\" dari daftar auto-text?"
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
                CoroutineScope(Dispatchers.IO).launch { db.autoTextDao().delete(item) }
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

    /** Reset Semua Auto-Text - same icon-circle+countdown style already established for Invoice/
     *  Status/Produk's Reset dialogs. Unlike Produk's "delete everything", this restores the 3
     *  factory defaults (AutoText.defaultSeedRows(), the same rows SellbyDatabase seeds on first
     *  run) rather than leaving the table empty. */
    private fun handleResetAllAutoTexts() {
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
            text = "Reset Semua Auto-Text?"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt()); gravity = Gravity.CENTER
        }
        val messageView = TextView(context).apply {
            text = "PERINGATAN: Seluruh daftar auto-text akan dikembalikan ke pengaturan awal pabrik (default)."
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
                    CoroutineScope(Dispatchers.IO).launch {
                        db.autoTextDao().deleteAll()
                        AutoText.defaultSeedRows().forEach { db.autoTextDao().upsert(it) }
                    }
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
