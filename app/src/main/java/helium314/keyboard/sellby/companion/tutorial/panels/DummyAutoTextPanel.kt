// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion.tutorial.panels

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R
import helium314.keyboard.sellby.companion.tutorial.AutoTextForm
import helium314.keyboard.sellby.companion.tutorial.AutoTextRow
import helium314.keyboard.sellby.companion.tutorial.AutoTextView
import helium314.keyboard.sellby.companion.tutorial.FieldId
import helium314.keyboard.sellby.companion.tutorial.SampleData
import helium314.keyboard.sellby.companion.tutorial.TargetId
import helium314.keyboard.sellby.companion.tutorial.TutorialController
import helium314.keyboard.sellby.companion.tutorial.TutorialState
import helium314.keyboard.sellby.companion.tutorial.spotlight.LocalTutorial
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialDecoy
import helium314.keyboard.sellby.companion.tutorial.spotlight.tutorialTarget

/**
 * Dummy copy of the real Auto-Text panel (sellby/ui/AutoTextPanelView.kt): the counter, a search
 * box, one row per Auto-Text (shortcut + one-line preview + the three-dots menu that opens Edit /
 * Hapus / x), the lime "+ Tambah Auto Text" bar, and the add/edit form (shortcut + message + Simpan).
 * "#nama-toko" is resolved with the store name, as the real panel does. Everything is derived from
 * [TutorialState.autoText] - nothing here is stored.
 */
@Composable
internal fun DummyAutoTextPanel(state: TutorialState, height: Dp) {
    val controller = LocalTutorial.current
    val inList = state.autoText.view == AutoTextView.List
    PanelShell(
        title = "Auto-Text",
        height = height,
        leadingIcon = if (inList) R.drawable.ic_settings_chevron_down_sellby else R.drawable.ic_settings_chevron_left_sellby,
        trailingIcon = if (inList) R.drawable.ic_toolbar_reset_sellby else null,
    ) {
        if (inList) AutoTextList(state) else AutoTextFormView(state.autoText.form, controller)
    }
}

// ---------------------------------------------------------------- list

@Composable
private fun ColumnScope.AutoTextList(state: TutorialState) {
    val rows = state.autoText.rows
    PanelScrollColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Total ${rows.size} Auto Text",
            color = PanelColors.Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        PanelField("", "Cari shortcut atau pesan...", Modifier.fillMaxWidth().tutorialDecoy(), height = 30.dp, leadingIcon = R.drawable.sym_keyboard_search_rounded)
        Column(Modifier.fillMaxWidth().tutorialTarget(TargetId.AtList, tappable = false)) {
            rows.forEachIndexed { i, row ->
                AutoTextListRow(
                    row = row,
                    storeName = state.storeName,
                    isFormOrder = row.shortcut == "Format Order",
                    // The row the lesson makes (and then edits) is the only one whose menu is a target.
                    isLessonRow = row.shortcut == SampleData.NEW_AUTOTEXT_SHORTCUT,
                    menuOpen = state.autoText.menuRow == i,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(PanelColors.Divider))
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().background(PanelColors.Teal).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        PillButton(
            text = "+  Tambah Auto Text",
            background = PanelColors.Lime,
            textColor = PanelColors.Text,
            fontSize = 12.5.sp,
            horizontalPadding = 28.dp,
            verticalPadding = 7.dp,
            modifier = Modifier.tutorialTarget(TargetId.AtAdd),
        )
    }
}

@Composable
private fun AutoTextListRow(row: AutoTextRow, storeName: String, isFormOrder: Boolean, isLessonRow: Boolean, menuOpen: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .let { if (isFormOrder) it.tutorialTarget(TargetId.AtRowFormOrder) else it.tutorialDecoy() },
        ) {
            Text(row.shortcut, color = PanelColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                row.resolved(storeName).replace("\n", " "),
                color = PanelColors.Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (menuOpen) {
            // What the three dots turn into once tapped (the real row slides these in): Edit, Hapus and a close "x".
            PillButton("Edit", PanelColors.Teal, Modifier.padding(start = 8.dp).tutorialTarget(TargetId.AtEdit), fontSize = 11.sp, horizontalPadding = 12.dp)
            Spacer(Modifier.width(6.dp))
            PillButton("Hapus", PanelColors.Red, Modifier.tutorialDecoy(), fontSize = 11.sp, horizontalPadding = 12.dp)
            Box(
                Modifier.padding(start = 6.dp).size(24.dp).background(PanelColors.Field, CircleShape).tutorialDecoy(),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = PanelColors.Muted, fontSize = 15.sp)
            }
        } else {
            Text(
                "⋮",
                color = PanelColors.Muted,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 8.dp).let { if (isLessonRow) it.tutorialTarget(TargetId.AtRowMenu) else it.tutorialDecoy() },
            )
        }
    }
}

// ---------------------------------------------------------------- form

@Composable
private fun ColumnScope.AutoTextFormView(form: AutoTextForm, controller: TutorialController) {
    // While the auto-typing runs, a field shows the partial text; otherwise the value from the state.
    val typing = controller.typingField
    PanelScrollColumn(
        Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (form.editingRow == null) "Tambah Auto Text" else "Edit Auto Text",
            color = PanelColors.Teal,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        PanelField(
            value = controller.fieldText(FieldId.AtShortcut, form.shortcut),
            hint = "Masukan Shortcut*",
            modifier = Modifier.fillMaxWidth().tutorialTarget(TargetId.AtShortcut, tappable = false),
            focused = typing == FieldId.AtShortcut,
        )
        PanelField(
            value = controller.fieldText(FieldId.AtMessage, form.message),
            hint = "Masukan Pesan",
            modifier = Modifier.fillMaxWidth().tutorialTarget(TargetId.AtMessage, tappable = false),
            focused = typing == FieldId.AtMessage,
            // The real field is 110dp tall; tightened to fit the short tutorial panel (3 lines are plenty here).
            height = 70.dp,
            multiline = true,
            multilineMaxLines = 3,
        )
    }
    // Sticky bottom bar so the Save button is always reachable.
    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        PillButton(
            text = "Simpan",
            background = PanelColors.Teal,
            fontSize = 12.5.sp,
            verticalPadding = 8.dp,
            modifier = Modifier.width(160.dp).tutorialTarget(TargetId.AtSimpan),
        )
    }
}
