package com.qtekfun.ultimatemaps.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Instant

/**
 * How the app's processes ended recently, as the system recorded it (Android 11 and later): the main process and the
 * isolated `:core` one, with the reason (a Java crash, a native crash, the low-memory killer, an ANR...), the signal or exit
 * status and the system's own description. It contains no positions or names; it is what tells "the core crashed" from "the
 * phone ran out of memory" in a report that has no log.
 */
object ExitReasons {
    /** Up to [limit] lines, newest first; empty before Android 11 or when the system has nothing. Never throws. */
    fun lines(context: Context, limit: Int = 12): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, limit).map(::line)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun line(i: ApplicationExitInfo): String {
        val process = i.processName.substringAfter(':', "main")
        val description = i.description?.take(MAX_DESCRIPTION).orEmpty()
        return "${Instant.ofEpochMilli(i.timestamp)} process=$process reason=${reasonName(i.reason)} status=${i.status}" +
            (if (description.isNotEmpty()) " ($description)" else "")
    }

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    private const val MAX_DESCRIPTION = 160
}
