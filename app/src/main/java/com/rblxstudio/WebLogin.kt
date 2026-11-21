package com.rblxstudio

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

// Login pelo site oficial do Roblox, dentro do app.
// A senha é digitada na página do próprio Roblox e nunca passa pelo nosso código;
// depois do login o app só guarda a sessão, para baixar roupas e acessórios.
class WebLogin(private val act: Activity, private val done: (Boolean, String) -> Unit) {
    private val dialog = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private var web: WebView? = null
    private var finished = false

    private fun robloxHost(h: String?) = h != null && (h == "roblox.com" || h.endsWith(".roblox.com"))

    private fun checkSession() {
        if (finished) return
        val all = CookieManager.getInstance().getCookie("https://www.roblox.com") ?: return
        for (part in all.split(";")) {
            val p = part.trim()
            if (p.startsWith(".ROBLOSECURITY=")) {
                val v = p.removePrefix(".ROBLOSECURITY=")
                if (v.length < 20) continue
                finished = true
                RobloxAuth.sessionLogin(act, v) { ok, msg ->
                    close()
                    done(ok, msg)
                }
                return
            }
        }
    }

    private fun close() {
        try {
            web?.stopLoading()
            web?.destroy()
        } catch (e: Exception) {
        }
        web = null
        if (dialog.isShowing) dialog.dismiss()
    }

    fun show() {
        val d = act.resources.displayMetrics.density
        val root = FrameLayout(act)
        root.setBackgroundColor(Color.BLACK)
        val w = WebView(act)
        web = w
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (!r.isForMainFrame) return false
                return !robloxHost(r.url.host)   // só navega dentro do roblox.com
            }

            override fun onPageFinished(v: WebView, url: String) {
                checkSession()
            }
        }
        root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val x = TextView(act)
        x.text = "Fechar"
        x.textSize = 13f
        x.setTextColor(Color.WHITE)
        x.typeface = Typeface.DEFAULT_BOLD
        x.setBackgroundColor(0xCC000000.toInt())
        x.setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        x.setOnClickListener {
            close()
            if (!finished) done(false, "Login cancelado")
        }
        val xl = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        xl.setMargins(0, (8 * d).toInt(), (8 * d).toInt(), 0)
        root.addView(x, xl)

        dialog.setContentView(root)
        dialog.setOnKeyListener { _, key, ev ->
            if (key == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                val ww = web
                if (ww != null && ww.canGoBack()) {
                    ww.goBack()
                } else {
                    close()
                    if (!finished) done(false, "Login cancelado")
                }
                true
            } else {
                false
            }
        }
        dialog.show()
        w.loadUrl("https://www.roblox.com/login")
    }
}
