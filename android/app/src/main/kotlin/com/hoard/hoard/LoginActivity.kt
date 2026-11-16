package com.hoard.hoard

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * In-app login. Opens the platform's real login page in a WebView (mobile user agent). As soon as
 * a session cookie appears (or the user taps Done), the cookies are exported, encrypted with the
 * Android Keystore and used by every later download. Optional and per platform.
 */
class LoginActivity : Activity() {

    private lateinit var platform: SocialPlatform
    private lateinit var web: WebView
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = SocialPlatform.fromId(intent.getStringExtra(EXTRA_PLATFORM))
            ?: SocialPlatform.INSTAGRAM

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B1033"))
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
        }
        val title = TextView(this).apply {
            text = "Log in to ${platform.label}"
            setTextColor(Color.WHITE)
            textSize = 17f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        bar.addView(Button(this).apply {
            text = "Done"
            setOnClickListener {
                if (!finishLoggedIn()) {
                    Toast.makeText(
                        this@LoginActivity, "Not logged in yet", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
        root.addView(bar)

        web = WebView(this)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = CookieStore.USER_AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (CookieStore.webViewHasSession(platform)) finishLoggedIn()
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (savedInstanceState == null) web.loadUrl(platform.loginUrl) else web.restoreState(savedInstanceState)
    }

    private fun finishLoggedIn(): Boolean {
        if (finished) return true
        if (!CookieStore.saveFromWebView(this, platform)) return false
        finished = true
        Toast.makeText(this, "Logged in to ${platform.label}", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"
    }
}
