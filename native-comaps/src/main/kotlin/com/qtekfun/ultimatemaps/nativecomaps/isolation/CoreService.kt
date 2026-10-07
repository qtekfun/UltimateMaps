package com.qtekfun.ultimatemaps.nativecomaps.isolation

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.qtekfun.ultimatemaps.nativecomaps.CoMapsCore

/**
 * The native core's own process (`android:process=":core"`, declared in this module's manifest). It loads
 * `libumcomaps.so` and the `.mwm` maps, and answers [CoreHost] requests over Binder. A native abort (a CoMaps
 * `CHECK`, SIGABRT) kills only this process; the main process notices through its death recipient and starts it
 * again (see [IsolatedCore]).
 *
 * Not exported and bound only by the app itself (the caller's uid is checked too). Binder threads call straight
 * into [CoreHost], which serialises. The core is created on the first request, not when the process starts, so
 * binding costs nothing until a search or a route is actually asked for.
 */
class CoreService : Service() {
    private val host by lazy { CoreHost(CoMapsCore()) } // CoMapsCore() loads the native library: only in this process

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (getCallingUid() != Process.myUid()) return false
            if (code == CoreProtocol.OP_KILL) {
                Process.killProcess(Process.myPid()) // the only way to stop a stuck native call
                return true
            }
            if (code !in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            val payload = data.createByteArray() ?: ByteArray(0)
            val out = host.handle(code, payload)
            reply?.writeByteArray(out)
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Not sticky and not foreground: the main process binds when it needs the core and the system may reclaim the
    // process when nobody is bound. The client simply starts it again (lazily) on the next call.
}
