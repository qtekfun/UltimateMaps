package com.qtekfun.ultimatemaps.nativecomaps.isolation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [CoreTransport] over Binder to [CoreService] in the `:core` process. `bindService(BIND_AUTO_CREATE)` starts the
 * process; a [IBinder.DeathRecipient] and [DeadObjectException] tell the client it died. Compiles against the
 * real Android API; its behaviour on a device has not been run yet (see `docs/phase2/robustness.md`).
 */
class BinderCoreTransport(context: Context) : CoreTransport {
    private val app = context.applicationContext

    override fun connect(timeoutMillis: Long): CoreConnection {
        val ready = CountDownLatch(1)
        var binder: IBinder? = null
        val dead = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                dead.set(true) // the process died (or was killed); the death recipient fires too
            }

            override fun onNullBinding(name: ComponentName) {
                dead.set(true)
                ready.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                dead.set(true)
                ready.countDown()
            }
        }
        val intent = Intent(app, CoreService::class.java)
        if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            runCatching { app.unbindService(connection) }
            throw CoreDiedException("cannot bind the core service")
        }
        if (!ready.await(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) || binder == null) {
            runCatching { app.unbindService(connection) }
            throw CoreDiedException("the core process did not start in time")
        }
        return BinderConnection(app, connection, binder!!, dead)
    }

    private class BinderConnection(
        private val app: Context,
        private val connection: ServiceConnection,
        private val binder: IBinder,
        private val dead: AtomicBoolean,
    ) : CoreConnection {
        private val recipient = IBinder.DeathRecipient { dead.set(true) }

        init {
            try {
                binder.linkToDeath(recipient, 0)
            } catch (_: RemoteException) {
                dead.set(true)
            }
        }

        override val isAlive: Boolean get() = !dead.get() && binder.isBinderAlive

        override fun call(op: Int, payload: ByteArray): ByteArray {
            if (!isAlive) throw CoreDiedException()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeByteArray(payload)
                if (!binder.transact(op, data, reply, 0)) throw CoreDiedException("the core refused the call")
                return reply.createByteArray() ?: throw CoreDiedException("empty reply")
            } catch (e: DeadObjectException) {
                dead.set(true)
                throw CoreDiedException("the core process died during the call", e)
            } catch (e: RemoteException) {
                // TransactionTooLargeException is a RemoteException too: a protocol bug, not a death. Chunking
                // keeps every transaction far below the limit, so seeing it means something is very wrong.
                if (!binder.isBinderAlive) dead.set(true)
                throw if (dead.get()) CoreDiedException("remote failure", e) else IllegalStateException(e.javaClass.simpleName, e)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        override fun kill() {
            val data = Parcel.obtain()
            try {
                binder.transact(CoreProtocol.OP_KILL, data, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) {
            } finally {
                data.recycle()
            }
            dead.set(true)
            close()
        }

        override fun close() {
            runCatching { binder.unlinkToDeath(recipient, 0) }
            runCatching { app.unbindService(connection) }
        }
    }
}
