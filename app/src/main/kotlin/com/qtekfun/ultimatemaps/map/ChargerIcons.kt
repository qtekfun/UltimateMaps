package com.qtekfun.ultimatemaps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * EV charging-station icons drawn with Canvas once per style load (never in the render loop). Both variants show a
 * plug; they differ in SHAPE and size, not only in colour: the normal one is a round badge, the fast one (50 kW and up)
 * is a larger rounded square with a lightning bolt cut into the plug, so they read the same for colour-blind users and
 * in both themes.
 */
internal object ChargerIcons {
    const val NORMAL = "mapas-chg"
    const val FAST = "mapas-chg-fast"
    const val NORMAL_DP = 28f
    const val FAST_DP = 34f

    // Contrast against white and against dark grey: teal 00897B and violet 7E57C2.
    const val NORMAL_COLOR = 0xFF00897B.toInt()
    const val FAST_COLOR = 0xFF7E57C2.toInt()

    fun render(fast: Boolean, dark: Boolean, density: Float, densityDpi: Int): Bitmap {
        val dp = if (fast) FAST_DP else NORMAL_DP
        val px = (dp * density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = densityDpi
        val c = Canvas(bmp)
        val s = px.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val accent = if (fast) FAST_COLOR else NORMAL_COLOR
        val paper = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        val ink = if (dark) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        // Badge: coloured ring around a paper area (circle, or rounded square when fast).
        paint.style = Paint.Style.FILL
        paint.color = accent
        val outer = RectF(s * 0.04f, s * 0.04f, s * 0.96f, s * 0.96f)
        val inner = RectF(s * 0.12f, s * 0.12f, s * 0.88f, s * 0.88f)
        if (fast) {
            c.drawRoundRect(outer, s * 0.22f, s * 0.22f, paint)
            paint.color = paper
            c.drawRoundRect(inner, s * 0.17f, s * 0.17f, paint)
        } else {
            c.drawOval(outer, paint)
            paint.color = paper
            c.drawOval(inner, paint)
        }
        drawPlug(c, paint, s, if (fast) accent else ink, paper, fast)
        return bmp
    }

    /** A plug seen from the front: two prongs, a rounded body and a cable below it; a bolt over the body when [bolt]. */
    private fun drawPlug(c: Canvas, paint: Paint, s: Float, color: Int, paper: Int, bolt: Boolean) {
        val cx = s / 2
        paint.style = Paint.Style.FILL
        paint.color = color
        val prongW = s * 0.07f
        val prongH = s * 0.17f
        c.drawRect(cx - s * 0.12f - prongW / 2, s * 0.20f, cx - s * 0.12f + prongW / 2, s * 0.20f + prongH, paint)
        c.drawRect(cx + s * 0.12f - prongW / 2, s * 0.20f, cx + s * 0.12f + prongW / 2, s * 0.20f + prongH, paint)
        val body = RectF(cx - s * 0.19f, s * 0.36f, cx + s * 0.19f, s * 0.62f)
        c.drawRoundRect(body, s * 0.07f, s * 0.07f, paint)
        c.drawRect(cx - s * 0.04f, s * 0.60f, cx + s * 0.04f, s * 0.78f, paint)
        if (bolt) {
            paint.color = paper
            val b = Path().apply {
                moveTo(cx + s * 0.03f, s * 0.38f)
                lineTo(cx - s * 0.09f, s * 0.51f)
                lineTo(cx - s * 0.005f, s * 0.51f)
                lineTo(cx - s * 0.03f, s * 0.61f)
                lineTo(cx + s * 0.09f, s * 0.47f)
                lineTo(cx + s * 0.005f, s * 0.47f)
                close()
            }
            c.drawPath(b, paint)
        }
    }
}
