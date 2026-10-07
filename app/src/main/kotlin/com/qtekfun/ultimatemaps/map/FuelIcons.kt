package com.qtekfun.ultimatemaps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Pump icons drawn with Canvas once per style load (never in the render loop). The two variants differ in SHAPE
 * and size, not only in colour: the normal one is a round badge, the cheap one is larger, has a double ring and a
 * downward triangle ("lower price") on top, so it reads the same for colour-blind users and in both themes.
 */
internal object FuelIcons {
    const val NORMAL = "mapas-fuel"
    const val CHEAP = "mapas-fuel-cheap"
    const val NORMAL_DP = 28f
    const val CHEAP_DP = 38f

    // Contrast against white and against dark grey: blue 0A84FF and green 1E8E3E.
    const val NORMAL_COLOR = 0xFF0A84FF.toInt()
    const val CHEAP_COLOR = 0xFF1E8E3E.toInt()

    fun render(cheap: Boolean, dark: Boolean, density: Float, densityDpi: Int): Bitmap {
        val dp = if (cheap) CHEAP_DP else NORMAL_DP
        val px = (dp * density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = densityDpi
        val c = Canvas(bmp)
        val s = px.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val accent = if (cheap) CHEAP_COLOR else NORMAL_COLOR
        val paper = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        val ink = if (dark) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        val cx = s / 2
        val cy = if (cheap) s * 0.58f else s / 2
        val r = if (cheap) s * 0.40f else s * 0.46f
        // Badge: coloured ring around a paper disc (a second inner ring when cheap).
        paint.style = Paint.Style.FILL
        paint.color = accent
        c.drawCircle(cx, cy, r, paint)
        paint.color = paper
        c.drawCircle(cx, cy, r - s * (if (cheap) 0.07f else 0.06f), paint)
        if (cheap) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.035f
            paint.color = accent
            c.drawCircle(cx, cy, r - s * 0.14f, paint)
        }
        // Pump: body with a window and a nozzle arm on the right.
        paint.style = Paint.Style.FILL
        paint.color = ink
        val bw = r * 0.78f
        val bh = r * 1.15f
        val body = RectF(cx - bw * 0.62f, cy - bh / 2, cx + bw * 0.38f, cy + bh / 2)
        c.drawRoundRect(body, s * 0.03f, s * 0.03f, paint)
        paint.color = paper
        c.drawRect(body.left + bw * 0.17f, body.top + bh * 0.14f, body.right - bw * 0.17f, body.top + bh * 0.46f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.045f
        paint.color = ink
        paint.strokeCap = Paint.Cap.ROUND
        val arm = Path().apply {
            moveTo(body.right, body.top + bh * 0.22f)
            lineTo(body.right + bw * 0.3f, body.top + bh * 0.22f)
            lineTo(body.right + bw * 0.3f, body.bottom - bh * 0.2f)
        }
        c.drawPath(arm, paint)
        if (cheap) {
            // "Lower price" marker: downward triangle above the badge.
            paint.style = Paint.Style.FILL
            paint.color = accent
            val t = Path().apply {
                moveTo(cx - s * 0.13f, s * 0.03f)
                lineTo(cx + s * 0.13f, s * 0.03f)
                lineTo(cx, s * 0.2f)
                close()
            }
            c.drawPath(t, paint)
        }
        return bmp
    }
}
