package com.hermes.chatandroid.ui.more

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.chatandroid.AppContainer
import com.hermes.chatandroid.data.Settings
import com.hermes.chatandroid.ui.components.EmptyState
import com.hermes.chatandroid.ui.components.ErrorBanner
import com.hermes.chatandroid.ui.components.isWideWindow

private const val DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

/** 페이지의 viewport 를 덮어써 핀치 줌을 허용한다(iOS DashboardWebView 와 동일). 데스크톱은 1024px 고정 폭. */
private fun viewportJs(desktop: Boolean): String {
    val content = if (desktop) "width=1024, user-scalable=yes"
    else "width=device-width, initial-scale=1, maximum-scale=10, user-scalable=yes"
    return "(function(){var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
        "m.setAttribute('content','$content');})();"
}

/**
 * 100dvh 보정: WebView 에서 dvh 가 0 으로 계산되면 대시보드 틀(h-dvh)이 0 높이로 접힌다.
 * 실제 innerHeight(px)를 CSS 변수로 넣고 h-dvh/max-h-dvh 를 그 값으로 덮어쓴다. 창 크기 변경 시 갱신.
 */
private const val DVH_FIX_JS = "(function(){if(window.__hcDvh)return;window.__hcDvh=1;" +
    "var st=document.createElement('style');" +
    "st.textContent='.h-dvh{height:var(--hc-vh)!important}.max-h-dvh{max-height:var(--hc-vh)!important}';" +
    "document.head.appendChild(st);" +
    "function u(){document.documentElement.style.setProperty('--hc-vh',window.innerHeight+'px');}" +
    "u();window.addEventListener('resize',u);})();"

/**
 * 맥미니 대시보드(:8000) WebView (T-179).
 * 커버 화면은 모바일, 펼친 화면은 데스크톱(UA + 1024px viewport)으로 자동 전환.
 * 로그인은 페이지 안에서 한 번 하면 쿠키로 유지된다. 대시보드 호스트 밖 링크는 외부 브라우저로 연다.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appSettings by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val url = appSettings.dashboardUrl
    // 폴드 커버(좁은 창)는 모바일, 펼친 내부 화면(600dp+)은 데스크톱 레이아웃이 기본.
    // 툴바 토글은 현재 창 크기에서만 유지되는 임시 전환(접고 펴면 다시 자동).
    val wide = isWideWindow()
    var manualDesktop by remember(wide) { mutableStateOf<Boolean?>(null) }
    val desktop = manualDesktop ?: wide
    val context = LocalContext.current

    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    // 진단: JS 콘솔 오류·실패한 요청(최근 40건). 대시보드가 빈 화면일 때 원인 확인용.
    val diag = remember { mutableStateListOf<String>() }
    var diagOpen by remember { mutableStateOf(false) }
    var diagSnapshot by remember { mutableStateOf("") }
    fun log(line: String) {
        diag.add(line)
        if (diag.size > 40) diag.removeAt(0)
    }

    val webView = remember {
        // 디버그 빌드에서만 chrome://inspect 원격 디버깅 허용
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        WebView(context).apply {
            // WRAP_CONTENT(기본)면 페이지의 100vh/100dvh 가 0 이 되어 대시보드 본문 틀 높이가 0 으로 접힌다.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // 시스템 글꼴 크기 배율을 무시 — 대시보드 사이드바 메뉴가 커진 글꼴에 밀려 0줄로 찌그러지는 것 방지
            settings.textZoom = 100
            CookieManager.getInstance().setAcceptCookie(true)
        }
    }

    val dashboardHost = url?.let { Uri.parse(it).host }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                log("[JS ${m.messageLevel()}] ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
            }
            return false
        }
    }
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.host == dashboardHost) return false
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            error = null
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(viewportJs(desktop), null)
            view.evaluateJavascript(DVH_FIX_JS, null)
            canGoBack = view.canGoBack()
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
            log("[NET] ${err.errorCode} ${err.description} ${request.method} ${request.url.path}")
            if (request.isForMainFrame) {
                error = "대시보드에 연결할 수 없습니다 (${err.description}). Tailscale 연결과 맥미니 :${Settings.DASHBOARD_PORT} 상태를 확인하세요."
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            log("[HTTP ${response.statusCode}] ${request.method} ${request.url.path}")
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            canGoBack = view.canGoBack()
        }
    }

    // 최초 로드 + 모드 전환 시 UA 교체 후 다시 로드. 주소가 바뀌면 새 주소로.
    LaunchedEffect(url, desktop) {
        if (url == null) return@LaunchedEffect
        val ua = if (desktop) DESKTOP_UA else WebSettings.getDefaultUserAgent(context)
        val uaChanged = webView.settings.userAgentString != ua
        webView.settings.userAgentString = ua
        val current = webView.url
        when {
            current == null || Uri.parse(current).host != Uri.parse(url).host -> webView.loadUrl(url)
            uaChanged -> webView.reload()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView.destroy()
        }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("대시보드", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "닫기") }
                },
                actions = {
                    if (url != null) {
                        IconButton(onClick = {
                            webView.evaluateJavascript(
                                "(function(){var r=document.getElementById('root');return [location.href, navigator.userAgent, " +
                                    "'viewport '+innerWidth+'x'+innerHeight, 'root text '+(r?r.innerText.length:-1), " +
                                    "'main h '+[...document.querySelectorAll('main,[class*=flex-1]')].slice(0,4).map(e=>Math.round(e.getBoundingClientRect().height)).join(',')].join('\\n')})()",
                            ) { diagSnapshot = it.trim('"').replace("\\n", "\n") }
                            diagOpen = true
                        }) {
                            BadgedBox(badge = { if (diag.isNotEmpty()) Badge { Text("${diag.size}") } }) {
                                Icon(Icons.Rounded.BugReport, contentDescription = "진단 로그")
                            }
                        }
                        IconButton(onClick = { webView.reload() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "새로고침")
                        }
                        IconButton(onClick = { manualDesktop = !desktop }) {
                            Icon(
                                if (desktop) Icons.Rounded.DesktopWindows else Icons.Rounded.PhoneAndroid,
                                contentDescription = if (desktop) "데스크톱 모드 (탭하면 모바일)" else "모바일 모드 (탭하면 데스크톱)",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (diagOpen) {
            val clipboard = LocalClipboardManager.current
            val report = (listOf("== 상태 ==", diagSnapshot, "== 오류/요청 실패 ${diag.size}건 ==") + diag).joinToString("\n")
            AlertDialog(
                onDismissRequest = { diagOpen = false },
                title = { Text("대시보드 진단") },
                text = {
                    SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                        Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(report)); diagOpen = false }) { Text("복사") }
                },
                dismissButton = { TextButton(onClick = { diagOpen = false }) { Text("닫기") } },
            )
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (url == null) {
                EmptyState(
                    title = "대시보드 주소가 없습니다",
                    subtitle = "설정에서 Hermes Bot 서버 또는 게이트웨이 호스트를 입력하세요.",
                )
                return@Column
            }
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            error?.let { ErrorBanner(it, onRetry = { error = null; webView.reload() }) }
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
