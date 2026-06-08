import SwiftUI

@main
struct HermesChatApp: App {
    @StateObject private var appSettings = AppSettings()
    @State private var activeSessionId: String? = nil

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                if let sessionId = activeSessionId {
                    ChatView(sessionId: sessionId, appSettings: appSettings)
                } else {
                    SessionListView(appSettings: appSettings, activeSessionId: $activeSessionId)
                }
            }
        }
    }
}
