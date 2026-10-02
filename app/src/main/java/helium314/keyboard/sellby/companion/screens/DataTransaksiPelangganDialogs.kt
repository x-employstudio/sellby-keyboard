// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.IntentUtils
import helium314.keyboard.settings.filePicker
import helium314.keyboard.sellby.companion.theme.SellbyColors
import helium314.keyboard.sellby.data.SellbyDatabase
import helium314.keyboard.sellby.data.dao.OrderWithItems
import helium314.keyboard.sellby.data.entity.Customer
import helium314.keyboard.sellby.data.entity.OrderStatus
import helium314.keyboard.sellby.util.ChannelMessenger
import helium314.keyboard.sellby.util.CurrencyFormat
import helium314.keyboard.sellby.util.XlsxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================================================================================
// "Data Transaksi" and "Data Pelanggan" popups, reachable from DashboardScreen's DetailButtonsRow
// (previously just Toast placeholders). Both share ~70% of their chrome (floating-card shell,
// header with title/download/close, search field, empty state) - kept in one file, matching this
// codebase's convention of one largeish file per feature area (DashboardScreen.kt/StatusPanelView.kt
// are both 1000+ lines holding many private composables each) rather than splitting shared chrome
// across two files. XlsxWriter itself stays a separate small file in sellby.util, matching how
// CurrencyFormat.kt/Channel.kt are each standalone single-purpose utils there.
// ============================================================================================

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
private fun FloatingPopupDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Tall floating card where only the list scrolls (header/search/filters stay pinned) - the
        // same "sequential Column children never overlap" principle DashboardScreen's own sticky
        // header already relies on, applied one level deeper inside this Dialog's own Surface.
        val maxCardHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = SellbyColors.White, shadowElevation = 10.dp) {
                Column(Modifier.heightIn(max = maxCardHeight).padding(vertical = 20.dp), content = content)
            }
        }
    }
}

@Composable
private fun PopupHeader(title: String, onDownload: () -> Unit, onDismiss: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = SellbyColors.TextDark, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            CircleGlyphButton("✕", onDismiss, size = 34.dp, fontSize = 16.sp)
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            onClick = onDownload,
            shape = RoundedCornerShape(20.dp),
            color = SellbyColors.TealPrimary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⬇", color = SellbyColors.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("Download Data", color = SellbyColors.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CircleGlyphButton(glyph: String, onClick: () -> Unit, size: Dp = 30.dp, fontSize: TextUnit = 13.sp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(SellbyColors.TealPrimary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = SellbyColors.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PopupSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SellbyColors.SurfaceMuted)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔍", fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = SellbyColors.TextDark),
            cursorBrush = SolidColor(SellbyColors.TealPrimary),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = SellbyColors.TextHint)
                inner()
            },
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text("✕", color = SellbyColors.TextMuted, fontSize = 13.sp, modifier = Modifier.clickable { onValueChange("") })
        }
    }
}

@Composable
private fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(message, color = SellbyColors.TextHint, fontSize = 13.sp)
    }
}

private fun orderStatusLabel(status: OrderStatus): String = when (status) {
    OrderStatus.PENDING -> "Pending"
    OrderStatus.LUNAS -> "Lunas"
    OrderStatus.PROSES -> "Di Proses"
    OrderStatus.SELESAI -> "Selesai"
    OrderStatus.ARSIP -> "Diarsipkan"
}

// Exact colors ported from StatusPanelView.kt's statusBadge() (in-keyboard reference UI) for
// visual consistency between the keyboard's own Status panel and this companion-app report view.
private fun statusBadgeColors(status: OrderStatus): Pair<Color, Color> = when (status) {
    OrderStatus.PENDING -> Color(0xFFFFF3E0) to Color(0xFFF97316)
    OrderStatus.LUNAS -> Color(0xFFDCFCE7) to Color(0xFF16A34A)
    OrderStatus.PROSES -> Color(0xFFFEF9C3) to Color(0xFFCA8A04)
    OrderStatus.SELESAI -> Color(0xFFE0F2FE) to Color(0xFF0284C7)
    OrderStatus.ARSIP -> Color(0xFFF1F5F9) to Color(0xFF475569)
}

