package np.com.narayanipauroti.kds

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import java.io.File

/** Saves files the web page downloads into the device's public Downloads folder. */
object DownloadHelper {

    /** Downloads an http(s) URL with the page's cookies via the system DownloadManager. */
    fun enqueue(context: Context, url: String, userAgent: String?, fileName: String, mimeType: String?): Boolean =
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription(context.getString(R.string.app_name))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            true
        } catch (e: Exception) {
            false
        }

    /** Saves a `data:` URL (also used for blob: downloads converted in JavaScript). */
    fun saveDataUrl(context: Context, dataUrl: String, fileName: String): Boolean =
        try {
            val comma = dataUrl.indexOf(',')
            require(dataUrl.startsWith("data:") && comma > 0)
            val header = dataUrl.substring(5, comma)
            val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
            val payload = dataUrl.substring(comma + 1)
            val bytes = if (header.endsWith(";base64")) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray()
            }
            saveBytes(context, bytes, fileName, mime)
            true
        } catch (e: Exception) {
            false
        }

    private fun saveBytes(context: Context, bytes: ByteArray, fileName: String, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create download entry")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open download")
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, fileName).writeBytes(bytes)
        }
    }
}
