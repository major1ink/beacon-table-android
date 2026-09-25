package ru.beacontable

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

private const val ASSETS = "https://appassets.androidplatform.net"
private const val LAUNCHER = "$ASSETS/assets/launcher.html"

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var origin: String? = null
    private var clearHistory = false
    private var crashed = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val scanner by lazy { LanScanner(this) }

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(it.resultCode, it.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        web = WebView(this)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        val root = FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.bg))
        root.addView(web)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            userAgentString = "$userAgentString BeaconTableApp/${BuildConfig.VERSION_NAME}"
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, userAgent, disposition, mimeType, _ ->
            download(url, userAgent, disposition, mimeType)
        }

        // Мост только для экрана запуска: страницы серверов его не видят.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "beaconApp", setOf(ASSETS)) { _, message, _, isMainFrame, reply ->
                if (!isMainFrame || message.data != "scan") return@addWebMessageListener
                scanner.scan(
                    onFound = { url, version ->
                        reply.postMessage(JSONObject().put("found", url).put("version", version).toString())
                    },
                    onDone = { hasNetwork ->
                        reply.postMessage(JSONObject().put("done", true).put("network", hasNetwork).toString())
                    },
                )
            }
        }

        onBackPressedDispatcher.addCallback(this) { back() }

        origin = savedInstanceState?.getString("origin")
        when {
            savedInstanceState == null -> showLauncher()
            savedInstanceState.getBoolean("crashed") && origin != null -> web.loadUrl(origin!!)
            web.restoreState(savedInstanceState) == null -> showLauncher()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!crashed) web.saveState(outState)
        outState.putString("origin", origin)
        outState.putBoolean("crashed", crashed)
    }

    override fun onResume() {
        super.onResume()
        if (!crashed) web.onResume()
    }

    override fun onPause() {
        if (!crashed) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        scanner.stop()
        if (!crashed) web.destroy()
        super.onDestroy()
    }

    private fun showLauncher(error: String? = null) {
        val url = LAUNCHER.toUri().buildUpon()
        if (error != null) url.appendQueryParameter("error", error)
        origin?.let { url.appendQueryParameter("server", it) }
        clearHistory = true
        web.loadUrl(url.build().toString())
    }

    private fun back() {
        if (isLauncher(web.url)) {
            finish()
            return
        }
        val history = web.copyBackForwardList()
        val prev = history.getItemAtIndex(history.currentIndex - 1)
        if (prev == null || isLauncher(prev.url)) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.leave_title)
                .setMessage(R.string.leave_message)
                .setPositiveButton(R.string.leave_yes) { _, _ -> showLauncher() }
                .setNegativeButton(R.string.leave_no, null)
                .show()
        } else {
            web.goBack()
        }
    }

    private fun download(url: String, userAgent: String, disposition: String?, mimeType: String?) {
        val name = URLUtil.guessFileName(url, disposition, mimeType)
        val request = DownloadManager.Request(url.toUri())
            .addRequestHeader("User-Agent", userAgent)
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        getSystemService<DownloadManager>()?.enqueue(request)
        Toast.makeText(this, getString(R.string.download_started, name), Toast.LENGTH_SHORT).show()
    }

    private fun openExternally(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
            assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (isLauncher(url.toString())) return false
            // С экрана запуска уходим на выбранный сервер; редирект (http → https
            // у прокси) тоже считаем им.
            if (isLauncher(view.url) || request.isRedirect || originOf(url) == origin) {
                origin = originOf(url)
                return false
            }
            openExternally(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (isLauncher(url)) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                scanner.stop()
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (clearHistory && isLauncher(url)) {
                clearHistory = false
                view.clearHistory()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || isLauncher(request.url.toString())) return
            showLauncher(getString(R.string.server_unreachable, request.url.authority))
        }

        // Рендер упал (чаще всего нехватка памяти на большой карте) — открываем
        // стол заново, а не закрываем приложение.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            crashed = true
            (view.parent as ViewGroup).removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                pickFiles.launch(params.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }
}

private fun isLauncher(url: String?) = url?.startsWith(LAUNCHER) == true

private fun originOf(url: Uri) = "${url.scheme}://${url.authority}"