private val MONTH_ABBR_ID = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Ags", "Sep", "Okt", "Nov", "Des")

private fun formatOrderDate(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTH_ABBR_ID[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
}

private fun isoDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

private fun todayDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)

private fun exportIntent(context: Context, filename: String): Intent =
    IntentUtils.getResolvableTypeIntent(context, XLSX_MIME)
        .setAction(Intent.ACTION_CREATE_DOCUMENT)
        .putExtra(Intent.EXTRA_TITLE, filename)

// ============================================================================================
// Data Pelanggan
// ============================================================================================

private data class CustomerRow(val customer: Customer, val orderCount: Int, val totalSpent: Double)

private val PELANGGAN_HEADERS = listOf("Nama", "Alamat", "Channel", "Kontak", "Total Pesanan", "Total Belanja")

private fun customerToXlsxRow(row: CustomerRow): List<Any> = listOf(
    row.customer.name,
    row.customer.address,
    row.customer.channel.displayLabel,
    row.customer.phone,
    row.orderCount,
    row.totalSpent,
)

@Composable
fun DataPelangganDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val db = remember { SellbyDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<CustomerRow>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        combine(db.customerDao().getAll(), db.orderDao().getCustomerAggregates()) { customers, aggregates ->
            val byPhone = aggregates.associateBy { it.customerPhone }
            customers.map { c -> CustomerRow(c, byPhone[c.phone]?.orderCount ?: 0, byPhone[c.phone]?.totalSpent ?: 0.0) }
        }.collect { rows = it }
    }

    val filtered = remember(rows, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) rows
        else rows.filter {
            it.customer.name.lowercase().contains(q) ||
                it.customer.phone.lowercase().contains(q) ||
                it.customer.address.lowercase().contains(q)
        }
    }

    // Reuses the existing, already-public helium314.keyboard.settings.filePicker() launcher
    // helper (same SAF Intent.ACTION_CREATE_DOCUMENT round-trip BackupRestorePreference.kt uses)
    // instead of hand-rolling a new rememberLauncherForActivityResult block.
    val saveLauncher = filePicker { uri ->
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    XlsxWriter.write(os, "Pelanggan", PELANGGAN_HEADERS, filtered.map(::customerToXlsxRow))
                }
                withContext(Dispatchers.Main) { Toast.makeText(context, "File berhasil disimpan", Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Gagal menyimpan file: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    FloatingPopupDialog(onDismiss = onDismiss) {
        PopupHeader(
            title = "Data Pelanggan",
            onDownload = { saveLauncher.launch(exportIntent(context, "sellby_pelanggan_${todayDateString()}.xlsx")) },
            onDismiss = onDismiss,
        )
        Spacer(Modifier.height(14.dp))
        PopupSearchField(query, { query = it }, "Cari nama, nomor, atau alamat")
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) {
            EmptyState(if (rows.isEmpty()) "Belum ada data pelanggan" else "Tidak ditemukan", Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.customer.id }) { row ->
                    CustomerListRow(row, context)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(SellbyColors.SurfaceMuted))
                }
            }
        }
    }
}

