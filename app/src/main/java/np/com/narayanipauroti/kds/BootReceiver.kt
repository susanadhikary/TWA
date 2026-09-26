package np.com.narayanipauroti.kds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Opens the KDS when the TV finishes booting. On Android 10+ this only works once
 * "Display over other apps" is allowed for the app (see MainActivity).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            // Background activity start blocked; the app can still be opened from the home screen.
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
