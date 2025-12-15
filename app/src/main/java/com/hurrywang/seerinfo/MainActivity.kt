package com.hurrywang.seerinfo

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.hurrywang.seerinfo.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 复用 PopupMenu，避免多次 inflate
    private var actionsMenu: PopupMenu? = null
    private var lastMenuShowUptimeMs: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val webView = binding.webView

        setupUserAgent(webView)
        setupWebViewSettings(webView)
        setupActionsMenu(webView)
        setupImageLongPressSave(webView)
        setupWebViewClient(webView)
        setupBackPressed(webView)

        webView.loadUrl("https://seerinfo.yuyuqaq.cn/")
        updateNavItems(webView)
    }

    private fun setupUserAgent(webView: WebView) {
        val appUaName = "seerinfo-android"
        val pkgName = packageName
        val (versionName, versionCode) = try {
            val pi = packageManager.getPackageInfo(pkgName, 0)
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
            WebSettings.getDefaultUserAgent(this)
        } catch (_: Exception) {
            webView.settings.userAgentString
        }

        val customUaSuffix = buildString {
            append(appUaName)
            if (versionName.isNotBlank()) append("/").append(versionName)
            append(" (").append(pkgName)
            if (versionCode >= 0) append("; vc=").append(versionCode)
            append(")")
        }

        webView.settings.userAgentString = "$baseUa $customUaSuffix"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true

            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true

            // 让 window.open / a.click 等行为更接近浏览器
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }

        // 确保 WebView 可触发长按
        webView.isLongClickable = true
        webView.isHapticFeedbackEnabled = true
        webView.setOnCreateContextMenuListener { _, _, _ -> }

        // 处理网页触发的下载（如 a 标签 download / 直接下载 APK）
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val urlLower = (url ?: "").lowercase()
                val cdLower = (contentDisposition ?: "").lowercase()

                val isApk = urlLower.endsWith(".apk") ||
                    cdLower.contains(".apk") ||
                    cdLower.contains("application/vnd.android.package-archive") ||
                    (mimeType?.equals("application/vnd.android.package-archive", ignoreCase = true) == true)

                // mimeType 可能为空或被 WebView 识别为 application/octet-stream
                val safeMime = when {
                    isApk -> "application/vnd.android.package-archive"
                    mimeType.isNullOrBlank() -> null
                    mimeType.equals("application/octet-stream", ignoreCase = true) -> null
                    else -> mimeType
                }

                var fileName = URLUtil.guessFileName(url, contentDisposition, safeMime)

                // 常见问题：服务器未给正确 mime/cd 时会被猜成 .bin
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

                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(req)
                toast("开始下载：$fileName")
            } catch (e: Exception) {
                toast("下载失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun setupActionsMenu(webView: WebView) {
        actionsMenu = PopupMenu(this, binding.fabActions).apply {
            menuInflater.inflate(R.menu.webview_actions_menu, menu)

            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_refresh -> {
                        webView.reload()
                        true
                    }

                    R.id.action_back -> {
                        if (webView.canGoBack()) webView.goBack()
                        true
                    }

                    R.id.action_forward -> {
                        if (webView.canGoForward()) webView.goForward()
                        true
                    }

                    R.id.action_clear_cache -> {
                        webView.clearCache(true)
                        webView.clearHistory()
                        webView.clearFormData()
                        toast("缓存已清除")
                        true
                    }

                    R.id.action_about -> {
                        showAboutDialog()
                        true
                    }

                    else -> false
                }
            }
        }

        binding.fabActions.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            if (now - lastMenuShowUptimeMs < 250L) return@setOnClickListener
            lastMenuShowUptimeMs = now

            updateNavItems(webView)
            actionsMenu?.show()
        }
    }

    private fun updateNavItems(webView: WebView) {
        actionsMenu?.menu?.findItem(R.id.action_back)?.isEnabled = webView.canGoBack()
        actionsMenu?.menu?.findItem(R.id.action_forward)?.isEnabled = webView.canGoForward()

        // 如果你希望 FAB 在不可用时禁用，可以在这里做:
        binding.fabActions.isEnabled = true
    }

    private fun showAboutDialog() {
        val pkgName = packageName
        val versionName = try {
            packageManager.getPackageInfo(pkgName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(
                getString(
                    R.string.about_message,
                    getString(R.string.about_author),
                    versionName.ifBlank { getString(R.string.about_version_unknown) },
                    getString(R.string.about_custom)
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupImageLongPressSave(webView: WebView) {
        webView.setOnLongClickListener { v ->
            val result = (v as WebView).hitTestResult
            val url = result.extra

            val isImage = result.type == WebView.HitTestResult.IMAGE_TYPE ||
                result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE

            if (!isImage || url.isNullOrBlank()) return@setOnLongClickListener false

            val ua = webView.settings.userAgentString
            val cookie = CookieManager.getInstance().getCookie(url)

            AlertDialog.Builder(this)
                .setTitle("保存图片")
                .setMessage("是否下载并保存该图片？")
                .setPositiveButton("保存") { _, _ ->
                    downloadImageToGallery(url, ua, cookie)
                }
                .setNegativeButton("取消", null)
                .show()

            true
        }
    }

    private fun setupWebViewClient(webView: WebView) {
        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateNavItems(webView)
            }
        }
    }

    private fun setupBackPressed(webView: WebView) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun downloadImageToGallery(url: String, userAgent: String, cookie: String?) {
        // 用 lifecycleScope：Activity 销毁会自动取消，避免泄漏
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                saveImageToMediaStore(url, userAgent, cookie)
            }

            result.fold(
                onSuccess = { toast("已保存到相册/图库") },
                onFailure = { toast("保存失败: ${it.message ?: it.javaClass.simpleName}") }
            )
        }
    }

    private fun saveImageToMediaStore(
        url: String,
        userAgent: String,
        cookie: String?
    ): Result<Unit> {
        var uri: Uri? = null
        return runCatching {
            var filename = URLUtil.guessFileName(url, null, null)

            // 先基于 url/filename 猜 mime
            val extFromUrl = MimeTypeMap.getFileExtensionFromUrl(url)
            val mimeFromExt = extFromUrl
                .takeIf { it.isNotBlank() }
                ?.lowercase()
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

            var mime = mimeFromExt ?: (URLConnection.guessContentTypeFromName(filename) ?: "")

            // 建立连接后再用 Content-Type 校正一次
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

            // 文件名补后缀
            val hasExt = filename.contains('.') && filename.substringAfterLast('.', "").length in 2..5
            if (!hasExt) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                filename = "$filename.$ext"
            }

            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Seerinfo"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建媒体文件")

            conn.inputStream.use { input ->
                resolver.openOutputStream(uri)?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("无法写入媒体文件")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }

            Unit
        }.recoverCatching { e ->
            // 失败时删除空记录，避免 0B
            try {
                uri?.let { contentResolver.delete(it, null, null) }
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        // WebView 释放（防泄漏）
        try {
            binding.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
        } catch (_: Exception) {
        }

        actionsMenu = null
        super.onDestroy()
    }
}