package com.reelbrain

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#05080F")
        window.statusBarColor = bg
        window.navigationBarColor = bg

        web = WebView(this)
        web.setBackgroundColor(bg)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false      // file:///android_asset still works
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith("file:///android_asset/")
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.evaluateJavascript("window.refresh && window.refresh()", null)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    private fun serviceEnabled(): Boolean {
        if (ReelCounterService.running) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val name = ReelCounterService::class.java
        return s.contains("$packageName/${name.name}") || s.contains("$packageName/.${name.simpleName}")
    }

    private inner class Bridge {
        @JavascriptInterface fun stats(): String = Store.snapshot(this@MainActivity, serviceEnabled())
        @JavascriptInterface fun setLimit(n: Int) = Store.setLimit(this@MainActivity, n)
        @JavascriptInterface fun setCompat(b: Boolean) = Store.setCompat(this@MainActivity, b)
        @JavascriptInterface fun setOverlay(n: Int) = Store.setOverlay(this@MainActivity, n)
        @JavascriptInterface fun setHaptic(b: Boolean) = Store.setHaptic(this@MainActivity, b)
        @JavascriptInterface fun resetToday() = Store.resetToday(this@MainActivity)

        @JavascriptInterface fun openSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface fun openAppInfo() = runOnUiThread {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }
}