@Composable
private fun CustomerListRow(row: CustomerRow, context: Context) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.customer.name, color = SellbyColors.TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(row.customer.channel.displayLabel, color = SellbyColors.TextHint, fontSize = 10.sp)
            }
            // Only takes up a line when there's an actual address to show - a "-" placeholder row
            // for every customer without one just made the list look sparser for no benefit.
            if (row.customer.address.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(row.customer.address, color = SellbyColors.TextMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            Text("${row.orderCount} Pesanan • ${CurrencyFormat.rupiah(row.totalSpent)}", color = SellbyColors.TealDark, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(SellbyColors.TealPrimary)
                .clickable {
                    if (!ChannelMessenger.openChat(context, row.customer.channel, row.customer.phone)) {
                        Toast.makeText(context, "Tidak dapat membuka ${row.customer.channel.displayLabel}", Toast.LENGTH_SHORT).show()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("💬", fontSize = 14.sp)
        }
    }
}

// ============================================================================================
// Data Transaksi
// ============================================================================================

private val TRANSAKSI_HEADERS = listOf(
    "Tanggal", "Nama Pelanggan", "Channel", "Kontak", "Produk", "Ekspedisi", "Alamat", "Metode Pembayaran", "Catatan", "Status", "Total",
)

private fun orderToXlsxRow(item: OrderWithItems): List<Any> {
    val order = item.order
    val productDetail = if (item.items.isNotEmpty()) item.items.joinToString("; ") { "${it.name} x${it.quantity}" } else order.productsSummary
    return listOf(
        isoDate(order.dateMillis),
        order.customerName,
        order.channel.displayLabel,
        order.customerPhone,
        productDetail,
        order.expedition,
        order.address,
        order.paymentMethod,
        order.notes,
        orderStatusLabel(order.status),
        order.totalAmount,
    )
}

private val STATUS_CHIPS: List<Pair<String, OrderStatus?>> = listOf(
    "Semua" to null,
    "Pending" to OrderStatus.PENDING,
    "Lunas" to OrderStatus.LUNAS,
    "Di Proses" to OrderStatus.PROSES,
    "Selesai" to OrderStatus.SELESAI,
    "Diarsipkan" to OrderStatus.ARSIP,
)

@Composable
fun DataTransaksiDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val db = remember { SellbyDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var orders by remember { mutableStateOf<List<OrderWithItems>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<OrderStatus?>(null) }
    var selected by remember { mutableStateOf<OrderWithItems?>(null) }

    LaunchedEffect(Unit) { db.orderDao().getAllWithItems().collect { orders = it } }

    val filtered = remember(orders, query, statusFilter) {
        val q = query.trim().lowercase()
        val byStatus = if (statusFilter == null) orders else orders.filter { it.order.status == statusFilter }
        if (q.isEmpty()) byStatus
        else byStatus.filter {
            it.order.customerName.lowercase().contains(q) ||
                it.order.productsSummary.lowercase().contains(q) ||
                it.order.customerPhone.contains(q) ||
                it.items.any { item -> item.name.lowercase().contains(q) }
        }
    }

    val saveLauncher = filePicker { uri ->
        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    XlsxWriter.write(os, "Transaksi", TRANSAKSI_HEADERS, filtered.map(::orderToXlsxRow))
                }
                withContext(Dispatchers.Main) { Toast.makeText(context, "File berhasil disimpan", Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Gagal menyimpan file: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    FloatingPopupDialog(onDismiss = onDismiss) {
        // Placed inside the Dialog's own content (not before/outside the FloatingPopupDialog call)
        // so it registers on the dialog window's own back dispatcher and takes priority over the
        // Dialog's default dismissOnBackPress - one back-press returns to the list, a second closes
        // the popup, same nested-BackHandler idiom already proven by TestKeyboardOverlay/the wizard.
        BackHandler(enabled = selected != null) { selected = null }
        val current = selected
        if (current != null) {
            OrderDetailView(current, onBack = { selected = null }, context = context, modifier = Modifier.weight(1f))
        } else {
            PopupHeader(
                title = "Data Transaksi",
                onDownload = { saveLauncher.launch(exportIntent(context, "sellby_transaksi_${todayDateString()}.xlsx")) },
                onDismiss = onDismiss,
            )
            Spacer(Modifier.height(14.dp))
            PopupSearchField(query, { query = it }, "Cari nama, produk, atau nomor")
            Spacer(Modifier.height(10.dp))
            StatusChipsRow(statusFilter) { statusFilter = it }
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) {
                EmptyState(if (orders.isEmpty()) "Belum ada transaksi" else "Tidak ditemukan", Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.order.id }) { order ->
                        OrderListRow(order) { selected = order }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(SellbyColors.SurfaceMuted))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChipsRow(statusFilter: OrderStatus?, onSelect: (OrderStatus?) -> Unit) {
    // rememberScrollState() is hoisted (not created inline in .horizontalScroll()) so
    // canScrollBackward/canScrollForward below can read the SAME instance the Row actually scrolls.
    val scrollState = rememberScrollState()
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scrollState).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            STATUS_CHIPS.forEach { (label, status) ->
                val isSelected = status == statusFilter
                Surface(
                    onClick = { onSelect(status) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) SellbyColors.TealPrimary else SellbyColors.SurfaceMuted,
                ) {
                    Text(
                        label,
                        color = if (isSelected) SellbyColors.White else SellbyColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
        // Fade hints so it's clear the chip row scrolls sideways - a FIXED height (not
        // fillMaxHeight()) on purpose: fillMaxHeight() here previously made this non-weighted Box
        // claim the dialog Column's entire remaining height budget during its first measure pass
        // (before the weighted LazyColumn below got its share), squeezing the actual order list
        // down to near-zero height - this is what made the data appear to "disappear". Neither fade
        // has any click handling of its own, so they never intercept taps on the chips beneath them.
        // canScrollBackward/canScrollForward make each fade appear only on the side that actually
        // has more content to reveal, matching left/right symmetrically as the row scrolls.
        if (scrollState.canScrollBackward) {
            Box(
                Modifier.align(Alignment.CenterStart).width(40.dp).height(36.dp)
                    .background(Brush.horizontalGradient(listOf(SellbyColors.White, SellbyColors.White.copy(alpha = 0f)))),
            )
        }
        if (scrollState.canScrollForward) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(40.dp).height(36.dp)
                    .background(Brush.horizontalGradient(listOf(SellbyColors.White.copy(alpha = 0f), SellbyColors.White))),
            )
        }
    }
}

@Composable
private fun OrderListRow(item: OrderWithItems, onClick: () -> Unit) {
    val (bg, fg) = statusBadgeColors(item.order.status)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.order.customerName, color = SellbyColors.TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(item.order.channel.displayLabel, color = SellbyColors.TextHint, fontSize = 10.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(percent = 50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(orderStatusLabel(item.order.status), color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatOrderDate(item.order.dateMillis)} • ${item.order.productsSummary}",
            color = SellbyColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            CurrencyFormat.rupiah(item.order.totalAmount), color = SellbyColors.TealDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.offset(y = (-2).dp),
        )
    }
}

@Composable
private fun OrderDetailView(item: OrderWithItems, onBack: () -> Unit, context: Context, modifier: Modifier = Modifier) {
    val order = item.order
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            // Same chevron-in-a-teal-circle used by the in-keyboard panel headers
            // (StatusPanelView.kt etc.'s circleButton(ic_settings_chevron_left_sellby)), not a bare
            // text glyph, per the request to match that panel design - sized up a bit past the
            // panel's own 32dp/18dp for a bigger tap target here.
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(SellbyColors.TealPrimary).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_settings_chevron_left_sellby),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("Detail Transaksi", color = SellbyColors.TextDark, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            val (bg, fg) = statusBadgeColors(order.status)
            Box(Modifier.clip(RoundedCornerShape(percent = 50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(orderStatusLabel(order.status), color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            DetailRow("Nama Pelanggan", order.customerName)
            DetailRow("Channel", order.channel.displayLabel)
            DetailRow(order.channel.messageLineLabel, order.customerPhone.ifEmpty { "-" })
            DetailRow("Tanggal", formatOrderDate(order.dateMillis))
            Text("Rincian Pembelian:", color = SellbyColors.TextDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            if (item.items.isNotEmpty()) {
                item.items.forEach { oi -> Text("• ${oi.name} ×${oi.quantity}", color = SellbyColors.TextMuted, fontSize = 12.5.sp) }
            } else {
                Text(order.productsSummary, color = SellbyColors.TextMuted, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            DetailRow("Pengiriman", order.expedition.ifEmpty { "-" })
            DetailRow("Alamat", order.address.ifEmpty { "-" })
            DetailRow("Catatan", order.notes.ifEmpty { "-" })
            DetailRow("Pembayaran", order.paymentMethod.ifEmpty { "-" })
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SellbyColors.OmzetCard).padding(14.dp)) {
                Column {
                    Text("Total Pembayaran", color = SellbyColors.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    Text(CurrencyFormat.rupiah(order.totalAmount), color = SellbyColors.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                onClick = {
                    if (!ChannelMessenger.openChat(context, order.channel, order.customerPhone)) {
                        Toast.makeText(context, "Tidak dapat membuka ${order.channel.displayLabel}", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(24.dp),
                color = SellbyColors.TealPrimary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Chat Customer", color = SellbyColors.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(label, color = SellbyColors.TextHint, fontSize = 10.5.sp)
        Text(value, color = SellbyColors.TextDark, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
