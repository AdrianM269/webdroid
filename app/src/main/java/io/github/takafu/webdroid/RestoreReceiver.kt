package io.github.takafu.webdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Revives [FloatingBubbleService] when the restore notification is tapped.
 *
 * Two problems with tapping that notification make a receiver necessary:
 *
 *  1. [FloatingBubbleService] is declared `android:exported="false"`, so a
 *     `PendingIntent.getService` aimed straight at it is not deliverable from
 *     outside the app, and is dropped outright once the process has been
 *     reclaimed. The user is left with a notification that does nothing.
 *  2. This is a plain background service, so it may not be running when the
 *     notification is tapped. The system delivers a broadcast to a
 *     manifest-declared receiver regardless, which lets us start it again.
 *
 * This must be a **top-level** class. Declared inside the service's
 * `companion object` it compiles to
 * `FloatingBubbleService$Companion$RestoreReceiver`, and the manifest entry
 * naming `FloatingBubbleService$RestoreReceiver` fails at instantiation with
 * `ClassNotFoundException` — which crashes the whole process every time the
 * notification is tapped.
 */
class RestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != FloatingBubbleService.ACTION_RESTORE_OVERLAYS) return
        Log.d("FloatingBubble", "RestoreReceiver: bringing service back")

        if (context == null) return
        val svc = Intent(context, FloatingBubbleService::class.java).apply {
            action = FloatingBubbleService.ACTION_RESTORE_OVERLAYS
        }
        try {
            // Plain startService, not startForegroundService: the service never
            // posts a foreground notification, so starting it as one would be
            // killed for not calling startForeground within 5 seconds. It may
            // already be running, in which case this only delivers the action.
            context.startService(svc)
        } catch (e: Exception) {
            Log.e("FloatingBubble", "RestoreReceiver: failed to start service: ${e.message}")
        }
    }
}
