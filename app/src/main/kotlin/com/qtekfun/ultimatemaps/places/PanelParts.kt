package com.qtekfun.ultimatemaps.places

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatemaps.R
import com.qtekfun.ultimatemaps.ui.theme.Mapas
import java.util.Locale

/** Rounded text button in the app's own design system. */
@Composable
fun PanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    tag: String? = null,
    horizontalPadding: Dp = 14.dp,
    singleLine: Boolean = false,
) {
    val bg = if (primary) Mapas.colors.accent else Mapas.colors.field
    val fg = if (primary) Mapas.colors.onAccent else Mapas.colors.accent
    Box(
        modifier
            .heightIn(min = Mapas.dimens.touchTarget)
            .clip(Mapas.shapes.control)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = Mapas.typography.body.copy(color = if (enabled) fg else Mapas.colors.secondaryLabel, textAlign = TextAlign.Center),
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

/** Single-line text field with a hint, used for the search bar and the list filter. */
@Composable
fun PanelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    imeAction: ImeAction = ImeAction.Search,
    onDone: () -> Unit = {},
    tag: String? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(Mapas.shapes.field)
            .background(Mapas.colors.field)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            BasicText(hint, style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Mapas.typography.body.copy(color = Mapas.colors.label),
            cursorBrush = SolidColor(Mapas.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onSearch = { onDone() }, onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocus() }
                .let { if (tag != null) it.testTag(tag) else it },
        )
    }
}

/** Two-line row: [title] and an optional secondary line, plus a trailing text. */
@Composable
fun PanelRow(
    title: String, subtitle: String?, trailing: String?, onClick: () -> Unit, tag: String? = null,
    /** A colour dot before the title (the colour of a customised list). */
    leadingColor: Int? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Mapas.dimens.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp)
            .let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingColor != null) {
            ColorDot(leadingColor)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Mapas.typography.body.copy(color = Mapas.colors.label), maxLines = 1)
            if (!subtitle.isNullOrEmpty()) {
                BasicText(subtitle, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel), maxLines = 1)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            BasicText(trailing, style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel))
        }
    }
}

@Composable
fun PanelNote(text: String, tag: String) {
    BasicText(
        text,
        style = Mapas.typography.callout.copy(color = Mapas.colors.secondaryLabel),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag),
    )
}

/** Name of the "category · address" subtitle, skipping empty parts. */
fun subtitleOf(category: String?, address: String?): String? =
    listOfNotNull(category?.takeIf { it.isNotBlank() }, address?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }

/** Place card: name, category and address, coordinates and the save / route / share buttons. */
@Composable
fun PlaceCard(
    info: PlaceInfo,
    saved: Boolean,
    onSave: () -> Unit,
    onRoute: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    /** "Set as Home" / "Set as Work"; a null callback hides its button. */
    onSetHome: (() -> Unit)? = null,
    onSetWork: (() -> Unit)? = null,
    /** Opens the dialer with the number (nothing is dialled) and the website in the browser. */
    onDial: (String) -> Unit = {},
    onOpenWebsite: (String) -> Unit = {},
    /** Local time for the "open now" line; injected so tests do not depend on the clock. */
    now: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() },
) {
    Column(Modifier.fillMaxWidth().testTag("place_card")) {
        Row(verticalAlignment = Alignment.Top) {
            BasicText(
                info.name,
                style = Mapas.typography.title.copy(color = Mapas.colors.label),
                modifier = Modifier.weight(1f).testTag("place_name"),
            )
            BasicText(
                stringResource(R.string.place_close),
                style = Mapas.typography.body.copy(color = Mapas.colors.accent),
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onClose)
                    .padding(8.dp)
                    .testTag("place_close"),
            )
        }
        subtitleOf(info.category, info.address)?.let {
            BasicText(it, style = Mapas.typography.body.copy(color = Mapas.colors.secondaryLabel), modifier = Modifier.testTag("place_subtitle"))
        }
        BasicText(
            "%.5f, %.5f".format(Locale.ROOT, info.point.lat, info.point.lon),
            style = Mapas.typography.caption.copy(color = Mapas.colors.secondaryLabel),
            modifier = Modifier.padding(top = 4.dp).testTag("place_coords"),
        )
        PlaceExtrasSection(info.point, info.extras, onDial, onOpenWebsite, now)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(
                stringResource(if (saved) R.string.place_saved else R.string.place_save),
                onSave, Modifier.weight(1f), primary = !saved, tag = "place_save",
            )
            PanelButton(stringResource(R.string.place_route), onRoute, Modifier.weight(1f), tag = "place_route")
            PanelButton(stringResource(R.string.place_share), onShare, Modifier.weight(1f), tag = "place_share")
        }
        if (onSetHome != null || onSetWork != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSetHome != null) PanelButton(stringResource(R.string.place_set_home), onSetHome, Modifier.weight(1f), tag = "place_set_home")
                if (onSetWork != null) PanelButton(stringResource(R.string.place_set_work), onSetWork, Modifier.weight(1f), tag = "place_set_work")
            }
        }
    }
}
