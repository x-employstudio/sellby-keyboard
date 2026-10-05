// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.tutorial.DummyProduct
import helium314.keyboard.sellby.companion.tutorial.FieldId
import helium314.keyboard.sellby.companion.tutorial.ProdukForm
import helium314.keyboard.sellby.companion.tutorial.ProdukPanelState
import helium314.keyboard.sellby.companion.tutorial.ProdukView
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialController
import helium314.keyboard.sellby.companion.tutorial.spotlight.LocalTutorial
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialDecoy
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialTarget
import helium314.keyboard.sellby.util.CurrencyFormat
import kotlin.math.roundToInt

/**
 * Dummy copy of the real Produk panel (sellby/ui/ProdukPanelView.kt): the product list with its
 * "+ Tambah Produk" bar, and the add form. Same labels, colours and field order; only the sizes are
 * tightened a little. Everything is derived from [ProdukPanelState] - nothing here is stored.
 */
@Composable
internal fun DummyProdukPanel(state: ProdukPanelState, height: Dp) {
    val controller = LocalTutorial.current
    val inList = state.view == ProdukView.List
    PanelShell(
        title = "Produk",
        height = height,
        leadingIcon = if (inList) R.drawable.ic_settings_chevron_down_sellby else R.drawable.ic_settings_chevron_left_sellby,
        trailingIcon = if (inList) R.drawable.ic_toolbar_reset_sellby else null,
    ) {
        if (inList) ProdukList(state.products) else ProdukFormView(state.form, controller)
    }
}

// ---------------------------------------------------------------- list

