// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.data.ExpeditionCatalog
import helium314.keyboard.sellby.data.ExpeditionCatalogItem
import helium314.keyboard.sellby.data.SellbyDatabase
import helium314.keyboard.sellby.data.entity.Expedition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Panel Ongkir (`sellby_panel_region`, tab "Ongkir") - ported from ongkir_panel.dart (546 lines,
 * read in full - acuan tunggal). A directory/launcher: tap a courier to open its app (with a
 * scheme fallback chain, then Play Store) or its web rate-checker, exactly matching
 * `OngkirData.launchExpedition()`. No text input, no InputConnection concerns at all here.
 */
class OngkirPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val db by lazy { SellbyDatabase.getInstance(context.applicationContext) }
    private var initialized = false
    private lateinit var content: FrameLayout
    private var toastView: View? = null
    private var toastRunnable: Runnable? = null

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun teal() = ContextCompat.getColor(context, R.color.calculator_accent)

    fun initialize() {
        if (initialized) return
        initialized = true
        orientation = VERTICAL
        addView(buildHeader())
        addView(View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1f))
            setBackgroundColor(0xFFE2E8F0.toInt())
        })
        content = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        addView(content)
    }

    /** Called by SellbyToolbarView every time the Ongkir tab is (re)opened - re-reads the enabled
     *  expedition set fresh, matching every other Sellby panel's refresh() convention. */
    fun refresh() {
        initialize()
        CoroutineScope(Dispatchers.IO).launch {
            // Defensive re-seed (same root cause/fix as AutoTextSuggestionEngine.startWatching):
            // Settings' "Hapus Semua Data" calls SellbyDatabase.clearAllTables(), which empties the
            // expedition table without ever re-triggering Room's one-shot onCreate seed - leaving
            // the grid permanently empty afterwards with no way to recover. IGNORE-conflict re-seed
            // here fills it back in (all enabled) every time the tab opens; harmless no-op otherwise.
            db.expeditionDao().seedIfAbsent(ExpeditionCatalog.all.map { Expedition(id = it.id, isEnabled = true) })
            // Cleans up couriers removed from the catalog (Rara/JDL) that may have been seeded
            // into the table before they were removed - see ExpeditionDao.deleteByIds().
            db.expeditionDao().deleteByIds(ExpeditionCatalog.REMOVED_IDS)
            val enabledIds = db.expeditionDao().getEnabledIds().first().toSet()
            val visible = ExpeditionCatalog.all.filter { it.id in enabledIds }
            withContext(Dispatchers.Main) {
                content.removeAllViews()
                content.addView(buildBody(visible))
                toastView = null
            }
        }
    }

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
            text = "Ongkir"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(teal())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        // No reset/second action on this header in ongkir_panel.dart - just a symmetry spacer,
        // same convention as Invoice's header before its reset button existed.
        val spacer = View(context).apply { layoutParams = LayoutParams(dp(32f), dp(32f)) }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            addView(back)
            addView(title)
            addView(spacer)
        }
    }

    private fun buildBody(items: List<ExpeditionCatalogItem>): View {
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (items.isEmpty()) {
                addView(TextView(context).apply {
                    text = "Belum ada ekspedisi diaktifkan.\nSilakan aktifkan di Pengaturan Toko."
                    textSize = 12f
                    setTextColor(0xFF94A3B8.toInt())
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(24f)
                    }
                })
            } else {
                // 3-column grid via plain LinearLayout rows (ongkir_panel.dart's GridView, 3 columns,
                // childAspectRatio 2.1 - approximated here with a fixed 38dp row height).
                items.chunked(3).forEach { row ->
                    val rowLayout = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(8f)
                        }
                    }
                    row.forEach { item ->
                        rowLayout.addView(buildCell(item), LinearLayout.LayoutParams(0, dp(56f), 1f).apply {
                            marginStart = dp(4f); marginEnd = dp(4f)
                        })
                    }
                    repeat(3 - row.size) {
                        rowLayout.addView(View(context), LinearLayout.LayoutParams(0, dp(56f), 1f).apply {
                            marginStart = dp(4f); marginEnd = dp(4f)
                        })
                    }
                    addView(rowLayout)
                }
            }
        }
        return ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(list)
        }
    }

    private fun buildCell(item: ExpeditionCatalogItem): View {
        val icon = ImageView(context).apply {
            setImageResource(expeditionIconRes(item.id))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(10f).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1.1f).coerceAtLeast(1), 0xFFCBD5E1.toInt())
            }
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            isClickable = true
            isFocusable = true
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnClickListener {
                AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(it, HapticEvent.KEY_PRESS)
                launchExpedition(item)
            }
        }
    }

    // R8 strips unreferenced resource-name lookups, so this must be an explicit map, not
    // resources.getIdentifier() - same reasoning as InvoicePanelView's paymentIconRes().
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

    // ---------------------------------------------------------------- launch logic (OngkirData.launchExpedition)

    private fun launchExpedition(item: ExpeditionCatalogItem) {
        if (item.isAppDelivery) launchAppDelivery(item) else launchRegularWebsite(item)
    }

    /** Extra hardcoded fallback schemes per specific courier, matching ongkir_panel.dart's
     *  `schemesToTry` list exactly (item.appScheme first, then these id-specific extras). */
    private fun schemesFor(item: ExpeditionCatalogItem): List<String> {
        val schemes = mutableListOf<String>()
        item.appScheme?.takeIf { it.isNotEmpty() }?.let { schemes.add(it) }
        when (item.id) {
            "grab" -> schemes.addAll(listOf("grab://open?screenType=EXPRESS", "grab://open", "grab://"))
            "gosend" -> schemes.addAll(listOf("gojek://gosend", "gojek://home", "gojek://"))
            "lalamove" -> schemes.add("lalamove://")
            "paxel" -> schemes.add("paxel://")
            "deliveree" -> schemes.add("deliveree://")
            "anteraja" -> schemes.add("anteraja://")
        }
        return schemes.distinct()
    }

    private fun launchAppDelivery(item: ExpeditionCatalogItem) {
        for (scheme in schemesFor(item)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // try the next scheme
            }
        }
        val pkg = item.appPackageName
        if (!pkg.isNullOrEmpty()) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (_: Exception) {
                    // fall through to the toast below
                }
            }
        }
        showToast("Tidak dapat membuka aplikasi ${item.displayName}")
    }

    private fun launchRegularWebsite(item: ExpeditionCatalogItem) {
        // No resolveActivity() pre-check: since Android 11 it returns null for an intent whose handler is not declared in
        // the manifest's <queries>, even when a browser exists (that was "Tidak dapat membuka situs cek ongkir" on every
        // phone). startActivity() itself needs no package visibility; it throws only when there is truly no browser.
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            showToast("Tidak dapat membuka situs cek ongkir ${item.displayName}")
        } catch (_: Exception) {
            showToast("Gagal membuka browser untuk ${item.displayName}")
        }
    }

    /** Same in-panel toast pattern as Settings/Invoice (dark pill, overlaid on the already-FrameLayout
     *  `content`) - this is now the 3rd panel that needs it; worth extracting to a shared helper once
     *  a 4th panel needs it too, not before (Invoice/Settings are already tested and working, so
     *  retrofitting them now for a code-sharing benefit with no user-facing change isn't worth the risk). */
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
}
