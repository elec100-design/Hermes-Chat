import SwiftUI

/// 홈 화면: 프로필을 2열 카드 그리드로 보여준다.
/// 카드마다 온라인 상태(/health 프로브)와 세션 수를 표시하고,
/// 탭하면 해당 프로필로 전환한 뒤 세션 탭으로 이동한다.
struct ProfileBoardView: View {
    @ObservedObject var appSettings: AppSettings
    @Binding var selectedTab: AppTab

    struct ProfileStatus: Equatable {
        var online: Bool?
        var sessionCount: Int?
    }

    @State private var status: [UUID: ProfileStatus] = [:]
    @State private var isProbing = false

    /// iPhone에선 2열, iPad에선 화면 폭에 맞춰 자동 증가
    private let columns = [
        GridItem(.adaptive(minimum: 160), spacing: 12),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(appSettings.profiles) { profile in
                        card(profile)
                    }
                }
                .padding()
            }
            .navigationTitle("프로필 보드")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if isProbing {
                        ProgressView().scaleEffect(0.8)
                    } else {
                        Button {
                            Task { await probeAll() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .refreshable { await probeAll() }
            .task { await probeAll() }
        }
    }

    private func card(_ profile: HermesProfile) -> some View {
        let profileStatus = status[profile.id]
        return Button {
            appSettings.selectProfile(profile)
            selectedTab = .sessions
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(statusColor(profileStatus?.online))
                        .frame(width: 10, height: 10)
                    Text(profile.name)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer()
                    if profile.id == appSettings.selectedProfileID {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.tint)
                    }
                }
                Text("포트 \(String(profile.port))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(sessionLabel(profileStatus))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }

    private func statusColor(_ online: Bool?) -> Color {
        switch online {
        case .some(true): return .green
        case .some(false): return .red
        case .none: return .gray
        }
    }

    private func sessionLabel(_ profileStatus: ProfileStatus?) -> String {
        guard let profileStatus, let online = profileStatus.online else { return "확인 중..." }
        guard online else { return "오프라인" }
        if let count = profileStatus.sessionCount { return "세션 \(count)개" }
        return "온라인"
    }

    private func probeAll() async {
        guard !isProbing else { return }
        isProbing = true
        defer { isProbing = false }

        await withTaskGroup(of: (UUID, ProfileStatus).self) { group in
            for profile in appSettings.profiles {
                let url = appSettings.baseURL(for: profile)
                let key = profile.apiKey.isEmpty ? appSettings.apiKey : profile.apiKey
                let id = profile.id
                group.addTask {
                    let online = await Self.probeHealth(baseURL: url)
                    var count: Int?
                    if online {
                        count = await Self.sessionCount(baseURL: url, apiKey: key)
                    }
                    return (id, ProfileStatus(online: online, sessionCount: count))
                }
            }
            for await (id, profileStatus) in group {
                status[id] = profileStatus
            }
        }
    }

    nonisolated private static func probeHealth(baseURL: URL) async -> Bool {
        var request = URLRequest(url: baseURL.appendingPathComponent("health"))
        request.timeoutInterval = 3
        guard let (_, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse else { return false }
        return http.statusCode == 200
    }

    nonisolated private static func sessionCount(baseURL: URL, apiKey: String) async -> Int? {
        var request = URLRequest(url: baseURL.appendingPathComponent("api/sessions"))
        request.timeoutInterval = 5
        if !apiKey.isEmpty {
            request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        }
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, http.statusCode == 200 else { return nil }
        struct Response: Decodable {
            struct Item: Decodable {}
            let data: [Item]
        }
        return (try? JSONDecoder().decode(Response.self, from: data))?.data.count
    }
}
