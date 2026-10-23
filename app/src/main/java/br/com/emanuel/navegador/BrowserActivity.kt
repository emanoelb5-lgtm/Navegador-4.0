package br.com.emanuel.navegador

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class BrowserActivity : AppCompatActivity() {
    private data class Tab(
        val id: Long, var url: String? = null, var title: String = "Nova aba",
        val private: Boolean = false, var desktop: Boolean = false,
        var home: Boolean = url == null, var web: WebView? = null,
        var state: Bundle? = null, var used: Long = System.currentTimeMillis(),
        var protectedInMemory: Boolean = true
    )
    private val tabs = mutableListOf<Tab>()
    private var activeId = 0L
    private var nextId = System.currentTimeMillis()
    private lateinit var store: BrowserStore
    private lateinit var shell: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var address: TextView
    private lateinit var addressIcon: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var backButton: View
    private lateinit var forwardButton: View
    private lateinit var tabsLabel: TextView
    private var sheet: BottomSheetDialog? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var privateProfile: String? = null
    private var privateStorage: WebStorage? = null
    private var previousNight = false
    private var engineAvailable = true
    private var clearing = false
    private var upload: ValueCallback<Array<Uri>>? = null
    private var uploadTab: Long? = null
    private var mediaRequest: PermissionRequest? = null
    private var mediaTab: Long? = null
    private var geoRequest: Pair<String, GeolocationPermissions.Callback>? = null
    private var geoTab: Long? = null
    private val current: Tab get() = tabs.first { it.id == activeId }
    private val night get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val bg get() = Color.parseColor(if (night) "#111918" else "#F7F8F8")
    private val paper get() = Color.parseColor(if (night) "#192321" else "#FFFFFF")
    private val ink get() = Color.parseColor(if (night) "#EDF5F1" else "#172E28")
    private val muted get() = Color.parseColor(if (night) "#A4B8B0" else "#5D706A")
    private val soft get() = Color.parseColor(if (night) "#23342D" else "#EEF3F0")
    private val accent get() = Color.parseColor(if (night) "#8BE1BD" else "#087460")
    private val tint get() = Color.parseColor(if (night) "#203F31" else "#E4F4ED")

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = upload
        val tabId = uploadTab
        upload = null; uploadTab = null
        val values = if (result.resultCode == RESULT_OK && tabId == activeId) {
            val data = result.data
            val clip = data?.clipData
            val uris = if (clip != null) (0 until minOf(clip.itemCount, 20)).map { clip.getItemAt(it).uri }
                       else listOfNotNull(data?.data)
            uris.filter { it.scheme == "content" }.toTypedArray().takeIf { it.isNotEmpty() }
        } else null
        callback?.onReceiveValue(values)
    }
    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = mediaRequest ?: return@registerForActivityResult
        val allowed = mediaTab == activeId && UrlPolicy.origin(current.url) == UrlPolicy.origin(request.origin.toString())
        val resources = request.resources.filter { r ->
            when (r) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> granted(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> granted(Manifest.permission.RECORD_AUDIO)
                else -> false
            }
        }.toTypedArray()
        if (allowed && resources.isNotEmpty()) request.grant(resources) else request.deny()
        mediaRequest = null; mediaTab = null
    }
    private val geoPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = geoRequest ?: return@registerForActivityResult
        val allowed = geoTab == activeId && UrlPolicy.origin(current.url) == UrlPolicy.origin(request.first) &&
            (granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION))
        request.second.invoke(request.first, allowed, false)
        geoRequest = null; geoTab = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrowserStore(this)
        previousNight = night
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                val profiles = ProfileStore.getInstance()
                profiles.allProfileNames.filter { it.startsWith("private_") }.forEach { name -> runCatching { profiles.deleteProfile(name) } }
            }
        }.onFailure { engineAvailable = false }
        tabs += store.tabs().map { Tab(it.id, it.url, it.title, desktop = it.desktop) }
        if (tabs.isEmpty()) tabs += Tab(nextId++)
        nextId = maxOf(nextId, (tabs.maxOfOrNull { it.id } ?: 0) + 1)
        activeId = store.prefs.getLong("active", tabs.first().id).takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
        buildShell()
        val incoming = intent?.dataString?.let { UrlPolicy.canonicalWebUrl(it) }
        if (incoming != null) newTab(incoming) else showCurrent()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullView != null -> leaveFullscreen()
                    sheet?.isShowing == true -> sheet?.dismiss()
                    current.home && current.web != null -> { current.home = false; showCurrent() }
                    current.web?.canGoBack() == true && !current.home -> current.web?.goBack()
                    !current.home -> goHome()
                    current.private -> closeTab(current)
                    else -> finish()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        intent.dataString?.let { UrlPolicy.canonicalWebUrl(it) }?.let { newTab(it) }
    }

    private fun buildShell() {
        if (::pageHost.isInitialized) pageHost.removeAllViews()
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !night; isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        pageHost = FrameLayout(this)
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        val chrome = column().apply { setBackgroundColor(paper); setPadding(dp(12), dp(2), dp(12), dp(4)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(accent); visibility = View.INVISIBLE
        }
        chrome.addView(progress, LinearLayout.LayoutParams(-1, dp(2)).apply { bottomMargin = dp(8) })
        val addressRow = row().apply { background = rounded(soft, 16); minimumHeight = dp(50); setPadding(dp(12), 0, dp(12), 0); contentDescription = "Endereço e pesquisa"; clickable { editAddress() } }
        addressIcon = FrameLayout(this)
        addressRow.addView(addressIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
        address = text("Pesquisar ou digitar endereço", 14, ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        addressRow.addView(address, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(6) })
        addressRow.addView(icon("search", 18, muted), LinearLayout.LayoutParams(dp(18), dp(18)))
        chrome.addView(addressRow)
        val navigation = row().apply { id = R.id.navigation_bar; setPadding(0, dp(6), 0, 0) }
        fun nav(name: String, label: String, action: () -> Unit): View {
            val button = column().apply { gravity = Gravity.CENTER; minimumHeight = dp(58); contentDescription = label; clickable(action) }
            val control = FrameLayout(this)
            control.addView(icon(name, 22, ink), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            if (name == "tabs") {
                control.removeAllViews()
                tabsLabel = text("1", 12, ink).apply { gravity = Gravity.CENTER; background = rounded(Color.TRANSPARENT, 5, ink); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                control.addView(tabsLabel, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            button.addView(control, LinearLayout.LayoutParams(dp(32), dp(27)))
            button.addView(text(label, 11, muted))
            navigation.addView(button, LinearLayout.LayoutParams(0, dp(60), 1f))
            return button
        }
        backButton = nav("back", "Voltar") { if (current.home && current.web != null) { current.home = false; showCurrent() } else current.web?.goBack() }
        forwardButton = nav("forward", "Avançar") { current.web?.goForward() }
        nav("home", "Início") { goHome() }
        nav("tabs", "Abas") { showTabs() }
        nav("menu", "Menu") { showMenu() }
        chrome.addView(navigation)
        shell.addView(chrome)
        setContentView(root)
    }

    private fun showCurrent() {
        pageHost.removeAllViews()
        val tab = current
        tab.used = System.currentTimeMillis()
        if (tab.home) pageHost.addView(homeView(tab.private)) else {
            val web = tab.web ?: createWebView(tab) ?: return
            (web.parent as? ViewGroup)?.removeView(web)
            pageHost.addView(web, FrameLayout.LayoutParams(-1, -1))
            web.onResume()
        }
        updateChrome(); persist()
    }

    private fun homeView(private: Boolean): View {
        val scroll = ScrollView(this).apply { id = R.id.home_screen; isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(24), dp(28), dp(24), dp(32)) }
        val brand = row()
        brand.addView(icon(if (private) "private" else "compass", 32, accent), LinearLayout.LayoutParams(dp(32), dp(32)))
        brand.addView(text("Navegador 4.0", 17, ink, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        content.addView(brand)
        content.addView(text(if (private) "UM ESPAÇO SÓ SEU" else "SEU ESPAÇO NA WEB", 11, accent, true).apply { letterSpacing = .12f }, margins(34, 16))
        content.addView(text(if (private) "Navegue com\nmais privacidade." else "Por onde vamos\ncomeçar?", 34, ink, true).apply { setLineSpacing(0f, 1.03f) })
        content.addView(text(if (private) "As abas privadas usam cookies e dados separados. Ao fechar todas elas, os dados dessa sessão são apagados. Sites e sua rede ainda podem identificar sua conexão." else "Uma página limpa para suas próximas descobertas.", 15, muted), margins(16, 22))
        val search = row().apply { minimumHeight = dp(56); background = rounded(paper, 18); setPadding(dp(16), 0, dp(16), 0); clickable { editAddress() }; contentDescription = "Iniciar pesquisa" }
        search.addView(icon("search", 22, accent), LinearLayout.LayoutParams(dp(22), dp(22)))
        search.addView(text("Pesquisar na internet", 15, muted), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        content.addView(search)
        if (!private) {
            content.addView(text("Seus atalhos", 16, ink, true), margins(30, 10))
            val defaults = listOf(PageRecord("Google", "https://www.google.com"), PageRecord("YouTube", "https://www.youtube.com"), PageRecord("Wikipédia", "https://pt.wikipedia.org"))
            val shortcuts = (store.pages("bookmarks") + defaults).distinctBy { it.url }.take(6)
            shortcuts.chunked(3).forEach { group ->
                val line = row()
                group.forEach { record ->
                    val tile = column().apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(12), dp(4), dp(12)); clickable { navigate(record.url) }; contentDescription = record.title }
                    val symbol = text(record.title.firstOrNull()?.uppercase() ?: "W", 20, accent, true).apply { gravity = Gravity.CENTER; background = rounded(tint, 17) }
                    tile.addView(symbol, LinearLayout.LayoutParams(dp(52), dp(52)))
                    tile.addView(text(record.title, 12, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, margins(8, 0))
                    line.addView(tile, LinearLayout.LayoutParams(0, -2, 1f))
                }
                repeat(3 - group.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                content.addView(line)
            }
        }
        if (!engineAvailable) content.addView(actionRow("warning", "Atualizar o motor de navegação", "O Android System WebView não está disponível.") { updateEngine() })
        scroll.addView(content); return scroll
    }

    private fun goHome() { cancelRequests(); current.web?.onPause(); current.home = true; sheet?.dismiss(); showCurrent() }
    private fun newTab(url: String? = null, private: Boolean = false): Tab? {
        if (clearing) return null
        if (tabs.size >= 30) { toast("Feche uma aba antes de abrir outra. Limite: 30 abas."); return null }
        if (private && !privateSupported()) { dialog("Atualize o motor", "A navegação privada exige isolamento de perfis e exclusão completa dos dados. Atualize o Android System WebView para habilitá-la.").setPositiveButton("Atualizar") { _, _ -> updateEngine() }.show(); return null }
        if (tabs.isNotEmpty()) protectAndPause(current)
        cancelRequests()
        val tab = Tab(nextId++, url, private = private)
        tabs += tab; activeId = tab.id
        sheet?.dismiss(); showCurrent(); trimTabs()
        return tab
    }
    private fun switchTab(tab: Tab) {
        if (clearing || current === tab) { sheet?.dismiss(); return }
        protectAndPause(current); cancelRequests(); activeId = tab.id; sheet?.dismiss(); showCurrent(); trimTabs()
    }
    private fun closeTab(tab: Tab) {
        if (clearing) return
        if (tab.id == activeId) cancelRequests()
        val index = tabs.indexOf(tab)
        val closingStorage = if (tab.private && tabs.count { it.private } == 1) privateStorage else null
        destroy(tab); tabs.remove(tab)
        if (tab.private && tabs.none { it.private }) {
            privateProfile = null; privateStorage = null
            closingStorage?.let { WebStorageCompat.deleteBrowsingData(it) {} }
        }
        if (tabs.isEmpty()) tabs += Tab(nextId++)
        if (tabs.none { it.id == activeId }) activeId = tabs[index.coerceIn(0, tabs.lastIndex)].id
        showCurrent(); showTabs()
    }
    private fun persist() {
        val regular = tabs.filterNot { it.private }
        val active = regular.firstOrNull { it.id == activeId }?.id ?: regular.firstOrNull()?.id ?: 0
        store.saveTabs(regular.map { SavedTab(it.id, it.title.take(300), if (it.home) null else it.url, it.desktop) }, active)
    }
    private fun navigate(value: String) {
        if (clearing) return
        val url = UrlPolicy.canonicalWebUrl(value) ?: run { toast("Esse endereço não pode ser aberto."); return }
        cancelRequests()
        current.home = false; current.url = url
        val existing = current.web
        if (existing == null) showCurrent() else { showCurrent(); existing.loadUrl(url) }
        persist()
    }
    private fun editAddress() {
        if (clearing) return
        sheet?.dismiss()
        val input = EditText(this).apply {
            id = R.id.address_input; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO; textSize = 16f
            hint = "Pesquisar ou digitar endereço"; setText(if (current.home) "" else current.url.orEmpty()); selectAll()
        }
        val frame = FrameLayout(this).apply { setPadding(dp(20), dp(6), dp(20), 0); addView(input) }
        val d = dialog("Para onde vamos?", null).setView(frame).setNegativeButton("Cancelar", null).setPositiveButton("Ir", null).create()
        d.setOnShowListener {
            val go = {
                val value = UrlPolicy.resolve(input.text.toString(), store.prefs.getString("search", "duckduckgo").orEmpty())
                if (value == null) input.error = "Digite uma busca ou um endereço HTTP/HTTPS válido."
                else { d.dismiss(); navigate(value) }
            }
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() }
            input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { go(); true } else false }
            input.requestFocus(); d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        d.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: Tab): WebView? {
        if (!engineAvailable) { showEngineError(); return null }
        return runCatching {
            val web = WebView(this)
            if (tab.private) {
                val name = privateProfile ?: "private_${UUID.randomUUID()}".also { privateProfile = it }
                WebViewCompat.setProfile(web, name)
                privateStorage = WebViewCompat.getProfile(web).webStorage
            }
            tab.web = web
            web.id = R.id.browser_view
            web.setBackgroundColor(paper)
            web.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                @Suppress("DEPRECATION")
                setAllowFileAccessFromFileURLs(false)
                @Suppress("DEPRECATION")
                setAllowUniversalAccessFromFileURLs(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                builtInZoomControls = true; displayZoomControls = false; setSupportZoom(true)
                useWideViewPort = true; loadWithOverviewMode = true
                textZoom = store.prefs.getInt("textZoom", 100)
                userAgentString = desktopAgent(tab.desktop)
            }
            val cookies = if (tab.private) WebViewCompat.getProfile(web).cookieManager else CookieManager.getInstance()
            cookies.setAcceptThirdPartyCookies(web, false)
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            web.webViewClient = pageClient(tab)
            web.webChromeClient = chromeClient(tab)
            web.setDownloadListener { url, agent, disposition, mime, _ -> confirmDownload(url, agent, disposition, mime) }
            web.setOnLongClickListener {
                val hit = web.hitTestResult
                val link = hit.extra?.takeIf { UrlPolicy.isWebUrl(it) }
                if (link == null) false else {
                    val options = if (hit.type == WebView.HitTestResult.IMAGE_TYPE) arrayOf("Abrir em nova aba", "Copiar endereço", "Baixar imagem") else arrayOf("Abrir em nova aba", "Copiar link", "Compartilhar link")
                    dialog("Opções do link", link).setItems(options) { _, which ->
                        when (which) {
                            0 -> newTab(link, tab.private)
                            1 -> copy(link)
                            2 -> if (hit.type == WebView.HitTestResult.IMAGE_TYPE) confirmDownload(link, web.settings.userAgentString, null, null) else share(link)
                        }
                    }.show(); true
                }
            }
            if (tab.state?.let { web.restoreState(it) } == null) tab.url?.let { web.loadUrl(it) }
            tab.state = null
            web
        }.getOrElse {
            tab.web?.destroy(); tab.web = null
            showEngineError(); null
        }
    }
    private fun desktopAgent(desktop: Boolean): String {
        val agent = WebSettings.getDefaultUserAgent(this)
        return if (desktop) agent.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Version/4.0", "").replace(" Mobile", "") else agent
    }

    private fun pageClient(tab: Tab) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val value = request.url.toString()
            if (UrlPolicy.isWebUrl(value)) return false
            if (request.isForMainFrame && request.hasGesture() && activeId == tab.id) externalLink(value)
            return true
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (tab.id == activeId) cancelRequests()
            UrlPolicy.canonicalWebUrl(url.orEmpty())?.let { tab.url = it; tab.home = false }
            tab.protectedInMemory = true
            if (tab.id == activeId) updateChrome()
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            UrlPolicy.canonicalWebUrl(url.orEmpty())?.let { tab.url = it }
            if (tab.id == activeId) updateChrome()
        }
        override fun onPageFinished(view: WebView, url: String?) {
            if (clearing || tabs.none { it === tab } || tab.web !== view) return
            if (!tab.private && UrlPolicy.isWebUrl(url) && url == tab.url) store.visit(tab.title, url!!)
            if (tab.id == activeId) updateChrome()
            persist()
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (tab.id == activeId) showPageError("Conexão não segura", "O certificado deste site não pôde ser validado.", tab)
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && tab.id == activeId) showPageError("Não foi possível abrir a página", "Confira sua conexão e o endereço do site.", tab)
        }
        override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) { callback.backToSafety(true) }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val wasCurrent = activeId == tab.id
            destroy(tab)
            if (wasCurrent) { cancelRequests(); showPageError("Esta página foi interrompida", "Você pode reabri-la. Suas outras abas continuam salvas.", tab) }
            return true
        }
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String?) {
            if (activeId != tab.id || UrlPolicy.displayHost(tab.url) != host || !tab.url.orEmpty().startsWith("https://")) { handler.cancel(); return }
            val fields = column().apply { setPadding(dp(20), 0, dp(20), 0) }
            val user = EditText(this@BrowserActivity).apply { hint = "Usuário"; setSingleLine() }
            val pass = EditText(this@BrowserActivity).apply { hint = "Senha"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            fields.addView(user); fields.addView(pass)
            dialog("Acessar $host", "A senha será enviada somente a este site.").setView(fields)
                .setNegativeButton("Cancelar") { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .setPositiveButton("Entrar") { _, _ ->
                    if (activeId == tab.id && UrlPolicy.displayHost(tab.url) == host) handler.proceed(user.text.toString(), pass.text.toString()) else handler.cancel()
                }.show()
        }
    }

    private fun chromeClient(tab: Tab) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { if (tab.id == activeId) { progress.progress = newProgress; progress.visibility = if (newProgress < 100 && !tab.home) View.VISIBLE else View.INVISIBLE } }
        override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title?.take(300)?.ifBlank { "Página" } ?: "Página"; persist() }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture || tab.id != activeId) return false
            val popup = newTab(private = tab.private) ?: return false
            popup.home = false
            val child = createWebView(popup) ?: return false
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget(); showCurrent(); return true
        }
        override fun onCloseWindow(window: WebView) { tabs.firstOrNull { it.web === window }?.let { closeTab(it) } }
        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            if (tab.id != activeId || UrlPolicy.origin(tab.url) == null) { callback.onReceiveValue(null); return true }
            upload?.onReceiveValue(null); upload = callback; uploadTab = tab.id
            val accepts = params.acceptTypes.filter { it.contains('/') && !it.contains(';') }.distinct().toTypedArray()
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = if (accepts.size == 1) accepts[0] else "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                if (accepts.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accepts)
            }
            runCatching { filePicker.launch(picker) }.onFailure { upload?.onReceiveValue(null); upload = null; uploadTab = null; toast("Nenhum seletor de arquivos está disponível.") }
            return true
        }
        override fun onPermissionRequest(request: PermissionRequest) {
            val expected = UrlPolicy.origin(tab.url)
            if (activeId != tab.id || expected == null || !expected.startsWith("https://") || expected != UrlPolicy.origin(request.origin.toString()) || mediaRequest != null) { request.deny(); return }
            val permissions = request.resources.mapNotNull {
                when (it) { PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA; PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO; else -> null }
            }.distinct().toTypedArray()
            if (permissions.isEmpty()) { request.deny(); return }
            mediaRequest = request; mediaTab = tab.id
            val needs = permissions.joinToString(" e ") { if (it == Manifest.permission.CAMERA) "câmera" else "microfone" }
            dialog("Permissão do site", "$expected deseja usar $needs.")
                .setNegativeButton("Bloquear") { _, _ -> cancelRequests() }
                .setOnCancelListener { cancelRequests() }
                .setPositiveButton("Permitir desta vez") { _, _ -> if (mediaRequest === request) mediaPermission.launch(permissions) }.show()
        }
        override fun onPermissionRequestCanceled(request: PermissionRequest) { if (mediaRequest === request) { mediaRequest = null; mediaTab = null } }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val expected = UrlPolicy.origin(tab.url)
            if (tab.id != activeId || expected == null || !expected.startsWith("https://") || expected != UrlPolicy.origin(origin) || geoRequest != null) { callback.invoke(origin, false, false); return }
            geoRequest = origin to callback; geoTab = tab.id
            dialog("Localização", "$expected deseja acessar sua localização.")
                .setNegativeButton("Bloquear") { _, _ -> cancelRequests() }.setOnCancelListener { cancelRequests() }
                .setPositiveButton("Permitir desta vez") { _, _ -> if (geoRequest?.second === callback) geoPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }.show()
        }
        override fun onGeolocationPermissionsHidePrompt() { geoRequest = null; geoTab = null }
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullView != null || tab.id != activeId) { callback.onCustomViewHidden(); return }
            fullView = view; fullCallback = callback; shell.visibility = View.GONE
            root.addView(view, FrameLayout.LayoutParams(-1, -1)); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.systemBars())
        }
        override fun onHideCustomView() { leaveFullscreen() }
    }

    private fun leaveFullscreen() {
        fullView?.let { root.removeView(it) }; fullView = null
        fullCallback?.onCustomViewHidden(); fullCallback = null
        if (::shell.isInitialized) shell.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
    }
    private fun updateChrome() {
        val tab = current
        val host = UrlPolicy.displayHost(tab.url)
        address.text = if (tab.home) if (tab.private) "Navegação privada" else "Pesquisar ou digitar endereço" else host.ifBlank { "Carregando…" }
        addressIcon.removeAllViews()
        val secure = tab.url.orEmpty().startsWith("https://")
        addressIcon.addView(icon(if (tab.home && tab.private) "private" else if (tab.home) "search" else if (secure) "lock" else "warning", 20, if (!tab.home && !secure) muted else accent), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        addressIcon.contentDescription = if (tab.home) "Pesquisa" else if (secure) "Informações da conexão HTTPS" else "Site HTTP: conexão não criptografada"
        addressIcon.clickable { if (!tab.home) showSiteInfo() else editAddress() }
        backButton.isEnabled = tab.web?.canGoBack() == true || (tab.home && tab.web != null)
        forwardButton.isEnabled = !tab.home && tab.web?.canGoForward() == true
        backButton.alpha = if (backButton.isEnabled) 1f else .35f
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else .35f
        tabsLabel.text = tabs.size.toString()
        progress.visibility = if (!tab.home && (tab.web?.progress ?: 100) < 100) View.VISIBLE else View.INVISIBLE
    }
    private fun showMenu() {
        val body = sheetBody("Menu")
        val tab = current
        body.addView(text(if (tab.home) "Nesta aba" else UrlPolicy.displayHost(tab.url), 12, muted), margins(4, 12))
        val actions = row()
        val pageActions: List<Triple<String, String, () -> Unit>> = listOf(Triple("reload", "Recarregar", { sheet?.dismiss(); if (!current.home) { showCurrent(); current.web?.reload() } }), Triple("share", "Compartilhar", { sheet?.dismiss(); current.url?.let { share(it) } ?: toast("Abra uma página para compartilhar.") }), Triple("star", "Salvar", { sheet?.dismiss(); bookmark() }))
        pageActions.forEach { (glyph, label, action) ->
            val cell = column().apply { gravity = Gravity.CENTER; minimumHeight = dp(62); background = rounded(tint, 13); clickable(action) }
            cell.addView(icon(glyph, 20, accent), LinearLayout.LayoutParams(dp(20), dp(20)))
            cell.addView(text(label, 11, accent), margins(4, 0))
            actions.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        }
        body.addView(actions)
        val items: List<Triple<String, String, () -> Unit>> = listOf(Triple("star", "Favoritos", { showPages("bookmarks", "Favoritos") }), Triple("history", "Histórico", { showPages("history", "Histórico") }), Triple("download", "Downloads", { showDownloads() }), Triple("search", "Buscar na página", { findInPage() }), Triple("desktop", if (tab.desktop) "Site para celular" else "Site para desktop", { sheet?.dismiss(); tab.desktop = !tab.desktop; tab.web?.settings?.userAgentString = desktopAgent(tab.desktop); tab.web?.reload(); persist() }), Triple("settings", "Configurações", { showSettings() }))
        items.chunked(2).forEach { pair ->
            val line = row()
            pair.forEach { (glyph, label, action) -> line.addView(actionRow(glyph, label, null, action), LinearLayout.LayoutParams(0, -2, 1f)) }
            body.addView(line)
        }
        body.addView(actionRow("private", "Nova aba privada", null) { newTab(private = true) }.apply { background = rounded(soft, 13) }, margins(8, 0))
        presentSheet(body)
    }
    private fun showTabs() {
        val body = sheetBody("Suas abas · ${tabs.size}")
        tabs.toList().forEach { tab ->
            val line = row().apply { background = rounded(if (tab.id == activeId) tint else soft, 13); setPadding(dp(2), 0, 0, 0) }
            line.addView(actionRow(if (tab.private) "private" else "globe", if (tab.home) "Nova aba${if (tab.private) " privada" else ""}" else tab.title, if (tab.private) "Privada" else UrlPolicy.displayHost(tab.url)) { switchTab(tab) }, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(iconButton("close", "Fechar ${tab.title}") { closeTab(tab) })
            body.addView(line, margins(6, 0))
        }
        body.addView(primaryButton("Nova aba", "plus") { newTab() }, margins(16, 0))
        if (tabs.any { it.private }) body.addView(actionRow("private", "Fechar todas as abas privadas", null) { closePrivateTabs() })
        presentSheet(body)
    }
    private fun showPages(key: String, label: String) {
        val body = sheetBody(label)
        val pages = store.pages(key)
        if (pages.isEmpty()) body.addView(text(if (key == "bookmarks") "Suas páginas favoritas aparecem aqui." else "As páginas visitadas aparecem aqui. Abas privadas não entram no histórico.", 14, muted), margins(12, 20))
        pages.forEach { record ->
            val item = actionRow(if (key == "bookmarks") "star" else "history", record.title.ifBlank { UrlPolicy.displayHost(record.url) }, UrlPolicy.displayHost(record.url)) { sheet?.dismiss(); navigate(record.url) }
            item.setOnLongClickListener { dialog("Remover item?", record.title).setNegativeButton("Cancelar", null).setPositiveButton("Remover") { _, _ -> store.savePages(key, store.pages(key).filterNot { it.url == record.url }); showPages(key, label) }.show(); true }
            body.addView(item)
        }
        presentSheet(body)
    }
    private fun bookmark() {
        val tab = current
        val url = tab.url?.takeIf { !tab.home && UrlPolicy.isWebUrl(it) } ?: run { toast("Abra uma página para salvar."); return }
        if (tab.private) { dialog("Salvar fora da sessão privada?", "Este favorito ficará disponível nas abas normais.").setNegativeButton("Cancelar", null).setPositiveButton("Salvar") { _, _ -> toast(if (store.toggleBookmark(tab.title, url)) "Favorito salvo" else "Favorito removido") }.show() }
        else toast(if (store.toggleBookmark(tab.title, url)) "Favorito salvo" else "Favorito removido")
    }
    private fun findInPage() {
        sheet?.dismiss()
        val web = current.web?.takeIf { !current.home } ?: run { toast("Abra uma página primeiro."); return }
        val input = EditText(this).apply { hint = "Palavra ou frase"; setSingleLine() }
        val d = dialog("Buscar na página", null).setView(input).setPositiveButton("Buscar", null).setNeutralButton("Próxima", null).setNegativeButton("Fechar") { _, _ -> web.clearMatches() }.create()
        d.setOnShowListener {
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { web.findAllAsync(input.text.toString()) }
            d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { web.findNext(true) }
        }
        d.setOnDismissListener { web.clearMatches(); web.setFindListener(null) }
        web.setFindListener { activeMatch, count, done -> if (done) d.setTitle(if (count == 0) "Nenhum resultado" else "Resultado ${activeMatch + 1} de $count") }
        d.show()
    }
    private fun showSiteInfo() {
        val url = current.url ?: return
        val origin = UrlPolicy.origin(url).orEmpty()
        dialog(if (origin.startsWith("https://")) "Conexão HTTPS" else "Conexão HTTP", "$origin\n\n${if (origin.startsWith("https://")) "A conexão é criptografada. Isso não garante que o conteúdo ou o responsável pelo site sejam confiáveis." else "Os dados enviados a este site não são criptografados."}\n\nCâmera, microfone e localização exigem sua autorização a cada solicitação. Cookies de terceiros ficam bloqueados.").setPositiveButton("Entendi", null).show()
    }
    private fun showSettings() {
        val body = sheetBody("Configurações")
        body.addView(actionRow("search", "Mecanismo de pesquisa", store.prefs.getString("search", "duckduckgo")) {
            val values = arrayOf("duckduckgo", "google", "bing")
            dialog("Pesquisar com", null).setSingleChoiceItems(arrayOf("DuckDuckGo", "Google", "Bing"), values.indexOf(store.prefs.getString("search", "duckduckgo"))) { d, which -> store.prefs.edit().putString("search", values[which]).apply(); d.dismiss(); showSettings() }.setNegativeButton("Cancelar", null).show()
        })
        body.addView(actionRow("file", "Tamanho do texto", "${store.prefs.getInt("textZoom", 100)}%") {
            val sizes = intArrayOf(85, 100, 115, 130, 150)
            dialog("Tamanho do texto", null).setItems(sizes.map { "$it%" }.toTypedArray()) { _, i -> store.prefs.edit().putInt("textZoom", sizes[i]).apply(); tabs.forEach { it.web?.settings?.textZoom = sizes[i] }; showSettings() }.show()
        })
        body.addView(actionRow("home", "Definir como navegador padrão", null) { defaultBrowser() })
        body.addView(actionRow("shield", "Limpar dados de navegação", "Histórico, cookies e dados dos sites") {
            dialog("Limpar dados de navegação?", "As sessões dos sites serão encerradas e as abas serão fechadas. Seus favoritos serão mantidos.").setNegativeButton("Cancelar", null).setPositiveButton("Limpar") { _, _ -> clearBrowsingData() }.show()
        })
        body.addView(actionRow("compass", "Sobre o Navegador 4.0", "Versão ${BuildConfig.VERSION_NAME}") {
            val engine = WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "indisponível"
            dialog("Navegador 4.0", "Versão ${BuildConfig.VERSION_NAME}\n\nInterface nativa em Kotlin. Motor Chromium/Blink pelo Android System WebView $engine.\n\nA aparência acompanha o tema do Android. Este aplicativo não contém IA ou sincronização nesta versão.").setPositiveButton("Fechar", null).setNeutralButton("Código-fonte") { _, _ -> sheet?.dismiss(); navigate("https://github.com/emanoelb5-lgtm/Navegador-4.0") }.show()
        })
        presentSheet(body)
    }

    private fun confirmDownload(value: String, agent: String?, disposition: String?, mime: String?) {
        if (!UrlPolicy.isWebUrl(value)) { toast("Este tipo de download ainda não é compatível."); return }
        val name = URLUtil.guessFileName(value, disposition, mime).replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(160).ifBlank { "arquivo" }
        dialog("Baixar arquivo?", "$name\n${UrlPolicy.displayHost(value)}${if (current.private) "\n\nO arquivo baixado continuará disponível depois de fechar a sessão privada." else ""}")
            .setNegativeButton("Cancelar", null).setPositiveButton("Baixar") { _, _ ->
                runCatching {
                    val request = DownloadManager.Request(Uri.parse(value)).setTitle(name).setDescription(UrlPolicy.displayHost(value))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    mime?.takeIf { it.isNotBlank() }?.let { request.setMimeType(it) }
                    if (agent != null) request.addRequestHeader("User-Agent", agent)
                    // Do not copy authenticated cookies into a redirect-capable external downloader.
                    if (Build.VERSION.SDK_INT >= 29) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    else request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
                    val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    val ids = store.prefs.getStringSet("downloads", emptySet()).orEmpty().toMutableSet().apply { add(id.toString()) }
                    store.prefs.edit().putStringSet("downloads", ids).apply(); toast("Download iniciado")
                }.onFailure { toast("Não foi possível iniciar o download.") }
            }.show()
    }
    private fun showDownloads() {
        val body = sheetBody("Downloads")
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val ids = store.prefs.getStringSet("downloads", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.sortedDescending().take(80)
        if (ids.isEmpty()) body.addView(text("Seus arquivos baixados aparecem aqui.", 14, muted), margins(12, 20))
        ids.forEach { id ->
            runCatching {
                manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val label = when (status) { DownloadManager.STATUS_SUCCESSFUL -> "Concluído · toque para abrir"; DownloadManager.STATUS_FAILED -> "Falhou · mantenha pressionado para remover"; DownloadManager.STATUS_PAUSED -> "Pausado"; else -> "Em andamento" }
                        val item = actionRow("download", name, label) {
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                val uri = manager.getUriForDownloadedFile(id)
                                if (uri != null) runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, manager.getMimeTypeForDownloadedFile(id) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { toast("Nenhum aplicativo pode abrir esse arquivo.") }
                            } else toast(label)
                        }
                        item.setOnLongClickListener { dialog("Remover download?", "O arquivo também será removido.").setNegativeButton("Cancelar", null).setPositiveButton("Remover") { _, _ -> manager.remove(id); val remaining = store.prefs.getStringSet("downloads", emptySet()).orEmpty().toMutableSet().apply { remove(id.toString()) }; store.prefs.edit().putStringSet("downloads", remaining).apply(); showDownloads() }.show(); true }
                        body.addView(item)
                    }
                }
            }
        }
        body.addView(actionRow("reload", "Atualizar lista", null) { showDownloads() })
        presentSheet(body)
    }
    private fun externalLink(value: String) {
        var uri = Uri.parse(value)
        if (uri.scheme == "intent") {
            val parsed = runCatching { Intent.parseUri(value, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
            val fallback = parsed.getStringExtra("browser_fallback_url")?.takeIf { UrlPolicy.isWebUrl(it) }
            if (fallback != null) { navigate(fallback); return }
            uri = parsed.data ?: return
        }
        if (uri.scheme !in setOf("tel", "mailto", "sms", "geo", "market", "whatsapp", "tg", "zoommtg")) { toast("Este tipo de link não é compatível."); return }
        val target = uri
        dialog("Abrir outro aplicativo?", target.toString().take(300)).setNegativeButton("Cancelar", null).setPositiveButton("Abrir") { _, _ ->
            val safe = Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
            runCatching { startActivity(safe) }.onFailure { toast("Nenhum aplicativo compatível foi encontrado.") }
        }.show()
    }
    private fun defaultBrowser() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                if (roles.isRoleHeld(RoleManager.ROLE_BROWSER)) toast("O Navegador 4.0 já é o navegador padrão.")
                else runCatching { startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER)) }.onFailure { toast("Abra os aplicativos padrão nas configurações do Android.") }
                return
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    }
    private fun updateEngine() { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))) }.onFailure { toast("Atualize o Android System WebView pela loja do seu aparelho.") } }
    private fun showEngineError() { showPageError("Motor de navegação indisponível", "Atualize ou ative o Android System WebView nas configurações do aparelho.", current, true) }
    private fun showPageError(title: String, detail: String, tab: Tab, engine: Boolean = false) {
        pageHost.removeAllViews(); progress.visibility = View.INVISIBLE
        val box = column().apply { gravity = Gravity.CENTER; setPadding(dp(28), dp(28), dp(28), dp(28)) }
        box.addView(icon("warning", 40, accent), LinearLayout.LayoutParams(dp(40), dp(40)))
        box.addView(text(title, 23, ink, true).apply { gravity = Gravity.CENTER }, margins(22, 12))
        box.addView(text(detail, 15, muted).apply { gravity = Gravity.CENTER }, margins(0, 24))
        box.addView(primaryButton(if (engine) "Atualizar motor" else "Tentar novamente", "reload") { if (engine) updateEngine() else tab.url?.let { showCurrent(); tab.web?.loadUrl(it) } })
        pageHost.addView(box, FrameLayout.LayoutParams(-1, -1))
    }
    private fun privateSupported() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) && WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA) }.getOrDefault(false)
    private fun closePrivateTabs() {
        val privateTabs = tabs.filter { it.private }
        val storage = privateStorage
        privateTabs.forEach { destroy(it); tabs.remove(it) }
        privateProfile = null; privateStorage = null
        storage?.let { WebStorageCompat.deleteBrowsingData(it) {} }
        if (tabs.isEmpty()) tabs += Tab(nextId++)
        if (tabs.none { it.id == activeId }) activeId = tabs.first().id
        cancelRequests(); showCurrent(); sheet?.dismiss(); toast("Sessão privada encerrada")
    }
    private fun clearBrowsingData() {
        sheet?.dismiss(); cancelRequests(); clearing = true
        val closingStorage = privateStorage
        privateStorage = null
        tabs.forEach { it.web?.clearCache(true); destroy(it) }; tabs.clear()
        privateProfile = null; tabs += Tab(nextId++); activeId = tabs.first().id
        store.savePages("history", emptyList()); persist(); showCurrent()
        val done = { clearing = false; toast("Dados de navegação removidos") }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            closingStorage?.let { WebStorageCompat.deleteBrowsingData(it) {} }
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { done() }
        } else {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done() }
        }
    }
    private fun cancelRequests() {
        upload?.onReceiveValue(null); upload = null; uploadTab = null
        mediaRequest?.deny(); mediaRequest = null; mediaTab = null
        geoRequest?.let { it.second.invoke(it.first, false, false) }; geoRequest = null; geoTab = null
    }
    private fun protectAndPause(tab: Tab) {
        tab.web?.let { web ->
            web.evaluateJavascript("Boolean(Array.from(document.querySelectorAll('input,textarea,select')).some(e=>e.value && e.value!==e.defaultValue) || Array.from(document.querySelectorAll('video,audio')).some(e=>!e.paused))") { value -> tab.protectedInMemory = value != "false" }
            web.onPause()
        }
    }
    private fun trimTabs(force: Boolean = false) {
        val live = tabs.filter { it.web != null }.sortedBy { it.used }
        var remaining = live.size
        for (tab in live) {
            if ((!force && remaining <= 4) || remaining <= 1) break
            if (tab.id == activeId || tab.protectedInMemory || (tab.web?.progress ?: 100) < 100) continue
            val state = Bundle()
            runCatching { tab.web?.saveState(state) }
            tab.state = state
            destroy(tab); remaining--
        }
    }
    private fun destroy(tab: Tab) { tab.web?.let { view -> (view.parent as? ViewGroup)?.removeView(view); view.stopLoading(); view.onPause(); view.webChromeClient = null; view.destroy() }; tab.web = null }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trimTabs(true) }
    override fun onPause() { super.onPause(); persist(); current.web?.onPause() }
    override fun onResume() { super.onResume(); if (tabs.isNotEmpty() && !current.home) current.web?.onResume() }
    override fun onSaveInstanceState(outState: Bundle) { persist(); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (previousNight != night) { previousNight = night; leaveFullscreen(); sheet?.dismiss(); buildShell(); showCurrent() }
    }
    override fun onDestroy() {
        sheet?.dismiss(); cancelRequests()
        val closingStorage = privateStorage; privateStorage = null
        tabs.forEach { destroy(it) }
        closingStorage?.let { runCatching { WebStorageCompat.deleteBrowsingData(it) {} } }
        super.onDestroy()
    }

    private fun sheetBody(label: String): LinearLayout {
        val body = column().apply { setPadding(dp(18), dp(10), dp(18), dp(20)) }
        body.addView(View(this).apply { background = rounded(soft, 3) }, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(6) })
        val header = row()
        header.addView(text(label, 21, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton("close", "Fechar painel") { sheet?.dismiss() })
        body.addView(header); return body
    }
    private fun presentSheet(body: LinearLayout) {
        sheet?.dismiss()
        val dialog = BottomSheetDialog(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(paper); addView(body) }
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            val bottom = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottom?.let {
                it.background = rounded(paper, 24)
                it.clipToOutline = true
                BottomSheetBehavior.from(it).apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
                val maxHeight = (resources.displayMetrics.heightPixels * .83f).toInt()
                scroll.layoutParams.height = minOf(body.measuredHeight.takeIf { h -> h > 0 } ?: maxHeight, maxHeight)
            }
        }
        sheet = dialog; dialog.show()
    }
    private fun actionRow(glyph: String, title: String, detail: String?, action: () -> Unit): LinearLayout {
        val item = row().apply { setPadding(dp(9), dp(12), dp(7), dp(12)); minimumHeight = dp(54); clickable(action) }
        item.addView(icon(glyph, 21, muted), LinearLayout.LayoutParams(dp(21), dp(21)).apply { marginEnd = dp(11) })
        val labels = column()
        labels.addView(text(title, 13, ink).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (!detail.isNullOrBlank()) labels.addView(text(detail, 11, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        item.addView(labels, LinearLayout.LayoutParams(0, -2, 1f)); return item
    }
    private fun primaryButton(label: String, glyph: String, action: () -> Unit) = row().apply {
        gravity = Gravity.CENTER; minimumHeight = dp(50); background = rounded(accent, 14); contentDescription = label; clickable(action)
        val foreground = if (night) Color.parseColor("#10362A") else Color.WHITE
        addView(icon(glyph, 20, foreground), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addView(text(label, 14, foreground, true))
    }
    private fun iconButton(name: String, label: String, action: () -> Unit) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); contentDescription = label; clickable(action)
        addView(icon(name, 22, muted), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }
    private fun View.clickable(action: () -> Unit) {
        isClickable = true; isFocusable = true
        foreground = RippleDrawable(ColorStateList.valueOf(if (night) 0x22FFFFFF else 0x18087460), null, rounded(Color.WHITE, 13))
        setOnClickListener { action() }
    }
    private fun icon(name: String, size: Int, color: Int) = Glyph(this, name, color).apply { layoutParams = ViewGroup.LayoutParams(dp(size), dp(size)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun text(value: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); typeface = Typeface.create("sans-serif${if (medium) "-medium" else ""}", Typeface.NORMAL); includeFontPadding = false }
    private fun text(value: String, size: Int, color: Int, medium: Boolean = false) = text(value, size.toFloat(), color, medium)
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun margins(top: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun dialog(title: String, message: String?) = MaterialAlertDialogBuilder(this).setTitle(title).apply { if (message != null) setMessage(message) }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun share(url: String) { runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Compartilhar página")) }.onFailure { toast("Não foi possível compartilhar.") } }
    private fun copy(value: String) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Link", value)); toast("Link copiado") }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
