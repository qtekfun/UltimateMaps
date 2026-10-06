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

    override fun init(apk: String, writableDir: String, tmpDir: String, locale: String): String =
        nativeInit(apk, writableDir, tmpDir, locale)

    override fun refreshMaps(): Int = nativeRefreshMaps()

    override fun search(
        query: String, hasPos: Boolean, lat: Double, lon: Double, limit: Int, timeoutMs: Int, locale: String,
    ): Array<String> = nativeSearch(query, hasPos, lat, lon, limit, timeoutMs, locale)

    override fun route(profile: Int, points: DoubleArray, avoidFlags: Int, timeoutSec: Int): DoubleArray =
        nativeRoute(profile, points, avoidFlags, timeoutSec)

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
}
