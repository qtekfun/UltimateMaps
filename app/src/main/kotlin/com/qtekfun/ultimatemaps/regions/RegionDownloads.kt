package com.qtekfun.ultimatemaps.regions

import com.qtekfun.ultimatemaps.core.net.DenyReason
import com.qtekfun.ultimatemaps.core.regions.CancelToken
import com.qtekfun.ultimatemaps.core.regions.DownloadCancelledException
import com.qtekfun.ultimatemaps.core.regions.HashMismatchException
import com.qtekfun.ultimatemaps.core.regions.NetworkDeniedException
import com.qtekfun.ultimatemaps.core.regions.Region
import java.io.IOException
import java.util.concurrent.Executor

/** Why a download stopped. The UI maps each to a message and, where it makes sense, to a retry. */
enum class FailureReason { OFFLINE_MODE, NOT_ALLOWED, NO_SPACE, NETWORK, CORRUPT }

/** State of one region's download. A region that is neither queued nor failed nor paused has no entry. */
sealed interface DownloadState {
    data object Queued : DownloadState
    data class Running(val done: Long, val total: Long) : DownloadState
    data class Paused(val done: Long, val total: Long) : DownloadState

    /** [neededBytes]/[freeBytes] are only set for [FailureReason.NO_SPACE]. */
    data class Failed(val reason: FailureReason, val neededBytes: Long = 0, val freeBytes: Long = 0) : DownloadState
}

/** Thrown by the installer when the chosen storage cannot hold the region (nothing was downloaded). */
class InsufficientSpaceException(val neededBytes: Long, val freeBytes: Long) : IOException("not enough space: need $neededBytes, free $freeBytes")

/** Does the actual work for one region (space check, download, verify, activate). Throws on failure. */
fun interface RegionInstaller {
    fun install(region: Region, cancel: CancelToken, onProgress: (done: Long, total: Long) -> Unit)
}

/**
 * Sequential download queue with pause/resume. One region at a time (gentle on data and disk); a region
 * with two files is a single job. Pausing cancels the current job but keeps the partial files, so resuming
 * continues with a `Range` request. Thread-safe; [onChange] is called from worker threads (throttled for
 * progress) and must be cheap.
 */
class RegionDownloads(
    private val installer: RegionInstaller,
    private val executor: Executor,
    private val onChange: () -> Unit = {},
    private val onInstalled: (String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) {
    private class Job(val region: Region) {
        val token = CancelToken()
        @Volatile var wantsDrop = false // cancelled for good (delete/cancel), not paused
    }

    private val lock = Any()
    private val states = LinkedHashMap<String, DownloadState>()
    private val jobs = HashMap<String, Job>()
    private var lastNotify = 0L

    /** Snapshot of every known state, in queue order. */
    fun states(): Map<String, DownloadState> = synchronized(lock) { LinkedHashMap(states) }

    fun state(id: String): DownloadState? = synchronized(lock) { states[id] }

    /** True while something is queued or running (the foreground service must stay alive). */
    val isActive: Boolean get() = synchronized(lock) { states.values.any { it is DownloadState.Queued || it is DownloadState.Running } }

    /** Queues [region] (also resumes a paused one and retries a failed one). Ignored if already queued or running. */
    fun enqueue(region: Region) {
        val job: Job
        synchronized(lock) {
            val cur = states[region.id]
            if (cur is DownloadState.Queued || cur is DownloadState.Running) return
            job = Job(region)
            jobs[region.id] = job
            states[region.id] = DownloadState.Queued
        }
        notifyChange(force = true)
        executor.execute { run(job) }
    }

    /** Pauses [id]; the partial files stay for [enqueue] to resume. Takes effect in the UI at once. */
    fun pause(id: String) {
        synchronized(lock) {
            val job = jobs[id] ?: return
            job.token.cancel()
            val total = job.region.totalBytes
            states[id] = when (val cur = states[id]) {
                is DownloadState.Running -> DownloadState.Paused(cur.done, total)
                is DownloadState.Queued -> DownloadState.Paused(0, total)
                else -> return
            }
        }
        notifyChange(force = true)
    }

    fun pauseAll() {
        synchronized(lock) { jobs.keys.toList() }.forEach(::pause)
    }

    /** Drops [id] entirely (cancel for good); the caller deletes any partial files. */
    fun cancel(id: String) {
        synchronized(lock) {
            jobs[id]?.let { it.wantsDrop = true; it.token.cancel() }
            states.remove(id)
        }
        notifyChange(force = true)
    }

    /** Forgets a failed or paused entry without touching disk. */
    fun clear(id: String) {
        synchronized(lock) {
            if (states[id] is DownloadState.Failed || states[id] is DownloadState.Paused) states.remove(id)
        }
        notifyChange(force = true)
    }

    private fun run(job: Job) {
        val id = job.region.id
        synchronized(lock) {
            if (jobs[id] !== job) return // replaced by a newer enqueue
            if (job.token.isCancelled) { // paused or dropped while queued
                if (job.wantsDrop) jobs.remove(id)
                return
            }
            states[id] = DownloadState.Running(0, job.region.totalBytes)
        }
        notifyChange(force = true)
        var failure: DownloadState? = null
        var done = false
        try {
            installer.install(job.region, job.token) { d, t ->
                synchronized(lock) { if (!job.token.isCancelled && jobs[id] === job) states[id] = DownloadState.Running(d, t) }
                notifyChange(force = false)
            }
            done = true
        } catch (e: DownloadCancelledException) {
            // paused or dropped: pause()/cancel() already set the state
        } catch (e: NetworkDeniedException) {
            failure = DownloadState.Failed(if (e.reason == DenyReason.OFFLINE_MODE) FailureReason.OFFLINE_MODE else FailureReason.NOT_ALLOWED)
        } catch (e: InsufficientSpaceException) {
            failure = DownloadState.Failed(FailureReason.NO_SPACE, e.neededBytes, e.freeBytes)
        } catch (e: HashMismatchException) {
            failure = DownloadState.Failed(FailureReason.CORRUPT)
        } catch (e: IOException) {
            failure = DownloadState.Failed(FailureReason.NETWORK)
        } catch (e: RuntimeException) {
            failure = DownloadState.Failed(FailureReason.NETWORK)
        }
        var installedNow = false
        synchronized(lock) {
            if (jobs[id] === job) {
                jobs.remove(id)
                when {
                    job.wantsDrop -> Unit
                    done -> { states.remove(id); installedNow = true }
                    job.token.isCancelled -> Unit // paused meanwhile; keep the Paused entry
                    failure != null -> states[id] = failure
                }
            }
        }
        if (installedNow) onInstalled(id)
        notifyChange(force = true)
    }

    private fun notifyChange(force: Boolean) {
        val now = clock()
        if (!force) synchronized(lock) {
            if (now - lastNotify < THROTTLE_NANOS) return
        }
        synchronized(lock) { lastNotify = now }
        onChange()
    }

    private companion object {
        const val THROTTLE_NANOS = 250_000_000L
    }
}
