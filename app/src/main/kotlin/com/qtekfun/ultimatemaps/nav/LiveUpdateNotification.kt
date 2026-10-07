package com.qtekfun.ultimatemaps.nav

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi

/**
 * Turns an already built navigation notification into a promoted ongoing one (Android 16 "Live Update"): the
 * platform builder is recovered from it and given a request for promotion, the short critical text of the status
 * bar chip, and a [Notification.ProgressStyle] with the trip progress. Uses only the platform API.
 *
 * The request goes in as the extra [Notification.EXTRA_REQUEST_PROMOTED_ONGOING] (the documented form) rather
 * than `Builder.setRequestPromotedOngoing`, which the reference lists as added in 36.1 and would crash on 36.0.
 * Promotion also needs the notification to be ongoing, titled, without custom views and not colorized; the base
 * notification already is, and [Notification.hasPromotableCharacteristics] confirms it in tests.
 */
object LiveUpdateNotification {
    /** Segment length of the progress bar; the progress is given in percent of the route. */
    private const val PROGRESS_MAX = 100

    @RequiresApi(36)
    fun promote(context: Context, base: Notification, plan: LiveUpdatePlan, @DrawableRes trackerIcon: Int): Notification {
        val style = Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgress(plan.progressPercent.coerceIn(0, PROGRESS_MAX))
            .addProgressSegment(Notification.ProgressStyle.Segment(PROGRESS_MAX))
            .setProgressTrackerIcon(Icon.createWithResource(context, trackerIcon))
        return Notification.Builder.recoverBuilder(context, base)
            .addExtras(Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            .setShortCriticalText(plan.chipText)
            .setStyle(style)
            .build()
    }
}
