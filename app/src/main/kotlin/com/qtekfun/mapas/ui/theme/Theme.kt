package com.qtekfun.mapas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Own design system (not Material You): colour, type, shape and size tokens in the style of Apple Maps.
 * Everything the UI draws comes from [MapasTheme]; no screen hard-codes a colour or a radius.
 */
@Immutable
data class MapasColors(
    val sheet: Color,
    val sheetHandle: Color,
    val control: Color,
    val controlPressed: Color,
    val label: Color,
    val secondaryLabel: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
    val warning: Color,
    val field: Color,
    val isDark: Boolean,
)

@Immutable
data class MapasTypography(
    val largeTitle: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val caption: TextStyle,
)

@Immutable
data class MapasShapes(
    val sheet: RoundedCornerShape,
    val control: RoundedCornerShape,
    val field: RoundedCornerShape,
    val pill: RoundedCornerShape,
)

/** Sizes. [touchTarget] grows in glove mode (RF-06: buttons >= 56 dp). */
@Immutable
data class MapasDimens(
    val touchTarget: Dp = 44.dp,
    val screenMargin: Dp = 16.dp,
    val sheetCollapsedHeight: Dp = 84.dp,
    val sheetMediumFraction: Float = 0.46f,
    val gloveTouchTarget: Dp = 56.dp,
)

val LightMapasColors = MapasColors(
    sheet = Color(0xF2FFFFFF),
    sheetHandle = Color(0x4D3C3C43),
    control = Color(0xF2FFFFFF),
    controlPressed = Color(0xFFE5E5EA),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    separator = Color(0x363C3C43),
    accent = Color(0xFF007AFF),
    onAccent = Color(0xFFFFFFFF),
    warning = Color(0xFFFF9500),
    field = Color(0x1F767680),
    isDark = false,
)

val DarkMapasColors = MapasColors(
    sheet = Color(0xF21C1C1E),
    sheetHandle = Color(0x4DEBEBF5),
    control = Color(0xF22C2C2E),
    controlPressed = Color(0xFF3A3A3C),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    separator = Color(0x54545458),
    accent = Color(0xFF0A84FF),
    onAccent = Color(0xFFFFFFFF),
    warning = Color(0xFFFF9F0A),
    field = Color(0x3D767680),
    isDark = true,
)

private val Sans = FontFamily.SansSerif

val DefaultMapasTypography = MapasTypography(
    largeTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp),
    body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp),
    callout = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    caption = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

val DefaultMapasShapes = MapasShapes(
    sheet = RoundedCornerShape(topStart = CornerSize(20.dp), topEnd = CornerSize(20.dp), bottomEnd = CornerSize(0.dp), bottomStart = CornerSize(0.dp)),
    control = RoundedCornerShape(12.dp),
    field = RoundedCornerShape(10.dp),
    pill = RoundedCornerShape(percent = 50),
)

private val LocalColors = staticCompositionLocalOf { LightMapasColors }
private val LocalTypography = staticCompositionLocalOf { DefaultMapasTypography }
private val LocalShapes = staticCompositionLocalOf { DefaultMapasShapes }
private val LocalDimens = staticCompositionLocalOf { MapasDimens() }

object Mapas {
    val colors: MapasColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val typography: MapasTypography @Composable @ReadOnlyComposable get() = LocalTypography.current
    val shapes: MapasShapes @Composable @ReadOnlyComposable get() = LocalShapes.current
    val dimens: MapasDimens @Composable @ReadOnlyComposable get() = LocalDimens.current
}

@Composable
fun MapasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    gloveMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dimens = if (gloveMode) MapasDimens().let { it.copy(touchTarget = it.gloveTouchTarget) } else MapasDimens()
    CompositionLocalProvider(
        LocalColors provides if (darkTheme) DarkMapasColors else LightMapasColors,
        LocalTypography provides DefaultMapasTypography,
        LocalShapes provides DefaultMapasShapes,
        LocalDimens provides dimens,
        content = content,
    )
}
