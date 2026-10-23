package ai.zcode.mobile.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bugfix: 官方壳没有接 WebChromeClient.onShowFileChooser，网页里的附件/图片按钮在手机上点了没反应。
 * 附件走 content:// 而不是 file://，这样 allowFileAccess 可以继续关着。
 */
object AttachmentTargets {
    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val CAMERA_DIR = "camera"

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /** 拍照落盘到应用私有缓存，再以 content:// 交给 WebView，随系统清缓存自动回收。 */
    fun createCameraTarget(context: Context): CameraTarget {
        val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "IMG_$stamp.jpg")
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return CameraTarget(uri, file)
    }

    /** SAF 选回来的 URI 需要长期读权限，否则 Activity 重建后就失效。 */
    fun persist(context: Context, uris: List<Uri>) {
        uris.forEach { uri ->
            if (uri.scheme == "content") {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
    }

    data class CameraTarget(val uri: Uri, val file: File) {
        val exists: Boolean get() = file.length() > 0
    }
}
