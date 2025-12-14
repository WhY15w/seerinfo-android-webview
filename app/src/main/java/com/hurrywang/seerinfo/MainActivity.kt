package com.hurrywang.seerinfo

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
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

    // 复用 PopupMenu，避免每次点击都 inflate 导致卡顿
    private var actionsMenu: PopupMenu? = null
    private var lastMenuShowUptimeMs: Long = 0L

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

        // 确保 WebView 可触发长按（部分 ROM/页面可能默认不触发）
        webView.isLongClickable = true
        webView.isHapticFeedbackEnabled = true
        webView.setOnCreateContextMenuListener { _, _, _ -> }

        fun updateFabEnabledState() {
            binding.fabActions.isEnabled = true
        }

        // 预创建并缓存菜单（第一次点击更快）
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
                        // 清除 WebView 缓存/历史
                        webView.clearCache(true)
                        webView.clearHistory()
                        webView.clearFormData()
                        Toast.makeText(this@MainActivity, "缓存已清除", Toast.LENGTH_SHORT).show()
                        true
                    }

                    R.id.action_about -> {
                        AlertDialog.Builder(this@MainActivity)
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
        }

        binding.fabActions.setOnClickListener {
            // 防抖：避免连点导致看起来“慢/卡”
            val now = SystemClock.uptimeMillis()
            if (now - lastMenuShowUptimeMs < 250) return@setOnClickListener
            lastMenuShowUptimeMs = now

            // 每次显示前更新可用状态
            actionsMenu?.menu?.findItem(R.id.action_back)?.isEnabled = webView.canGoBack()
            actionsMenu?.menu?.findItem(R.id.action_forward)?.isEnabled = webView.canGoForward()

            actionsMenu?.show()
        }

        // 长按图片保存
        webView.setOnLongClickListener { v ->
            val result = (v as WebView).hitTestResult
            val url = result.extra

            val isImage = result.type == WebView.HitTestResult.IMAGE_TYPE ||
                result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE

            if (!isImage || url.isNullOrBlank()) return@setOnLongClickListener false

            AlertDialog.Builder(this)
                .setTitle("保存图片")
                .setMessage("是否下载并保存该图片？")
                .setPositiveButton("保存") { _, _ ->
                    downloadImage(url)
                }
                .setNegativeButton("取消", null)
                .show()

            true
        }

        // 注册下载广播接收器
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1L
                if (id != downloadId || id < 0) return

                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val c: Cursor? = try {
                    dm.query(query)
                } catch (_: Exception) {
                    null
                }

                c.use {
                    if (it == null || !it.moveToFirst()) {
                        Toast.makeText(context, "下载状态未知", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Toast.makeText(context, "图片下载完成（下载目录）", Toast.LENGTH_SHORT).show()
                    } else {
                        val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Toast.makeText(context, "下载失败，原因码：$reason", Toast.LENGTH_SHORT).show()
                    }
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

            val filename = URLUtil.guessFileName(url, null, null)
            request.setTitle(filename)
            request.setDescription(url)

            // 让系统展示下载通知，用户也能看到是否真的开始下载/是否失败
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            // 保存到「下载」目录（比 Pictures 更符合下载行为，也更容易被用户找到）
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)

            Toast.makeText(this, "开始下载…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        actionsMenu = null

        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {}
    }
}