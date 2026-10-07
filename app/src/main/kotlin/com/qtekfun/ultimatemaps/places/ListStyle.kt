package com.qtekfun.ultimatemaps.places

import java.text.BreakIterator

/**
 * Customisation of a saved list: an emoji, a colour from a fixed palette and free notes. The values live in the
 * `icon`, `color` and `notes` columns the lists have had since the first schema, so backups already carry them.
 * Pure functions, tested on the JVM.
 */
object ListStyle {
    /** ARGB colours offered in the editor (the system palette of the app's own look). */
    val PALETTE: List<Int> = listOf(
        0xFFFF3B30.toInt(), // red
        0xFFFF9500.toInt(), // orange
        0xFFFFCC00.toInt(), // yellow
        0xFF34C759.toInt(), // green
        0xFF0A84FF.toInt(), // blue
        0xFFAF52DE.toInt(), // purple
        0xFFA2845E.toInt(), // brown
        0xFF8E8E93.toInt(), // grey
    )

    const val MAX_NOTES = 500
    const val MAX_NAME = 80

    /**
     * The first user-perceived character of [input] when it is an emoji or symbol, else null. Letters and digits
     * are rejected: `icon` also holds symbolic names written by older versions, which are plain ASCII words.
     */
    fun normalizeEmoji(input: String?): String? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val first = text.substring(0, it.next().takeIf { end -> end > 0 } ?: text.length)
        val cp = first.codePointAt(0)
        return if (Character.isLetterOrDigit(cp) && cp < 0x250) null else first
    }

    /** The emoji to draw for a stored [icon], or null when it is empty or a symbolic name. */
    fun displayEmoji(icon: String?): String? = normalizeEmoji(icon)

    fun normalizeNotes(input: String?): String? = input?.trim()?.take(MAX_NOTES)?.takeIf { it.isNotEmpty() }

    fun normalizeName(input: String?): String? = input?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
}
