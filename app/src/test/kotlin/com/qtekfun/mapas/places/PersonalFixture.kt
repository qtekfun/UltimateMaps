package com.qtekfun.mapas.places

import androidx.sqlite.driver.AndroidSQLiteDriver
import com.qtekfun.mapas.core.data.SqlitePlacesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Deterministic wiring for the personal-feature tests: the controllers run every coroutine immediately on the
 * calling thread (`Dispatchers.Unconfined`), so a test never waits on real time or on another thread.
 */
class PersonalFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val io = Dispatchers.Unconfined

    private class MemorySetting : LongSetting {
        var value: Long? = null
        override fun get() = value
        override fun set(value: Long) { this.value = value }
    }

    private val repo = SqlitePlacesRepository(AndroidSQLiteDriver(), ":memory:")
    private val setting = MemorySetting()
    val service = PlacesService(repo, DefaultList(repo, setting) { "Favorites" })
    val lazyService: Lazy<PlacesService> = lazyOf(service)

    fun close() {
        repo.close()
    }
}
