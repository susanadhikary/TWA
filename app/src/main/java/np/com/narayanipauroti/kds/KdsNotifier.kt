package np.com.narayanipauroti.kds

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Shows native Android notifications on behalf of the web page's Notification API. */
class KdsNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.notification_channel_description) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Web Notification.permission value: "granted", "default" or "denied". */
    fun permissionState(): String = when {
        manager.areNotificationsEnabled() -> "granted"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED -> "default"
        else -> "denied"
    }

    @SuppressLint("MissingPermission")
    fun show(tag: String, title: String, body: String, silent: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_NOTIFICATION_TAG, tag)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSilent(silent)
        if (!silent) builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        try {
            manager.notify(tag, NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    fun cancel(tag: String) = manager.cancel(tag, NOTIFICATION_ID)

    companion object {
        const val EXTRA_NOTIFICATION_TAG = "np.com.narayanipauroti.kds.NOTIFICATION_TAG"
        private const val CHANNEL_ID = "kds_alerts"
        private const val NOTIFICATION_ID = 1
    }
}
