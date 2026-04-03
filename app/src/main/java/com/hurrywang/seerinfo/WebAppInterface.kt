package com.hurrywang.seerinfo

import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WebAppInterface(
    private val context: Context,
    private val webView: WebView
) {
    @JavascriptInterface
    fun clearAllCache() {
        // 使用协程或主线程 Handler 确保在主线程执行 WebView 操作
        CoroutineScope(Dispatchers.Main).launch {
            try {
                webView.clearCache(true)
                webView.clearFormData()
                webView.clearHistory()

                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }

                WebStorage.getInstance().deleteAllData()
                context.cacheDir?.deleteRecursively()

                try {
                    context.getDatabasePath("webview.db")?.parentFile?.listFiles()?.forEach {
                        if (it.name.startsWith("webview")) it.deleteRecursively()
                    }
                } catch (_: Exception) {}

                showToast("缓存已彻底清除")
            } catch (e: Exception) {
                showToast("清除失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}