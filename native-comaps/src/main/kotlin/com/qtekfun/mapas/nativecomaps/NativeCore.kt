package com.qtekfun.mapas.nativecomaps

/**
 * Raw JNI bridge to `libumcomaps.so` (CoMaps core without rendering). Process singleton:
 * CoMaps has global state. The types are flat on purpose (see [NativeBridge]).
 */
internal class NativeCore : NativeBridge {
    external fun nativeInit(apk: String, writableDir: String, tmpDir: String, locale: String): String
    external fun nativeRefreshMaps(): Int
    external fun nativeSearch(
        query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String,
    ): Array<String>
    external fun nativeRoute(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray

    /** `Object[3]`: `{ DoubleArray ruta, DoubleArray guiado, Array<String> nombres }` (ver [RawGuidedRoute]). */
    external fun nativeRouteGuidance(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): Array<Any?>

    override fun init(apk: String, writableDir: String, tmpDir: String, locale: String): String =
        nativeInit(apk, writableDir, tmpDir, locale)

    override fun refreshMaps(): Int = nativeRefreshMaps()

    override fun search(
        query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String,
    ): Array<String> = nativeSearch(query, hasPos, lat, lon, limit, timeoutMs, locale)

    override fun route(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray =
        nativeRoute(profile, points, avoidFlags, timeoutSec)

    override fun routeGuidance(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): RawGuidedRoute {
        val r = nativeRouteGuidance(profile, points, avoidFlags, timeoutSec)
        require(r.size == 3) { "malformed guided route response: ${r.size} elements" }
        val rawNames = r[2] as? Array<*> ?: throw IllegalArgumentException("names is not Array<String>")
        return RawGuidedRoute(
            route = r[0] as? DoubleArray ?: throw IllegalArgumentException("route is not a DoubleArray"),
            guidance = r[1] as? DoubleArray ?: throw IllegalArgumentException("guidance is not a DoubleArray"),
            names = Array(rawNames.size) { rawNames[it] as? String ?: "" },
        )
    }

    companion object {
        init {
            System.loadLibrary("umcomaps")
        }
    }
}

/** Contract of the native bridge; allows testing the facade on the JVM with a fake. */
internal interface NativeBridge {
    /** Returns "" if all is well, or the error message. */
    fun init(apk: String, writableDir: String, tmpDir: String, locale: String): String
    fun refreshMaps(): Int

    /** 5 strings per result: name, address, category, lat, lon. */
    fun search(
        query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String,
    ): Array<String>

    /** `[code, distanciaM, duracionS, lat0, lon0, ...]`. */
    fun route(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray

    /** Same as [route] but with the guidance (maneuvers and limits). The extra computation only happens in this call. */
    fun routeGuidance(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): RawGuidedRoute
}

/** Route in the format of [NativeBridge.route] + guidance in that of [GuidanceWire] + street name table. */
internal class RawGuidedRoute(val route: DoubleArray, val guidance: DoubleArray, val names: Array<String>)
