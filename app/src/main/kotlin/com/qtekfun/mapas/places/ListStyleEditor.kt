package com.qtekfun.mapas.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.core.data.PlaceList
import com.qtekfun.mapas.ui.theme.Mapas

/** The values the list editor hands back; [ListStyle] normalises them. */
class ListEdit(val name: String, val emoji: String, val color: Int?, val notes: String)

/** Editor of a saved list: name, one emoji, a colour from [ListStyle.PALETTE] (or none) and notes. */
@Composable
fun ListStyleEditor(list: PlaceList, onSave: (ListEdit) -> Unit, onCancel: () -> Unit, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    var name by remember(list.id) { mutableStateOf(list.name) }
    var emoji by remember(list.id) { mutableStateOf(ListStyle.displayEmoji(list.icon).orEmpty()) }
    var color by remember(list.id) { mutableStateOf(list.color) }
    var notes by remember(list.id) { mutableStateOf(list.notes.orEmpty()) }
    Column(modifier.fillMaxWidth().testTag("list_editor")) {
        PanelTextField(name, { name = it }, stringResource(R.string.list_edit_name_hint), onFocus = onFocus, imeAction = ImeAction.Next, tag = "edit_name")
        Spacer(Modifier.height(8.dp))
        PanelTextField(emoji, { emoji = ListStyle.normalizeEmoji(it).orEmpty() }, stringResource(R.string.list_edit_emoji_hint), onFocus = onFocus, imeAction = ImeAction.Next, tag = "edit_emoji")
        Spacer(Modifier.height(8.dp))
        BasicText(stringResource(R.string.list_edit_color), style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Swatch(null, color == null, "swatch_none") { color = null }
            ListStyle.PALETTE.forEachIndexed { i, c -> Swatch(c, color == c, "swatch_$i") { color = c } }
        }
        Spacer(Modifier.height(8.dp))
        PanelTextField(notes, { notes = it }, stringResource(R.string.list_edit_notes_hint), onFocus = onFocus, imeAction = ImeAction.Done, tag = "edit_notes")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(stringResource(R.string.list_edit_cancel), onCancel, Modifier.weight(1f), tag = "edit_cancel")
            PanelButton(stringResource(R.string.list_edit_save), { onSave(ListEdit(name, emoji, color, notes)) }, Modifier.weight(1f), primary = true, tag = "edit_save")
        }
    }
}

@Composable
private fun Swatch(argb: Int?, selected: Boolean, tag: String, onClick: () -> Unit) {
    val label = stringResource(if (argb == null) R.string.list_edit_color_none else R.string.list_edit_color_swatch)
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (argb == null) Mapas.colors.field else Color(argb))
            .border(if (selected) 3.dp else 1.dp, if (selected) Mapas.colors.label else Mapas.colors.separator, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
    )
}

/** A small filled circle with the list colour, or nothing when it has none. */
@Composable
fun ColorDot(argb: Int?, modifier: Modifier = Modifier) {
    if (argb != null) Box(modifier.size(10.dp).clip(CircleShape).background(Color(argb)))
}
