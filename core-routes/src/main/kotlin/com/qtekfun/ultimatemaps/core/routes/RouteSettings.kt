package com.qtekfun.ultimatemaps.core.routes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the user configures in Settings. The overlay is OFF by default and nothing is downloaded while it is off.
 * [hiking] and [cycling] choose which kinds are drawn; turning both off would hide everything, so [normalized] restores both.
 */
data class RouteSettings(
    val enabled: Boolean = false,
    val hiking: Boolean = true,
    val cycling: Boolean = true,
) {
    fun accepts(kind: TrailKind): Boolean = if (kind.isBike) cycling else hiking
}

fun RouteSettings.normalized(): RouteSettings = if (!hiking && !cycling) copy(hiking = true, cycling = true) else this

interface RouteSettingsStore {
    val settings: StateFlow<RouteSettings>
    fun update(transform: (RouteSettings) -> RouteSettings)
}

/** In-memory store for tests and previews. Always keeps the settings [normalized]. */
class InMemoryRouteSettingsStore(initial: RouteSettings = RouteSettings()) : RouteSettingsStore {
    private val state = MutableStateFlow(initial.normalized())
    override val settings: StateFlow<RouteSettings> = state
    override fun update(transform: (RouteSettings) -> RouteSettings) {
        state.value = transform(state.value).normalized()
    }
}
