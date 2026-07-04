package com.hurrywang.seerinfo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageDownloader: ImageDownloader

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

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

        setupRefreshLayout()
        setupWebView(binding.webView)
        setupBackPressed()
    }

    private fun setupRefreshLayout() {
        val webView = binding.webView
        binding.swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }
        webView.setRefreshStateListener(object : RefreshAwareWebView.RefreshStateListener {
            override fun refreshState(canRefresh: Boolean) {
                binding.swipeRefreshLayout.isEnabled = canRefresh
            }
        })
        binding.swipeRefreshLayout.isEnabled = !webView.canScrollVertically(-1)
    }

    private fun setupWebView(webView: WebView) {
        // 使用外部提取的扩展函数配置
        webView.setupSettings()
        webView.setupUserAgent(this)
        webView.setupDownloadListener(this)

        setupImageLongPressSave(webView)
        setupWebViewClients(webView)

        webView.addJavascriptInterface(WebAppInterface(this, webView), "Android")
        webView.loadUrl("https://seerinfo.yuyuqaq.cn/")
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

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (binding.swipeRefreshLayout.isRefreshing) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
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

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        try {
            binding.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
        } catch (_: Exception) {}

        super.onDestroy()
    }
}