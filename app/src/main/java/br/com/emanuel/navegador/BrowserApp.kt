package br.com.emanuel.navegador

import android.app.Application
import android.webkit.WebView

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Remote pages must never be remotely debuggable, including in development APKs.
        WebView.setWebContentsDebuggingEnabled(false)
    }
}
