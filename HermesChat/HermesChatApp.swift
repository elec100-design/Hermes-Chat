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
    @State private var selectedTab: AppTab = .board
    @Environment(\.scenePhase) private var scenePhase

    /// Info.plist BGTaskSchedulerPermittedIdentifiers와 일치해야 한다 (T-095)
    nonisolated private static let refreshTaskID = "ai.hermes.chat.refresh"

    var body: some Scene {
        WindowGroup {
            TabView(selection: $selectedTab) {
                ProfileBoardView(appSettings: appSettings, selectedTab: $selectedTab)
                    .tabItem { Label("보드", systemImage: "square.grid.2x2") }
                    .tag(AppTab.board)

                SessionListView(appSettings: appSettings)
                    .tabItem { Label("세션", systemImage: "bubble.left.and.bubble.right") }
                    .tag(AppTab.sessions)

                KanbanView(appSettings: appSettings)
                    .tabItem { Label("칸반", systemImage: "rectangle.split.3x1") }
                    .tag(AppTab.kanban)

                DashboardWebView(appSettings: appSettings)
                    .tabItem { Label("대시보드", systemImage: "gauge.with.dots.needle.50percent") }
                    .tag(AppTab.dashboard)

                NavigationStack {
                    SettingsView(appSettings: appSettings)
                }
                .tabItem { Label("설정", systemImage: "gearshape") }
                .tag(AppTab.settings)
            }
            .task {
                await NotificationService.shared.requestAuthorization()
            }
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

    /// 다음 백그라운드 폴링 예약. 실행 보장은 없으며(iOS 스케줄러 재량),
    /// 실패는 조용히 무시한다 — 다음 백그라운드 진입/실행 때 재예약된다.
    nonisolated private static func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
