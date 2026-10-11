package com.hurrywang.seerinfo

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.core.net.toUri
import java.net.HttpURLConnection
import java.net.URL

internal const val CORS_BYPASS_HOST = "newseer.61.com"
internal const val CORS_BYPASS_PATH_PREFIX = "/Assets/StandaloneWindows64/PetAnimPackage/"
private const val TAG = "Seerinfo/Cors"

fun WebView.setupUserAgent(context: Context) {
    val appUaName = "seerinfo-android"
    val pkgName = context.packageName
    val (versionName, versionCode) = try {
        val pi = context.packageManager.getPackageInfo(pkgName, 0)
        val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        (pi.versionName ?: "") to vc
    } catch (_: Exception) {
        "" to -1L
    }

    val baseUa = try {
        WebSettings.getDefaultUserAgent(context)
    } catch (_: Exception) {
        this.settings.userAgentString
    }

    val customUaSuffix = buildString {
        append(appUaName)
        if (versionName.isNotBlank()) append("/").append(versionName)
        append(" (").append(pkgName)
        if (versionCode >= 0) append("; vc=").append(versionCode)
        append(")")
    }

    this.settings.userAgentString = "$baseUa $customUaSuffix"
}

fun fetchWithCorsBypass(url: String, userAgent: String): WebResourceResponse? {
    Log.d(TAG, "fetch start: $url")
    return try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let {
                setRequestProperty("Cookie", it)
            }
            requestMethod = "GET"
        }
        val contentType = conn.contentType ?: "application/octet-stream"
        val mimeType = contentType.substringBefore(";").trim().ifBlank { "application/octet-stream" }
        val encoding = conn.contentEncoding
        val code = conn.responseCode
        Log.d(TAG, "fetch ok: $url -> $code $mimeType len=${conn.contentLength} enc=$encoding")

        // Keep the connection alive until WebView finishes reading the stream.
        val stream = object : java.io.FilterInputStream(conn.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    conn.disconnect()
                }
            }
        }

        val responseHeaders = buildCorsResponseHeaders(conn)
        WebResourceResponse(mimeType, encoding, stream).apply {
            setStatusCodeAndReasonPhrase(code, conn.responseMessage ?: "OK")
            this.responseHeaders = responseHeaders
        }
    } catch (e: Exception) {
        Log.w(TAG, "fetch failed: $url", e)
        null
    }
}

private fun buildCorsResponseHeaders(conn: HttpURLConnection): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    conn.headerFields.forEach { (key, values) ->
        if (key.isNullOrBlank() || values.isNullOrEmpty()) return@forEach
        // HttpURLConnection exposes multi-value headers as a list; join with ", " to preserve them.
        headers[key] = values.joinToString(", ")
    }
    headers["Access-Control-Allow-Origin"] = "*"
    headers["Access-Control-Allow-Methods"] = "GET, OPTIONS"
    headers["Access-Control-Allow-Headers"] = "*"
    return headers
}

@SuppressLint("SetJavaScriptEnabled")
fun WebView.setupSettings() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        useWideViewPort = true
        loadWithOverviewMode = true
        loadsImagesAutomatically = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
        // 按 HTTP 缓存语义加载，弱网/离线时优先复用缓存，减少白屏
        cacheMode = WebSettings.LOAD_DEFAULT
    }

    // 提高渲染进程优先级并允许其被回收前充分调度，避免 App 切后台后渲染进程被杀、
    // 回到前台时出现白屏（Android 8+ 生效）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }

    isLongClickable = true
    isHapticFeedbackEnabled = true
    setOnCreateContextMenuListener { _, _, _ -> }
}

fun WebView.setupDownloadListener(context: Context) {
    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        try {
            val urlLower = (url ?: "").lowercase()
            val cdLower = (contentDisposition ?: "").lowercase()

            val isApk = urlLower.endsWith(".apk") ||
                    cdLower.contains(".apk") ||
                    cdLower.contains("application/vnd.android.package-archive") ||
                    (mimeType?.equals("application/vnd.android.package-archive", ignoreCase = true) == true)

            val safeMime = when {
                isApk -> "application/vnd.android.package-archive"
                mimeType.isNullOrBlank() -> null
                mimeType.equals("application/octet-stream", ignoreCase = true) -> null
                else -> mimeType
            }

            var fileName = URLUtil.guessFileName(url, contentDisposition, safeMime)

            if (isApk && !fileName.lowercase().endsWith(".apk")) {
                fileName = fileName.replace(Regex("\\.[A-Za-z0-9]{1,5}$"), ".Apk")
                if (!fileName.lowercase().endsWith(".apk")) fileName += ".Apk"
            }

            val req = DownloadManager.Request(url.toUri()).apply {
                if (isApk) setMimeType("application/vnd.android.package-archive")
                else if (!safeMime.isNullOrBlank()) setMimeType(safeMime)

                setTitle(fileName)
                setDescription(url)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)

                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(context, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "下载失败: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }
}