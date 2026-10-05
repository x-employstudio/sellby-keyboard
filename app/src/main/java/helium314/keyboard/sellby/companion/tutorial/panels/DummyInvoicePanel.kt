// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.tutorial.DummyProduct
import helium314.keyboard.sellby.companion.tutorial.FieldId
import helium314.keyboard.sellby.companion.tutorial.FormOrderOffer
import helium314.keyboard.sellby.companion.tutorial.InvoiceItem
import helium314.keyboard.sellby.companion.tutorial.InvoiceState
import helium314.keyboard.sellby.companion.tutorial.InvoiceView
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialController
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import helium314.keyboard.sellby.companion.tutorial.spotlight.LocalTutorial
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialDecoy
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialTarget
import helium314.keyboard.sellby.util.CurrencyFormat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Dummy copy of the real Invoice panel (sellby/ui/InvoicePanelView.kt): channel row, customer,
 * "Input otomatis" product search, items, shipping with the expedition/service sub-screen, payment
 * methods and the teal total bar with "Buat Invoice". Same labels, order and colours; sizes are
 * tightened. Deliberate difference: the search results sit INLINE under the search box (the real
 * panel floats them in a PopupWindow, which can't be spotlit). Everything is derived from the
 * [InvoiceState] - nothing is stored.
 */
@Composable
internal fun DummyInvoicePanel(state: TutorialState, height: Dp) {
    val controller = LocalTutorial.current
    Box(Modifier.fillMaxWidth().height(height)) {
        PanelShell(
            title = "Invoice",
            height = height,
            leadingIcon = R.drawable.ic_settings_chevron_down_sellby,
            trailingIcon = R.drawable.ic_toolbar_reset_sellby,
        ) {
            InvoiceForm(state, controller)
        }
        if (state.invoice.view == InvoiceView.Expedition) {
            ExpeditionPicker(state.invoice, height)
        }
        val offer = state.formOrderOffer
        if (offer != null && state.invoice.view == InvoiceView.Form) {
            FormOrderOfferPopup(offer)
        }
    }
}

/** "Form Order Terdeteksi": offered when Invoice opens while a filled-in Form Order is on the clipboard. */
@Composable
private fun FormOrderOfferPopup(offer: FormOrderOffer) {
    PanelScrim {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .background(Color.White, RoundedCornerShape(18.dp))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Form Order Terdeteksi", color = PanelColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Isi data pelanggan berikut ke Invoice?",
                color = PanelColors.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                listOf("Nama" to offer.nama, "No HP" to offer.wa, "Alamat" to offer.alamat).forEach { (label, value) ->
                    Row {
                        Text(label, color = PanelColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp))
                        Text(value, color = PanelColors.Muted, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Abaikan", PanelColors.Field, Modifier.weight(1f).tutorialDecoy(), textColor = PanelColors.Slate, fontSize = 12.sp, verticalPadding = 8.dp)
                PillButton("Tempel", PanelColors.Teal, Modifier.weight(1f).tutorialTarget(TargetId.OfferTempel), fontSize = 12.sp, verticalPadding = 8.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- form

@Composable
private fun ColumnScope.InvoiceForm(state: TutorialState, controller: TutorialController) {
    val inv = state.invoice
    val typing = controller.typingField
    val searchActive = inv.searchOpen || typing == FieldId.InvoiceSearch
    // The panel body is scrolled by the tutorial itself (TutorialScreen): the search box ends up near
    // the top of the viewport, so the results that appear below it are visible too.
    PanelScrollColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SectionHeader("Pilih Channel")
        ChannelRow()

        SectionHeader("Detail Pelanggan")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelField(
                value = controller.fieldText(FieldId.InvoiceNama, inv.nama),
                hint = "Nama Pelanggan*",
                modifier = Modifier.weight(1f).tutorialTarget(TargetId.InvoiceNama, tappable = false),
                focused = typing == FieldId.InvoiceNama,
            )
            Box(
                Modifier.size(32.dp).background(PanelColors.Teal, RoundedCornerShape(8.dp)).tutorialDecoy(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_invoice_contact_sellby), null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
        }
        PanelField(
            value = controller.fieldText(FieldId.InvoiceWa, inv.wa),
            hint = "Nomor Whatsapp*",
            modifier = Modifier.fillMaxWidth().tutorialTarget(TargetId.InvoiceWa, tappable = false),
            prefix = "+62  ",
            focused = typing == FieldId.InvoiceWa,
        )

        SectionHeader("Rincian Produk")
        SubHeader("Input otomatis")
        PanelField(
            value = controller.fieldText(FieldId.InvoiceSearch, inv.search),
            hint = "Cari Produk...",
            modifier = Modifier.fillMaxWidth().tutorialTarget(TargetId.InvoiceSearch),
            focused = searchActive,
            leadingIcon = R.drawable.sym_keyboard_search_rounded,
        )
        val query = controller.fieldText(FieldId.InvoiceSearch, inv.search)
        if (inv.searchOpen && query.isNotEmpty()) {
            SearchResults(query, state, inv)
        }
        SubHeader("Input Manual")
        ManualEntryRows()
        DashedDivider()
        Column(Modifier.fillMaxWidth().tutorialTarget(TargetId.InvoiceItems, tappable = false)) {
            if (inv.items.isEmpty()) {
                Text(
                    "Belum ada produk yang ditambahkan",
                    color = PanelColors.Hint,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            } else {
                inv.items.forEach { ItemCard(it) }
            }
        }
        DashedDivider()
        PanelField("", "Diskon Tambahan (Opsional)", Modifier.fillMaxWidth().tutorialDecoy(), prefix = "Rp ")
        PanelField("", "Catatan", Modifier.fillMaxWidth().tutorialDecoy())

        SectionHeader("Detail Pengiriman")
        PanelField(
            value = controller.fieldText(FieldId.InvoiceAlamat, inv.alamat),
            hint = "Alamat Penerima",
            modifier = Modifier.fillMaxWidth().tutorialTarget(TargetId.InvoiceAlamat, tappable = false),
            focused = typing == FieldId.InvoiceAlamat,
        )
        ExpeditionSummaryRow(inv)
        PanelField(
            value = CurrencyFormat.liveDigitsToGrouped(controller.fieldText(FieldId.InvoiceOngkir, inv.ongkir)),
            hint = "Ongkir",
            modifier = Modifier.fillMaxWidth().tutorialTarget(TargetId.InvoiceOngkir, tappable = false),
            prefix = "Rp ",
            focused = typing == FieldId.InvoiceOngkir,
        )

        SectionHeader("Metode Pembayaran")
        Text(
            "*bisa pilih lebih dari satu",
            color = PanelColors.Hint,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        PaymentRow(inv)
        Spacer(Modifier.height(6.dp))
    }
    BottomBar(inv)
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        color = PanelColors.Teal,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun SubHeader(title: String) {
    Text(title, color = PanelColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun DashedDivider() {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = PanelColors.CardBorder,
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
    }
}

// ---------------------------------------------------------------- channel + payment circles

private val GrayFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun CircleChoice(icon: Int, selected: Boolean, size: Dp, iconSize: Dp, grayWhenIdle: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(Color.White, CircleShape)
            .border(if (selected) 2.5.dp else 1.2.dp, if (selected) PanelColors.Teal else PanelColors.CardBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = if (grayWhenIdle && !selected) GrayFilter else null,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** WhatsApp is the (only) selected channel - the lesson never changes it. */
@Composable
private fun ChannelRow() {
    val channels = listOf(
        R.drawable.ic_channel_whatsapp_sellby,
        R.drawable.ic_channel_whatsapp_business_sellby,
        R.drawable.ic_channel_telegram_sellby,
        R.drawable.ic_channel_instagram_sellby,
    )
    Row(
        Modifier.fillMaxWidth().tutorialTarget(TargetId.InvoiceChannel, tappable = false),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        channels.forEachIndexed { i, icon ->
            CircleChoice(icon, selected = i == 0, size = 40.dp, iconSize = 24.dp, grayWhenIdle = true, modifier = Modifier.tutorialDecoy())
        }
    }
}

@Composable
private fun PaymentRow(inv: InvoiceState) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleChoice(R.drawable.ic_payment_cash_sellby, selected = inv.payment == "Tunai", size = 44.dp, iconSize = 26.dp, grayWhenIdle = false, modifier = Modifier.tutorialDecoy())
        CircleChoice(R.drawable.ic_payment_qris_sellby, selected = inv.payment == "QRIS", size = 44.dp, iconSize = 26.dp, grayWhenIdle = false, modifier = Modifier.tutorialDecoy())
        CircleChoice(
            R.drawable.ic_payment_bca_sellby,
            selected = inv.payment == SampleData.BANK,
            size = 44.dp,
            iconSize = 26.dp,
            grayWhenIdle = false,
            modifier = Modifier.tutorialTarget(TargetId.PayBca),
        )
        // "+ Tambah Metode" circle at the end of the real carousel.
        Box(
            Modifier
                .size(44.dp)
                .background(Color.White, CircleShape)
                .border(1.2.dp, PanelColors.Teal, CircleShape)
                .tutorialDecoy(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_add_sellby), null, tint = PanelColors.Teal, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------- search + items

@Composable
private fun SearchResults(query: String, state: TutorialState, inv: InvoiceState) {
    val matches = state.produk.products.filter { it.name.contains(query, ignoreCase = true) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
            .border(1.dp, PanelColors.CardBorder, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (matches.isEmpty()) {
            Text("Produk tidak ditemukan", color = PanelColors.Hint, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
        } else {
            matches.forEach { SearchResultRow(it, inv) }
        }
    }
}

@Composable
private fun SearchResultRow(product: DummyProduct, inv: InvoiceState) {
    val isTarget = product.name == SampleData.KAOS_M
    val pcs = if (isTarget) inv.searchPcs else 1
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(product.name, color = PanelColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(
                CurrencyFormat.rupiah(product.finalPrice.toDouble()),
                color = PanelColors.Teal,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.height(28.dp).background(PanelColors.Field, RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperArrow("−", Modifier.tutorialDecoy())
            Text("$pcs pcs", color = PanelColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            StepperArrow("+", if (isTarget) Modifier.tutorialTarget(TargetId.SearchStepperPlus) else Modifier.tutorialDecoy())
        }
        Spacer(Modifier.width(8.dp))
        PillButton(
            "+ Input",
            PanelColors.Teal,
            if (isTarget) Modifier.tutorialTarget(TargetId.SearchAddM) else Modifier.tutorialDecoy(),
            fontSize = 11.sp,
            verticalPadding = 6.dp,
        )
    }
}

@Composable
private fun StepperArrow(label: String, modifier: Modifier) {
    Box(modifier.size(width = 26.dp, height = 28.dp), contentAlignment = Alignment.Center) {
        Text(label, color = PanelColors.Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** The real "Input Manual" rows, drawn but not part of the lesson (the guide only mentions them). */
@Composable
private fun ManualEntryRows() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PanelField("", "Nama Produk", Modifier.weight(1f).tutorialDecoy())
        Row(
            Modifier.height(32.dp).background(PanelColors.Field, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("1 pcs", color = PanelColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PanelField("", "Harga Satuan", Modifier.weight(1f).tutorialDecoy(), prefix = "Rp ")
        PillButton("+ Tambah", PanelColors.Teal, Modifier.tutorialDecoy(), fontSize = 11.sp, verticalPadding = 7.dp)
    }
}

@Composable
private fun ItemCard(item: InvoiceItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp)
            .background(Color(0xFFFEF9C3), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(item.name, color = PanelColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("x${item.qty}", color = PanelColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(CurrencyFormat.rupiah(item.unitPrice.toDouble()), color = PanelColors.Muted, fontSize = 10.sp)
        }
        Text(CurrencyFormat.rupiah(item.subtotal.toDouble()), color = PanelColors.Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Icon(
            painterResource(R.drawable.ic_settings_delete_sellby),
            contentDescription = null,
            tint = PanelColors.Red,
            modifier = Modifier.padding(start = 8.dp).size(width = 16.dp, height = 19.dp).tutorialDecoy(),
        )
    }
}

// ---------------------------------------------------------------- shipping

private fun expeditionSummary(inv: InvoiceState): String = when {
    inv.expedition != null && inv.service != null -> "${inv.expedition} - ${inv.service}"
    inv.expedition != null -> inv.expedition
    inv.service != null -> inv.service
    else -> "Pilih ekspedisi dan layanan"
}

@Composable
private fun ExpeditionSummaryRow(inv: InvoiceState) {
    val hasSelection = inv.expedition != null || inv.service != null
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(PanelColors.Field, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp)
            .tutorialTarget(TargetId.ExpeditionRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            expeditionSummary(inv),
            color = if (hasSelection) PanelColors.Text else PanelColors.Hint,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_settings_chevron_right_sellby), null, tint = PanelColors.Hint, modifier = Modifier.size(14.dp))
    }
}

private data class ExpeditionOption(val name: String, val icon: Int)

private val Expeditions = listOf(
    ExpeditionOption("JNE", R.drawable.ic_expedition_jne_sellby),
    ExpeditionOption("J&T", R.drawable.ic_expedition_jnt_sellby),
    ExpeditionOption("SiCepat", R.drawable.ic_expedition_sicepat_sellby),
    ExpeditionOption("Anteraja", R.drawable.ic_expedition_anteraja_sellby),
    ExpeditionOption("POS", R.drawable.ic_expedition_pos_sellby),
    ExpeditionOption("TIKI", R.drawable.ic_expedition_tiki_sellby),
)

private val Services = listOf("Reguler", "Next Day", "Same Day", "Kargo", "Ekonomis", "Instan")

/** Full-panel sub-screen: expedition cards (2 per row) | services, with Batalkan / Simpan below. */
@Composable
private fun ExpeditionPicker(inv: InvoiceState, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            // Swallow taps so nothing of the form underneath can be hit through this sub-screen.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        PanelShell(
            title = "Invoice",
            height = height,
            leadingIcon = R.drawable.ic_settings_chevron_left_sellby,
            trailingIcon = null,
        ) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier
                        .weight(60f)
                        .fillMaxHeight()
                        .lockedScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Expeditions.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { option ->
                                ExpeditionCard(
                                    option,
                                    selected = inv.pendingExpedition == option.name,
                                    modifier = Modifier
                                        .weight(1f)
                                        .let { if (option.name == SampleData.EXPEDITION) it.tutorialTarget(TargetId.ExpJne) else it.tutorialDecoy() },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(PanelColors.Divider))
                Column(
                    Modifier
                        .weight(40f)
                        .fillMaxHeight()
                        .lockedScroll(rememberScrollState()),
                ) {
                    Services.forEach { service ->
                        val selected = inv.pendingService == service
                        Text(
                            service,
                            color = if (selected) PanelColors.Teal else PanelColors.Text,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected) Color(0xFFBCE3EB) else Color.Transparent)
                                .let { if (service == SampleData.SERVICE) it.tutorialTarget(TargetId.SvcReguler) else it.tutorialDecoy() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(PanelColors.Divider))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton("Batalkan", PanelColors.Field, Modifier.weight(1f).tutorialDecoy(), textColor = PanelColors.Slate, fontSize = 12.sp, verticalPadding = 8.dp)
                PillButton("Simpan", PanelColors.Teal, Modifier.weight(1f).tutorialTarget(TargetId.ExpSimpan), fontSize = 12.sp, verticalPadding = 8.dp)
            }
        }
    }
}

@Composable
private fun ExpeditionCard(option: ExpeditionOption, selected: Boolean, modifier: Modifier) {
    Box(
        modifier
            .height(44.dp)
            .background(Color.White, RoundedCornerShape(10.dp))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) PanelColors.Teal else PanelColors.CardBorder, RoundedCornerShape(10.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(option.icon), contentDescription = option.name, modifier = Modifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------- bottom bar

@Composable
private fun BottomBar(inv: InvoiceState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PanelColors.Teal)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Total Pembayaran", color = Color.White, fontSize = 10.sp)
            Text(
                CurrencyFormat.rupiah(inv.grandTotal.toDouble()),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        PillButton(
            "Buat Invoice",
            Color(0xFFE2DC74),
            Modifier.tutorialTarget(TargetId.BuatInvoice),
            textColor = PanelColors.Text,
            fontSize = 12.sp,
            horizontalPadding = 18.dp,
            verticalPadding = 8.dp,
        )
    }
}
