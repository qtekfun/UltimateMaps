# Live Update: distance to the next turn in the status bar (Android 16+)

## What it does

On Android 16 and later the navigation foreground notification asks the system to promote it to a **promoted ongoing notification** ("Live Update"). When the system accepts, it can show a chip in the status bar (next to the front camera) with the distance to the next maneuver, e.g. `160 m`, `1,2 km`, `500 ft`. The expanded notification shows a progress bar with the trip progress. Below Android 16 the notification is exactly what it was.

Code: `nav/LiveUpdatePlan.kt` (pure decisions, chip text, dedupe), `nav/LiveUpdateNotification.kt` (platform calls, `@RequiresApi(36)`), `nav/NavigationService.kt` (small additive hook), setting `NavSettings.liveUpdateChip` (key `live_update_chip`, in the settings backup), string pair `strings_live_update.xml`.

## Research (official sources)

Read on 2026-10-07 through a page-to-text tool, so quotes are as that tool returned them.

- Guide, "Create a live update notification": https://developer.android.com/develop/ui/views/notifications/live-update
  - Needs `<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />`, described as a **non-runtime permission** (no prompt).
  - Request promotion with `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` (or `NotificationCompat.Builder.setRequestPromotedOngoing(true)`).
  - Eligibility: standard style, `BigTextStyle`, `CallStyle`, `ProgressStyle` or `MetricStyle`; ongoing (`FLAG_ONGOING_EVENT`); a `contentTitle`; no `customContentView` (no RemoteViews); not a group summary; not `setColorized(true)`; channel not `IMPORTANCE_MIN`.
  - Runtime checks: `Notification.hasPromotableCharacteristics()`, `NotificationManager.canPostPromotedNotifications()` (false when the user disabled it for the app), `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`, and `Notification.FLAG_PROMOTED_ONGOING` on the posted notification.
  - Intended for ongoing, user-initiated, time-sensitive activity (active navigation is the first example); OEMs may add criteria; do not repost a Live Update the user dismissed.
- `Notification.Builder` reference: https://developer.android.com/reference/android/app/Notification.Builder
  - `setShortCriticalText(String)`: API level 36. "Suggested max length is 7 characters, and there is no guarantee how much or how little of this text will be shown." It is for the chip; it has the highest precedence for the chip content; `""` leaves the chip without content.
  - `setRequestPromotedOngoing(boolean)`: listed as "Added in version 36.1" (that is why this app sets the documented extra instead; see below).
- `Notification.ProgressStyle` reference: https://developer.android.com/reference/android/app/Notification.ProgressStyle
  - API level 36. "suggested for ... their own progress in a navigation journey". `setProgress` is in the units of the segment lengths and the maximum is the sum of the segment lengths; segments are optional (default one segment of 100); a tracker icon can be set.
- The platform `android.jar` of API 37 (local SDK) confirms the symbols used: `Notification.EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"`, `FLAG_PROMOTED_ONGOING`, `Manifest.permission.POST_PROMOTED_NOTIFICATIONS`, `Notification.Builder.setShortCriticalText`, `Notification.ProgressStyle` with `Segment`.

