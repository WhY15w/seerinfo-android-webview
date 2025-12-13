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
import android.webkit.WebSettings

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val webView = binding.webView

        val appName = getString(R.string.app_name)
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
            // appName/versionName (packageName; vc=versionCode)
            append(appName)
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
                        val versionText = buildString {
                            append(versionName.ifBlank { getString(R.string.about_version_unknown) })
                            if (versionCode >= 0) append(" (").append(getString(R.string.about_version_code_prefix)).append(versionCode).append(")")
                        }

                        val extraText = buildString {
                            append(getString(R.string.about_package_prefix)).append(pkgName)
                            if (versionCode >= 0) append("\n").append(getString(R.string.about_version_code_prefix)).append(versionCode)
                        }

                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.about_title))
                            .setMessage(
                                getString(
                                    R.string.about_message,
                                    getString(R.string.about_author),
                                    versionText,
                                    getString(R.string.about_custom)
                                ) + "\n\n" + extraText
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
