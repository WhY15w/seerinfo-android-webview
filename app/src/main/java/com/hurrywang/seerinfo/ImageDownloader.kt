package com.hurrywang.seerinfo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

class ImageDownloader(private val context: Context) {

    suspend fun downloadAndSave(url: String, userAgent: String, cookie: String?): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (url.startsWith("data:", ignoreCase = true)) {
                    saveBase64ImageToMediaStore(url)
                } else {
                    saveNetworkImageToMediaStore(url, userAgent, cookie)
                }
            }
        }
    }

    private fun saveNetworkImageToMediaStore(url: String, userAgent: String, cookie: String?) {
        var uri: Uri? = null
        try {
            var filename = URLUtil.guessFileName(url, null, null)

            val extFromUrl = MimeTypeMap.getFileExtensionFromUrl(url)
            val mimeFromExt = extFromUrl
                .takeIf { it.isNotBlank() }
                ?.lowercase()
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

            var mime = mimeFromExt ?: (URLConnection.guessContentTypeFromName(filename) ?: "")

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            }
            conn.connect()
            
            val contentType = conn.contentType?.substringBefore(";")?.trim().orEmpty()
            if (contentType.startsWith("image/", ignoreCase = true)) {
                mime = contentType
            }
            if (!mime.startsWith("image/", ignoreCase = true)) {
                throw IOException("链接不是图片类型: $contentType")
            }

            val hasExt = filename.contains('.') && filename.substringAfterLast('.', "").length in 2..5
            if (!hasExt) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                filename = "$filename.$ext"
            }

            uri = createMediaStoreUri(filename, mime)

            conn.inputStream.use { input ->
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("无法写入媒体文件")
            }

            finishPendingMediaStoreUri(uri)
        } catch (e: Exception) {
            deleteFailedUri(uri)
            throw e
        }
    }

    private fun saveBase64ImageToMediaStore(dataUrl: String) {
        var uri: Uri? = null
        try {
            val dataPrefix = "data:"
            if (!dataUrl.startsWith(dataPrefix, ignoreCase = true)) throw IOException("无效的 data URL")

            val commaIndex = dataUrl.indexOf(',')
            if (commaIndex < 0) throw IOException("无效的 data URL 格式")

            val metadata = dataUrl.substring(dataPrefix.length, commaIndex)
            val base64Data = dataUrl.substring(commaIndex + 1)

            val isBase64 = metadata.contains("base64", ignoreCase = true)
            val mime = metadata.replace(";base64", "", ignoreCase = true)
                .takeIf { it.isNotBlank() } ?: "image/png"

            if (!mime.startsWith("image/", ignoreCase = true)) throw IOException("不是图片类型: $mime")

            val imageBytes = if (isBase64) {
                Base64.decode(base64Data, Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(base64Data, "UTF-8").toByteArray()
            }

            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            val filename = "IMG_${System.currentTimeMillis()}.$ext"

            uri = createMediaStoreUri(filename, mime)

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(imageBytes)
            } ?: throw IOException("无法写入媒体文件")

            finishPendingMediaStoreUri(uri)
        } catch (e: Exception) {
            deleteFailedUri(uri)
            throw e
        }
    }

    private fun createMediaStoreUri(filename: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Seerinfo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建媒体文件")
    }

    private fun finishPendingMediaStoreUri(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
    }

    private fun deleteFailedUri(uri: Uri?) {
        try {
            uri?.let { context.contentResolver.delete(it, null, null) }
        } catch (_: Exception) {}
    }
}