import SwiftUI
import WebKit

/// 맥미니 대시보드(:8000)를 그대로 임베드하는 탭.
/// 로그인(세션 토큰)은 웹 페이지 안에서 한 번 입력하면 WKWebView 쿠키로 유지된다.
struct DashboardWebView: View {
    @ObservedObject var appSettings: AppSettings

    var body: some View {
        NavigationStack {
            WebView(url: appSettings.dashboardURL)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle("대시보드")
                .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct WebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.allowsBackForwardNavigationGestures = true
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // 호스트/포트가 바뀐 경우에만 다시 로드 (페이지 내 탐색 상태 보존)
        if webView.url?.host != url.host || webView.url?.port != url.port {
            webView.load(URLRequest(url: url))
        }
    }
}
