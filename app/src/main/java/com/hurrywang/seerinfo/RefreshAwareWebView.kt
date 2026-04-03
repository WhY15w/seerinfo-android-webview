package com.hurrywang.seerinfo

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

class RefreshAwareWebView : WebView {

    interface RefreshStateListener {
        fun refreshState(canRefresh: Boolean)
    }

    private var refreshStateListener: RefreshStateListener? = null
    private var lastRefreshState: Boolean? = null

    fun setRefreshStateListener(refreshStateListener: RefreshStateListener?) {
        this.refreshStateListener = refreshStateListener
        post { dispatchRefreshState(canRefresh()) }
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(
        context,
        attrs,
        defStyleAttr,
        defStyleRes,
    )

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY) {
            dispatchRefreshState(canRefresh())
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> dispatchRefreshState(false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dispatchRefreshState(canRefresh())
        }
        return super.onTouchEvent(event)
    }

    private fun canRefresh(): Boolean {
        return !canScrollVertically(-1)
    }

    private fun dispatchRefreshState(canRefresh: Boolean) {
        if (lastRefreshState == canRefresh) return
        lastRefreshState = canRefresh
        refreshStateListener?.refreshState(canRefresh)
    }
}