import SwiftUI

struct ProfileDetailView: View {
    let profile: HermesProfile
    @ObservedObject var appSettings: AppSettings
    @StateObject private var bridgeClient: BridgeClient

    var body: some View {
        Form {
            Section("프로필") {
                Text(profile.name)
                Text("포트 \(profile.port)")
            }
            Section("SOUL.md") {
                Text("구현 예정")
            }
            Section {
                Button("Gateway 재시작") {
                    Task { await restartGateway() }
                }
            }
        }
        .navigationTitle(profile.name)
        .task { await loadSoul() }
    }

    private func loadSoul() async {}
    private func restartGateway() async {}
}
