package com.qtekfun.ultimatemaps.search

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * The `play` flavor's "Search on Google Maps (web)" row: it hands the typed text to the user's browser (no Google API is
 * called by the app, so no Google content is mixed into this map). The user shares the place back with Android's Share, which
 * `MainActivity` reads like any map link.
 */
object WebHandoff {
    const val MAX_QUERY = 200

    /** The Google Maps URLs scheme for a search (`api=1`), or null for blank text. */
    fun searchUrl(query: String): String? {
        val q = query.trim().take(MAX_QUERY)
        if (q.isEmpty()) return null
        return "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
    }

    /**
     * Opens [url] in the default browser through the browser selector, so it never resolves to UltimateMaps itself (the
     * manifest claims google.com/maps links) and needs no package-visibility query.
     */
    fun browserIntent(url: String): Intent =
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            .setData(Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Fallback when there is no default browser: a chooser that leaves this app's own activity out of the list. */
    fun chooserIntent(url: String, own: ComponentName): Intent =
        Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(own))
}
