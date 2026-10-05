// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.tutorial.DummyOrder
import helium314.keyboard.sellby.companion.tutorial.DummyOrderStatus
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.StatusAction
import helium314.keyboard.sellby.companion.tutorial.StatusOverlay
import helium314.keyboard.sellby.companion.tutorial.StatusState
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialDecoy
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialTarget
import helium314.keyboard.sellby.util.CurrencyFormat

/**
 * Dummy copy of the real Status panel (sellby/ui/StatusPanelView.kt): 4 status tabs with counts,
 * search box, the order card with the buttons of its status, the Detail Transaksi sheet and the
 * confirmation dialog. The real panel swipes between cards; here the card of the selected tab is
 * simply the first order of that status. Derived from [StatusState] and the orders - nothing is stored.
 */
@Composable
internal fun DummyStatusPanel(state: TutorialState, height: Dp) {
    val status = state.status
    Box(Modifier.fillMaxWidth().height(height)) {
        PanelShell(
            title = "Status",
            height = height,
            leadingIcon = R.drawable.ic_settings_chevron_down_sellby,
            trailingIcon = R.drawable.ic_toolbar_reset_sellby,
        ) {
            StatusBody(state, status)
        }
        val order = state.orders.firstOrNull { it.status == status.tab }
        if (status.overlay == StatusOverlay.Detail && order != null) DetailSheet(order)
        if (status.overlay == StatusOverlay.Confirm && status.confirm != null && order != null) {
            ConfirmDialog(status.confirm, order)
        }
    }
}

// ---------------------------------------------------------------- body

private val TabOrder = listOf(
    DummyOrderStatus.Pending to "Pending",
    DummyOrderStatus.Lunas to "Lunas",
    DummyOrderStatus.Proses to "Proses",
    DummyOrderStatus.Selesai to "Selesai",
)

@Composable
private fun ColumnScope.StatusBody(state: TutorialState, status: StatusState) {
    PanelScrollColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().tutorialTarget(TargetId.StatusTabs, tappable = false),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TabOrder.forEach { (tab, label) ->
                val count = state.orders.count { it.status == tab }
                val selected = tab == status.tab
                Text(
                    "$label($count)",
                    color = if (selected) Color.White else PanelColors.Muted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(if (selected) PanelColors.Teal else PanelColors.Field, RoundedCornerShape(16.dp))
                        .padding(horizontal = 2.dp, vertical = 5.dp),
                )
            }
        }
        PanelField("", "Cari nama, nomor, atau produk...", Modifier.fillMaxWidth().tutorialDecoy(), height = 30.dp, leadingIcon = R.drawable.sym_keyboard_search_rounded)
        Text(
            "Klik kartu untuk membuka detail transaksi",
            color = PanelColors.Hint,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val order = state.orders.firstOrNull { it.status == status.tab }
        if (order == null) {
            Text(
                "Belum ada transaksi",
                color = PanelColors.Hint,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            )
        } else {
            OrderCard(order)
        }
    }
}

