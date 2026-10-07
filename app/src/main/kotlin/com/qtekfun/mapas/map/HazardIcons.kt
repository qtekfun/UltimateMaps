package com.qtekfun.mapas.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.qtekfun.mapas.core.map.HazardKind

/**
 * Markers of cameras and traffic incidents, drawn with Canvas once per style load (never in the render loop). Every
 * kind differs in SHAPE as well as colour, so they read the same for colour-blind users and in both themes:
 * round = camera, diamond = section, triangle = beacon or accident, hexagon-like square = closure, rounded square =
 * slow traffic or roadworks, circle with a bar = obstacle or weather. The glyph inside is a letter or sign.
 */
internal object HazardIcons {
    const val SIZE_DP = 30f

    fun name(kind: HazardKind) = "mapas-hz-" + kind.name.lowercase()

    private enum class Shape { CIRCLE, DIAMOND, TRIANGLE, SQUARE, ROUNDED }

    private class Style(val shape: Shape, val color: Int, val glyph: String)

    // Colours with contrast against white and dark grey.
    private fun style(kind: HazardKind) = when (kind) {
        HazardKind.FIXED_CAMERA -> Style(Shape.CIRCLE, 0xFFD93025.toInt(), "R")
        HazardKind.SECTION -> Style(Shape.DIAMOND, 0xFFD93025.toInt(), "R")
        HazardKind.V16 -> Style(Shape.TRIANGLE, 0xFFE8710A.toInt(), "V16")
        HazardKind.ACCIDENT -> Style(Shape.TRIANGLE, 0xFFD93025.toInt(), "!")
        HazardKind.CLOSURE -> Style(Shape.SQUARE, 0xFFD93025.toInt(), "-")
        HazardKind.CONGESTION -> Style(Shape.ROUNDED, 0xFFE8710A.toInt(), "~")
        HazardKind.OBSTACLE -> Style(Shape.DIAMOND, 0xFFE8710A.toInt(), "!")
        HazardKind.WEATHER -> Style(Shape.CIRCLE, 0xFF0A84FF.toInt(), "*")
        HazardKind.ROADWORKS -> Style(Shape.ROUNDED, 0xFF8A6D00.toInt(), "^")
    }

    fun render(kind: HazardKind, dark: Boolean, density: Float, densityDpi: Int): Bitmap {
        val px = (SIZE_DP * density).toInt().coerceAtLeast(12)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = densityDpi
        val c = Canvas(bmp)
        val s = px.toFloat()
        val st = style(kind)
        val paper = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val outer = shapePath(st.shape, s, 0.04f * s)
        val inner = shapePath(st.shape, s, 0.15f * s)
        paint.style = Paint.Style.FILL
        paint.color = st.color
        c.drawPath(outer, paint)
        paint.color = paper
        c.drawPath(inner, paint)
        paint.color = st.color
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = s * if (st.glyph.length > 1) 0.30f else 0.46f
        val y = s / 2 - (paint.ascent() + paint.descent()) / 2 + if (st.shape == Shape.TRIANGLE) s * 0.08f else 0f
        c.drawText(st.glyph, s / 2, y, paint)
        return bmp
    }

    private fun shapePath(shape: Shape, s: Float, inset: Float): Path {
        val p = Path()
        val r = RectF(inset, inset, s - inset, s - inset)
        when (shape) {
            Shape.CIRCLE -> p.addOval(r, Path.Direction.CW)
            Shape.ROUNDED -> p.addRoundRect(r, s * 0.18f, s * 0.18f, Path.Direction.CW)
            Shape.SQUARE -> p.addRect(r, Path.Direction.CW)
            Shape.DIAMOND -> {
                p.moveTo(s / 2, r.top); p.lineTo(r.right, s / 2); p.lineTo(s / 2, r.bottom); p.lineTo(r.left, s / 2); p.close()
            }
            Shape.TRIANGLE -> {
                p.moveTo(s / 2, r.top); p.lineTo(r.right, r.bottom); p.lineTo(r.left, r.bottom); p.close()
            }
        }
        return p
    }
}
