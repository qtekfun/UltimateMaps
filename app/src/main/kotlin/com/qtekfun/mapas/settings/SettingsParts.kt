package com.qtekfun.mapas.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.mapas.R
import com.qtekfun.mapas.ui.theme.Mapas

/** At least 48 dp, and the (larger) glove-mode target when glove mode is on. */
internal val minTarget: Dp @Composable get() = maxOf(48.dp, Mapas.dimens.touchTarget)

/** Back arrow and the title of the current screen (the hub or a category). */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val back = stringResource(R.string.settings_back)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(minTarget)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = back }
                .testTag("settings_back"),
            contentAlignment = Alignment.Center,
        ) {
            val tint = Mapas.colors.accent
            Canvas(Modifier.size(22.dp)) { drawChevron(tint, pointRight = false) }
        }
        BasicText(
            title,
            style = Mapas.typography.title.copy(color = Mapas.colors.label),
            modifier = Modifier.padding(start = 4.dp).semantics { heading() }.testTag("settings_heading"),
        )
    }
}

/** A "<" or ">" stroke, used by the back arrow, the hub rows and the Advanced group. */
internal fun DrawScope.drawChevron(tint: Color, pointRight: Boolean = true, pointDown: Boolean = false, pointUp: Boolean = false) {
    val w = size.width
    val h = size.height
    val stroke = 2.4f * density
    val a: Offset
    val b: Offset
    val c: Offset
    if (pointUp) {
        a = Offset(w * 0.2f, h * 0.65f); b = Offset(w * 0.5f, h * 0.35f); c = Offset(w * 0.8f, h * 0.65f)
    } else if (pointDown) {
        a = Offset(w * 0.2f, h * 0.35f); b = Offset(w * 0.5f, h * 0.65f); c = Offset(w * 0.8f, h * 0.35f)
    } else if (pointRight) {
        a = Offset(w * 0.35f, h * 0.2f); b = Offset(w * 0.65f, h * 0.5f); c = Offset(w * 0.35f, h * 0.8f)
    } else {
        a = Offset(w * 0.65f, h * 0.2f); b = Offset(w * 0.35f, h * 0.5f); c = Offset(w * 0.65f, h * 0.8f)
    }
    drawLine(tint, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, b, c, strokeWidth = stroke, cap = StrokeCap.Round)
}

/** A short heading that opens a group inside a category screen (announced as a heading by screen readers). */
@Composable
internal fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    BasicText(text, style = Mapas.typography.title.copy(color = Mapas.colors.label), modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun Card(tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Mapas.shapes.control).background(Mapas.colors.field).padding(12.dp).testTag(tag)) { content() }
}

@Composable
internal fun TextButton(label: String, tag: String, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    val tint = color ?: Mapas.colors.accent
    BasicText(
        label,
        style = Mapas.typography.callout.copy(color = if (enabled) tint else Mapas.colors.secondaryLabel),
        modifier = Modifier
            .heightIn(min = minTarget)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .testTag(tag),
    )
}

/**
 * A destructive action (delete, clear): set apart from the rest of its card by a thin line and drawn in the warning
 * colour. Always one tap, as before: the confirmation is the visible result message under it.
 */
@Composable
internal fun DestructiveButton(label: String, tag: String, onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Mapas.colors.separator))
    TextButton(label, tag, color = Mapas.colors.warning, onClick = onClick)
}

@Composable
internal fun SwitchRow(title: String, body: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = minTarget).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Mapas.typography.body.copy(color = Mapas.colors.label))
            if (body.isNotEmpty()) BasicText(body, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
        Box(
            Modifier
                .size(width = 51.dp, height = 31.dp)
                .clip(CircleShape)
                .background(if (checked) Mapas.colors.accent else Mapas.colors.separator)
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(27.dp).clip(CircleShape).background(Mapas.colors.onAccent))
        }
    }
}

@Composable
internal fun ChoiceRow(label: String, selected: Boolean, radio: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val mark = when {
        radio -> if (selected) "◉" else "○"
        else -> if (selected) "☑" else "☐"
    }
    val modifier = if (radio) {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(true) })
    } else {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = onChange)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = minTarget).then(modifier).padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(mark, style = Mapas.typography.body.copy(color = if (selected) Mapas.colors.accent else Mapas.colors.secondaryLabel))
        BasicText(label, style = Mapas.typography.body.copy(color = Mapas.colors.label))
    }
}

/**
 * A collapsible "Advanced" group, closed by default, for things most people never change (source addresses, refresh
 * intervals). Its header is tagged "[tag]_header"; the open/closed state is kept across rotation.
 */
@Composable
internal fun AdvancedGroup(tag: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.hub_advanced_open else R.string.hub_advanced_closed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minTarget)
            .clickable(role = Role.Button) { open = !open }
            .semantics { stateDescription = state }
            .testTag("${tag}_header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            stringResource(R.string.hub_advanced),
            style = Mapas.typography.title.copy(color = Mapas.colors.label),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        val tint = Mapas.colors.secondaryLabel
        Canvas(Modifier.size(20.dp)) { drawChevron(tint, pointDown = !open, pointUp = open) }
    }
    if (open) {
        Column(Modifier.fillMaxWidth().testTag("${tag}_content")) {
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