@Composable
private fun ColumnScope.ProdukList(products: List<DummyProduct>) {
    PanelScrollColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelField("", "Cari Produk...", Modifier.fillMaxWidth().tutorialDecoy(), height = 30.dp)
        // The lesson sends the description of the product it just created, so only that card carries the target.
        val kirimIndex = products.indexOfFirst { it.name.startsWith(SampleData.KAOS_NAMA) }
        if (products.isEmpty()) {
            Text(
                "Belum ada produk tersimpan",
                color = PanelColors.Hint,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        }
        products.forEachIndexed { i, product -> ProductCard(product, isKirimTarget = i == kirimIndex) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(PanelColors.Teal)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        PillButton(
            text = "+  Tambah Produk",
            background = PanelColors.Lime,
            textColor = PanelColors.Text,
            fontSize = 12.5.sp,
            horizontalPadding = 28.dp,
            verticalPadding = 7.dp,
            modifier = Modifier.tutorialTarget(TargetId.ProdukAdd),
        )
    }
}

@Composable
private fun ProductCard(product: DummyProduct, isKirimTarget: Boolean) {
    val hasDiscount = product.discount > 0
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .border(1.1.dp, PanelColors.CardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                product.name,
                color = PanelColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (hasDiscount) {
                PillButton("Disc ${product.discountPercent}%", PanelColors.Orange, fontSize = 9.5.sp, verticalPadding = 2.dp)
            }
        }
        Spacer(Modifier.height(3.dp))
        if (hasDiscount) {
            Text(
                CurrencyFormat.rupiah(product.price.toDouble()),
                color = PanelColors.Hint,
                fontSize = 10.5.sp,
                textDecoration = TextDecoration.LineThrough,
            )
        }
        Text(
            CurrencyFormat.rupiah(product.finalPrice.toDouble()),
            color = PanelColors.Teal,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Stock ${product.stock} Pcs",
                color = PanelColors.Muted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).tutorialDecoy(),
            )
            PillButton("Hapus", PanelColors.Red, Modifier.tutorialDecoy())
            Spacer(Modifier.width(4.dp))
            PillButton("Edit", PanelColors.Slate, Modifier.tutorialDecoy())
            Spacer(Modifier.width(4.dp))
            PillButton(
                "Kirim Deskripsi",
                PanelColors.Teal,
                if (isKirimTarget) Modifier.tutorialTarget(TargetId.ProdukKirimDeskripsi) else Modifier.tutorialDecoy(),
                fontSize = 10.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- form

@Composable
private fun ColumnScope.ProdukFormView(form: ProdukForm, controller: TutorialController) {
    // While the auto-typing runs, the field shows the partial text; otherwise the value from the state.
    val nama = controller.fieldText(FieldId.ProdukNama, form.nama)
    val hargaRaw = controller.fieldText(FieldId.ProdukHarga, form.harga)
    val discountRaw = controller.fieldText(FieldId.ProdukDiscount, form.discount)
    val harga = CurrencyFormat.liveDigitsToGrouped(hargaRaw)
    val discount = CurrencyFormat.liveDigitsToGrouped(discountRaw)
    val price = CurrencyFormat.parseDigits(hargaRaw)
    val off = CurrencyFormat.parseDigits(discountRaw)
    val percent = if (price > 0 && off > 0) ((off / price) * 100).roundToInt().coerceAtMost(100) else 0
    val typing = controller.typingField

    PanelScrollColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PanelField(
            value = nama,
            hint = "Nama Produk",
            modifier = Modifier.fillMaxWidth().tutorialTarget(TargetId.ProdukNama, tappable = false),
            focused = typing == FieldId.ProdukNama,
        )
        PanelField(
            value = controller.fieldText(FieldId.ProdukDeskripsi, form.deskripsi),
            hint = "Deskripsi Produk (3-4 baris)...",
            modifier = Modifier.fillMaxWidth().tutorialTarget(TargetId.ProdukDeskripsi, tappable = false),
            focused = typing == FieldId.ProdukDeskripsi,
            // Room for exactly two lines: the field keeps its size while the text is typed in.
            height = 56.dp,
            multiline = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelField(
                value = harga,
                hint = "Harga Produk",
                modifier = Modifier.weight(1f).tutorialTarget(TargetId.ProdukHarga, tappable = false),
                prefix = "Rp ",
                focused = typing == FieldId.ProdukHarga,
            )
            PanelField(
                value = discount,
                hint = "Discount",
                modifier = Modifier.weight(1f).tutorialTarget(TargetId.ProdukDiscount, tappable = false),
                prefix = "Rp ",
                focused = typing == FieldId.ProdukDiscount,
            )
        }
        if (percent > 0) {
            Text(
                "Discount $percent%",
                color = PanelColors.Teal,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TogglePill("Tanpa Variasi", selected = !form.variasi, Modifier.weight(1f))
            // Only this pill is the lesson's target (the other one is just the neighbouring choice).
            TogglePill("Dengan Variasi", selected = form.variasi, Modifier.weight(1f).tutorialTarget(TargetId.ProdukVariasiToggle))
        }
        if (!form.variasi) {
            PanelField(
                value = form.stok,
                hint = "Stock Produk",
                modifier = Modifier.fillMaxWidth(),
                suffix = "Pcs",
            )
        } else {
            PillButton("+  Tambah Variasi", PanelColors.Teal, Modifier.tutorialTarget(TargetId.VariasiAdd), fontSize = 10.5.sp)
            form.variasiRows.forEachIndexed { i, (name, stock) ->
                // Only the first two rows take part in the lesson (and carry typing fields/targets).
                val nameField = if (i == 0) FieldId.VariasiNama0 else FieldId.VariasiNama1
                val stockField = if (i == 0) FieldId.VariasiStok0 else FieldId.VariasiStok1
                Row(
                    Modifier.fillMaxWidth().tutorialTarget(if (i == 0) TargetId.VariasiRow0 else TargetId.VariasiRow1, tappable = false),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PanelField(
                        controller.fieldText(nameField, name),
                        "Nama Variasi",
                        Modifier.weight(3f),
                        focused = typing == nameField,
                    )
                    PanelField(
                        controller.fieldText(stockField, stock),
                        "Stock",
                        Modifier.weight(1.4f),
                        suffix = "Pcs",
                        focused = typing == stockField,
                    )
                }
            }
        }
    }
    // Sticky bottom bar so the Save button is always reachable, however far the form is scrolled.
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        PillButton(
            text = "Simpan",
            background = PanelColors.Teal,
            fontSize = 12.5.sp,
            verticalPadding = 8.dp,
            modifier = Modifier.width(160.dp).tutorialTarget(TargetId.ProdukSimpan),
        )
    }
}

@Composable
private fun TogglePill(label: String, selected: Boolean, modifier: Modifier) {
    Box(
        modifier
            .height(28.dp)
            .background(if (selected) PanelColors.Teal else PanelColors.Field, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else PanelColors.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
