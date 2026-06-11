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
            .onChange(of: scenePhase) { phase in
                // 칸반 전이(done/blocked) 감지 폴링 — 포그라운드에서만 (T-093)
                if phase == .active {
                    NotificationService.shared.startPolling(appSettings: appSettings)
                } else {
                    NotificationService.shared.stopPolling()
                }
            }
        }
    }
}
