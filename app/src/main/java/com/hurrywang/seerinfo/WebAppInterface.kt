package com.hurrywang.seerinfo

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class WebAppInterface(
    private val context: Context,
    private val webView: WebView
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun clearAllCache() {
        mainHandler.post {
            try {
                webView.clearCache(true)
                webView.clearFormData()
                webView.clearHistory()

                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }

                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                showToast("清除失败: ${e.message ?: e.javaClass.simpleName}")
                return@post
            }

            // 递归删除缓存目录耗时明显，放到 IO 线程避免卡住主线程
            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching {
                    context.cacheDir.deleteRecursively()

                    try {
                        context.getDatabasePath("webview.db")?.parentFile?.listFiles()?.forEach {
                            if (it.name.startsWith("webview")) it.deleteRecursively()
                        }
                    } catch (_: Exception) {}
                }
                mainHandler.post {
                    if (result.isSuccess) showToast("缓存已彻底清除")
                    else showToast("清除失败")
                }
            }
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun share(dataJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(dataJson)
                val title = json.optString("title")
                val url = json.optString("url")

                if (url.isBlank()) {
                    showToast("没有可分享的链接")
                    return@post
                }

                val body = listOf(title, url).filter { it.isNotBlank() }.joinToString("\n")

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                val chooser = Intent.createChooser(sendIntent, title.ifBlank { "分享" })
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(chooser)
            } catch (e: Exception) {
                showToast("分享失败: ${e.message ?: e.javaClass.simpleName}")
            }
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
