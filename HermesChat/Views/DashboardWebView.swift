import SwiftUI
import WebKit

/// 맥미니 대시보드(:8000)를 그대로 임베드하는 탭.
/// 로그인(세션 토큰)은 웹 페이지 안에서 한 번 입력하면 WKWebView 쿠키로 유지된다.
///
/// 대시보드 페이지는 맥미니가 서빙하므로 앱에서 HTML을 직접 못 고친다.
/// 대신 두 가지 보조 기능을 제공한다.
///  1) 핀치 줌 — 페이지의 `<meta viewport>`가 보통 `user-scalable=no`라 확대가 막혀 있어,
///     viewport를 덮어써서 두 손가락 확대/축소를 허용한다.
///  2) 데스크톱 모드 — 모바일 레이아웃에서 CSS로 숨겨진 버튼은 줌만으론 안 보이므로,
///     데스크톱 user-agent + 넓은 viewport(width=1024)로 전체 레이아웃을 불러온다.
struct DashboardWebView: View {
    @ObservedObject var appSettings: AppSettings

    /// 데스크톱 모드 토글 상태(앱 내 단순 설정값, 비밀값 아님).
    @AppStorage("dashboardDesktopMode") private var desktopMode = false

    var body: some View {
        NavigationStack {
            WebView(url: appSettings.dashboardURL, desktopMode: desktopMode)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle("tab.dashboard")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            desktopMode.toggle()
                        } label: {
                            Image(systemName: desktopMode ? "desktopcomputer" : "iphone")
                        }
                        .accessibilityLabel(desktopMode ? "dashboard.desktop.label" : "dashboard.mobile.label")
                    }
                }
        }
    }
}

private struct WebView: UIViewRepresentable {
    let url: URL
    let desktopMode: Bool

    /// 데스크톱 모드에서 사용할 macOS Safari user-agent.
    private static let desktopUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    /// viewport 메타태그를 덮어써서 핀치 줌을 허용하는 JS를 만든다.
    /// 모바일은 device-width 기준, 데스크톱은 width=1024 고정 폭으로 전체 레이아웃을 보여준다.
    private static func viewportJS(desktop: Bool) -> String {
        let content = desktop
            ? "width=1024, user-scalable=yes"
            : "width=device-width, initial-scale=1, maximum-scale=10, user-scalable=yes"
        return """
        (function() {
            var content = "\(content)";
            var meta = document.querySelector('meta[name=viewport]');
            if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                document.head.appendChild(meta);
            }
            meta.setAttribute('content', content);
        })();
        """
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(desktopMode: desktopMode)
    }

    func makeUIView(context: Context) -> WKWebView {
        // 첫 페인트부터 줌이 걸리도록 user script로 viewport를 미리 깔아둔다.
        let configuration = WKWebViewConfiguration()
        let userScript = WKUserScript(
            source: WebView.viewportJS(desktop: desktopMode),
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: true
        )
        configuration.userContentController.addUserScript(userScript)

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.customUserAgent = desktopMode ? WebView.desktopUserAgent : nil
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // 모드가 바뀌면 user-agent를 교체하고 새 viewport로 다시 로드한다.
        if context.coordinator.desktopMode != desktopMode {
            context.coordinator.desktopMode = desktopMode
            webView.customUserAgent = desktopMode ? WebView.desktopUserAgent : nil
            webView.reload()
            return
        }

        // 호스트/포트가 바뀐 경우에만 다시 로드 (페이지 내 탐색 상태 보존)
        if webView.url?.host != url.host || webView.url?.port != url.port {
            webView.load(URLRequest(url: url))
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var desktopMode: Bool

        init(desktopMode: Bool) {
            self.desktopMode = desktopMode
        }

        // 페이지 로드가 끝날 때마다 현재 모드에 맞는 viewport를 다시 주입해
        // 모드 전환 후 새로 로드된 페이지에도 줌이 확실히 걸리게 한다.
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            webView.evaluateJavaScript(WebView.viewportJS(desktop: desktopMode))
        }
    }
}
