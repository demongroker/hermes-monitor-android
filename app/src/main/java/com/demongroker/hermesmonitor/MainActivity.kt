package com.demongroker.hermesmonitor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebView shell around the Mission Control dashboard.
 * First run: base URL + token setup (POST /api/login). The token is stored in
 * EncryptedSharedPreferences and never hardcoded. The session cookie returned
 * by /api/login is injected into CookieManager so the dashboard's own fetches
 * authenticate; API polling uses the Bearer header.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: SecureStore
    private lateinit var webView: WebView
    private lateinit var setupView: View
    private lateinit var errorView: View
    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var cancelSetupButton: Button
    private lateinit var setupStatus: TextView
    private lateinit var errorMessage: TextView
    private lateinit var settingsButton: ImageButton

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge; we pad the root with the real insets below.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        store = SecureStore(this)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        webView = findViewById(R.id.webview)
        setupView = findViewById(R.id.setupView)
        errorView = findViewById(R.id.errorView)
        baseUrlInput = findViewById(R.id.baseUrlInput)
        tokenInput = findViewById(R.id.tokenInput)
        connectButton = findViewById(R.id.connectButton)
        cancelSetupButton = findViewById(R.id.cancelSetupButton)
        setupStatus = findViewById(R.id.setupStatus)
        errorMessage = findViewById(R.id.errorMessage)
        settingsButton = findViewById(R.id.settingsButton)

        configureWebView()

        settingsButton.setOnClickListener { showSetup(prefill = true) }
        cancelSetupButton.setOnClickListener { showDashboard(load = false) }
        connectButton.setOnClickListener { attemptConnect() }
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            if (webView.url != null) webView.reload() else webView.loadUrl(store.baseUrl)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    setupView.visibility == View.VISIBLE && store.isConfigured ->
                        showDashboard(load = false)
                    webView.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (store.isConfigured) {
            showDashboard(load = true)
        } else {
            showSetup(prefill = false)
        }
    }

    // ---------------------------------------------------------------- WebView

    private fun configureWebView() {
        // Navy before any load: no white flash.
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.navy))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    openExternally(uri)
                    return true
                }
                val baseHost = Uri.parse(store.baseUrl).host
                // External links leave the app; same-host navigation stays inside.
                if (request.isForMainFrame && uri.host != null &&
                    !uri.host.equals(baseHost, ignoreCase = true)
                ) {
                    openExternally(uri)
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showError(
                        getString(R.string.error_load_failed) +
                            "\n${error.description} (${error.errorCode})"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    showError(getString(R.string.error_http, errorResponse.statusCode))
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // Never proceed past an SSL error.
                handler.cancel()
                showError(getString(R.string.error_ssl))
            }
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectCookies(baseUrl: String, cookies: List<String>) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            cookies.forEach { setCookie(baseUrl, it) }
            flush()
        }
    }

    // ------------------------------------------------------------------ Views

    private fun showSetup(prefill: Boolean) {
        webView.visibility = View.GONE
        errorView.visibility = View.GONE
        setupView.visibility = View.VISIBLE
        settingsButton.visibility = View.GONE
        if (prefill) {
            baseUrlInput.setText(store.baseUrl)
            tokenInput.setText(store.token)
            cancelSetupButton.visibility = View.VISIBLE
        } else {
            baseUrlInput.setText("")
            tokenInput.setText("")
            cancelSetupButton.visibility = View.GONE
        }
        setupStatus.text = ""
    }

    private fun showDashboard(load: Boolean) {
        setupView.visibility = View.GONE
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        settingsButton.visibility = View.VISIBLE
        triggerImmediateAlertCheck()
        if (load) {
            lifecycleScope.launch {
                // Refresh the session cookie with the stored token (best effort),
                // then load the dashboard. If offline, the load error view handles it.
                val result = withContext(Dispatchers.IO) {
                    Api.login(store.baseUrl, store.token)
                }
                if (result.ok) injectCookies(store.baseUrl, result.cookies)
                webView.loadUrl(store.baseUrl)
            }
        }
    }

    private fun showError(message: String) {
        setupView.visibility = View.GONE
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorMessage.text = message
    }

    private fun attemptConnect() {
        val url = baseUrlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        val uri = Uri.parse(url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            setSetupStatus(getString(R.string.error_bad_url), error = true)
            return
        }
        if (uri.host.isNullOrBlank()) {
            setSetupStatus(getString(R.string.error_bad_url), error = true)
            return
        }
        if (token.isEmpty()) {
            setSetupStatus(getString(R.string.error_no_token), error = true)
            return
        }
        connectButton.isEnabled = false
        setSetupStatus(getString(R.string.connecting), error = false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { Api.login(url, token) }
            connectButton.isEnabled = true
            if (result.ok) {
                store.save(url, token)
                injectCookies(url, result.cookies)
                setSetupStatus(getString(R.string.connected), error = false)
                showDashboard(load = true)
            } else {
                setSetupStatus(result.message, error = true)
            }
        }
    }

    private fun setSetupStatus(message: String, error: Boolean) {
        setupStatus.text = message
        setupStatus.setTextColor(
            ContextCompat.getColor(this, if (error) R.color.danger else R.color.ok)
        )
    }

    private fun triggerImmediateAlertCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<AlertWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            HermesMonitorApp.UNIQUE_IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    // -------------------------------------------------------------- Lifecycle

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
