package com.forai.codepad

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Shows rendered HTML / Markdown in a sandboxed WebView (no network, no file access). */
class PreviewActivity : AppCompatActivity() {

    companion object {
        var html: String = ""
        var title: String = "Preview"
    }

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setTitle(title)
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.blockNetworkLoads = true
        w.settings.builtInZoomControls = true
        w.settings.displayZoomControls = false
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
        web = w
        setContentView(w)
        ViewCompat.setOnApplyWindowInsetsListener(w) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        w.loadDataWithBaseURL("https://preview.codepad.local/", html, "text/html", "utf-8", null)
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
