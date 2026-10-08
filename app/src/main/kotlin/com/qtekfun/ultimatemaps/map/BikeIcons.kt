package com.qtekfun.ultimatemaps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Bike-share station icon drawn with Canvas once per style load (never in the render loop): a blue round badge with a
 * small bicycle (two wheels, frame, handlebar and saddle). The shape and the glyph, not only the colour, tell it from the
 * charger badge (teal circle with a plug) and from the petrol pins, in both themes.
 */
internal object BikeIcons {
    const val STATION = "mapas-bike"
    const val DP = 26f

    // Contrast against white and against dark grey.
    const val COLOR = 0xFF1E6FD9.toInt()

    fun render(dark: Boolean, density: Float, densityDpi: Int): Bitmap {
        val px = (DP * density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = densityDpi
        val c = Canvas(bmp)
        val s = px.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val paper = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.FILL
        paint.color = COLOR
        c.drawOval(RectF(s * 0.04f, s * 0.04f, s * 0.96f, s * 0.96f), paint)
        paint.color = paper
        c.drawOval(RectF(s * 0.12f, s * 0.12f, s * 0.88f, s * 0.88f), paint)
        // Bicycle in the badge colour.
        paint.color = COLOR
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.055f
        paint.strokeCap = Paint.Cap.ROUND
        val wheel = s * 0.12f
        val y = s * 0.62f
        val lx = s * 0.33f
        val rx = s * 0.67f
        c.drawCircle(lx, y, wheel, paint)
        c.drawCircle(rx, y, wheel, paint)
        val crankX = s * 0.5f
        val crankY = y
        val seatX = s * 0.43f
        val seatY = s * 0.40f
        val barX = s * 0.62f
        val barY = s * 0.38f
        c.drawLine(lx, y, seatX, seatY, paint) // rear stay and seat tube
        c.drawLine(seatX, seatY, barX, barY, paint) // top tube
        c.drawLine(lx, y, crankX, crankY, paint) // chain stay
        c.drawLine(crankX, crankY, seatX, seatY, paint) // seat tube
        c.drawLine(crankX, crankY, barX, barY, paint) // down tube
        c.drawLine(barX, barY, rx, y, paint) // fork
        c.drawLine(seatX - s * 0.05f, seatY - s * 0.04f, seatX + s * 0.05f, seatY - s * 0.04f, paint) // saddle
        c.drawLine(barX - s * 0.03f, barY - s * 0.04f, barX + s * 0.05f, barY - s * 0.04f, paint) // handlebar
        return bmp
    }
}
