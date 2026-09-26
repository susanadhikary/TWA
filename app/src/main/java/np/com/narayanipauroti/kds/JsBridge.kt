package np.com.narayanipauroti.kds

import org.json.JSONException
import org.json.JSONObject

/**
 * Native side of assets/kds_bridge.js. Messages arrive as JSON strings from the
 * trusted KDS origin only; replies are pushed back with [MainActivity.sendToPage].
 */
class JsBridge(private val activity: MainActivity, private val notifier: KdsNotifier) {

    private val template: String by lazy {
        activity.assets.open("kds_bridge.js").bufferedReader().use { it.readText() }
    }

    fun script(): String = template.replace(PERMISSION_PLACEHOLDER, notifier.permissionState())

    fun handle(raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return
        }
        val id = msg.optInt("id")
        when (msg.optString("type")) {
            "requestNotificationPermission" ->
                activity.requestNotificationPermission { state -> reply(id, state) }
            "notify" -> notifier.show(
                msg.optString("tag"),
                msg.optString("title"),
                msg.optString("body"),
                msg.optBoolean("silent")
            )
            "closeNotification" -> notifier.cancel(msg.optString("tag"))
            "print" -> activity.printPage()
            "share" -> reply(
                id,
                activity.share(msg.optString("title"), msg.optString("text"), msg.optString("url"))
            )
            "download" -> activity.saveDataUrl(
                msg.optString("data"),
                msg.optString("filename").ifBlank { "download" }
            )
        }
    }

    fun pushPermissionState() = send(
        JSONObject().put("type", "permission").put("value", notifier.permissionState())
    )

    fun notificationClicked(tag: String) = send(
        JSONObject().put("type", "notificationclick").put("tag", tag)
    )

    private fun reply(id: Int, value: Any) = send(
        JSONObject().put("type", "reply").put("id", id).put("value", value)
    )

    private fun send(json: JSONObject) = activity.sendToPage(json.toString())

    companion object {
        const val JS_OBJECT = "KdsBridge"
        private const val PERMISSION_PLACEHOLDER = "__KDS_NOTIFICATION_PERMISSION__"
    }
}
