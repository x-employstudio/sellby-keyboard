// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.sellby.data.SellbyDatabase
import helium314.keyboard.sellby.data.dao.OrderWithItems
import helium314.keyboard.sellby.data.entity.Order
import helium314.keyboard.sellby.data.entity.OrderStatus
import helium314.keyboard.sellby.input.enableSellbyRouting
import helium314.keyboard.sellby.util.Channel
import helium314.keyboard.sellby.util.ChannelMessenger
import helium314.keyboard.sellby.util.CurrencyFormat
import helium314.keyboard.sellby.util.bold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Panel Status (`sellby_panel_region`, tab "Status") - ported from status_panel.dart (1397 lines,
 * read in full - acuan tunggal). 4 tabs only (Pending/Lunas/Proses/Selesai; ARSIP has no tab, an
 * archived order simply stops rendering anywhere in this panel - confirmed intentional in Flutter,
 * and confirmed with the user this round: kept 1:1, not adding a 5th tab. Archived rows stay in
 * Room untouched, they're just not queried by any tab here - a future out-of-scope "dashboard"
 * surface is where the user wants them visible/downloadable later, not this panel.
 *
 * Reactive design differs from Flutter on purpose: instead of manual setState()+prefs-save after
 * every action, [Order]/[OrderWithItems] come from Room's Flow (getAllWithItems()), collected once
 * and re-rendered on every emission - so a status change, delete, or a brand new order created from
 * the Invoice panel all refresh this panel automatically without any explicit "reload" call.
 */
class StatusPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val db by lazy { SellbyDatabase.getInstance(context.applicationContext) }
    private var initialized = false
    private lateinit var content: FrameLayout
    private lateinit var panelOverlay: FrameLayout
    private var toastView: View? = null
    private var toastRunnable: Runnable? = null
    private var confirmOverlay: View? = null
    private var countdownRunnable: Runnable? = null
    private var detailOverlay: View? = null

    private var loadJob: Job? = null
    private var allOrders: List<OrderWithItems> = emptyList()
    private var selectedTabIndex = 0

    private val tabPills = mutableListOf<TextView>()
    private lateinit var searchField: EditText
    private lateinit var searchClearButton: View
    private lateinit var viewPager: ViewPager2
    private lateinit var pagerColumn: View
    private lateinit var indicatorRow: View
    private lateinit var indicatorThumb: View
    private lateinit var emptyStateView: TextView
    private val adapter = OrderCardAdapter()

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun teal() = ContextCompat.getColor(context, R.color.calculator_accent)

    fun initialize() {
        if (initialized) return
        initialized = true
        orientation = VERTICAL

        // Everything (header included) lives inside `column`; `panelOverlay` is a sibling added
        // AFTER it so it draws on top of the whole panel, not just `content` - EVERY confirm/detail
        // overlay in this panel renders there now (user asked for consistent "above the panel"
        // positioning across every popup in every panel, not just Reset), so they all sit over the
        // header/chevron/reset/tabbar/search too. This is entirely local to this panel's own view
        // tree - it can never reach outside StatusPanelView's own bounds, so it can't cover the
        // physical keyboard below it. (A previous attempt solved a version of this via a scrim
        // shared across the whole IME window - reverted after it caused a serious regression that
        // was hard to diagnose without device access; this version carries none of that risk since
        // nothing outside this file is touched.)
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(buildHeader())
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
                setBackgroundColor(0xFFE2E8F0.toInt())
            })
            addView(buildTabBar())
            addView(buildSearchBar())
            addView(TextView(context).apply {
                text = "Klik kartu untuk membuka detail transaksi"
                textSize = 10f
                setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(3f)
                }
            })
            // Was 4dp - the card's own ScrollView wrapper already has 4dp of top padding
            // (buildOrderCard), so removing this spacer still leaves a small, deliberate gap
            // instead of doubling up on it.
            content = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            content.addView(buildPagerHost())
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

    /** Called by SellbyToolbarView every time the Status tab is (re)opened - mirrors every other
     *  Sellby panel's refresh() convention. Unlike Invoice/Ongkir, the Room Flow collector started
     *  here is left running continuously (cheap, and this panel needs live updates while it stays
     *  open across several actions in the same session) rather than being torn down and relaunched. */
    fun refresh() {
        initialize()
        confirmOverlay?.let { panelOverlay.removeView(it); confirmOverlay = null }
        detailOverlay?.let { panelOverlay.removeView(it); detailOverlay = null }
        countdownRunnable?.let { removeCallbacks(it) }
        selectedTabIndex = 0
        searchField.text?.clear()
        viewPager.setCurrentItem(0, false)
        if (loadJob == null) {
            loadJob = CoroutineScope(Dispatchers.IO).launch {
                db.orderDao().getAllWithItems().collect { list ->
                    withContext(Dispatchers.Main) {
                        allOrders = list
                        render()
                    }
                }
            }
        } else {
            render()
        }
    }

    // ---------------------------------------------------------------- header / chrome

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
            text = "Status"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        // Icons.refresh_rounded in Flutter - reuses the reset icon already converted for Invoice's
        // header (same circular-arrow shape) instead of converting a near-duplicate asset.
        val reset = circleButton(R.drawable.ic_toolbar_reset_sellby).apply {
            setOnClickListener { handleResetAllOrders() }
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

    private fun buildTabBar(): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        }
        TAB_LABELS.indices.forEach { index ->
            val pill = TextView(context).apply {
                maxLines = 1
                gravity = Gravity.CENTER
                // Matches _buildTabBar's Container(padding: vertical:6) + Padding(horizontal:4)
                // exactly. WRAP_CONTENT height (not a fixed box) so the pill sizes itself around
                // 11sp text the normal way - autosize (2 earlier rounds) needed a resolved height to
                // compute against, and kept overshooting/undershooting the target size depending on
                // exactly how much padding vs. box height it was given. Dropped entirely in favor of
                // a simple, predictable 2-tier size picked in render() instead (11sp normally, a
                // smaller fixed fallback only once a count actually formats to "N,NK").
                setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2.5f); marginEnd = dp(2.5f)
                }
                isClickable = true; isFocusable = true
                setOnClickListener {
                    AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(it, HapticEvent.KEY_PRESS)
                    if (selectedTabIndex != index) {
                        selectedTabIndex = index
                        viewPager.setCurrentItem(0, false)
                        render()
                    }
                }
            }
            tabPills.add(pill)
            row.addView(pill)
        }
        return row
    }

    private fun buildSearchBar(): View {
        searchField = EditText(context).apply {
            hint = "Cari Transaksi..."
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
            visibility = GONE
            setOnClickListener { searchField.text?.clear() }
        }
        searchClearButton = clearGlyph
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)).apply {
                marginStart = dp(14f); marginEnd = dp(14f)
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
            searchClearButton.visibility = if (searchField.text.isNotEmpty()) VISIBLE else GONE
            render()
        }
        return row
    }

    // ---------------------------------------------------------------- pager + indicator

    private fun buildPagerHost(): View {
        viewPager = ViewPager2(context).apply {
            // Was weight=1 (stretch to fill pagerColumn's remaining space) - that left a big empty
            // gap between the card's bottom and the indicator dots below it, since the card only
            // needs its own natural height, not the pager's full stretched height. A fixed height
            // close to the card's actual need instead (~124dp of content: 20dp card padding + ~18dp
            // date/badge row + ~41dp name/summary/total row + ~9dp dashed divider + 28dp button row,
            // plus 8dp from the card's own ScrollView padding - all 4 status tabs land in the same
            // range since the action row's height doesn't change with button count, only how many
            // sit side by side). A true "measure the bound card and resize to match" approach was
            // considered but dropped - ViewPager2 forces each page to the pager's own height, so
            // reading a bound item's height back just returns what was already set, not its natural
            // content height; getting that measurement right without that circularity needs more
            // care than is worth risking right now.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(138f))
            offscreenPageLimit = 1
            adapter = this@StatusPanelView.adapter
            // Fixed-dp peek (instead of Flutter's viewportFraction:0.84 percentage) - simpler and
            // reliable without post-layout width measurement, visually close (small strip of the
            // next/prev card peeking on each side, matching the same approximation-over-percentage
            // call already made for Ongkir's grid aspect ratio).
            val peekPx = dp(20f)
            (getChildAt(0) as? RecyclerView)?.apply {
                clipToPadding = false
                setPadding(peekPx, 0, peekPx, 0)
            }
            setPageTransformer(CompositePageTransformer().apply { addTransformer(MarginPageTransformer(dp(8f))) })
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    updateIndicator(position + positionOffset, this@StatusPanelView.adapter.itemCount)
                }
            })
        }
        indicatorThumb = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(18f), dp(3f))
            background = GradientDrawable().apply { cornerRadius = dp(2f).toFloat(); setColor(teal()) }
        }
        val track = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(50f), dp(3f))
            background = GradientDrawable().apply { cornerRadius = dp(2f).toFloat(); setColor(0xFFE2E8F0.toInt()) }
            addView(indicatorThumb)
        }
        indicatorRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f); bottomMargin = dp(6f)
            }
            addView(track)
        }
        pagerColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(viewPager)
            addView(indicatorRow)
        }
        emptyStateView = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
        }
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(pagerColumn)
            addView(emptyStateView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private val indicatorMaxTranslatePx by lazy { dp(50f - 18f) }

    private fun updateIndicator(page: Float, count: Int) {
        if (!::indicatorThumb.isInitialized) return
        if (count <= 1) {
            indicatorRow.visibility = GONE
            return
        }
        indicatorRow.visibility = VISIBLE
        val ratio = (page / (count - 1).toFloat()).coerceIn(0f, 1f)
        indicatorThumb.translationX = ratio * indicatorMaxTranslatePx
    }

    // ---------------------------------------------------------------- render (reactive core)

    private fun statusForTabIndex(index: Int): OrderStatus = when (index) {
        0 -> OrderStatus.PENDING
        1 -> OrderStatus.LUNAS
        2 -> OrderStatus.PROSES
        else -> OrderStatus.SELESAI
    }

    private fun render() {
        if (!::viewPager.isInitialized) return
        val status = statusForTabIndex(selectedTabIndex)
        val query = searchField.text.toString().trim().lowercase()
        val forTab = allOrders.filter { it.order.status == status }
        val filtered = if (query.isEmpty()) forTab else forTab.filter {
            it.order.customerName.lowercase().contains(query) ||
                it.order.productsSummary.lowercase().contains(query) ||
                it.order.customerPhone.contains(query) ||
                // productsSummary is just a generic "N Produk" count string, not actual product
                // names - search each order's real OrderItem names too, so searching e.g. "Baju"
                // finds every order that included a product with that name.
                it.items.any { item -> item.name.lowercase().contains(query) }
        }

        tabPills.forEachIndexed { index, pill ->
            val selected = index == selectedTabIndex
            val count = allOrders.count { it.order.status == statusForTabIndex(index) }
            val countText = formatTabCount(count)
            pill.text = "${TAB_LABELS[index]}($countText)"
            // Matches Flutter's FittedBox(scaleDown): 11sp is the real design size, only stepping
            // down once the count itself grows past 3 digits into "N,NK" territory (the only case
            // that could realistically overflow the pill's width at 11sp).
            pill.textSize = if (countText.length > 3) 9f else 11f
            pill.setTypeface(pill.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            pill.setTextColor(if (selected) Color.WHITE else 0xFF64748B.toInt())
            pill.background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(if (selected) teal() else 0xFFE2E8F0.toInt())
            }
        }

        adapter.submit(filtered)
        if (viewPager.currentItem >= filtered.size && filtered.isNotEmpty()) {
            viewPager.setCurrentItem(filtered.size - 1, false)
        }

        val isEmpty = filtered.isEmpty()
        pagerColumn.visibility = if (isEmpty) GONE else VISIBLE
        emptyStateView.visibility = if (isEmpty) VISIBLE else GONE
        emptyStateView.text = if (query.isNotEmpty()) "Tidak ada transaksi yang cocok" else "Belum ada transaksi di status ini"
        updateIndicator(viewPager.currentItem.toFloat(), filtered.size)
    }

    private fun formatTabCount(count: Int): String {
        if (count < 1000) return count.toString()
        val k = kotlin.math.floor(count / 100.0) / 10.0
        var formatted = "%.1f".format(k)
        if (formatted.endsWith(".0")) formatted = formatted.dropLast(2)
        return formatted.replace(".", ",") + "K"
    }

    // ---------------------------------------------------------------- card adapter

    private inner class OrderCardAdapter : RecyclerView.Adapter<OrderCardAdapter.VH>() {
        private var items: List<OrderWithItems> = emptyList()
        fun submit(newItems: List<OrderWithItems>) {
            items = newItems
            notifyDataSetChanged()
        }
        inner class VH(val container: FrameLayout) : RecyclerView.ViewHolder(container)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return VH(container)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.container.removeAllViews()
            holder.container.addView(buildOrderCard(items[position]))
        }
        override fun getItemCount() = items.size
    }

    // ---------------------------------------------------------------- order card

    private fun statusBadge(status: OrderStatus): View {
        val (text, bg, fg) = when (status) {
            OrderStatus.PENDING -> Triple("Pending", 0xFFFFF3E0.toInt(), 0xFFF97316.toInt())
            OrderStatus.LUNAS -> Triple("Lunas", 0xFFDCFCE7.toInt(), 0xFF16A34A.toInt())
            OrderStatus.PROSES -> Triple("Di Proses", 0xFFFEF9C3.toInt(), 0xFFCA8A04.toInt())
            OrderStatus.SELESAI -> Triple("Selesai", 0xFFE0F2FE.toInt(), 0xFF0284C7.toInt())
            OrderStatus.ARSIP -> Triple("Diarsipkan", 0xFFF1F5F9.toInt(), 0xFF475569.toInt())
        }
        return TextView(context).apply {
            this.text = text
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg)
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(bg) }
        }
    }

    private fun channelBadge(channel: Channel): View {
        val (bg, fg) = when (channel) {
            Channel.WHATSAPP -> 0xFFDCFCE7.toInt() to 0xFF16A34A.toInt() // hijau
            Channel.WHATSAPP_BUSINESS -> 0xFFBBF7D0.toInt() to 0xFF166534.toInt() // hijau tua
            Channel.TELEGRAM -> 0xFFE0F2FE.toInt() to 0xFF0284C7.toInt() // biru
            Channel.INSTAGRAM -> 0xFFF3E8FF.toInt() to 0xFF9333EA.toInt() // ungu
        }
        return TextView(context).apply {
            text = channel.displayLabel
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg)
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(bg) }
        }
    }

    private fun dashedDivider(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1.2f)).apply {
            topMargin = dp(4f); bottomMargin = dp(4f)
        }
        repeat(35) { i ->
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(1.2f), 1f)
                setBackgroundColor(if (i % 2 == 0) 0xFFCBD5E1.toInt() else Color.TRANSPARENT)
            })
        }
    }

    private fun actionButton(text: String, bg: Int, fg: Int, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(fg)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(bg) }
            layoutParams = LinearLayout.LayoutParams(0, dp(28f), 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun deleteIconButton(onClick: () -> Unit): View {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_delete_sellby)
            setColorFilter(0xFFEF4444.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(15f), dp(18f), Gravity.CENTER)
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32f), dp(28f)).apply { marginStart = dp(8f) }
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(0x1FEF4444) }
            isClickable = true; isFocusable = true
            addView(icon)
            setOnClickListener { onClick() }
        }
    }

    private fun buildOrderCard(orderWithItems: OrderWithItems): View {
        val order = orderWithItems.order
        val dateLabel = TextView(context).apply {
            text = formatDateMillis(order.dateMillis)
            textSize = 10.5f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(0xFF64748B.toInt())
        }
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(dateLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(channelBadge(order.channel), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
            addView(statusBadge(order.status))
        }
        val nameLabel = TextView(context).apply {
            text = order.customerName
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val summaryLabel = TextView(context).apply {
            text = order.productsSummary
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val totalLabel = TextView(context).apply {
            text = CurrencyFormat.rupiah(order.totalAmount)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
        }
        val middleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f); bottomMargin = dp(2f)
            }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(nameLabel)
                addView(summaryLabel)
            })
            addView(totalLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) })
        }
        val upperSection = LinearLayout(context).apply {
            orientation = VERTICAL
            isClickable = true; isFocusable = true
            addView(topRow)
            addView(middleRow)
            setOnClickListener { showDetailOverlay(orderWithItems) }
        }

        val actionRow: View = when (order.status) {
            OrderStatus.PENDING -> LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(actionButton("Ingatkan", 0xFFF97316.toInt(), Color.WHITE) { handleRemindCustomer(order) })
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6f), 0) })
                addView(actionButton("Lunas", 0xFF22C55E.toInt(), Color.WHITE) { handleMarkAsLunas(order) })
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6f), 0) })
                addView(actionButton("Hapus", 0xFFEF4444.toInt(), Color.WHITE) { handleDeleteOrder(orderWithItems) })
            }
            OrderStatus.LUNAS -> LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(actionButton("Proses Pesanan", 0xFFFACC15.toInt(), 0xFF1E293B.toInt()) { handleProcessOrder(order) })
                addView(deleteIconButton { handleDeleteOrder(orderWithItems) })
            }
            OrderStatus.PROSES -> LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(actionButton("Selesai", teal(), Color.WHITE) { handleFinishOrder(order) })
                addView(deleteIconButton { handleDeleteOrder(orderWithItems) })
            }
            else -> LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(actionButton("Arsipkan", 0xFF1E293B.toInt(), Color.WHITE) { handleArchiveOrder(order) })
                addView(deleteIconButton { handleDeleteOrder(orderWithItems) })
            }
        }

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1.1f).coerceAtLeast(1), 0xFFCBD5E1.toInt())
            }
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            addView(upperSection)
            addView(dashedDivider())
            addView(actionRow)
        }
        // ScrollView, not a plain vertically-centered FrameLayout: each ViewPager2 page is a fixed
        // height, and a WRAP_CONTENT card taller than that would get silently cropped (RecyclerView
        // clips items to their bounds) - confirmed on device as the button row disappearing below
        // the toolbar. The panel height fix above should make this a non-issue in the common case,
        // but this keeps every button reachable via a small scroll even on a shorter screen/larger
        // font scale, rather than silently hiding them again.
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            clipToPadding = false
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------------------------------------------------------------- detail overlay

    private fun detailRow(label: String, value: String): View {
        val labelView = TextView(context).apply {
            text = label
            textSize = 11.5f
            setTextColor(0xFF64748B.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(105f), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val colon = TextView(context).apply { text = ": "; textSize = 11.5f; setTextColor(0xFF64748B.toInt()) }
        val valueView = TextView(context).apply {
            text = value
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1E293B.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) }
            addView(labelView); addView(colon); addView(valueView)
        }
    }

    private fun showDetailOverlay(orderWithItems: OrderWithItems) {
        val order = orderWithItems.order
        detailOverlay?.let { panelOverlay.removeView(it) }
        lateinit var scrim: FrameLayout
        fun dismiss() {
            panelOverlay.removeView(scrim)
            if (detailOverlay === scrim) detailOverlay = null
        }

        // No close ("×") button anymore - closing is one of the two bottom buttons now (Kembali).
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "Detail Transaksi"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(statusBadge(order.status))
        }

        val itemsList = LinearLayout(context).apply { orientation = VERTICAL }
        if (orderWithItems.items.isNotEmpty()) {
            orderWithItems.items.forEach { item ->
                itemsList.addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3f) }
                    addView(TextView(context).apply {
                        text = "• ${item.name}"
                        textSize = 12f
                        setTextColor(0xFF475569.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(context).apply {
                        text = "x${item.quantity}"
                        textSize = 12f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(0xFF1E293B.toInt())
                    })
                })
            }
        } else {
            itemsList.addView(TextView(context).apply {
                text = "• ${order.productsSummary}"
                textSize = 12f
                setTextColor(0xFF475569.toInt())
            })
        }

        val totalBox = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFF1F5F9.toInt()) }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
            addView(TextView(context).apply {
                text = "Total Pembayaran"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = CurrencyFormat.rupiah(order.totalAmount)
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(teal())
            })
        }

        val detailBody = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(detailRow("Nama Pelanggan", order.customerName))
            addView(detailRow("Channel", order.channel.displayLabel))
            addView(detailRow(order.channel.messageLineLabel, order.customerPhone.ifEmpty { "-" }))
            addView(detailRow("Tanggal", formatDateMillis(order.dateMillis)))
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6f)) })
            addView(TextView(context).apply {
                text = "Rincian Pembelian:"
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF1E293B.toInt())
            })
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4f)) })
            addView(itemsList)
            addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6f)) })
            addView(detailRow("Pengiriman", order.expedition.ifEmpty { "-" }))
            addView(detailRow("Alamat", order.address.ifEmpty { "-" }))
            addView(detailRow("Catatan", order.notes.ifEmpty { "-" }))
            addView(detailRow("Pembayaran", order.paymentMethod.ifEmpty { "-" }))
            addView(totalBox)
        }
        // weight=1 (was WRAP_CONTENT) so this fills the card's full height and scrolls internally
        // if content overflows - the panel's own height stays constant while this is open (user
        // explicitly asked for that, content just scrolls instead), so this is the only thing that
        // has to absorb whatever extra content there is.
        val scrollBody = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6f); bottomMargin = dp(10f)
            }
            // User wants the scrollbar KEPT visible, just not overlapping text - plain right padding
            // didn't achieve that (confirmed by device screenshot) because the default
            // INSIDE_OVERLAY style draws the bar overlaid ON TOP of the content box regardless of
            // padding, it doesn't reserve a lane for itself. OUTSIDE_INSET makes the View
            // auto-expand its own effective padding to carve out a dedicated strip for the
            // scrollbar outside the content area, so text can never bleed into it - no manual dp
            // guess needed (that's also why the earlier isVerticalScrollBarEnabled=false attempt
            // got reverted - hiding it wasn't what was actually asked for, keeping it clear of text
            // was).
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET
            // OUTSIDE_INSET's own auto-reserved lane sat right against the content with no visual
            // breathing room - a bit of extra right padding on top of it widens that gap (this
            // padding stacks with the style's own auto-inset rather than replacing it, since OUTSIDE
            // styles reserve the scrollbar strip beyond whatever padding is already set).
            setPadding(0, 0, dp(6f), 0)
            addView(detailBody)
        }

        val backButton = TextView(context).apply {
            text = "Kembali"
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF475569.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFE2E8F0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { dismiss() }
        }
        val chatButton = TextView(context).apply {
            text = "Chat Customer"
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(teal()) }
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginStart = dp(10f) }
            isClickable = true; isFocusable = true
            setOnClickListener { openCustomerChatHistory(order) }
        }
        val bottomButtonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(backButton); addView(chatButton)
        }

        // Tall (MATCH_PARENT height, filling whatever the panel's own constant height currently
        // is - not expanding it) - a near-full detail sheet instead of a small floating dialog, per
        // request ("tampilannya memanjang kebawah") - but NOT full width: width is set below, once
        // laid out, to 70% of the panel's width (user asked it not just follow the screen width,
        // then asked for another 10 points off that) and centered, same doOnLayout-after-measure
        // technique InvoicePanelView already uses for its payment carousel centering.
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.WHITE) }
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8f); bottomMargin = dp(8f)
            }
            addView(headerRow)
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply { topMargin = dp(6f) }
                setBackgroundColor(0xFFE2E8F0.toInt())
            })
            addView(scrollBody)
            addView(bottomButtonRow)
        }
        scrim = FrameLayout(context).apply {
            setBackgroundColor(0x73000000)
            isClickable = true
            addView(card)
        }
        panelOverlay.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scrim.doOnLayout {
            (card.layoutParams as FrameLayout.LayoutParams).width = (scrim.width * 0.7f).toInt()
            card.requestLayout()
        }
        detailOverlay = scrim
    }

    /** "Chat Customer" on the Detail Transaksi sheet - opens the existing chat with this customer
     *  on whatever channel the order was placed with (no prefilled text, unlike
     *  sendTextMessageAndOpenWA), so the shop owner can review the conversation history. */
    private fun openCustomerChatHistory(order: Order) {
        val rawIdentifier = order.customerPhone.trim()
        if (rawIdentifier.isEmpty()) {
            showToast("Kontak pelanggan tidak tersedia")
            return
        }
        if (!ChannelMessenger.openChat(context, order.channel, rawIdentifier)) {
            showToast("Gagal membuka ${order.channel.displayLabel}")
        }
    }

    // ---------------------------------------------------------------- confirm overlay (with optional countdown)

    /** Beda dari showConfirmOverlay Invoice (confirm button selalu teal, tanpa countdown): Status
     *  butuh warna confirm per-aksi (status_panel.dart's _dialogConfirmColor) dan Reset Semua butuh
     *  countdown 5 detik sebelum tombol Lanjutkan aktif. Dibuat lokal ke panel ini (bukan diekstrak
     *  ke helper bersama Settings/Invoice/Ongkir yang sudah teruji) untuk menghindari resiko regresi
     *  menyentuh 3 file yang sudah jalan, demi 1 dialog baru.
     *
     *  Sempat ada varian "fullScreen" (dialog Reset tampil di atas SEGALANYA lewat scrim root baru
     *  di KeyboardWrapperView) - DIREVERT total: user laporkan bug serius (keyboard jadi hitam,
     *  nyangkut) setelah itu terpasang, dan infrastruktur barunya (layout XML bersama +
     *  KeyboardSwitcher.java) terlalu beresiko utk didiagnosis lebih lanjut tanpa akses device
     *  langsung.
     *
     *  Every confirm dialog in this panel now renders in [panelOverlay] (above the WHOLE panel -
     *  header/chevron/tabbar/search included, not just [content]) - user asked for this Z-order to
     *  be consistent across every popup in every panel, not just Reset. Still 100% local to this
     *  panel's own view tree, so it can never reach the physical keyboard below it.
     *
     *  [compact] (Reset Semua Transaksi only) controls just the card's SIZE on top of that -
     *  smaller padding/message/buttons, still centered like every other dialog here now. */
    private fun showConfirmOverlay(title: String, message: String, confirmColor: Int, hasCountdown: Boolean = false, compact: Boolean = false, onConfirm: () -> Unit) {
        confirmOverlay?.let { panelOverlay.removeView(it) }
        countdownRunnable?.let { removeCallbacks(it) }
        lateinit var scrim: FrameLayout
        fun dismiss() {
            countdownRunnable?.let { removeCallbacks(it) }
            panelOverlay.removeView(scrim)
            if (confirmOverlay === scrim) confirmOverlay = null
        }

        // ic_dialog_info is already a self-contained "i in a circle" glyph - no separate tinted
        // backdrop behind it anymore (was double-circling), just the icon itself, small.
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
            text = message; textSize = if (compact) 11f else 12f; setTextColor(0xFF64748B.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, if (compact) dp(12f) else dp(16f))
        }
        val btnHeight = if (compact) dp(34f) else dp(38f)
        val cancelBtn = TextView(context).apply {
            text = "Batalkan"; textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF475569.toInt()); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(0xFFE2E8F0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f)
            isClickable = true; isFocusable = true
            setOnClickListener { dismiss() }
        }
        var remaining = if (hasCountdown) 5 else 0
        val confirmBtn = TextView(context).apply {
            textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f).apply { marginStart = dp(10f) }
            isClickable = true; isFocusable = true
        }
        fun updateConfirmBtn() {
            val disabled = hasCountdown && remaining > 0
            // Matches _dialogConfirmColor's foreground special-case exactly: yellow (Proses
            // Pesanan's FACC15) needs dark text for contrast, every other confirm color uses white.
            val enabledFg = if (confirmColor == 0xFFFACC15.toInt()) 0xFF1E293B.toInt() else Color.WHITE
            confirmBtn.text = if (disabled) "Lanjutkan (${remaining}s)" else "Lanjutkan"
            confirmBtn.setTextColor(if (disabled) 0xFF64748B.toInt() else enabledFg)
            confirmBtn.background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(if (disabled) 0xFFCBD5E1.toInt() else confirmColor) }
            confirmBtn.isEnabled = !disabled
            confirmBtn.setOnClickListener { if (!disabled) { dismiss(); onConfirm() } }
        }
        updateConfirmBtn()
        if (hasCountdown) {
            val tick = object : Runnable {
                override fun run() {
                    remaining--
                    updateConfirmBtn()
                    if (remaining > 0) postDelayed(this, 1000)
                }
            }
            countdownRunnable = tick
            postDelayed(tick, 1000)
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(cancelBtn); addView(confirmBtn)
        }
        val cardPadding = if (compact) dp(14f) else dp(18f)
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.WHITE) }
            setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                marginStart = if (compact) dp(28f) else dp(22f); marginEnd = if (compact) dp(28f) else dp(22f)
            }
            // Approximates Flutter's Material(elevation: 10) drop shadow around the dialog card.
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

    // ---------------------------------------------------------------- actions (status_panel.dart's _handle*)

    private fun renderCustomStatusMessage(order: Order, key: String, defaultMsg: String): String {
        val customTemplate = context.prefs().getString(key, "") ?: ""
        if (customTemplate.isBlank()) return defaultMsg
        val storeName = context.prefs().getString("store_name", "")?.ifEmpty { "Toko Kami" } ?: "Toko Kami"
        return customTemplate
            .replace("#nama-toko", storeName)
            .replace("#nama-pelanggan", order.customerName)
            .replace("#tanggal-invoice", formatDateMillis(order.dateMillis))
            .replace("#total-pembayaran", CurrencyFormat.rupiah(order.totalAmount))
            .replace("#ekspedisi", order.expedition)
            .replace("#alamat-penerima", order.address)
            .replace("#nomor-pelanggan", order.customerPhone)
            .replace("#catatan", order.notes)
            .replace("#metode-pembayaran", order.paymentMethod)
    }

    /** Channel-aware deep-link (best-effort, silently ignored if it fails) + commit the same text
     *  to whatever InputConnection is bound (KeyboardSwitcher.commitSellbyText, the same mechanism
     *  Invoice's "Buat Invoice" uses) + close the panel - matches _sendTextMessageAndOpenWA exactly:
     *  ALWAYS commits+closes regardless of whether the deep-link itself succeeded. Text prefill
     *  (via wa.me's ?text=) only applies for WhatsApp/WhatsApp Business - see
     *  ChannelMessenger.openChat()'s doc comment. */
    private fun sendTextMessageAndOpenWA(order: Order, messageText: String) {
        val rawIdentifier = order.customerPhone.trim()
        if (rawIdentifier.isNotEmpty()) {
            // Text delivery is now entirely ChannelMessenger's responsibility (embedded in the
            // wa.me URL for WhatsApp/WhatsApp Business, deferred-until-reattached for
            // Telegram/Instagram, or its own immediate fallback if the deep-link launch fails
            // outright) - no longer an unconditional commitSellbyText() here, which used to race
            // the app-switch and land the text in whatever chat happened to be open before it.
            ChannelMessenger.openChat(context, order.channel, rawIdentifier, messageText)
        } else {
            // No identifier at all - nothing to open, still deliver the text as before.
            KeyboardSwitcher.getInstance().commitSellbyText(messageText)
        }
        KeyboardSwitcher.getInstance().sellbyToolbarView?.closeIfOpen()
    }

    private fun handleRemindCustomer(order: Order) {
        val defaultText = "Halo Kak ${order.channel.bold(order.customerName)},\n" +
            "Kami mengingatkan untuk pesanan dengan total tagihan ${order.channel.bold(CurrencyFormat.rupiah(order.totalAmount))} (${formatDateMillis(order.dateMillis)}) belum terselesaikan pembayarannya yaa.\n" +
            "Mohon segera konfirmasi bukti transfer jika sudah melakukan pembayaran. Terima kasih banyak! 🙏"
        val messageText = renderCustomStatusMessage(order, "sellby_template_status_pending", defaultText)
        sendTextMessageAndOpenWA(order, messageText)
    }

    private fun handleMarkAsLunas(order: Order) {
        showConfirmOverlay(
            title = "Ubah ke Lunas?",
            message = "Pesanan Kak ${order.customerName} akan dipindahkan ke tab Lunas untuk disiapkan pengirimannya.",
            confirmColor = 0xFF22C55E.toInt(),
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                db.orderDao().updateStatus(order.id, OrderStatus.LUNAS)
                val defaultText = "Halo Kak ${order.channel.bold(order.customerName)},\n" +
                    "Pembayaran Anda sebesar ${order.channel.bold(CurrencyFormat.rupiah(order.totalAmount))} telah kami terima dan terkonfirmasi ${order.channel.bold("LUNAS")}. Pesanan Anda sedang kami siapkan untuk dikirimkan. Terima kasih banyak! 🙏"
                val messageText = renderCustomStatusMessage(order, "sellby_template_status_lunas", defaultText)
                withContext(Dispatchers.Main) { sendTextMessageAndOpenWA(order, messageText) }
            }
        }
    }

    private fun handleProcessOrder(order: Order) {
        showConfirmOverlay(
            title = "Proses Pesanan Ini?",
            message = "Pesanan Kak ${order.customerName} akan ditandai sedang dipacking / dalam proses pengiriman.",
            confirmColor = 0xFFFACC15.toInt(),
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                db.orderDao().updateStatus(order.id, OrderStatus.PROSES)
                val defaultText = "Halo Kak ${order.channel.bold(order.customerName)},\n" +
                    "Pesanan Anda saat ini sedang ${order.channel.bold("DIPROSES / DIPACKING")} dan akan segera diserahkan ke kurir pengiriman. Mohon ditunggu yaa. Terima kasih! 📦✨"
                val messageText = renderCustomStatusMessage(order, "sellby_template_status_proses", defaultText)
                withContext(Dispatchers.Main) { sendTextMessageAndOpenWA(order, messageText) }
            }
        }
    }

    private fun handleFinishOrder(order: Order) {
        showConfirmOverlay(
            title = "Selesaikan Pesanan?",
            message = "Pesanan Kak ${order.customerName} akan ditandai selesai dan telah diterima dengan baik oleh pembeli.",
            confirmColor = teal(),
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                db.orderDao().updateStatus(order.id, OrderStatus.SELESAI)
                val defaultText = "Halo Kak ${order.channel.bold(order.customerName)},\n" +
                    "Pesanan Anda di ${order.channel.bold(order.notes.ifEmpty { "Toko Kami" })} telah ${order.channel.bold("SELESAI / DITERIMA")}. Terima kasih banyak telah berbelanja! 😊"
                val messageText = renderCustomStatusMessage(order, "sellby_template_status_selesai", defaultText)
                withContext(Dispatchers.Main) { sendTextMessageAndOpenWA(order, messageText) }
            }
        }
    }

    private fun handleArchiveOrder(order: Order) {
        showConfirmOverlay(
            title = "Arsipkan Pesanan?",
            message = "Pesanan Kak ${order.customerName} akan dipindahkan ke arsip agar daftar transaksi aktif tetap bersih.",
            confirmColor = 0xFF1E293B.toInt(),
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                db.orderDao().updateStatus(order.id, OrderStatus.ARSIP)
            }
        }
    }

    private fun handleDeleteOrder(orderWithItems: OrderWithItems) {
        val order = orderWithItems.order
        showConfirmOverlay(
            title = "Hapus Pesanan Ini?",
            message = "Pesanan Kak ${order.customerName} sebesar ${CurrencyFormat.rupiah(order.totalAmount)} akan dihapus dari daftar transaksi.",
            confirmColor = 0xFFEF4444.toInt(),
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                db.orderDao().delete(order)
                // Kembalikan stok produk terkait - pola sama persis InvoicePanelView's stock
                // decrement (match by name, re-query getAll() per item so multiple items matching
                // the same product name each see the latest stock, not a stale snapshot).
                orderWithItems.items.forEach { item ->
                    val product = db.productDao().getAll().first().firstOrNull { it.name.trim().equals(item.name.trim(), ignoreCase = true) }
                    if (product != null) {
                        db.productDao().update(product.copy(stock = product.stock + item.quantity))
                    }
                }
            }
        }
    }

    private fun handleResetAllOrders() {
        showConfirmOverlay(
            title = "Reset Semua Transaksi?",
            message = "PERINGATAN: Seluruh riwayat transaksi (Pending, Lunas, Proses, Selesai) akan dihapus secara keseluruhan.",
            confirmColor = 0xFFEF4444.toInt(),
            hasCountdown = true,
            compact = true,
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                db.orderDao().deleteAll()
            }
        }
    }

    // ---------------------------------------------------------------- date formatting

    private fun formatDateMillis(millis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val month = MONTHS[cal.get(java.util.Calendar.MONTH)]
        val year = cal.get(java.util.Calendar.YEAR)
        return "$day $month $year"
    }

    companion object {
        private val TAB_LABELS = listOf("Pending", "Lunas", "Proses", "Selesai")
        // Matches invoice_panel.dart's _formatDateToday() month-name array exactly (not system
        // locale data), same reasoning/values as InvoicePanelView's own copy.
        private val MONTHS = listOf(
            "Januari", "Februari", "Maret", "April", "Mei", "Juni",
            "Juli", "Agustus", "September", "Oktober", "November", "Desember",
        )
    }
}
