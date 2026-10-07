package com.qtekfun.ultimatemaps.core.search

/** Wheelchair access as tagged in OSM (`wheelchair=yes|limited|no`; `designated` counts as yes). */
enum class Wheelchair {
    YES, LIMITED, NO;

    companion object {
        fun fromOsm(value: String?): Wheelchair? = when (value?.trim()?.lowercase()) {
            "yes", "designated" -> YES
            "limited" -> LIMITED
            "no" -> NO
            else -> null
        }
    }
}

/**
 * Optional details of a place, from its OSM tags. Every field is raw text as stored in the map data: the app only
 * reshapes it for display and for the dialer and browser. The CoMaps core wrapper fills it in for the results it returns
 * (see `docs/decisions.md`, "Native place tags"); a place without any of these tags has no extras.
 */
data class PlaceExtras(
    val phone: String? = null,
    val website: String? = null,
    val wheelchair: Wheelchair? = null,
    /** The OSM `opening_hours` text, shown as is. */
    val openingHours: String? = null,
) {
    val isEmpty: Boolean
        get() = phone.isNullOrBlank() && website.isNullOrBlank() && wheelchair == null && openingHours.isNullOrBlank()

    companion object {
        /** `tel:` URI for the first number of an OSM phone value (several are separated by `;`), or null when it has no digits. */
        fun dialUri(phone: String?): String? {
            val first = phone?.split(';', ',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
            val plus = first.startsWith("+") || first.startsWith("00")
            var digits = first.filter { it.isDigit() }
            if (first.startsWith("00")) digits = digits.drop(2)
            if (digits.length < MIN_DIGITS) return null
            return "tel:" + (if (plus) "+" else "") + digits
        }

        /** The first phone number as written, for display. */
        fun firstPhone(phone: String?): String? =
            phone?.split(';')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

        /**
         * An `http` or `https` link for [website]; a value without a scheme gets `https://`. Other schemes (`javascript:`,
         * `intent:`, `file:` ...) and text with spaces give null, so a hostile tag can never become an arbitrary intent.
         */
        fun webUri(website: String?): String? {
            val w = website?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (w.any { it.isWhitespace() || it.isISOControl() }) return null
            val scheme = SCHEME.find(w)?.groupValues?.get(1)?.lowercase()
            val uri = when {
                scheme == null -> "https://$w"
                scheme == "http" || scheme == "https" -> w
                else -> return null
            }
            val host = uri.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            return uri.takeIf { host.contains('.') && !host.startsWith('.') }
        }

        private const val MIN_DIGITS = 3
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(?!\\d)")
    }
}
