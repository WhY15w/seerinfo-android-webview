package com.hurrywang.seerinfo

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.hurrywang.seerinfo.databinding.ActivityMainBinding
import android.webkit.WebSettings

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var downloadId: Long = -1
    private lateinit var downloadReceiver: BroadcastReceiver

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val webView = binding.webView

        val appUaName = "seerinfo-android"
        val pkgName = packageName
        val (versionName, versionCode) = try {
            val pi = packageManager.getPackageInfo(pkgName, 0)
            val vc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
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

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            userAgentString = "$baseUa $customUaSuffix"
        }

        fun updateFabEnabledState() {
            binding.fabActions.isEnabled = true
        }


        binding.fabActions.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.webview_actions_menu, popup.menu)

            popup.menu.findItem(R.id.action_back)?.isEnabled = webView.canGoBack()
            popup.menu.findItem(R.id.action_forward)?.isEnabled = webView.canGoForward()

            popup.setOnMenuItemClickListener { item ->
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
                    R.id.action_about -> {
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
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // 长按图片保存
        webView.setOnLongClickListener { v ->
            val result = (v as WebView).hitTestResult
            if (result.type == WebView.HitTestResult.IMAGE_TYPE ||
                result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val url = result.extra
                if (url != null) {
                    AlertDialog.Builder(this)
                        .setTitle("保存图片")
                        .setMessage("是否将图片保存到相册？")
                        .setPositiveButton("保存") { _, _ ->
                            downloadImage(url)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@setOnLongClickListener true
                }
            }
            false
        }

        // 注册下载广播接收器
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateFabEnabledState()
            }
        }

        webView.loadUrl("https://seerinfo.yuyuqaq.cn/")

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

        updateFabEnabledState()
    }

    // 下载方法
    private fun downloadImage(url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val filename = URLUtil.guessFileName(url, null, "image/jpeg")
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, filename)

    
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {}
    }
}