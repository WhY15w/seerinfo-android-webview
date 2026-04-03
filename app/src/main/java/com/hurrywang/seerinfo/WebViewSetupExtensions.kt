package com.hurrywang.seerinfo

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.core.net.toUri

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