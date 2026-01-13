package com.hurrywang.seerinfo

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.util.Base64
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
import android.webkit.JavascriptInterface
import android.webkit.WebStorage

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
        setupImageLongPressSave(webView)
        setupWebViewClient(webView)
        setupWebChromeClient(webView) 
        setupBackPressed(webView)

        webView.loadUrl("https://seerinfo.yuyuqaq.cn/")
        webView.addJavascriptInterface(WebAppInterface(), "Android")
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

        // 注册 JavaScript Bridge，供网页调用原生功能
        webView.addJavascriptInterface(WebAppInterface(), "Android")

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
//                updateNavItems(webView)
            }
        }
    }

    private fun setupWebChromeClient(webView: WebView) {
    webView.webChromeClient = object :  android.webkit.WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?
        ): Boolean {
            // 创建一个临时 WebView 来接收新窗口的请求
            val newWebView = WebView(this@MainActivity).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?
                    ): Boolean {
                        // 在原 WebView 中打开 URL
                        // url?.let { webView. loadUrl(it) }
                        url?.let {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
        startActivity(intent)
    }
                        return true
                    }
                }
            }

            // 将消息传递给新的 WebView
            val transport = resultMsg?.obj as?  WebView.WebViewTransport
            transport?.webView = newWebView
            resultMsg?.sendToTarget()

            return true
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
            // 检查是否为 data: URL (base64 编码的图片)
            if (url.startsWith("data:", ignoreCase = true)) {
                return@runCatching saveBase64ImageToMediaStore(url)
            }

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

    /**
     * 保存 base64 编码的图片到相册
     * 支持格式: data:image/png;base64,xxxxx 或 data:image/jpeg;base64,xxxxx
     */
    private fun saveBase64ImageToMediaStore(dataUrl: String): Unit {
        var uri: Uri? = null
        try {
            // 解析 data URL: data:[<mediatype>][;base64],<data>
            val dataPrefix = "data:"
            if (!dataUrl.startsWith(dataPrefix, ignoreCase = true)) {
                throw IOException("无效的 data URL")
            }

            val commaIndex = dataUrl.indexOf(',')
            if (commaIndex < 0) {
                throw IOException("无效的 data URL 格式")
            }

            val metadata = dataUrl.substring(dataPrefix.length, commaIndex)
            val base64Data = dataUrl.substring(commaIndex + 1)

            // 解析 MIME 类型
            val isBase64 = metadata.contains("base64", ignoreCase = true)
            val mime = metadata.replace(";base64", "", ignoreCase = true)
                .takeIf { it.isNotBlank() } ?: "image/png"

            if (!mime.startsWith("image/", ignoreCase = true)) {
                throw IOException("不是图片类型: $mime")
            }

            // 解码 base64 数据
            val imageBytes = if (isBase64) {
                Base64.decode(base64Data, Base64.DEFAULT)
            } else {
                // 如果不是 base64 编码，尝试 URL 解码
                java.net.URLDecoder.decode(base64Data, "UTF-8").toByteArray()
            }

            // 生成文件名
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            val filename = "IMG_${System.currentTimeMillis()}.$ext"

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

            resolver.openOutputStream(uri)?.use { output ->
                output.write(imageBytes)
            } ?: throw IOException("无法写入媒体文件")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
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

    /**
     * JavaScript Bridge 接口，供网页调用原生功能
     */
    inner class WebAppInterface {
        /**
         * 清除 WebView 所有缓存（包括文件缓存、数据库、Cookie 等）
         * 前端调用: window.Android.clearAllCache()
         */
        @JavascriptInterface
        fun clearAllCache() {
            runOnUiThread {
                try {
                    val webView = binding.webView

                    // 清除 WebView 缓存（磁盘和内存）
                    webView.clearCache(true)

                    // 清除表单数据
                    webView.clearFormData()

                    // 清除历史记录
                    webView.clearHistory()

                    // 清除 Cookie
                    CookieManager.getInstance().apply {
                        removeAllCookies(null)
                        flush()
                    }

                    // 清除 WebStorage（localStorage、sessionStorage、IndexedDB）
                    WebStorage.getInstance().deleteAllData()

                    // 清除应用内部 WebView 缓存目录
                    cacheDir?.deleteRecursively()
                    
                    // 清除 WebView 数据库
                    try {
                        getDatabasePath("webview.db")?.parentFile?.listFiles()?.forEach { 
                            if (it.name.startsWith("webview")) it.deleteRecursively() 
                        }
                    } catch (_: Exception) {}

                    toast("缓存已彻底清除")
                } catch (e: Exception) {
                    toast("清除失败: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }

        /**
         * 显示 Toast 提示
         * 前端调用: window.Android.showToast("消息内容")
         */
        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                toast(message)
            }
        }

        /**
         * 获取 App 版本信息
         * 前端调用: window.Android.getAppVersion()
         * @return 版本号字符串
         */
        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (_: Exception) {
                ""
            }
        }
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