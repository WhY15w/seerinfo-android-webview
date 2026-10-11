package com.hurrywang.seerinfo

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class SeerinfoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        prewarmWebView()
    }

    /**
     * WebView Provider（Chromium）首次初始化开销可达数百毫秒，如果在 Activity 的主线程上
     * 触发会直接拉长冷启动白屏时间。这里在 App 启动时就于后台线程预热 Provider 与
     * Cookie 存储，并提前对站点做一次 DNS/TLS 握手，让首屏加载更快。
     */
    private fun prewarmWebView() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WebSettings.getDefaultUserAgent(this@SeerinfoApp)
            } catch (_: Throwable) {
            }
            preconnectMainSite()

            Handler(Looper.getMainLooper()).post {
                try {
                    // 触发 Cookie 存储初始化，避免首次访问时的同步加载
                    CookieManager.getInstance().setAcceptCookie(true)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun preconnectMainSite() {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(MainActivity.MAIN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            connection.connect()
        } catch (_: Exception) {
            // 预热失败无感知即可，正常加载流程不受影响
        } finally {
            connection?.disconnect()
        }
    }
}
