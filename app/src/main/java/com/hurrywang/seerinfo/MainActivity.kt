package com.hurrywang.seerinfo

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.hurrywang.seerinfo.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageDownloader: ImageDownloader

    private var actionsMenu: PopupMenu? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        webView.viewTreeObserver.addOnScrollChangedListener {
            binding.swipeRefreshLayout.isEnabled = webView.scrollY == 0
        }
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
        webView.webViewClient = object : WebViewClient() {
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

        actionsMenu = null
        super.onDestroy()
    }
}