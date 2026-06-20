import BackgroundTasks
import SwiftUI

enum AppTab: Hashable {
    case board
    case sessions
    case kanban
    case dashboard
    case settings
}

@main
struct HermesChatApp: App {
    @StateObject private var appSettings = AppSettings()
    @StateObject private var coordinator = VoiceEntryCoordinator.shared
    @State private var selectedTab: AppTab = .board
    @Environment(\.scenePhase) private var scenePhase

    /// Info.plist BGTaskSchedulerPermittedIdentifiers와 일치해야 한다 (T-095)
    nonisolated private static let refreshTaskID = "ai.hermes.chat.refresh"

    var body: some Scene {
        WindowGroup {
            TabView(selection: $selectedTab) {
                ProfileBoardView(appSettings: appSettings, selectedTab: $selectedTab)
                    .tabItem { Label("tab.board", systemImage: "square.grid.2x2") }
                    .tag(AppTab.board)

                SessionListView(appSettings: appSettings)
                    .tabItem { Label("tab.sessions", systemImage: "bubble.left.and.bubble.right") }
                    .tag(AppTab.sessions)

                KanbanView(appSettings: appSettings)
                    .tabItem { Label("tab.kanban", systemImage: "rectangle.split.3x1") }
                    .tag(AppTab.kanban)

                DashboardWebView(appSettings: appSettings)
                    .tabItem { Label("tab.dashboard", systemImage: "gauge.with.dots.needle.50percent") }
                    .tag(AppTab.dashboard)

                NavigationStack {
                    SettingsView(appSettings: appSettings)
                }
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
                .tag(AppTab.settings)
            }
            .fullScreenCover(isPresented: Binding(
                get: { !appSettings.isFirstLaunchComplete },
                set: { if !$0 { appSettings.isFirstLaunchComplete = true } }
            )) {
                OnboardingView(appSettings: appSettings)
            }
            .task {
                await NotificationService.shared.requestAuthorization()
                // 콜드 런치(Siri·위젯·URL)로 진입 요청이 이미 들어와 있을 수 있다 (T-131)
                await routeVoiceEntryIfNeeded()
            }
            // 음성 진입 요청 라우팅 — 세션 탭으로 전환 후 대상 세션을 push (T-131)
            .onChange(of: coordinator.pendingVoiceEntry) { _, pending in
                if pending { Task { await routeVoiceEntryIfNeeded() } }
            }
            // hermes://voice 딥링크 (T-133)
            .onOpenURL { url in handleDeepLink(url) }
            .onChange(of: scenePhase) { _, phase in
                // 칸반 전이(done/blocked) 감지 폴링 — 포그라운드에서만 (T-093)
                if phase == .active {
                    NotificationService.shared.startPolling(appSettings: appSettings)
                } else {
                    NotificationService.shared.stopPolling()
                }
                // 백그라운드 진입 시 주기 폴링 예약 — iOS가 기회적으로만 실행 (T-095)
                if phase == .background {
                    Self.scheduleBackgroundRefresh()
                }
            }
        }
        .backgroundTask(.appRefresh(Self.refreshTaskID)) {
            let bridge = await appSettings.bridgeClient
            await NotificationService.shared.checkKanbanTransitions(bridge: bridge)
            Self.scheduleBackgroundRefresh()
        }
    }

    /// 음성 진입 요청을 라우팅한다 — 세션 탭으로 전환하고 대상 세션을 push (T-131).
    /// arm 플래그를 세워 두면 push된 ChatView가 onAppear에서 소비해
    /// 실제 ChatViewModel이 생성된 뒤 voice.start를 발동한다.
    @MainActor
    private func routeVoiceEntryIfNeeded() async {
        guard coordinator.beginRouting() else { return }
        selectedTab = .sessions

        // 이미 채팅에 들어가 있고 특정 세션 지정도 없으면 경로를 건드리지 않는다 —
        // 보이는 ChatView가 arm을 onChange로 소비해 그 세션에서 바로 음성을 시작한다.
        // (콜드 런치/목록 화면이면 경로가 비어 있으니 대상 세션을 새로 push.)
        guard coordinator.sessionsPath.isEmpty || coordinator.targetSessionId != nil else {
            return
        }

        let session: Session
        do {
            if let id = coordinator.targetSessionId,
               let existing = appSettings.sessions.first(where: { $0.id == id }) {
                session = existing
            } else if let recent = appSettings.sessions.first {
                // 대화 연속성 우선 — 최근 세션 resume
                session = recent
            } else {
                // 목록이 비어 있으면(콜드 런치 등) 새 세션 생성
                session = try await appSettings.createSession()
            }
        } catch {
            appSettings.sessionLoadError = error.localizedDescription
            coordinator.cancelRouting()
            return
        }
        coordinator.targetSessionId = nil
        // 루트로 리셋 후 push — 이미 다른 세션에 들어가 있어도 대상 세션으로 이동.
        // 오래된 ChatView의 onDisappear는 boundSessionId 가드로 새 음성을 끄지 않는다.
        coordinator.sessionsPath = NavigationPath()
        coordinator.sessionsPath.append(session)
    }

    /// hermes://voice (옵션 ?session=<id>) 딥링크를 음성 진입 요청으로 변환 (T-133).
    @MainActor
    private func handleDeepLink(_ url: URL) {
        guard url.scheme?.lowercased() == "hermes", url.host?.lowercased() == "voice" else { return }
        let sessionId = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "session" })?.value
        coordinator.requestVoiceEntry(sessionId: sessionId)
    }

    /// 다음 백그라운드 폴링 예약. 실행 보장은 없으며(iOS 스케줄러 재량),
    /// 실패는 조용히 무시한다 — 다음 백그라운드 진입/실행 때 재예약된다.
    nonisolated private static func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
