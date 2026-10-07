package com.qtekfun.mapas.shortcuts

/**
 * What a launcher app shortcut (long-press on the icon) opens. The static shortcuts in `res/xml/shortcuts.xml`
 * start `MainActivity` with one of these actions; [AppShortcuts.parse] maps an action back to its target.
 */
enum class ShortcutTarget(val action: String) {
    /** The search tab with the sheet fully open. */
    SEARCH("com.qtekfun.mapas.action.SEARCH"),

    /** The saved places and lists. */
    SAVED("com.qtekfun.mapas.action.SAVED"),

    /** The "Maps" screen with the downloaded regions. */
    MAPS("com.qtekfun.mapas.action.MAPS"),

    /** The emergency screen. */
    EMERGENCY("com.qtekfun.mapas.action.EMERGENCY"),
}

object AppShortcuts {
    /** The target of an intent [action], or null for anything else (launcher, map links, null). */
    fun parse(action: String?): ShortcutTarget? = ShortcutTarget.entries.firstOrNull { it.action == action }
}
