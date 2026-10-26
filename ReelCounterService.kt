package com.reelbrain

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Counts swipes through Instagram Reels and YouTube Shorts and shows a floating,
 * animated Reel Brain window on top of them.
 *
 * It never reads text on screen. It only checks (a) which app is open, (b) whether the
 * Reels / Shorts pager view exists on screen (by view id), and (c) scroll events.
 *
 * If an app update renames its view ids and counting stops, edit IG_IDS / YT_IDS below,
 * or switch on "Compatibility mode" in Settings.
 */
class ReelCounterService : AccessibilityService() {

    companion object {
        @Volatile
        var running = false

        private const val IG = "com.instagram.android"
        private const val YT = "com.google.android.youtube"

        private val IG_IDS = listOf(
            "$IG:id/clips_viewer_view_pager",
            "$IG:id/clips_viewer_pager",
            "$IG:id/clips_viewer_container"
        )
        private val YT_IDS = listOf(
            "$YT:id/reel_recycler",
            "$YT:id/reel_player_page_container",
            "$YT:id/reel_watch_fragment_root"
        )
        private const val DEBOUNCE_MS = 900L
    }

    private val lastIdx = HashMap<String, Int>()
    private var lastCountAt = 0L
    private var cacheAt = 0L
    private var cacheApp = ""
    private var cacheVal = false

    /* ---------- floating window ---------- */
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var webReady = false
    private var shownMode = -1

    private val tick = object : Runnable {
        override fun run() {
            if (web == null) return
            push()
            handler.postDelayed(this, 1000)
        }
    }

    private fun push() {
        val w = web ?: return
        if (!webReady) return
        w.evaluateJavascript("window.push&&window.push(" + Store.snapshot(this, true) + ")", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showOverlay() {
        val mode = Store.overlay(this)
        if (mode == 0) { hideOverlay(); return }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val w = ((if (mode == 2) 164 else 92) * d).toInt()
        val h = (60 * d).toInt()

        val existing = web
        if (existing != null) {
            if (shownMode != mode) {
                val lp = existing.layoutParams as WindowManager.LayoutParams
                lp.width = w; lp.height = h
                try { wm.updateViewLayout(existing, lp) } catch (e: Exception) {}
                shownMode = mode
                push()
            }
            return
        }

        val v = WebView(applicationContext)
        v.setBackgroundColor(Color.TRANSPARENT)
        with(v.settings) {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        v.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webReady = true
                push()
            }
        }
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = 0
        lp.y = safeTop(wm) + (6 * d).toInt()
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            return
        }
        web = v
        shownMode = mode
        webReady = false
        v.loadUrl("file:///android_asset/widget.html")
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    /** Pixels from the top of the screen to just below the status bar / camera cut-out. */
    private fun safeTop(wm: WindowManager): Int {
        var top = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val ins = wm.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            top = ins.top
        }
        if (top <= 0) {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) top = resources.getDimensionPixelSize(id)
        }
        return top
    }

    private fun hideOverlay() {
        handler.removeCallbacks(tick)
        val v = web ?: return
        web = null
        webReady = false
        shownMode = -1
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
        v.destroy()
    }

    /* ---------- service lifecycle ---------- */
    override fun onServiceConnected() { running = true }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        hideOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        hideOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /* ---------- events ---------- */
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        val app = when (pkg) {
            IG -> "ig"
            YT -> "yt"
            else -> null
        }

        if (app == null) {
            // Another app or system screen came to the front: hide the floating window.
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName) hideOverlay()
            return
        }

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastIdx.remove(app)
            cacheAt = 0L
            if (reelsVisible(app)) showOverlay() else hideOverlay()
            return
        }
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val cls = e.className?.toString() ?: ""
        val inReels = reelsVisible(app)
        val compat = Store.compat(this) && cls.contains("ViewPager")
        if (!inReels && !compat) {
            hideOverlay() // scrolled a normal feed, not Reels
            return
        }

        // Ignore horizontal scrolls (carousels, tabs).
        if (Build.VERSION.SDK_INT >= 28 && e.scrollDeltaX != 0 && e.scrollDeltaY == 0) return

        val now = SystemClock.uptimeMillis()
        if (now - lastCountAt < 350) return

        val idx = e.fromIndex
        if (idx >= 0) {
            if (lastIdx[app] == idx) return
            lastIdx[app] = idx
        } else if (now - lastCountAt < DEBOUNCE_MS) {
            return
        }

        lastCountAt = now
        val total = Store.add(this, app)
        showOverlay()
        push()
        alertIfNeeded(total)
    }

    @Suppress("DEPRECATION")
    private fun reelsVisible(app: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (app == cacheApp && now - cacheAt < 1500) return cacheVal

        var found = false
        val root = rootInActiveWindow
        if (root != null) {
            for (id in if (app == "ig") IG_IDS else YT_IDS) {
                val list = root.findAccessibilityNodeInfosByViewId(id)
                if (!list.isNullOrEmpty()) {
                    found = true
                    if (Build.VERSION.SDK_INT < 33) list.forEach { it.recycle() }
                    break
                }
            }
            if (Build.VERSION.SDK_INT < 33) root.recycle()
        }
        cacheApp = app
        cacheAt = now
        cacheVal = found
        return found
    }

    @Suppress("DEPRECATION")
    private fun alertIfNeeded(total: Int) {
        val lim = Store.limit(this)
        if (!Store.haptic(this) || total < lim || (total - lim) % 25 != 0) return
        Toast.makeText(this, "Reel Brain: $total reels today (limit $lim)", Toast.LENGTH_LONG).show()
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
