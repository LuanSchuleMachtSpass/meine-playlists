package de.meineplaylists

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/** Zeigt die offizielle Spotify-Anmeldeseite und fängt die Weiterleitung mit dem Code ab. */
class LoginActivity : Activity() {
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.fitsSystemWindows = true
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                intercept(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (intercept(Uri.parse(url))) view.stopLoading()
            }
        }
        setContentView(web)
        web.loadUrl(Auth.buildAuthUrl(this))
    }

    private fun intercept(uri: Uri): Boolean {
        if (!uri.toString().startsWith(Auth.REDIRECT_URI)) return false
        if (handled) return true
        handled = true
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        when {
            error != null -> fail(if (error == "access_denied") "Anmeldung abgebrochen." else "Fehler: $error")
            code == null || uri.getQueryParameter("state") != Auth.expectedState(this) -> fail("Ungültige Antwort von Spotify.")
            else -> bg {
                try {
                    Auth.exchangeCode(this, code)
                    ui { setResult(RESULT_OK); finish() }
                } catch (e: Exception) {
                    ui { fail(errorText(e)) }
                }
            }
        }
        return true
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }
}
