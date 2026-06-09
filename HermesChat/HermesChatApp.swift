import SwiftUI

@main
struct HermesChatApp: App {
    @StateObject private var appSettings = AppSettings()

    var body: some Scene {
        WindowGroup {
            SessionListView(appSettings: appSettings)
        }
    }
}
