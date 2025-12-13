package com.hurrywang.seerinfo

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.hurrywang.seerinfo.databinding.ActivityMainBinding
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
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
                        val pm = packageManager
                        val pkg = packageName
                        val versionName = try {
                            pm.getPackageInfo(pkg, 0).versionName ?: ""
                        } catch (_: PackageManager.NameNotFoundException) {
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
                        true
                    }

                    else -> false
                }
            }
            popup.show()
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
}
