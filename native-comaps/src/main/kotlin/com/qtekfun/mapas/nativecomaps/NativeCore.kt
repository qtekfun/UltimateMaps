package com.qtekfun.mapas.nativecomaps

/**
 * Puente JNI crudo hacia `libumcomaps.so` (nucleo de CoMaps sin render). Singleton de proceso:
 * CoMaps tiene estado global. Los tipos son planos a proposito (ver [NativeBridge]).
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
        require(r.size == 3) { "respuesta de ruta con guiado mal formada: ${r.size} elementos" }
        val rawNames = r[2] as? Array<*> ?: throw IllegalArgumentException("nombres no es Array<String>")
        return RawGuidedRoute(
            route = r[0] as? DoubleArray ?: throw IllegalArgumentException("ruta no es DoubleArray"),
            guidance = r[1] as? DoubleArray ?: throw IllegalArgumentException("guiado no es DoubleArray"),
            names = Array(rawNames.size) { rawNames[it] as? String ?: "" },
        )
    }

    companion object {
        init {
            System.loadLibrary("umcomaps")
        }
    }
}

/** Contrato del puente nativo; permite probar la fachada en la JVM con un falso. */
internal interface NativeBridge {
    /** Devuelve "" si va bien, o el mensaje de error. */
    fun init(apk: String, writableDir: String, tmpDir: String, locale: String): String
    fun refreshMaps(): Int

    /** 5 cadenas por resultado: nombre, direccion, categoria, lat, lon. */
    fun search(
        query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String,
    ): Array<String>

    /** `[code, distanciaM, duracionS, lat0, lon0, ...]`. */
    fun route(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray

    /** Igual que [route] pero con el guiado (maniobras y limites). El calculo extra solo ocurre en esta llamada. */
    fun routeGuidance(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): RawGuidedRoute
}

/** Ruta en el formato de [NativeBridge.route] + guiado en el de [GuidanceWire] + tabla de nombres de calle. */
internal class RawGuidedRoute(val route: DoubleArray, val guidance: DoubleArray, val names: Array<String>)