Not verified: the exact number of characters the chip really shows on a Pixel 8 on Android 17; whether the system promotes a notification that is also a foreground-service notification (the guide does not exclude it, and the owner's request assumes it works); whether the permission needs to be granted by the user on some OEMs (the guide says non-runtime); behaviour on the lock screen.

## How the implementation follows it

- Promotion only when `Build.VERSION.SDK_INT >= 36`, the setting is on, a navigation state exists, the notification is ongoing, and the trip has not arrived.
- Built from the existing `NotificationCompat` notification with `Notification.Builder.recoverBuilder`, then `addExtras(EXTRA_REQUEST_PROMOTED_ONGOING = true)`, `setShortCriticalText`, and a `ProgressStyle` (one segment of 100, progress in percent of the route done, the app icon as tracker). The base notification already satisfies the eligibility list: ongoing, titled, no custom views, not a group summary, not colorized, low-importance (not min) channel.
- The extra is used instead of `Builder.setRequestPromotedOngoing` because the latter is "Added in 36.1": on an Android 16.0 device (API 36 without the minor release) calling it would throw `NoSuchMethodError`. The extra is the documented equivalent. The androidx.core version in the project is transitive and not pinned, so no compat wrapper is relied on.
- Chip text (`LiveUpdatePolicy`): the distance to the next maneuver (remaining distance if there is no next maneuver), in the user's units: `160 m`, `1.2 km` / `1,2 km`, `35 km`, `500 ft`, `0.3 mi`, always up to 7 characters. Off route / recalculating: `Reroute` (`Recalc.`). No GPS signal or a location problem: `No GPS` (`Sin GPS`). Never blank.
- Progress: `traveled / (traveled + remaining)` in whole percent.
- Cost: the service already throttles to 1 s (5 s with battery saver); `NotificationDedupe` additionally skips an update identical to the last (same title, body, chip and percent), so the chip is redrawn only when something shown changed.
- Arrival: the final "You have arrived" notification is built without promotion and is no longer ongoing; the service stops the foreground state as before.
- Privacy: only a distance or a short word reaches the chip; no position, nothing is logged.
- Settings: Settings, Navigation, "Show distance in the status bar" (default on; hidden below Android 16). Its description tells the user that promoted notifications can also be turned off for the app in the system settings.

## What was verified

By tests only (JVM and Robolectric), no device:

- `LiveUpdatePolicyTest` (pure): compact distance in metric and imperial, locale decimal separator, never longer than 7 characters, plan for on route / no next maneuver / off route / rerouting / no signal / problems, no plan when off, below API 36, without state or after arrival, progress clamp, dedupe.
- `LiveUpdateNotificationTest` (Robolectric SDK 36): the promoted notification has the request extra, the short critical text, a `ProgressStyle` with the progress and max, keeps title, text, category and actions, and meets the documented eligibility rules (ongoing, titled, no custom views, not group summary, not colorized). `LiveUpdateBelowSdk36Test` (SDK 34): no plan, so the regular notification.
- `LiveUpdateSettingTest`: default on, switch hidden below Android 16 and working on it, persistence, damaged value, present in the backup specs (and `SettingsSchemaCoverageTest` still passes).
- Limits of the Robolectric SDK 36 environment: its `android-all` has no `isRequestPromotedOngoing()` and `hasPromotableCharacteristics()` returns false even for a notification that meets every rule, so the real platform decision is NOT tested.

## Checklist for the owner (Pixel 8, Android 17)

Install a test build, start a navigation (simulated or real) and check:

1. With the app in the background (home screen) and the screen on: does a chip with the distance (e.g. `160 m`) appear in the status bar next to the front camera? Does it count down while you move and change to `Reroute` when you leave the route?
2. Does it fit? Longer values (`1,2 km`, `35 km`) cut or shown whole?
3. Pull the shade: is the notification at the top with the progress bar and tracker icon, and do Open and Stop work?
4. Tap and long-press the chip: what opens (the app?) and what does a long press offer (for example turn off promotion)?
5. Lock the screen: is the chip or a notification shown, and how much (the notification is `VISIBILITY_PUBLIC`)?
6. Settings, Android, Notifications, UltimateMaps: is there a "Live Updates / promoted notifications" switch, and does turning it off remove the chip while navigation continues? Does the app's own switch (Settings, Navigation) off remove it within a couple of seconds?
7. Arrive: the chip disappears and a normal "You have arrived" notification stays.
8. Battery saver on: the chip updates every 5 s at most. Is that acceptable?
9. Does the status bar flicker or the notification feel heavy while the distance changes?
10. With imperial units (Settings, Navigation, Units): does the chip show feet and miles (the notification body still shows metres, pre-existing)?