private fun statusBadgeColors(status: DummyOrderStatus): Triple<String, Color, Color> = when (status) {
    DummyOrderStatus.Pending -> Triple("Pending", Color(0xFFFFF3E0), Color(0xFFF97316))
    DummyOrderStatus.Lunas -> Triple("Lunas", Color(0xFFDCFCE7), Color(0xFF16A34A))
    DummyOrderStatus.Proses -> Triple("Di Proses", Color(0xFFFEF9C3), Color(0xFFCA8A04))
    DummyOrderStatus.Selesai -> Triple("Selesai", Color(0xFFE0F2FE), Color(0xFF0284C7))
    DummyOrderStatus.Arsip -> Triple("Diarsipkan", Color(0xFFF1F5F9), Color(0xFF475569))
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Text(
        text,
        color = fg,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun OrderCard(order: DummyOrder) {
    val (label, bg, fg) = statusBadgeColors(order.status)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.1.dp, PanelColors.CardBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // The tappable upper half (opens Detail Transaksi).
        Column(Modifier.fillMaxWidth().tutorialTarget(TargetId.StatusCard)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SampleData.INVOICE_DATE, color = PanelColors.Muted, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                Badge("WhatsApp", Color(0xFFDCFCE7), Color(0xFF16A34A))
                Spacer(Modifier.width(6.dp))
                Badge(label, bg, fg)
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(order.customerName, color = PanelColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(order.productsSummary, color = PanelColors.Muted, fontSize = 11.sp, maxLines = 1)
                }
                Text(CurrencyFormat.rupiah(order.total.toDouble()), color = PanelColors.Teal, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        Canvas(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)) {
            drawLine(
                color = PanelColors.CardBorder,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = size.height,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
        ActionRow(order.status)
    }
}

@Composable
private fun ActionRow(status: DummyOrderStatus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            DummyOrderStatus.Pending -> {
                ActionButton("Ingatkan", Color(0xFFF97316), Color.White, Modifier.weight(1f).tutorialTarget(TargetId.BtnIngatkan))
                ActionButton("Lunas", Color(0xFF22C55E), Color.White, Modifier.weight(1f).tutorialTarget(TargetId.BtnLunas))
                ActionButton("Hapus", PanelColors.Red, Color.White, Modifier.weight(1f).tutorialDecoy())
            }
            DummyOrderStatus.Lunas -> {
                ActionButton("Proses Pesanan", Color(0xFFFACC15), PanelColors.Text, Modifier.weight(1f).tutorialTarget(TargetId.BtnProses))
                DeleteButton()
            }
            DummyOrderStatus.Proses -> {
                ActionButton("Selesai", PanelColors.Teal, Color.White, Modifier.weight(1f).tutorialTarget(TargetId.BtnSelesai))
                DeleteButton()
            }
            else -> {
                ActionButton("Arsipkan", PanelColors.Text, Color.White, Modifier.weight(1f).tutorialTarget(TargetId.BtnArsip))
                DeleteButton()
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, bg: Color, fg: Color, modifier: Modifier) {
    Box(modifier.height(28.dp).background(bg, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun DeleteButton() {
    Box(
        Modifier.size(width = 32.dp, height = 28.dp).background(Color(0x1FEF4444), RoundedCornerShape(8.dp)).tutorialDecoy(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_settings_delete_sellby), null, tint = PanelColors.Red, modifier = Modifier.size(width = 15.dp, height = 18.dp))
    }
}

// ---------------------------------------------------------------- overlays

@Composable
private fun DetailSheet(order: DummyOrder) {
    val (label, bg, fg) = statusBadgeColors(order.status)
    PanelScrim {
        Column(
            Modifier
                .fillMaxWidth(0.84f)
                .fillMaxHeight(0.96f)
                .background(Color.White, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Detail Transaksi", color = PanelColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Badge(label, bg, fg)
            }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp).background(PanelColors.Divider))
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                DetailRow("Nama Pelanggan", order.customerName)
                DetailRow("Channel", "WhatsApp")
                DetailRow("No. Telepon", order.contact)
                DetailRow("Tanggal", SampleData.INVOICE_DATE)
                Text("Rincian Pembelian:", color = PanelColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                order.items.forEach { (name, qty) ->
                    Row {
                        Text("• $name", color = PanelColors.Slate, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("x$qty", color = PanelColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                DetailRow("Pengiriman", order.expedition)
                DetailRow("Alamat", order.address)
                DetailRow("Pembayaran", order.payment)
                Row(
                    Modifier.fillMaxWidth().padding(top = 3.dp).background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("Total Pembayaran", color = PanelColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(CurrencyFormat.rupiah(order.total.toDouble()), color = PanelColors.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Kembali", PanelColors.Field, Modifier.weight(1f).tutorialTarget(TargetId.DetailKembali), textColor = PanelColors.Slate, fontSize = 12.sp, verticalPadding = 8.dp)
                PillButton("Chat Customer", PanelColors.Teal, Modifier.weight(1f).tutorialDecoy(), fontSize = 12.sp, verticalPadding = 8.dp)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(label, color = PanelColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(92.dp))
        Text(": $value", color = PanelColors.Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

private data class ConfirmCopy(val title: String, val message: String, val color: Color, val textColor: Color)

private fun confirmCopy(action: StatusAction, order: DummyOrder): ConfirmCopy = when (action) {
    StatusAction.Lunas -> ConfirmCopy(
        "Ubah ke Lunas?",
        "Pesanan Kak ${order.customerName} akan dipindahkan ke tab Lunas untuk disiapkan pengirimannya.",
        Color(0xFF22C55E), Color.White,
    )
    StatusAction.Proses -> ConfirmCopy(
        "Proses Pesanan Ini?",
        "Pesanan Kak ${order.customerName} akan ditandai sedang dipacking / dalam proses pengiriman.",
        Color(0xFFFACC15), PanelColors.Text,
    )
    StatusAction.Selesai -> ConfirmCopy(
        "Selesaikan Pesanan?",
        "Pesanan Kak ${order.customerName} akan ditandai selesai dan telah diterima dengan baik oleh pembeli.",
        PanelColors.Teal, Color.White,
    )
    StatusAction.Arsip -> ConfirmCopy(
        "Arsipkan Pesanan?",
        "Pesanan Kak ${order.customerName} akan dipindahkan ke arsip agar daftar transaksi aktif tetap bersih.",
        PanelColors.Text, Color.White,
    )
}

@Composable
private fun ConfirmDialog(action: StatusAction, order: DummyOrder) {
    val copy = confirmCopy(action, order)
    PanelScrim {
        Column(
            Modifier
                .padding(horizontal = 22.dp)
                .background(Color.White, RoundedCornerShape(18.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(20.dp).background(copy.color, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Text("i", color = copy.textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(copy.title, color = PanelColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text(
                copy.message,
                color = PanelColors.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Batalkan", PanelColors.Field, Modifier.weight(1f).tutorialDecoy(), textColor = PanelColors.Slate, fontSize = 12.sp, verticalPadding = 8.dp)
                PillButton("Lanjutkan", copy.color, Modifier.weight(1f).tutorialTarget(TargetId.ConfirmLanjutkan), textColor = copy.textColor, fontSize = 12.sp, verticalPadding = 8.dp)
            }
        }
    }
}
