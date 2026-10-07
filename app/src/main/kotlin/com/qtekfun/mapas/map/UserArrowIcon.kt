package com.qtekfun.mapas.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The heading arrow of the user marker while navigating: a blue chevron with a white outline, pointing UP in the
 * bitmap (the style rotates it by the course, flat on the map plane). Drawn once per style load, never per frame.
 */
internal object UserArrowIcon {
    const val NAME = "mapas-user-arrow"
    const val SIZE_DP = 44f
    const val COLOR = 0xFF007AFF.toInt()
    const val OUTLINE = 0xFFFFFFFF.toInt()

    fun render(density: Float, densityDpi: Int): Bitmap {
        val px = (SIZE_DP * density).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = densityDpi
        val s = px.toFloat()
        // Tip at the top, notch at the bottom centre (a chevron, the usual navigation arrow).
        val arrow = Path().apply {
            moveTo(s * 0.5f, s * 0.08f)
            lineTo(s * 0.84f, s * 0.88f)
            lineTo(s * 0.5f, s * 0.68f)
            lineTo(s * 0.16f, s * 0.88f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = Canvas(bmp)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = s * 0.12f
        paint.color = OUTLINE
        c.drawPath(arrow, paint)
        paint.style = Paint.Style.FILL
        paint.color = COLOR
        c.drawPath(arrow, paint)
        return bmp
    }
}
