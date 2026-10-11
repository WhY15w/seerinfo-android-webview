package com.hurrywang.seerinfo

import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.hurrywang.seerinfo.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val MAIN_URL = "https://seerinfo.yuyuqaq.cn/"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageDownloader: ImageDownloader

    // 渲染进程崩溃后需要销毁重建 WebView，因此用可变引用而非直接依赖 binding.webView
    private var activeWebView: RefreshAwareWebView? = null
    private val popupWebViews = mutableListOf<WebView>()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastErrorUrl: String? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        filePathCallback?.let { callback ->
            val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    data.clipData?.let { clipData ->
                        Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
                    } ?: data.data?.let { arrayOf(it) }
                }
            } else null
            callback.onReceiveValue(uris)
        }
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 兼容 Android 15 强制开启的 Edge-to-Edge，处理系统状态栏和导航栏遮挡
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imageDownloader = ImageDownloader(this)

        attachWebView()
        setupRefreshLayout()
        setupErrorView()
        setupBackPressed()
    }

    /**
     * 创建并装配一个新的 WebView。首次进入与渲染进程崩溃自恢复共用该入口，
     * 保证重建后的配置（UA、JS 桥、下载监听等）与初始状态完全一致。
     */
    private fun attachWebView() {
        val webView = RefreshAwareWebView(this)
        // 通过 addView(view, w, h) 让父容器生成自身类型的 LayoutParams，避免类型转换问题
        binding.swipeRefreshLayout.addView(
            webView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        activeWebView = webView

        // 页面加载完成前 WebView 底色跟随主题，避免暗色模式下启动白屏闪烁
        webView.setBackgroundColor(resolveThemeBackgroundColor())

        webView.setupSettings()
        webView.setupUserAgent(this)
        webView.setupDownloadListener(this)
        setupImageLongPressSave(webView)
        setupWebViewClients(webView)

        webView.addJavascriptInterface(WebAppInterface(this, webView), "Android")
        webView.setRefreshStateListener(object : RefreshAwareWebView.RefreshStateListener {
            override fun refreshState(canRefresh: Boolean) {
                binding.swipeRefreshLayout.isEnabled = canRefresh
            }
        })

        webView.loadUrl(MAIN_URL)
    }

    private fun setupRefreshLayout() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            lastErrorUrl = null
            activeWebView?.reload()
        }
    }

    private fun setupErrorView() {
        binding.retryButton.setOnClickListener {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, R.string.toast_network_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideErrorView()
            val target = lastErrorUrl
            lastErrorUrl = null
            activeWebView?.let { webView ->
                if (target.isNullOrBlank()) webView.reload() else webView.loadUrl(target)
            }
        }
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
                    lifecycleScope.launch {
                        imageDownloader.downloadAndSave(url, ua, cookie)
                            .onSuccess { Toast.makeText(this@MainActivity, "已保存到相册/图库", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(this@MainActivity, "保存失败: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_SHORT).show() }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
    }

    private fun setupWebViewClients(webView: WebView) {
        val userAgent = webView.settings.userAgentString ?: ""
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                if (request.method.equals("GET", ignoreCase = true) &&
                    !request.isForMainFrame &&
                    url.host?.isTargetCorsBypassHost() == true &&
                    url.path?.startsWith(CORS_BYPASS_PATH_PREFIX) == true
                ) {
                    return fetchWithCorsBypass(url.toString(), userAgent)
                }
                return null
            }

            private fun String.isTargetCorsBypassHost(): Boolean {
                return this == CORS_BYPASS_HOST || this.endsWith(".${CORS_BYPASS_HOST}")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleExternalNavigation(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hideErrorView()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (binding.swipeRefreshLayout.isRefreshing) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                lastErrorUrl = request.url.toString()
                showErrorView(getString(R.string.error_page_generic))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                super.onReceivedHttpError(view, request, errorResponse)
                // 4xx 通常会自带响应体（如登录页），只把主框架的 5xx 视为页面级故障
                if (!request.isForMainFrame || errorResponse.statusCode < 500) return
                lastErrorUrl = request.url.toString()
                showErrorView(getString(R.string.error_page_server, errorResponse.statusCode))
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // 渲染进程崩溃时 WebView 会直接白屏，这里销毁并立即重建，避免不可恢复的白屏
                if (view === activeWebView) {
                    rebuildWebViewAfterRenderCrash(view)
                } else {
                    destroyWebViewQuietly(view)
                }
                return true
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 100) {
                    binding.webProgressBar.visibility = View.GONE
                } else {
                    binding.webProgressBar.apply {
                        visibility = View.VISIBLE
                        progress = newProgress
                    }
                }
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val newWebView = WebView(this@MainActivity).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            url?.let {
                                startActivity(Intent(Intent.ACTION_VIEW, it.toUri()))
                            }
                            return true
                        }
                    }
                }
                popupWebViews.add(newWebView)
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    /**
     * 非网页协议（tel: / mailto: / intent: 等）交给外部应用处理，
     * http(s) 链接保留在应用内导航。
     */
    private fun handleExternalNavigation(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https", null -> false
            "intent" -> {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                } catch (_: Exception) {
                }
                true
            }
            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    // 没有可处理该协议的应用时留在当前页
                }
                true
            }
        }
    }

    private fun showErrorView(message: String) {
        binding.errorMessage.text = message
        binding.errorView.visibility = View.VISIBLE
        binding.webProgressBar.visibility = View.GONE
        if (binding.swipeRefreshLayout.isRefreshing) {
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun hideErrorView() {
        binding.errorView.visibility = View.GONE
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun resolveThemeBackgroundColor(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        return value.data
    }

    private fun rebuildWebViewAfterRenderCrash(crashedWebView: WebView) {
        destroyWebViewQuietly(crashedWebView)
        if (crashedWebView === activeWebView) {
            activeWebView = null
            hideErrorView()
            binding.webProgressBar.visibility = View.GONE
            attachWebView()
        }
    }

    private fun destroyWebViewQuietly(webView: WebView) {
        try {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Exception) {
        }
    }

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = activeWebView
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onPause() {
        activeWebView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        activeWebView?.onResume()
    }

    override fun onDestroy() {
        popupWebViews.forEach { destroyWebViewQuietly(it) }
        popupWebViews.clear()

        activeWebView?.let {
            try {
                it.removeJavascriptInterface("Android")
            } catch (_: Exception) {
            }
            destroyWebViewQuietly(it)
        }
        activeWebView = null

        super.onDestroy()
    }
}
